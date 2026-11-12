"""Offline release packaging checks. Does not claim installation/device execution."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

CERTIFICATE = '96b14e2e5f2a67dbbb9b2d091c62c800ff61d0a91e5c2e234dd35a28efac4ed7'


def load_alignments(data):
    assert data[:4] == b'\x7fELF', 'Invalid ELF library'
    bits = data[4]
    assert bits in (1, 2) and data[5] in (1, 2), 'Unsupported ELF header'
    endian = '<' if data[5] == 1 else '>'
    offset = struct.unpack_from(endian + ('Q' if bits == 2 else 'I'), data, 32 if bits == 2 else 28)[0]
    entry, count = struct.unpack_from(endian + 'HH', data, 54 if bits == 2 else 42)
    result = []
    for i in range(count):
        base = offset + i * entry
        if struct.unpack_from(endian + 'I', data, base)[0] != 1:
            continue
        if bits == 2:
            file_offset, address = struct.unpack_from(endian + 'QQ', data, base + 8)
            alignment = struct.unpack_from(endian + 'Q', data, base + 48)[0]
        else:
            file_offset, address = struct.unpack_from(endian + 'II', data, base + 4)
            alignment = struct.unpack_from(endian + 'I', data, base + 28)[0]
        assert alignment >= 16384 and (address - file_offset) % 16384 == 0, 'Library is not 16 KiB load aligned'
        result.append(alignment)
    assert result, 'No ELF load segments'
    return result


def verify(apk, version, build_tools):
    def run(tool, *args):
        return subprocess.check_output([str(build_tools / tool), *map(str, args)], text=True, stderr=subprocess.STDOUT)
    signature = run('apksigner', 'verify', '--verbose', '--print-certs', apk)
    digest = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', signature)
    assert digest and digest[1] == CERTIFICATE, 'Release signing identity changed'
    badging = run('aapt', 'dump', 'badging', apk)
    assert "package: name='online.ptsmovies.stream'" in badging
    assert f"versionCode='{version}'" in badging and f"versionName='1.0.{version}'" in badging
    assert "sdkVersion:'26'" in badging and "targetSdkVersion:'35'" in badging
    assert "launchable-activity: name='online.ptsmovies.stream.MainActivity'" in badging
    assert 'application-debuggable' not in badging and 'uses-split' not in badging
    assert "uses-feature: name='android.hardware.touchscreen'" not in badging, 'Touchscreen must remain optional'
    tree = run('aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml')
    for attribute in ['allowBackup', 'usesCleartextTraffic']:
        assert re.search(rf'{attribute}\([^\n]*\)=\(type 0x12\)0x0\b', tree), f'{attribute} must remain disabled'
    assert 'online.ptsmovies.stream.PtsApplication' in tree
    run('zipalign', '-c', '-P', '16', '-v', '4', apk)
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        assert 'classes.dex' in names
        assert all(n in names for n in ['assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm']), 'Dependency baseline profiles missing'
        assert not any(n.lower().endswith(('.mp4', '.mkv', '.m3u8', '.ts', '.jks', '.keystore')) for n in names), 'Fixture media or private signing file packaged'
        native = {n: load_alignments(archive.read(n)) for n in names if n.endswith('.so')}
        assert any('/arm64-v8a/' in n for n in native), 'Universal APK must support modern ARM phones'
        anchor = archive.read('res/raw/pts_lan_certificate.pem')
        source_anchor = Path('app/src/main/res/raw/pts_lan_certificate.pem').read_bytes()
        assert anchor == source_anchor, 'LAN trust anchor was changed or removed'
    return {'package': 'online.ptsmovies.stream', 'version_code': version, 'min_android_api': 26, 'target_android_api': 35,
            'bytes': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
            'signer_sha256': digest[1], 'native_load_alignments': native, 'zip_16k_alignment': True,
            'baseline_profiles_present': True, 'standalone_release_apk': True, 'device_installation_executed': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--version', required=True, type=int)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--build-tools', type=Path, default=Path(os.environ.get('ANDROID_HOME', '')) / 'build-tools/35.0.0')
    args = parser.parse_args()
    report = verify(args.apk, args.version, args.build_tools)
    text = json.dumps(report, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + '\n')
    print(text)
