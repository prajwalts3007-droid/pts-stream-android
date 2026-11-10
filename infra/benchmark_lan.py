#!/usr/bin/env python3
"""One bounded Pi-self benchmark. Not a phone/Wi-Fi test. Never touches library media."""
import base64
import hashlib
import hmac
import http.client
import json
import os
import pathlib
import socket
import ssl
import tempfile
import time
import urllib.parse


def main():
    key = pathlib.Path('/etc/pts-media/signing-key').read_bytes().strip()
    tls = ssl.create_default_context(cafile='/opt/pts-lan/server.crt')
    address = os.environ.get('LAN_ADDRESS', '192.168.29.75')
    with tempfile.TemporaryDirectory(prefix='.pts-speed-check-', dir='/DATA/Movie') as folder:
        os.chmod(folder, 0o755)
        fixture = pathlib.Path(folder) / 'generated-throughput.bin'
        payload = bytes(range(256)) * 16384  # 4 MiB; removed even after failure.
        fixture.write_bytes(payload)
        p = base64.urlsafe_b64encode(str(fixture.resolve()).encode()).decode().rstrip('=')
        expiry = str(int(time.time()) + 60)
        signature = hmac.new(key, '\n'.join((p, expiry, '', '')).encode(), hashlib.sha256).hexdigest()
        path = '/media?' + urllib.parse.urlencode(dict(p=p, e=expiry, a='', s='', sig=signature))
        result = {'scope': 'Pi-self only; excludes phone and radio path', 'bytes_per_request': len(payload)}
        for label, local in [('loopback_origin', False), ('verified_tls_relay', True)]:
            start = time.monotonic()
            connection = http.client.HTTPConnection('127.0.0.1', 8787, timeout=15)
            if local:
                connection = http.client.HTTPSConnection('media.ptsmovies.online', 4443, context=tls, timeout=15)
                connection.sock = tls.wrap_socket(socket.create_connection((address, 4443), timeout=15), server_hostname='media.ptsmovies.online')
            try:
                connection.request('GET', path)
                response = connection.getresponse()
                assert response.status == 200
                received = response.read(len(payload) + 1)
                assert received == payload
                elapsed = time.monotonic() - start
                result[label] = dict(seconds=round(elapsed, 4), mib_per_second=round(len(received) / elapsed / 1048576, 2))
            finally:
                connection.close()
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
