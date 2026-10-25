#!/usr/bin/env python3
"""Opt-in, text-only snapshots of ONE exact OpenCode session via native CLI."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

DESTINATION = "gdrive:00_AI_Context/40_AI_Results/OpenCode_Sessions/"
SESSION_ID = re.compile(r"ses_[A-Za-z0-9]+")
# Defense in depth, not a claim that arbitrary confidential text is detectable.
SECRET = re.compile(
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----|\b(?:sk-|gh[pousr]_|github_pat_|AIza)[A-Za-z0-9_-]{12,}"
    r"|\b(?:bearer|basic)\s+[A-Za-z0-9_+/=-]{12,}"
    r"|[\"']?(?:api[_-]?key|password|secret|token|authorization)[\"']?\s*[:=]\s*\S+",
    re.IGNORECASE,
)


class ExportFailure(Exception):
    pass


def run(args):
    try:
        result = subprocess.run(args, capture_output=True, timeout=120, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ExportFailure("cli_unavailable_or_timeout") from None
    if result.returncode:
        # CLI stderr can contain history, credentials or local paths.
        raise ExportFailure("cli_failed")
    return result.stdout


def sessions():
    try:
        rows = json.loads(run(["opencode", "session", "list", "--format", "json"]))
        if not isinstance(rows, list):
            raise ValueError()
        return [row["id"] for row in rows if isinstance(row, dict) and
                isinstance(row.get("id"), str) and SESSION_ID.fullmatch(row["id"])]
    except (ValueError, KeyError, TypeError):
        raise ExportFailure("invalid_session_list") from None


def snapshot(session_id):
    if not SESSION_ID.fullmatch(session_id) or session_id not in sessions():
        raise ExportFailure("exact_session_required")
    try:
        exported = json.loads(run(["opencode", "export", session_id]))
        if exported["info"]["id"] != session_id:
            raise ValueError()
        messages = []
        for message in exported["messages"]:
            role = message["info"].get("role")
            if role not in ("user", "assistant"):
                continue
            texts = [p["text"] for p in message["parts"] if p.get("type") == "text"
                     and isinstance(p.get("text"), str) and not p.get("synthetic")]
            if texts:
                messages.append({"role": role, "text": "\n".join(texts)})
        if not messages:
            raise ValueError()
        data = json.dumps({"session_id": session_id, "messages": messages},
                          ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
    except (ValueError, KeyError, TypeError, AttributeError):
        raise ExportFailure("invalid_session_export") from None
    if SECRET.search(data.decode("utf-8")):
        raise ExportFailure("secret_detected_export_blocked")
    return data


def upload(session_id, data, *, approved_sha256=None, allow_private_history=False):
    digest = hashlib.sha256(data).hexdigest()
    if SECRET.search(data.decode("utf-8")):
        raise ExportFailure("secret_detected_export_blocked")
    if not allow_private_history and approved_sha256 != digest:
        raise ExportFailure("review_required_use_exact_sha256")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    name = f"{session_id}_{stamp}_{uuid.uuid4().hex}.json"
    with tempfile.TemporaryDirectory(prefix="agentbridge-session-") as directory:
        path = Path(directory) / name
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
        run(["rclone", "copyto", str(path), DESTINATION + name, "--immutable"])
        listing = run(["rclone", "lsf", DESTINATION, "--files-only", "--include", name])
        if name not in listing.decode("utf-8").splitlines():
            raise ExportFailure("upload_not_visible")
    return name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true", help="List IDs only; exports nothing")
    parser.add_argument("--session-id", help="Exact owner-selected ID, never latest/all")
    parser.add_argument("--review-file", type=Path, help="Write text-only snapshot locally (exclusive 0600)")
    parser.add_argument("--upload", action="store_true")
    parser.add_argument("--approved-sha256", help="Approval of this exact reviewed text snapshot")
    parser.add_argument("--allow-private-history", action="store_true",
                        help="Explicitly allow confidential text history uploads; known secrets still blocked")
    parser.add_argument("--interval", type=int, default=0,
                        help="Opt-in foreground periodic upload, seconds >=60; Ctrl-C disables")
    args = parser.parse_args()
    if args.list:
        print("\n".join(sessions()))
        return
    if not args.session_id or (args.interval and (args.interval < 60 or not args.upload or args.review_file)):
        parser.error("exact --session-id required; interval needs upload, >=60 and no review-file")
    while True:
        data = snapshot(args.session_id)
        if args.review_file:
            fd = os.open(args.review_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(data)
        print("snapshot_sha256=" + hashlib.sha256(data).hexdigest())
        if args.upload:
            print("uploaded=" + upload(args.session_id, data, approved_sha256=args.approved_sha256,
                                      allow_private_history=args.allow_private_history))
        if not args.interval:
            return
        time.sleep(args.interval)


if __name__ == "__main__":
    try:
        main()
    except (ExportFailure, OSError):
        # Never print exception strings from subprocess or private files.
        print("Export stopped: verification/approval/CLI failure. No history is logged.")
        raise SystemExit(1)
    except KeyboardInterrupt:
        print("Periodic export disabled.")
