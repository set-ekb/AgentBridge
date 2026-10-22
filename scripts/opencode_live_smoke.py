#!/usr/bin/env python3
"""Explicit live smoke test of OpenCode's real server API (no gateway restart).

Creates one new session and sends one harmless no-tools-requested prompt.
May use the configured LLM quota. Never automatically resends/aborts.
"""
from __future__ import annotations

import argparse
import sys
import time
import uuid
from pathlib import Path

from opencode_bridge_gateway import CONFIG, private_file
from opencode_task_bridge import OpenCodeApi, TaskFailure, TaskManager

SMOKE_PROMPT = (
    "Это проверка связи с Android Bridge. "
    "Не используй инструменты, не читай и не изменяй файлы, "
    "не выполняй команды и не управляй Android. "
    "Ответь точно одной строкой: Bridge OpenCode работает"
)


def main():
    parser = argparse.ArgumentParser(description="One harmless real OpenCode round-trip")
    parser.add_argument("--execute", action="store_true",
                        help="Create a session and send exactly one LLM prompt")
    parser.add_argument("--opencode-port", type=int, default=4096)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()

    if not args.execute:
        print("Dry run: no API calls, sessions or model requests.")
        print("Use --execute to create ONE test session and send a harmless prompt.")
        return 0
    if not 1 <= args.opencode_port <= 65535 or not 10 <= args.timeout <= 300:
        parser.error("Invalid port or timeout")
    try:
        password = private_file(CONFIG / "password")
        manager = TaskManager(CONFIG / "smoke-tasks",
                              OpenCodeApi(args.opencode_port, password))
        task_id = str(uuid.uuid4())
        print("Smoke task ID:", task_id)
        print("Submitting one safe text-only prompt. No automatic retry.")
        update = manager.create(task_id, SMOKE_PROMPT, disable_tools=True)
        print("Initial state:", update["state"])
        if update["state"] != "running":
            print("Stop: dispatch is not confirmed; inspect session manually.")
            return 2
        until = time.monotonic() + args.timeout
        while time.monotonic() < until:
            time.sleep(2)
            update = manager.status(task_id)
            if update["state"] == "running":
                continue
            print("Final state:", update["state"])
            if update["state"] == "done":
                print("Model answer:", update["answer"][:500])
                print("PASS: OpenCode session created, response received.")
                return 0
            print("Reason:", update.get("reason", "not_reported"))
            print("No automatic retry; inspect the session manually.")
            return 2
        print("TIMEOUT: task may still be running; do not send again.")
        return 2
    except (TaskFailure, OSError, ValueError, RuntimeError) as exc:
        # Never print the OpenCode password or pairing token.
        print("Smoke check could not finish:", type(exc).__name__,
              str(exc) if isinstance(exc, TaskFailure) else "")
        return 2


if __name__ == "__main__":
    sys.exit(main())
