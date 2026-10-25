import hashlib
import io
import json
import subprocess
import unittest
import tempfile
from pathlib import Path
from contextlib import redirect_stdout, redirect_stderr
from unittest.mock import patch

import opencode_session_export as exporter

SESSION = "ses_owner123"


class ExportTests(unittest.TestCase):
    def fake_cli(self, args):
        self.calls.append(args)
        if args[:3] == ["opencode", "session", "list"]:
            return json.dumps([{"id": SESSION}, {"id": "ses_other"}]).encode()
        if args[:2] == ["opencode", "export"]:
            self.assertEqual(args, ["opencode", "export", SESSION])
            return json.dumps({"info": {"id": SESSION, "title": "private-title"}, "messages": [
                {"info": {"role": "user"}, "parts": [{"type": "text", "text": self.text},
                    {"type": "file", "url": "private-file"}]},
                {"info": {"role": "assistant"}, "parts": [
                    {"type": "tool", "state": {"output": "private-tool-output"}},
                    {"type": "reasoning", "text": "private-reasoning"}]}]}).encode()
        if args[:2] == ["rclone", "copyto"]:
            self.assertIn("--immutable", args)
            self.assertNotIn(args[3], self.remote)
            self.remote.add(args[3])
            return b""
        if args[:2] == ["rclone", "lsf"]:
            return (args[-1] + "\n").encode()
        raise AssertionError(args)

    def setUp(self):
        self.calls, self.remote = [], set()
        self.text = "Owner-approved example history"
        self.patcher = patch.object(exporter, "run", side_effect=self.fake_cli)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def test_only_exact_selected_session_and_text_parts(self):
        data = exporter.snapshot(SESSION)
        self.assertIn(self.text.encode(), data)
        self.assertNotIn(b"private", data)
        self.assertEqual(len([c for c in self.calls if c[:2] == ["opencode", "export"]]), 1)
        for invalid in ("latest", "all", "ses_missing", "../bad"):
            with self.assertRaises(exporter.ExportFailure):
                exporter.snapshot(invalid)

    def test_no_upload_without_exact_review_or_explicit_consent(self):
        data = exporter.snapshot(SESSION)
        for digest in (None, "0" * 64):
            with self.assertRaises(exporter.ExportFailure):
                exporter.upload(SESSION, data, approved_sha256=digest)
        self.assertFalse(self.remote)

    def test_snapshots_never_overwrite_drive(self):
        data = exporter.snapshot(SESSION)
        digest = hashlib.sha256(data).hexdigest()
        first = exporter.upload(SESSION, data, approved_sha256=digest)
        second = exporter.upload(SESSION, data, approved_sha256=digest)
        self.assertNotEqual(first, second)
        self.assertEqual(len(self.remote), 2)
        self.assertTrue(all(c[1] not in ("sync", "delete", "purge") for c in self.calls))

    def test_secret_blocks_upload_even_with_consent_and_is_not_logged(self):
        for secret in ("api_key=private-value", "Bearer " + "x" * 30,
                       "sk-" + "a" * 30, "-----BEGIN PRIVATE KEY-----"):
            self.text = secret
            logs = io.StringIO()
            with redirect_stdout(logs), redirect_stderr(logs):
                with self.assertRaises(exporter.ExportFailure) as error:
                    exporter.snapshot(SESSION)
            self.assertNotIn(secret, str(error.exception) + logs.getvalue())
        self.assertFalse(self.remote)

    def test_cli_failure_and_timeout_do_not_leak_stderr(self):
        self.patcher.stop()
        for result in (subprocess.CompletedProcess([], 1, b"secret", b"private-token"),
                       subprocess.TimeoutExpired("opencode", 120, stderr=b"private-token")):
            with patch.object(exporter.subprocess, "run") as run:
                if isinstance(result, Exception):
                    run.side_effect = result
                else:
                    run.return_value = result
                with self.assertRaises(exporter.ExportFailure) as error:
                    exporter.run(["opencode", "export", SESSION])
                self.assertNotIn("private-token", str(error.exception))

    def test_review_file_private_exclusive_and_no_periodic_export_by_default(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "review.json"
            argv = ["export", "--session-id", SESSION, "--review-file", str(path)]
            with patch("sys.argv", argv), patch.object(exporter.time, "sleep") as sleep, redirect_stdout(io.StringIO()):
                exporter.main()
                sleep.assert_not_called()
                self.assertEqual(path.stat().st_mode & 0o077, 0)
                with self.assertRaises(FileExistsError):
                    exporter.main()
        self.assertFalse(self.remote)

    def test_changed_history_requires_new_review_for_periodic_upload(self):
        data = exporter.snapshot(SESSION)
        digest = hashlib.sha256(data).hexdigest()
        exporter.upload(SESSION, data, approved_sha256=digest)
        self.text = "Changed private history"
        with self.assertRaises(exporter.ExportFailure):
            exporter.upload(SESSION, exporter.snapshot(SESSION), approved_sha256=digest)
        self.assertEqual(len(self.remote), 1)


if __name__ == "__main__":
    unittest.main()
