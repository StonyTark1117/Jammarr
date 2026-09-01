#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import io
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).with_name("prepare-mojang-server.py")
SPEC = importlib.util.spec_from_file_location("prepare_mojang_server", SCRIPT)
assert SPEC and SPEC.loader
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)


class PrepareMojangServerTest(unittest.TestCase):
    @staticmethod
    def jar_bytes(entry: str = "net/minecraft/server/Main.class") -> bytes:
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr(entry, b"fixture")
        return output.getvalue()

    def fixture(
        self, root: Path, *, version: str = "1.20.1", include_server: bool = True
    ) -> tuple[str, bytes]:
        server = self.jar_bytes()
        server_path = root / "remote-server.jar"
        server_path.write_bytes(server)
        metadata = {"id": version, "downloads": {}}
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
                    "id": version,
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
            self.assertTrue(value["officialMojangDownload"])
            self.assertTrue(value["unmoddedVanillaServer"])
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

    def test_legacy_beta_archive_fallback_is_pinned_and_attested(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest_url, _ = self.fixture(
                root, version="b1.7.3", include_server=False
            )
            archived = self.jar_bytes("net/minecraft/server/Beta.class")
            archived_path = root / "archived-beta-server.jar"
            archived_path.write_bytes(archived)
            fallback = {
                "b1.7.3": {
                    "url": archived_path.as_uri(),
                    "sha1": hashlib.sha1(archived).hexdigest(),
                    "size": len(archived),
                    "source": "Pinned test archive",
                    "provenanceUrl": "https://example.invalid/b1.7.3.json",
                }
            }
            attestation_path = HELPER.resolve_server(
                "b1.7.3", root / "cache", manifest_url, fallback
            )
            value = json.loads(attestation_path.read_text("utf-8"))
            self.assertEqual(
                (root / "cache/b1.7.3/server.jar").read_bytes(), archived
            )
            self.assertFalse(value["officialMojangDownload"])
            self.assertTrue(value["unmoddedVanillaServer"])
            self.assertEqual(value["source"], "Pinned test archive")
            self.assertEqual(value["provenanceUrl"], fallback["b1.7.3"]["provenanceUrl"])

    def test_server_containing_jammarr_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest_url, _ = self.fixture(root)
            server_path = root / "remote-server.jar"
            contaminated = self.jar_bytes("stonytark/jammarr/Jammarr.class")
            server_path.write_bytes(contaminated)
            metadata_path = root / "version.json"
            metadata = json.loads(metadata_path.read_text("utf-8"))
            metadata["downloads"]["server"]["sha1"] = hashlib.sha1(contaminated).hexdigest()
            metadata["downloads"]["server"]["size"] = len(contaminated)
            metadata_payload = json.dumps(metadata).encode()
            metadata_path.write_bytes(metadata_payload)
            manifest_path = root / "manifest.json"
            manifest = json.loads(manifest_path.read_text("utf-8"))
            manifest["versions"][0]["sha1"] = hashlib.sha1(metadata_payload).hexdigest()
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "contains Jammarr entries"):
                HELPER.resolve_server("1.20.1", root / "cache", manifest_url)


if __name__ == "__main__":
    unittest.main()
