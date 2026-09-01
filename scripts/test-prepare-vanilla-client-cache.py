#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
SCRIPT = SCRIPT_DIR / "prepare-vanilla-client-cache.py"
SPEC = importlib.util.spec_from_file_location("prepare_vanilla_client_cache", SCRIPT)
assert SPEC and SPEC.loader
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


def descriptor(path: Path) -> dict[str, object]:
    value = path.read_bytes()
    return {
        "url": path.as_uri(),
        "sha1": hashlib.sha1(value).hexdigest(),
        "size": len(value),
    }


class PrepareVanillaClientCacheTest(unittest.TestCase):
    def test_manifest_derives_all_current_modern_versions(self) -> None:
        manifest = PREPARE.target_matrix.load_manifest(Path("gradle/targets.json"))
        versions = PREPARE.modern_versions(manifest)
        self.assertEqual(versions[0], "1.12.2")
        self.assertIn("1.20", versions)
        self.assertIn("1.21.11", versions)
        self.assertEqual(versions[-1], "26.2")
        self.assertNotIn("1.8.9", versions)
        self.assertEqual(len(versions), len(set(versions)))

    def test_prepare_version_resolves_complete_tiny_fixture(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            remote = root / "remote"
            metadata_root = remote / "meta"
            objects_root = remote / "objects"
            library_file = remote / "library.jar"
            client_file = remote / "client.jar"
            asset_file = objects_root / "placeholder"
            library_file.parent.mkdir(parents=True, exist_ok=True)
            library_file.write_bytes(b"library")
            client_file.write_bytes(b"client")
            asset_bytes = b"asset"
            asset_hash = hashlib.sha1(asset_bytes).hexdigest()
            asset_file = objects_root / asset_hash[:2] / asset_hash
            asset_file.parent.mkdir(parents=True)
            asset_file.write_bytes(asset_bytes)
            index_file = remote / "assets.json"
            index_file.write_text(
                json.dumps({"objects": {"fixture": {"hash": asset_hash, "size": len(asset_bytes)}}}),
                encoding="utf-8",
            )

            lwjgl = metadata_root / "org.lwjgl3/3.3.3.json"
            lwjgl.parent.mkdir(parents=True)
            lwjgl.write_text(
                json.dumps(
                    {
                        "uid": "org.lwjgl3",
                        "version": "3.3.3",
                        "libraries": [
                            {
                                "name": "org.lwjgl:lwjgl:3.3.3",
                                "downloads": {"artifact": descriptor(library_file)},
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            minecraft = metadata_root / "net.minecraft/1.21.11.json"
            minecraft.parent.mkdir(parents=True)
            minecraft.write_text(
                json.dumps(
                    {
                        "uid": "net.minecraft",
                        "version": "1.21.11",
                        "requires": [{"uid": "org.lwjgl3", "suggests": "3.3.3"}],
                        "libraries": [],
                        "mainJar": {
                            "name": "com.mojang:minecraft:1.21.11:client",
                            "downloads": {"artifact": descriptor(client_file)},
                        },
                        "assetIndex": {"id": "fixture", **descriptor(index_file)},
                    }
                ),
                encoding="utf-8",
            )
            cache = PREPARE.VerifiedCache(
                root / "shared",
                root / "cache",
                metadata_root_url=metadata_root.as_uri(),
                asset_objects_base_url=objects_root.as_uri(),
            )
            value = PREPARE.prepare_version(cache, "1.21.11")
            self.assertEqual(value["componentUids"], ["org.lwjgl3", "net.minecraft"])
            self.assertEqual(value["classpathEntryCount"], 2)
            self.assertEqual(value["assetObjectCount"], 1)
            self.assertTrue(value["allArtifactSha1AndSizeVerified"])
            self.assertFalse(value["sharedCacheMutated"])


if __name__ == "__main__":
    unittest.main()
