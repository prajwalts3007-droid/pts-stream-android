import importlib.util
import http.client
import http.server
import os
import pathlib
import threading
import unittest

spec = importlib.util.spec_from_file_location("proxy", pathlib.Path(__file__).with_name("pi-lan-proxy.py"))
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)


class Upstream(http.server.BaseHTTPRequestHandler):
    last = None

    def do_GET(self):
        type(self).last = (self.path, dict(self.headers))
        if "sig=valid-fixture" not in self.path:
            self.send_response(403)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        partial = self.headers.get("Range") == "bytes=2-5"
        data = b"2345" if partial else b"0123456789"
        self.send_response(206 if partial else 200)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Type", "video/mp4")
        if partial:
            self.send_header("Content-Range", "bytes 2-5/10")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_):
        pass


class ProxyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.upstream = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        os.environ["UPSTREAM_PORT"] = str(cls.upstream.server_port)
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), proxy.Handler)
        for server in (cls.upstream, cls.server):
            threading.Thread(target=server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        for server in (cls.upstream, cls.server):
            server.shutdown()
            server.server_close()

    def request(self, path, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        connection.request("GET", path, headers=headers or {})
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def test_signed_range_preserved_without_account_credentials(self):
        status, headers, data = self.request("/media?p=fixture&sig=valid-fixture", {"Range": "bytes=2-5", "Cookie": "session=private", "Authorization": "Bearer private"})
        self.assertEqual((206, b"2345"), (status, data))
        self.assertEqual("bytes 2-5/10", headers["Content-Range"])
        self.assertEqual("/media?p=fixture&sig=valid-fixture", Upstream.last[0])
        self.assertNotIn("Cookie", Upstream.last[1])
        self.assertNotIn("Authorization", Upstream.last[1])

    def test_unauthorized_media_is_still_forbidden(self):
        self.assertEqual(403, self.request("/media?p=fixture&sig=bad")[0])

    def test_only_media_and_health_are_exposed(self):
        self.assertEqual(b"pts-pi-lan-v1", self.request("/lan-health")[2])
        for path in ("/api/v1/auth/me", "/etc/passwd", "http://other.invalid/media", "/media/../admin"):
            self.assertEqual(404, self.request(path)[0])


if __name__ == "__main__":
    unittest.main()
