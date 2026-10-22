"""Durable, per-task OpenCode sessions for the local AgentBridge gateway.

No generic proxy, shell endpoint or arbitrary session IDs from callers.
Only explicit owner-originated prompts, status and best-effort abort.
"""
from __future__ import annotations

import base64
import json
import os
import re
import tempfile
import threading
import urllib.error
import urllib.request
from pathlib import Path

TASK_ID = re.compile(r"^[a-f0-9]{8}-[a-f0-9-]{28,36}$")
SESSION_ID = re.compile(r"^ses_[A-Za-z0-9]+$")
MAX_OUTPUT = 12288


class TaskFailure(Exception):
    pass


class OpenCodeApi:
    def __init__(self, port: int, password: str):
        self.base = f"http://127.0.0.1:{port}"
        credentials = base64.b64encode(("opencode:" + password).encode()).decode()
        self.authorization = "Basic " + credentials

    def request(self, path: str, payload=None):
        if not path.startswith("/") or "?" in path or ".." in path:
            raise TaskFailure("invalid_opencode_endpoint")
        headers = {"Authorization": self.authorization, "Accept": "application/json"}
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + path, data=data, headers=headers,
                                     method="POST" if payload is not None else "GET")
        try:
            with urllib.request.urlopen(req, timeout=12) as response:
                raw = response.read(262145)
                if len(raw) > 262144:
                    raise TaskFailure("opencode_response_too_large")
                return json.loads(raw) if raw else None
        except (OSError, ValueError, urllib.error.HTTPError) as exc:
            raise TaskFailure("opencode_unavailable") from exc


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
        finally:
            temp_path.unlink(missing_ok=True)

    def create(self, task_id: str, prompt: str):
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
                self.api.request("/session/" + session_id + "/prompt_async",
                    {"parts": [{"type": "text", "text": prompt}]})
            except TaskFailure:
                # May have been admitted by OpenCode. Do NOT retry automatically.
                record["state"] = "unknown"
                self._put(record)
                return {"state": "unknown", "answer": "", "reason": "dispatch_unconfirmed"}
            record["state"] = "running"
            self._put(record)
            return {"state": "running", "answer": ""}

    def status(self, task_id: str):
        with self.lock:
            record = self._get(task_id)
            state = record.get("state")
            if state in ("cancelled", "unknown"):
                return {"state": state, "answer": "", "reason":
                    "dispatch_unconfirmed" if state == "unknown" else ""}
            session = record["session"]
            statuses = self.api.request("/session/status")
            messages = self.api.request("/session/" + session + "/message")
            if not isinstance(statuses, dict) or not isinstance(messages, list):
                raise TaskFailure("invalid_opencode_status")
            latest = None
            for item in messages:
                if not isinstance(item, dict) or not isinstance(item.get("info"), dict):
                    continue
                info = item["info"]
                if info.get("role") == "assistant":
                    latest = item
            if latest is not None and isinstance(latest["info"].get("error"), dict):
                return {"state": "failed", "answer": "", "reason": "opencode_response_error"}
            if latest is not None and session not in statuses:
                parts = latest.get("parts")
                text = "\n".join(str(p.get("text")) for p in (parts if isinstance(parts, list) else [])
                                 if isinstance(p, dict) and p.get("type") == "text" and isinstance(p.get("text"), str))
                if text.strip():
                    return {"state": "done", "answer": text[-MAX_OUTPUT:]}
            return {"state": "running", "answer": ""}

    def cancel(self, task_id: str):
        with self.lock:
            record = self._get(task_id)
            if record.get("state") == "cancelled":
                return {"state": "cancelled", "confirmed": True}
            session = record["session"]
            # Do not mark cancelled until OpenCode confirms abort.
            result = self.api.request("/session/" + session + "/abort", {})
            if result is not True:
                raise TaskFailure("abort_unconfirmed")
            record["state"] = "cancelled"
            self._put(record)
            return {"state": "cancelled", "confirmed": True}
