#!/usr/bin/env python3
"""Exercise the real wrapper against local TCP resets, without external downloads."""
import hashlib
import http.server
import io
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAVA8 = Path(os.environ.get('JAMMARR_JAVA8_HOME', '/usr/lib/jvm/java-8-openjdk'))
JAVA21 = Path(os.environ.get('JAMMARR_JAVA21_HOME', '/usr/lib/jvm/java-21-openjdk'))
WRAPPER_SHA256 = '497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7'


class WrapperDownloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory(prefix='jammarr-wrapper-test-')
        directory = Path(cls.fixture.name)
        source = directory / 'GradleMain.java'
        source.write_text('''package org.gradle.launcher;
public class GradleMain {
    public static void main(String[] args) { System.out.println("BOOTSTRAP_OK"); }
}
''')
        subprocess.run([str(JAVA8 / 'bin/javac'), '-J-Xmx128m', '-d', str(directory), str(source)],
                       check=True, capture_output=True, text=True, timeout=30)
        launcher = io.BytesIO()
        with zipfile.ZipFile(launcher, 'w') as archive:
            name = 'org/gradle/launcher/GradleMain.class'
            archive.writestr(name, (directory / name).read_bytes())
        distribution = io.BytesIO()
        with zipfile.ZipFile(distribution, 'w') as archive:
            for name in ['gradle-fixture/', 'gradle-fixture/lib/', 'gradle-fixture/bin/']:
                archive.writestr(name, '')
            archive.writestr('gradle-fixture/lib/gradle-launcher-fixture.jar', launcher.getvalue())
            archive.writestr('gradle-fixture/bin/gradle', '#!/bin/sh\nexit 0\n')
        cls.distribution = distribution.getvalue()

    @classmethod
    def tearDownClass(cls):
        cls.fixture.cleanup()

    def bootstrap(self, wrapper, java, resets, checksum=None):
        payload = self.distribution
        requests = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                requests.append(self.path)
                if len(requests) <= resets:
                    # A TCP reset reproduces SocketException: Connection reset from CI.
                    self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
                    self.connection.close()
                    self.close_connection = True
                    return
                self.send_response(200)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            with tempfile.TemporaryDirectory(prefix='jammarr-wrapper-download-') as temporary:
                directory = Path(temporary)
                jar = directory / 'gradle-wrapper.jar'
                shutil.copyfile(ROOT / wrapper, jar)
                original = (ROOT / wrapper).with_suffix('.properties').read_text()
                properties = dict(line.split('=', 1) for line in original.splitlines()
                                  if '=' in line and not line.startswith('#'))
                properties.update({
                    'distributionUrl': f'http://127.0.0.1:{server.server_port}/gradle-fixture.zip',
                    'distributionSha256Sum': checksum or hashlib.sha256(payload).hexdigest(),
                    # Keep the repository's retry count, shortening only test backoff.
                    'retryBackOffMs': '1',
                    'networkTimeout': '1000',
                })
                jar.with_suffix('.properties').write_text(''.join(f'{k}={v}\n' for k, v in properties.items()))
                result = subprocess.run([
                    str(java / 'bin/java'), '-Xmx128m', f'-Dgradle.user.home={directory / "cache"}',
                    '-classpath', str(jar), 'org.gradle.wrapper.GradleWrapperMain', '--version',
                ], cwd=directory, text=True, capture_output=True, timeout=20)
                return result, len(requests)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=5)

    def test_root_wrapper_recovers_from_connection_resets(self):
        result, requests = self.bootstrap(Path('gradle/wrapper/gradle-wrapper.jar'), JAVA21, resets=4)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('BOOTSTRAP_OK', result.stdout)
        self.assertEqual(requests, 5)

    def test_nested_wrapper_recovers_on_java8(self):
        result, requests = self.bootstrap(
            Path('platforms/mc1.20.5/neoforge/gradle/wrapper/gradle-wrapper.jar'), JAVA8, resets=4)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('BOOTSTRAP_OK', result.stdout)
        self.assertEqual(requests, 5)

    def test_permanent_connection_failure_still_fails_with_bounded_retries(self):
        result, requests = self.bootstrap(Path('gradle/wrapper/gradle-wrapper.jar'), JAVA21, resets=100)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Connection reset', result.stderr)
        self.assertNotIn('BOOTSTRAP_OK', result.stdout)
        self.assertLessEqual(requests, 8)

    def test_bad_distribution_checksum_still_fails(self):
        result, _ = self.bootstrap(Path('gradle/wrapper/gradle-wrapper.jar'), JAVA21,
                                   resets=0, checksum='0' * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Verification of Gradle distribution failed', result.stdout + result.stderr)
        self.assertNotIn('BOOTSTRAP_OK', result.stdout)

    def test_all_wrappers_use_the_verified_retry_capable_bootstrap(self):
        paths = subprocess.check_output(['git', 'ls-files', '*gradle-wrapper.jar'], cwd=ROOT, text=True).splitlines()
        self.assertTrue(paths)
        for name in paths:
            with self.subTest(wrapper=name):
                jar = ROOT / name
                self.assertEqual(hashlib.sha256(jar.read_bytes()).hexdigest(), WRAPPER_SHA256)
                properties = dict(line.split('=', 1) for line in jar.with_suffix('.properties').read_text().splitlines()
                                  if '=' in line and not line.startswith('#'))
                self.assertEqual(properties.get('retries'), '3')
                self.assertEqual(properties.get('retryBackOffMs'), '1000')


if __name__ == '__main__':
    unittest.main()
