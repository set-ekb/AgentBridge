#!/usr/bin/env python3
"""Read-only, loopback-only status gateway: AgentBridge -> Termux -> OpenCode.

The OpenCode Basic-auth password never leaves Termux. AgentBridge receives
a separate, revocable pairing token stored in Android Keystore.
Task operations are disabled unless explicitly started with --enable-tasks.
There is no generic proxy, shell, file, or arbitrary OpenCode API endpoint.
"""
from __future__ import annotations

import argparse
import base64
import hmac
import json
import os
import secrets
import stat
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
from opencode_task_bridge import TaskManager, OpenCodeApi, TaskFailure

CONFIG = Path.home() / ".config" / "agentbridge-opencode"
DEFAULT_OPENCODE_PORT = 4096
DEFAULT_GATEWAY_PORT = 4097


def private_file(path: Path, *, create: bool = False) -> str:
    """Load secret; refuse symlinks and group/world-readable files."""
    if create:
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if not path.exists():
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as stream:
                stream.write(secrets.token_urlsafe(32) + "\n")
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
        raise RuntimeError(f"Secret file permissions are unsafe: {path.name} (require 0600)")
    secret = path.read_text(encoding="utf-8").strip()
    if not secret:
        raise RuntimeError(f"Empty secret file: {path.name}")
    return secret


def sanitized_status(opencode_port: int, password: str) -> dict:
    """Query only two read-only OpenCode endpoints; never return private content."""
    base = f"http://127.0.0.1:{opencode_port}"
    basic = base64.b64encode(("opencode:" + password).encode("utf-8")).decode("ascii")

    def get(path):
        req = urllib.request.Request(
            base + path, headers={"Authorization": "Basic " + basic}
        )
        with urllib.request.urlopen(req, timeout=3) as response:
            raw = response.read(16385)
            if len(raw) > 16384:
                raise ValueError("OpenCode reply is too large")
            return json.loads(raw)

    try:
        health = get("/global/health")
        statuses = get("/session/status")
        if not isinstance(health, dict) or not isinstance(statuses, dict):
            raise ValueError("Invalid OpenCode status format")
        return {
            "gateway": "agentbridge-opencode",
            "mode": "read_only",
            "healthy": bool(health.get("healthy")),
            "version": str(health.get("version", ""))[:40],
            "active_sessions": len(statuses),
        }
    except (OSError, ValueError, urllib.error.URLError):
        return {
            "gateway": "agentbridge-opencode",
            "mode": "read_only",
            "healthy": False,
            "version": "",
            "active_sessions": 0,
            "reason": "opencode_unavailable",
        }


def handler_factory(pairing_token: str, opencode_port: int, password: str, tasks=None):
    class Handler(BaseHTTPRequestHandler):
        server_version = "AgentBridgeOpenCodeLink/0.1"
        sys_version = ""

        def log_message(self, fmt, *args):
            # Do not log bearer secrets or request contents.
            pass

        def authorized(self):
            candidate = self.headers.get("Authorization", "")
            if not hmac.compare_digest(candidate, "Bearer " + pairing_token):
                self.respond(401, {"error": "unauthorized"})
                return False
            return True

        def do_GET(self):
            if not self.authorized():
                return
            if self.path == "/status":
                self.respond(200, sanitized_status(opencode_port, password))
                return
            match = re.fullmatch(r"/tasks/([a-f0-9-]{36})", self.path)
            if tasks is not None and match:
                try:
                    self.respond(200, tasks.status(match.group(1)))
                except TaskFailure as exc:
                    self.task_error(exc)
                return
            self.respond(404, {"error": "not_found"})

        def do_POST(self):
            if not self.authorized():
                return
            if tasks is None:
                self.respond(405, {"error": "read_only"})
                return
            try:
                if self.path == "/tasks":
                    if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
                        self.respond(415, {"error": "json_required"})
                        return
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= 20000:
                        self.respond(413, {"error": "invalid_body_size"})
                        return
                    body = json.loads(self.rfile.read(size))
                    if not isinstance(body, dict) or set(body) != {"id", "prompt"}:
                        raise TaskFailure("invalid_body")
                    result = tasks.create(body["id"], body["prompt"])
                    self.respond(200, result)
                    return
                match = re.fullmatch(r"/tasks/([a-f0-9-]{36})/cancel", self.path)
                if match:
                    self.respond(200, tasks.cancel(match.group(1)))
                    return
                self.respond(404, {"error": "not_found"})
            except (ValueError, TypeError, json.JSONDecodeError):
                self.respond(400, {"error": "invalid_json"})
            except TaskFailure as exc:
                self.task_error(exc)

        def task_error(self, exc):
            reason = str(exc)
            code = (404 if reason == "task_not_found" else
                    400 if reason.startswith("invalid_") else
                    503 if reason in ("opencode_unavailable", "abort_unconfirmed") else 409)
            self.respond(code, {"error": reason})

        def respond(self, code: int, value: dict):
            payload = json.dumps(value, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(payload)

    return Handler


def main():
    parser = argparse.ArgumentParser(description="AgentBridge/OpenCode local read-only link")
    parser.add_argument("--port", type=int, default=DEFAULT_GATEWAY_PORT)
    parser.add_argument("--opencode-port", type=int, default=DEFAULT_OPENCODE_PORT)
    parser.add_argument("--enable-tasks", action="store_true", help="Allow paired Android app to dispatch tasks to OpenCode")
    args = parser.parse_args()

    if not all(1 <= p <= 65535 for p in (args.port, args.opencode_port)):
        parser.error("Invalid port")
    os.umask(0o077)
    password = private_file(CONFIG / "password")
    token = private_file(CONFIG / "pairing-token", create=True)
    tasks = TaskManager(CONFIG / "tasks", OpenCodeApi(args.opencode_port, password)) if args.enable_tasks else None
    server = ThreadingHTTPServer(
        ("127.0.0.1", args.port), handler_factory(token, args.opencode_port, password, tasks)
    )
    print(f"AgentBridge/OpenCode link listening on 127.0.0.1:{args.port} (read-only)")
    print("Task dispatch:", "ENABLED — OpenCode native tools are subject to its own permissions" if args.enable_tasks else "DISABLED (read-only)")
    print("Pairing token is stored locally in ~/.config/agentbridge-opencode/pairing-token")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
