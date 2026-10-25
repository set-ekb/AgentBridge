"""Durable, per-task OpenCode sessions for the local AgentBridge gateway.

No generic proxy, shell endpoint or arbitrary session IDs from callers.
Only explicit owner-originated prompts, status and best-effort abort.
"""
from __future__ import annotations

import base64
import http.client
import json
import os
import re
import tempfile
import threading
import time
import socket
from contextlib import contextmanager
from pathlib import Path

TASK_ID = re.compile(r"^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$")
SESSION_ID = re.compile(r"^ses_[A-Za-z0-9]+$")
MAX_OUTPUT = 12288


class TaskFailure(Exception):
    pass


class OpenCodeApi:
    def __init__(self, port: int, password: str):
        self.port = port
        credentials = base64.b64encode(("opencode:" + password).encode()).decode()
        self.authorization = "Basic " + credentials

    def request(self, path: str, payload=None, *, deadline=None):
        if not path.startswith("/") or "?" in path or ".." in path:
            raise TaskFailure("invalid_opencode_endpoint")
        headers = {"Authorization": self.authorization, "Accept": "application/json"}
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        deadline = min(deadline, time.monotonic() + 6) if deadline is not None else time.monotonic() + 6
        connection = None
        timer = None
        try:
            timeout = deadline - time.monotonic()
            if timeout <= 0:
                raise TaskFailure("opencode_unavailable")
            connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=timeout)
            connection.connect()
            sock = connection.sock
            # A socket timeout alone is an inactivity limit: a slow header/body
            # could exceed the poll budget. Shutdown this specific socket at
            # the absolute deadline, including header and body reads.
            def expire():
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TaskFailure("opencode_unavailable")
            timer = threading.Timer(remaining, expire)
            timer.daemon = True
            timer.start()
            connection.request("POST" if payload is not None else "GET", path, body=data, headers=headers)
            with connection.getresponse() as response:
                if not 200 <= response.status < 300:
                    raise TaskFailure("opencode_unavailable")
                raw = response.read(262145)
                if len(raw) > 262144:
                    raise TaskFailure("opencode_response_too_large")
                return json.loads(raw) if raw else None
        except (OSError, ValueError, http.client.HTTPException) as exc:
            raise TaskFailure("opencode_unavailable") from exc
        finally:
            if timer is not None:
                timer.cancel()
            if connection is not None:
                connection.close()


