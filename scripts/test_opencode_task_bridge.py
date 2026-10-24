import importlib.util
import json
import tempfile
import unittest
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

    def request(self, path, payload=None):
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
