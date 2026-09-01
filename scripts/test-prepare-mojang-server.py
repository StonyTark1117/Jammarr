#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("prepare-mojang-server.py")
SPEC = importlib.util.spec_from_file_location("prepare_mojang_server", SCRIPT)
assert SPEC and SPEC.loader
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)


class PrepareMojangServerTest(unittest.TestCase):
    def fixture(self, root: Path, *, include_server: bool = True) -> tuple[str, bytes]:
        server = b"exact-mojang-server-fixture"
        server_path = root / "remote-server.jar"
        server_path.write_bytes(server)
        metadata = {"id": "1.20.1", "downloads": {}}
        if include_server:
            metadata["downloads"]["server"] = {
                "url": server_path.as_uri(),
                "sha1": hashlib.sha1(server).hexdigest(),
                "size": len(server),
            }
        metadata_payload = json.dumps(metadata).encode()
        metadata_path = root / "version.json"
        metadata_path.write_bytes(metadata_payload)
        manifest = {
            "versions": [
                {
                    "id": "1.20.1",
                    "url": metadata_path.as_uri(),
                    "sha1": hashlib.sha1(metadata_payload).hexdigest(),
                }
            ]
        }
        manifest_path = root / "manifest.json"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        return manifest_path.as_uri(), server

    def test_resolves_and_reuses_exact_server(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest_url, expected = self.fixture(root)
            cache = root / "cache"
            attestation_path = HELPER.resolve_server("1.20.1", cache, manifest_url)
            value = json.loads(attestation_path.read_text("utf-8"))
            server = cache / "1.20.1/server.jar"
            self.assertEqual(server.read_bytes(), expected)
            self.assertFalse(value["cacheReused"])
            self.assertFalse(value["jammarrPresent"])
            attestation_path = HELPER.resolve_server("1.20.1", cache, manifest_url)
            value = json.loads(attestation_path.read_text("utf-8"))
            self.assertTrue(value["cacheReused"])

    def test_corrupt_cache_is_replaced(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest_url, expected = self.fixture(root)
            cache = root / "cache"
            server = cache / "1.20.1/server.jar"
            server.parent.mkdir(parents=True)
            server.write_bytes(b"corrupt")
            HELPER.resolve_server("1.20.1", cache, manifest_url)
            self.assertEqual(server.read_bytes(), expected)

    def test_missing_server_download_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest_url, _ = self.fixture(root, include_server=False)
            with self.assertRaisesRegex(SystemExit, "server download is missing"):
                HELPER.resolve_server("1.20.1", root / "cache", manifest_url)


if __name__ == "__main__":
    unittest.main()
