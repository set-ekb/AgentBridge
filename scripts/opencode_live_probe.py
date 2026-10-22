#!/usr/bin/env python3
"""Read-only localhost compatibility probe for AgentBridge/OpenCode in Termux.

No POST requests, model calls, file writes, or mutations are performed.
Passwords/tokens are never printed, even when an HTTP request fails.
"""
from __future__ import annotations

import argparse
import base64
import json
import stat
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path.home() / ".config" / "agentbridge-opencode"
MAX_RESPONSE = 16384


def secret(name: str) -> str:
    file = ROOT / name
    info = file.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
        raise ValueError(name + ": unsafe permissions (expected 0600)")
    value = file.read_text(encoding="utf-8").strip()
    if not value:
        raise ValueError(name + ": empty credential")
    return value


def get_json(port: int, path: str, authorization: str | None = None):
    headers = {"Accept": "application/json"}
    if authorization:
        headers["Authorization"] = authorization
    request = urllib.request.Request(
        "http://127.0.0.1:" + str(port) + path,
        headers=headers,
        method="GET",
    )
    with urllib.request.urlopen(request, timeout=4) as response:
        raw = response.read(MAX_RESPONSE + 1)
        if len(raw) > MAX_RESPONSE:
            raise ValueError("response exceeds 16 KiB")
        return response.status, json.loads(raw)


def main():
    parser = argparse.ArgumentParser(description="Safe local OpenCode / AgentBridge probe")
    parser.add_argument("--opencode-port", type=int, default=4096)
    parser.add_argument("--gateway-port", type=int, default=4097)
    args = parser.parse_args()
    if not all(1 <= port <= 65535 for port in (args.opencode_port, args.gateway_port)):
        parser.error("port outside 1..65535")

    try:
        password = secret("password")
        pairing = secret("pairing-token")
        basic = base64.b64encode(("opencode:" + password).encode("utf-8")).decode("ascii")
        auth = "Basic " + basic
        _, health = get_json(args.opencode_port, "/global/health", auth)
        if not isinstance(health, dict):
            raise ValueError("OpenCode health response is not JSON object")
        print("OpenCode:", "OK" if health.get("healthy") is True else "NOT HEALTHY",
              "| version:", str(health.get("version", "?"))[:40])
        _, status = get_json(args.opencode_port, "/session/status", auth)
        if not isinstance(status, dict):
            raise ValueError("OpenCode session status is not JSON object")
        print("OpenCode /session/status: OK | reported:", len(status), "session(s)")

        try:
            get_json(args.gateway_port, "/status")
        except urllib.error.HTTPError as ex:
            if ex.code != 401:
                raise ValueError("Unauthenticated gateway did not return HTTP 401")
            ex.close()
            print("Gateway unauthorized request: 401 (expected)")
        else:
            raise ValueError("Gateway accepts unauthenticated requests")

        _, result = get_json(args.gateway_port, "/status", "Bearer " + pairing)
        if not isinstance(result, dict) or result.get("gateway") != "agentbridge-opencode":
            raise ValueError("Unexpected gateway response")
        if result.get("mode") != "read_only":
            raise ValueError("Unexpected gateway mode")
        print("Gateway authorized: OK | healthy:", result.get("healthy"),
              "| active_sessions:", result.get("active_sessions"))
        print("PASS: local GET-only compatibility probe finished; no task executed")
    except (OSError, ValueError, json.JSONDecodeError, urllib.error.URLError) as ex:
        # Do not print exception strings: HTTP URLs and failures may expose details.
        print("FAIL:", type(ex).__name__, "| verify both loopback servers and private credentials")
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
