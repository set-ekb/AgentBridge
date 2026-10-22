import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from http.server import ThreadingHTTPServer
from threading import Thread
from urllib.request import Request, urlopen
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location(
    "gateway", Path(__file__).with_name("opencode_bridge_gateway.py")
)
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)


class GatewayTests(unittest.TestCase):
    def test_private_file_creation_and_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config" / "pairing-token"
            token = gateway.private_file(path, create=True)
            self.assertEqual(token, gateway.private_file(path))
            self.assertEqual(path.stat().st_mode & 0o077, 0)

    def test_rejects_group_readable_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "token"
            path.write_text("not-secret")
            path.chmod(0o644)
            with self.assertRaises(RuntimeError):
                gateway.private_file(path)

    def test_loopback_auth_and_read_only_endpoints(self):
        server = ThreadingHTTPServer(
            ("127.0.0.1", 0),
            gateway.handler_factory("pairing-secret", 4096, "server-secret"),
        )
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        url = "http://127.0.0.1:" + str(server.server_port)
        try:
            with self.assertRaises(HTTPError) as e:
                urlopen(url + "/status")
            self.assertEqual(e.exception.code, 401)

            with patch.object(gateway, "sanitized_status", return_value={
                "gateway": "agentbridge-opencode", "mode": "read_only",
                "healthy": True, "version": "1.19.3", "active_sessions": 0,
            }):
                request = Request(url + "/status", headers={
                    "Authorization": "Bearer pairing-secret",
                })
                with urlopen(request) as response:
                    self.assertEqual(response.code, 200)
                    self.assertIn(b'"healthy": true', response.read())

            with self.assertRaises(HTTPError) as e:
                urlopen(Request(url + "/status", method="POST",
                                headers={"Authorization": "Bearer pairing-secret"}))
            self.assertEqual(e.exception.code, 405)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