class TaskManager:
    def __init__(self, root: Path, api: OpenCodeApi):
        self.root = root
        self.api = api
        self.lock = threading.RLock()
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        if root.is_symlink() or root.stat().st_mode & 0o077:
            raise RuntimeError("Unsafe task directory permissions")

    def _path(self, task_id: str) -> Path:
        if not TASK_ID.fullmatch(task_id):
            raise TaskFailure("invalid_task_id")
        return self.root / (task_id + ".json")

    def _get(self, task_id: str):
        path = self._path(task_id)
        if not path.exists():
            raise TaskFailure("task_not_found")
        if path.is_symlink() or path.stat().st_mode & 0o077:
            raise TaskFailure("unsafe_task_file")
        content = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(content, dict) or content.get("id") != task_id:
            raise TaskFailure("corrupt_task_file")
        return content

    def _put(self, value):
        path = self._path(value["id"])
        with tempfile.NamedTemporaryFile("w", dir=self.root, prefix=".task-",
                                         delete=False, encoding="utf-8") as stream:
            temp_path = Path(stream.name)
            os.chmod(temp_path, 0o600)
            json.dump(value, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.replace(temp_path, path)
            directory = os.open(self.root, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            temp_path.unlink(missing_ok=True)

    def create(self, task_id: str, prompt: str, *, disable_tools: bool = False):
        if not isinstance(prompt, str) or not 1 <= len(prompt) <= 16000:
            raise TaskFailure("invalid_prompt")
        with self.lock:
            try:
                existing = self._get(task_id)
                return self.status(task_id) if existing else None
            except TaskFailure as exc:
                if str(exc) != "task_not_found":
                    raise
            session = self.api.request("/session", {"title": "AgentBridge: " + task_id[:12]})
            session_id = session.get("id") if isinstance(session, dict) else None
            if not isinstance(session_id, str) or not SESSION_ID.fullmatch(session_id):
                raise TaskFailure("invalid_session_id")
            record = {"id": task_id, "session": session_id, "state": "dispatching"}
            # Persist before sending: a lost response must never cause re-execution.
            self._put(record)
            try:
                request = {"parts": [{"type": "text", "text": prompt}]}
                # For live smoke testing, disable all agent tools at the API
                # level. Normal owner tasks keep OpenCode's own permissions.
                if disable_tools:
                    request["tools"] = {"*": False}
                self.api.request("/session/" + session_id + "/prompt_async", request)
            except TaskFailure:
                # May have been admitted by OpenCode. Do NOT retry automatically.
                record["state"] = "unknown"
                self._put(record)
                return {"state": "unknown", "answer": "", "reason": "dispatch_unconfirmed"}
            record["state"] = "running"
            self._put(record)
            return {"state": "running", "answer": ""}

    @contextmanager
    def _status_lock(self, deadline):
        acquired = self.lock.acquire() if deadline is None else self.lock.acquire(
            timeout=max(0, deadline - time.monotonic()))
        if not acquired:
            raise TaskFailure("opencode_unavailable")
        try:
            yield
        finally:
            self.lock.release()

    def _terminal(self, record, state, answer="", reason=""):
        result = {"state": state, "answer": answer}
        if reason:
            result["reason"] = reason
        record.update(state=state, result=result)
        self._put(record)
        return result

    def status(self, task_id: str, *, deadline=None):
        with self._status_lock(deadline):
            record = self._get(task_id)
            state = record.get("state")
            if state in ("done", "failed", "cancelled"):
                return record.get("result", {"state": state, "answer": ""})
            session = record["session"]
            if not isinstance(session, str) or not SESSION_ID.fullmatch(session):
                raise TaskFailure("invalid_session_id")
            statuses = self.api.request("/session/status", deadline=deadline)
            messages = self.api.request("/session/" + session + "/message", deadline=deadline)
            if not isinstance(statuses, dict) or not isinstance(messages, list):
                raise TaskFailure("invalid_opencode_status")
            latest = None
            for item in messages:
                if not isinstance(item, dict) or not isinstance(item.get("info"), dict):
                    continue
                info = item["info"]
                if info.get("role") == "assistant":
                    latest = item
            parts = latest.get("parts") if latest else None
            text = "\n".join(p["text"] for p in (parts if isinstance(parts, list) else [])
                             if isinstance(p, dict) and p.get("type") == "text"
                             and isinstance(p.get("text"), str))[-MAX_OUTPUT:]
            if latest is not None and isinstance(latest["info"].get("error"), dict):
                return self._terminal(record, "failed", text, reason="opencode_response_error")
            session_status = statuses.get(session)
            busy = isinstance(session_status, dict) and session_status.get("type") != "idle"
            if latest is not None and not busy:
                finish = latest["info"].get("finish")
                # An intermediate tool-call assistant message is NOT a
                # completed task, even when the busy map briefly becomes empty.
                if finish == "stop":
                    if text.strip():
                        return self._terminal(record, "done", text[-MAX_OUTPUT:])
                    return self._terminal(record, "failed", reason="no_text_response")
            if state in ("unknown", "dispatching"):
                # A missing busy entry is not proof of admission or completion.
                admitted = busy or any(isinstance(item, dict) and
                    isinstance(item.get("info"), dict) and
                    item["info"].get("role") == "user" for item in messages)
                record["state"] = "running" if admitted else "unknown"
                self._put(record)
                if not admitted:
                    return {"state": "unknown", "answer": "", "reason": "dispatch_unconfirmed"}
            return {"state": "running", "answer": ""}

    def cancel(self, task_id: str):
        with self.lock:
            record = self._get(task_id)
            if record.get("state") in ("done", "failed", "cancelled"):
                return {**self.status(task_id), "confirmed": record["state"] == "cancelled"}
            session = record["session"]
            # Do not mark cancelled until OpenCode confirms abort.
            result = self.api.request("/session/" + session + "/abort", {})
            if result is not True:
                raise TaskFailure("abort_unconfirmed")
            self._terminal(record, "cancelled")
            return {"state": "cancelled", "confirmed": True}
