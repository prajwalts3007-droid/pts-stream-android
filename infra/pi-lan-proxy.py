#!/usr/bin/env python3
"""LAN-only TLS relay. Media authorization remains with the loopback streamer."""
import http.client
import http.server
import ipaddress
import os
import ssl
import urllib.parse


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass  # Signed query strings are credentials.

    def reply(self, status, body=b""):
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "private, no-store")
        self.end_headers()
        if self.command == "GET":
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path == "/lan-health" and not parsed.query:
            self.reply(200, b"pts-pi-lan-v1")
            return
        if parsed.scheme or parsed.netloc or parsed.path != "/media" or len(self.path) > 8192:
            self.reply(404)
            return
        upstream = http.client.HTTPConnection("127.0.0.1", int(os.getenv("UPSTREAM_PORT", "8787")), timeout=60)
        sent = False
        try:
            headers = {name: self.headers[name] for name in ("Range", "If-Range") if self.headers.get(name)}
            upstream.request(self.command, self.path, headers=headers)
            response = upstream.getresponse()
            self.send_response(response.status)
            for name, value in response.getheaders():
                if name.lower() in {"content-length", "content-type", "content-range", "accept-ranges", "etag", "last-modified"}:
                    self.send_header(name, value)
            self.send_header("Cache-Control", "private, no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            sent = True
            if self.command == "GET":
                while True:
                    chunk = response.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        except (OSError, http.client.HTTPException):
            if not sent:
                self.reply(502)
        finally:
            self.close_connection = True
            upstream.close()


def main():
    address = os.environ["LAN_ADDRESS"]
    ip = ipaddress.IPv4Address(address)
    if not any(ip in ipaddress.IPv4Network(net) for net in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")):
        raise ValueError("A private LAN address is required")
    credentials = os.environ["CREDENTIALS_DIRECTORY"]
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(credentials + "/server.crt", credentials + "/server.key")
    server = http.server.ThreadingHTTPServer((address, int(os.getenv("LAN_PORT", "4443"))), Handler)
    server.daemon_threads = True
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
