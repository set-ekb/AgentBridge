import importlib.util
import json
import tempfile
import unittest
import time
import io
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

def load_module(path, name):
    import sys
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(path))
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod

module = load_module("opencode_task_bridge.py", "opencode_task_bridge")
gateway = load_module("opencode_bridge_gateway.py", "gateway")

TASK = "12345678-1234-1234-1234-123456789abc"

class FakeOpenCode:
    def __init__(self):
        self.prompt_count = 0
        self.last_prompt = None
        self.abort_count = 0
        self.incomplete = True
        self.fail_abort = False

    def request(self, path, payload=None, *, deadline=None):
        if path == "/session" and payload:
            return {"id": "ses_test123"}
        if path == "/session/ses_test123/prompt_async":
            self.prompt_count += 1
            self.last_prompt = payload
            return None
        if path == "/session/status":
            return {"ses_test123": {"type": "busy"}} if self.incomplete else {}
        if path == "/session/ses_test123/message":
            if self.incomplete:
                return []
            return [{"info": {"role": "assistant", "finish": "stop"},
                     "parts": [{"type": "text", "text": "Итог готов"}]}]
        if path == "/session/ses_test123/abort":
            self.abort_count += 1
            if self.fail_abort:
                raise module.TaskFailure("opencode_unavailable")
            return True
        raise AssertionError(path)

class TaskTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.api = FakeOpenCode()
        self.tasks = module.TaskManager(Path(self.temp.name) / "tasks", self.api)

    def test_unknown_reconciles_without_dispatch(self):
        self.tasks.create(TASK, "x")
        record = self.tasks._get(TASK)
        record["state"] = "unknown"
        self.tasks._put(record)
        self.assertEqual(self.tasks.status(TASK)["state"], "running")
        self.api.incomplete = False
        self.assertEqual(self.tasks.status(TASK)["state"], "done")
        self.assertEqual(self.api.prompt_count, 1)

    def test_terminal_survives_restart_and_upstream_loss(self):
        self.tasks.create(TASK, "x")
        self.api.incomplete = False
        result = self.tasks.status(TASK)
        self.assertEqual(self.tasks._get(TASK)["state"], "done")
        reloaded = module.TaskManager(self.tasks.root, self.api)
        with patch.object(self.api, "request", side_effect=AssertionError("must not call upstream")):
            self.assertEqual(reloaded.status(TASK), result)
            self.assertEqual(reloaded.create(TASK, "different prompt"), result)
            self.assertEqual(reloaded.cancel(TASK)["state"], "done")

    def test_dispatching_without_evidence_stays_unknown(self):
        self.tasks.create(TASK, "x")
        record = self.tasks._get(TASK)
        record["state"] = "dispatching"
        self.tasks._put(record)
        with patch.object(self.api, "request", side_effect=[{}, []]):
            self.assertEqual(self.tasks.status(TASK)["state"], "unknown")

    def test_invalid_uuid_has_no_upstream_side_effect(self):
        with patch.object(self.api, "request") as request:
            with self.assertRaises(module.TaskFailure):
                self.tasks.create("../bad", "x")
            request.assert_not_called()

    def test_lost_dispatch_ack_reconciles_after_restart(self):
        original = self.api.request
        def lost_ack(path, payload=None, **kwargs):
            result = original(path, payload, **kwargs)
            if path.endswith("prompt_async"):
                raise module.TaskFailure("opencode_unavailable")
            return result
        with patch.object(self.api, "request", side_effect=lost_ack):
            self.assertEqual(self.tasks.create(TASK, "x")["state"], "unknown")
        self.api.incomplete = False
        reloaded = module.TaskManager(self.tasks.root, self.api)
        self.assertEqual(reloaded.create(TASK, "x")["state"], "done")
        self.assertEqual(self.api.prompt_count, 1)

    def test_failed_and_cancelled_are_durable(self):
        for state in ("failed", "cancelled"):
            with self.subTest(state=state):
                tasks = module.TaskManager(Path(self.temp.name) / state, self.api)
                tasks.create(TASK, "x")
                if state == "cancelled":
                    tasks.cancel(TASK)
                else:
                    with patch.object(self.api, "request", side_effect=[{}, [{
                        "info": {"role": "assistant", "error": {"message": "private"}},
                        "parts": [{"type": "text", "text": "partial result"}]}]]):
                        self.assertEqual(tasks.status(TASK)["answer"], "partial result")
                expected = tasks._get(TASK)["result"]
                with patch.object(self.api, "request", side_effect=AssertionError("upstream")):
                    reloaded = module.TaskManager(tasks.root, self.api)
                    self.assertEqual(reloaded.status(TASK), expected)
                    self.assertEqual(reloaded.create(TASK, "x"), expected)

    def test_concurrent_uuid_is_dispatched_once(self):
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda _: self.tasks.create(TASK, "x"), range(8)))
        self.assertTrue(all(r["state"] == "running" for r in results))
        self.assertEqual(self.api.prompt_count, 1)

    def test_upstream_timeout_uses_remaining_budget_and_hides_secret(self):
        api = module.OpenCodeApi(4096, "private-password")
        deadline = time.monotonic() + 0.5
        with patch.object(module.http.client, "HTTPConnection", side_effect=TimeoutError("private-password")) as call:
            with self.assertRaises(module.TaskFailure) as error:
                api.request("/session/status", deadline=deadline)
        self.assertEqual(str(error.exception), "opencode_unavailable")
        self.assertGreater(call.call_args.kwargs["timeout"], 0)
        self.assertLessEqual(call.call_args.kwargs["timeout"], 0.5)

    def test_atomic_write_failure_preserves_previous_record(self):
        self.tasks.create(TASK, "x")
        before = self.tasks._get(TASK)
        self.api.incomplete = False
        with patch.object(module.os, "replace", side_effect=OSError("disk failure")):
            with self.assertRaises(OSError):
                self.tasks.status(TASK)
        self.assertEqual(self.tasks._get(TASK), before)
        self.assertEqual(list(self.tasks.root.glob(".task-*")), [])

    def test_absolute_upstream_deadline_covers_slow_headers_and_body(self):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        from threading import Thread
        class SlowHandler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                try:
                    if self.path == "/headers":
                        time.sleep(0.5)
                    self.send_response(200)
                    self.send_header("Content-Length", "100")
                    self.end_headers()
                    for _ in range(100):
                        self.wfile.write(b" ")
                        self.wfile.flush()
                        time.sleep(0.04)
                except (BrokenPipeError, ConnectionResetError):
                    pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), SlowHandler)
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            api = module.OpenCodeApi(server.server_port, "private-password")
            for path in ("/headers", "/body"):
                started = time.monotonic()
                with self.assertRaises(module.TaskFailure):
                    api.request(path, deadline=started + 0.15)
                self.assertLess(time.monotonic() - started, 0.5)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(2)

    def test_intermediate_tool_result_does_not_confirm_done(self):
        self.tasks.create(TASK, "x")
        with patch.object(self.api, "request", side_effect=[{}, [{
            "info": {"role": "assistant", "finish": "tool-calls"},
            "parts": [{"type": "text", "text": "working"}]}]]):
            self.assertEqual(self.tasks.status(TASK)["state"], "running")

    def test_unknown_upstream_loss_never_becomes_success(self):
        self.tasks.create(TASK, "x")
        record = self.tasks._get(TASK)
        record["state"] = "unknown"
        self.tasks._put(record)
        with patch.object(self.api, "request", side_effect=module.TaskFailure("opencode_unavailable")):
            with self.assertRaises(module.TaskFailure):
                self.tasks.status(TASK)
        self.assertEqual(self.tasks._get(TASK)["state"], "unknown")
        self.assertEqual(self.api.prompt_count, 1)

    def test_long_wait_deadline_includes_upstream_and_lock(self):
        self.tasks.create(TASK, "x")
        with patch.object(self.api, "request", wraps=self.api.request) as calls:
            self.tasks.status(TASK, deadline=time.monotonic() + 1)
        self.assertTrue(all("deadline" in c.kwargs for c in calls.call_args_list))
        from threading import Event, Thread
        ready, release = Event(), Event()
        def hold():
            with self.tasks.lock:
                ready.set()
                release.wait(2)
        worker = Thread(target=hold)
        worker.start()
        ready.wait(2)
        try:
            started = time.monotonic()
            with self.assertRaises(module.TaskFailure):
                self.tasks.status(TASK, deadline=started + 0.05)
            self.assertLess(time.monotonic() - started, 0.5)
        finally:
            release.set()
            worker.join(2)

    def test_gateway_404_timeout_disconnect_no_dispatch_or_secret_log(self):
        from http.server import ThreadingHTTPServer
        from threading import Thread
        self.tasks.create(TASK, "private-prompt")
        server = ThreadingHTTPServer(("127.0.0.1", 0),
            gateway.handler_factory("private-bearer", 4096, "private-password", self.tasks))
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        logs = io.StringIO()
        try:
            base = f"http://127.0.0.1:{server.server_port}"
            headers = {"Authorization": "Bearer private-bearer"}
            with redirect_stderr(logs):
                with self.assertRaises(HTTPError) as error:
                    urlopen(Request(base + "/tasks/00000000-0000-0000-0000-000000000000/wait?timeout=1", headers=headers))
                self.assertEqual(error.exception.code, 404)
                error.exception.close()
                with self.assertRaises(TimeoutError):
                    urlopen(Request(base + f"/tasks/{TASK}/wait?timeout=1", headers=headers), timeout=0.05)
                time.sleep(1.1)
                with urlopen(Request(base + f"/tasks/{TASK}", headers=headers)) as response:
                    self.assertEqual(json.load(response)["state"], "running")
            self.assertEqual(self.api.prompt_count, 1)
            self.assertEqual(logs.getvalue(), "")
        finally:
            server.shutdown()
            server.server_close()
            worker.join(2)

    def test_create_poll_restart_idempotent(self):
        self.assertEqual(self.tasks.create(TASK, "Проверь файл")["state"], "running")
        self.assertEqual(self.tasks.status(TASK)["state"], "running")
        reloaded = module.TaskManager(self.tasks.root, self.api)
        self.assertEqual(reloaded.create(TASK, "Проверь файл")["state"], "running")
        self.assertEqual(self.api.prompt_count, 1)
        self.api.incomplete = False
        self.assertEqual(reloaded.status(TASK), {"state": "done", "answer": "Итог готов"})

    def test_live_smoke_disables_every_tool(self):
        result = self.tasks.create(TASK, "Ответь текстом", disable_tools=True)
        self.assertEqual(result["state"], "running")
        self.assertEqual(self.api.last_prompt["tools"], {"*": False})
        self.assertEqual(self.api.prompt_count, 1)

    def test_abort_needs_acknowledgement(self):
        self.tasks.create(TASK, "Проверь файл")
        self.api.fail_abort = True
        with self.assertRaises(module.TaskFailure):
            self.tasks.cancel(TASK)
        self.assertEqual(self.tasks.status(TASK)["state"], "running")
        self.api.fail_abort = False
        self.assertTrue(self.tasks.cancel(TASK)["confirmed"])
        self.assertEqual(self.tasks.status(TASK)["state"], "cancelled")

    def test_rejects_unknown_task_and_bad_input(self):
        with self.assertRaises(module.TaskFailure):
            self.tasks.status(TASK)
        with self.assertRaises(module.TaskFailure):
            self.tasks.create(TASK, "")
        with self.assertRaises(module.TaskFailure):
            self.tasks.create("../bad", "x")

    def test_long_wait_returns_terminal_and_never_reexecutes(self):
        from http.server import ThreadingHTTPServer
        from threading import Thread
        self.tasks.create(TASK, "Проверь файл")
        self.api.incomplete = False
        server = ThreadingHTTPServer(("127.0.0.1", 0),
            gateway.handler_factory("secret-pair", 4096, "server-pass", self.tasks))
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            base = f"http://127.0.0.1:{server.server_port}/tasks/{TASK}/wait"
            request = Request(base + "?timeout=1",
                headers={"Authorization": "Bearer secret-pair"})
            with urlopen(request, timeout=5) as response:
                result = json.load(response)
            self.assertEqual(result["state"], "done")
            self.assertEqual(result["answer"], "Итог готов")
            self.assertEqual(self.api.prompt_count, 1)
            with patch.object(self.tasks, "status", wraps=self.tasks.status) as status:
                before = time.monotonic()
                with urlopen(Request(base + "?timeout=20",
                    headers={"Authorization": "Bearer secret-pair"}), timeout=5) as response:
                    self.assertEqual(json.load(response)["state"], "done")
                self.assertLessEqual(status.call_args.kwargs["deadline"] - before, 15.1)
            with self.assertRaises(HTTPError) as exc:
                urlopen(Request(base + "?timeout=200",
                    headers={"Authorization": "Bearer secret-pair"}), timeout=5)
            self.assertEqual(exc.exception.code, 400)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=2)

    def test_gateway_refuses_post_without_opt_in(self):
        from http.server import ThreadingHTTPServer
        from threading import Thread
        server = ThreadingHTTPServer(("127.0.0.1", 0),
            gateway.handler_factory("secret-pair", 4096, "server-pass"))
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/tasks"
            with self.assertRaises(HTTPError) as e:
                urlopen(Request(url, data=json.dumps({"id": TASK, "prompt": "x"}).encode(),
                                headers={"Authorization": "Bearer secret-pair",
                                         "Content-Type": "application/json"}))
            self.assertEqual(e.exception.code, 405)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=2)

if __name__ == "__main__":
    unittest.main()
