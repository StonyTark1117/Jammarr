#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
from pathlib import Path
import socket
import tempfile
import threading
import unittest


SCRIPT = Path(__file__).with_name("deferred-connection-relay.py")
SPEC = importlib.util.spec_from_file_location("deferred_connection_relay", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
RELAY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELAY)


class DeferredConnectionRelayTest(unittest.TestCase):
    def test_holds_then_forwards_one_loopback_connection(self) -> None:
        upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        upstream.bind(("127.0.0.1", 0))
        upstream.listen(1)
        accepted = threading.Event()

        def echo_once() -> None:
            connection, _ = upstream.accept()
            accepted.set()
            with connection:
                connection.sendall(connection.recv(16))

        server_thread = threading.Thread(target=echo_once, daemon=True)
        server_thread.start()
        with tempfile.TemporaryDirectory() as temporary:
            release_file = Path(temporary) / "release"
            relay = RELAY.DeferredConnectionRelay(
                f"127.0.0.1:{upstream.getsockname()[1]}"
            )
            relay_thread = threading.Thread(
                target=relay.run, args=(release_file,), daemon=True
            )
            relay_thread.start()
            try:
                with socket.create_connection(
                    ("127.0.0.1", int(relay.endpoint.rpartition(":")[2]))
                ) as client:
                    client.settimeout(0.1)
                    client.sendall(b"ready")
                    with self.assertRaises(socket.timeout):
                        client.recv(5)
                    self.assertFalse(accepted.is_set())
                    release_file.touch()
                    self.assertEqual(client.recv(5), b"ready")
                    self.assertTrue(accepted.wait(1))
            finally:
                relay.stop()
                relay_thread.join(timeout=2)
                upstream.close()
                server_thread.join(timeout=2)

    def test_rejects_non_loopback_or_invalid_targets(self) -> None:
        for target in ("example.com:25565", "127.0.0.1:0", "127.0.0.1:70000"):
            with self.subTest(target=target), self.assertRaises(ValueError):
                RELAY.DeferredConnectionRelay(target)


if __name__ == "__main__":
    unittest.main()
