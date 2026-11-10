#!/usr/bin/env python3
"""Run on the owned Pi. Tests only an ephemeral generated fixture, not library media."""
import base64
import hashlib
import hmac
import http.client
import os
import pathlib
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.parse

pid = subprocess.check_output(["systemctl", "show", "pts-media.service", "-p", "MainPID", "--value"], text=True).strip()
for value in pathlib.Path(f"/proc/{pid}/environ").read_bytes().split(b"\0"):
    if b"=" in value:
        key, data = value.split(b"=", 1)
        os.environ[key.decode()] = data.decode()
source = pathlib.Path("/opt/pts-media/server.py").read_text()
namespace = {}
exec(compile(source[:source.index("class Handler")], "owned-streamer-prefix", "exec"), namespace)
context = ssl.create_default_context(cafile="/opt/pts-lan/server.crt")


def request(path, headers=None):
    connection = http.client.HTTPSConnection("media.ptsmovies.online", 4443, context=context, timeout=10)
    connection.sock = context.wrap_socket(socket.create_connection(("192.168.29.75", 4443), timeout=10), server_hostname="media.ptsmovies.online")
    connection.request("GET", path, headers=headers or {})
    response = connection.getresponse()
    result = response.status, dict(response.getheaders()), response.read()
    connection.close()
    return result


with tempfile.TemporaryDirectory(prefix=".pts-lan-audit-", dir="/DATA/Movie") as folder:
    os.chmod(folder, 0o755)  # Existing streamer runs as the unprivileged ptsmedia user.
    fixture = pathlib.Path(folder) / "synthetic-range.bin"
    payload = bytes(range(256)) * 1024
    fixture.write_bytes(payload)
    p = base64.urlsafe_b64encode(str(fixture.resolve()).encode()).decode().rstrip("=")
    expiry = str(int(time.time()) + 60)
    signature = hmac.new(namespace["KEY"], "\n".join((p, expiry, "", "")).encode(), hashlib.sha256).hexdigest()
    query = urllib.parse.urlencode({"p": p, "e": expiry, "a": "", "s": "", "sig": signature})
    status, headers, data = request("/media?" + query, {"Range": "bytes=100-199"})
    assert status == 206 and data == payload[100:200], f"Synthetic range returned HTTP {status}"
    assert headers["Content-Range"] == f"bytes 100-199/{len(payload)}"
    assert request("/media?" + query.replace(signature, "0" * 64))[0] == 403
    assert request("/api/v1/auth/me")[0] == 404
    print("PASS: verified TLS identity; authenticated synthetic byte range 206; invalid signature 403; API isolation 404; fixture removed on exit")
