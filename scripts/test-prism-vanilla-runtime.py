#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
import zipfile


SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


RUNTIME = load("prism_vanilla_runtime", SCRIPT_DIR / "prism_vanilla_runtime.py")
HELPER = load("run_prism_vanilla_client", SCRIPT_DIR / "run-prism-vanilla-client.py")


def descriptor(path: Path) -> dict[str, object]:
    value = path.read_bytes()
    return {
        "url": path.as_uri(),
        "sha1": hashlib.sha1(value).hexdigest(),
        "size": len(value),
    }


class PrismVanillaRuntimeTest(unittest.TestCase):
    def test_launch_uses_isolated_verified_fallbacks_and_assets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shared = root / "shared"
            for name in ("assets", "libraries", "meta", "java"):
                (shared / name).mkdir(parents=True)
            remote = root / "remote"
            metadata_root = remote / "meta"
            objects_root = remote / "objects"
            metadata_root.mkdir(parents=True)
            library = remote / "library.jar"
            native = remote / "lwjgl-3.3.3-natives-linux.jar"
            client = remote / "client.jar"
            library.write_bytes(b"library")
            with zipfile.ZipFile(native, "w") as archive:
                archive.writestr("linux/x64/liblwjgl.so", b"native")
            client.write_bytes(b"client")
            asset = b"asset"
            asset_hash = hashlib.sha1(asset).hexdigest()
            asset_path = objects_root / asset_hash[:2] / asset_hash
            asset_path.parent.mkdir(parents=True)
            asset_path.write_bytes(asset)
            index = remote / "index.json"
            index.write_text(
                json.dumps({"objects": {"fixture": {"hash": asset_hash, "size": len(asset)}}}),
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
                                "natives": {"linux": "natives-linux"},
                                "downloads": {
                                    "artifact": descriptor(library),
                                    "classifiers": {"natives-linux": descriptor(native)},
                                },
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            java_path = shutil.which("java")
            self.assertIsNotNone(java_path)
            java_major = HELPER.java_major(Path(java_path))
            self.assertIsNotNone(java_major)
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
                            "downloads": {"artifact": descriptor(client)},
                        },
                        "assetIndex": {"id": "fixture", **descriptor(index)},
                        "compatibleJavaMajors": [java_major],
                        "mainClass": "net.minecraft.client.main.Main",
                        "minecraftArguments": "--username ${auth_player_name} --gameDir ${game_directory} --assetsDir ${assets_root} --assetIndex ${assets_index_name}",
                    }
                ),
                encoding="utf-8",
            )
            args = argparse.Namespace(
                shared_root=shared,
                fallback_cache_root=root / "cache",
                metadata_root_url=metadata_root.as_uri(),
                asset_objects_base_url=objects_root.as_uri(),
                minecraft="1.21.11",
                server="127.0.0.1:25565",
                username="VanillaProbe",
            )
            game_dir = root / "instance/minecraft"
            game_dir.mkdir(parents=True)
            command, details = RUNTIME.direct_launch_command(args, game_dir, HELPER)
            expected_arguments = [
                "--username", "VanillaProbe", "--gameDir", str(game_dir),
                "--assetsDir", str((root / "cache/assets").resolve()),
                "--assetIndex", "fixture", "--quickPlayMultiplayer", "127.0.0.1:25565",
            ]
            self.assertEqual(command[-len(expected_arguments):], expected_arguments)
            self.assertEqual(details["classpathEntryCount"], 2)
            self.assertEqual(details["nativeBundleCount"], 1)
            self.assertEqual((game_dir.parent / "natives/liblwjgl.so").read_bytes(), b"native")
            self.assertEqual(details["assetObjectCount"], 1)
            self.assertTrue(details["allArtifactSha1AndSizeVerified"])
            self.assertFalse(details["sharedCacheMutated"])
            self.assertGreater(details["artifactSourceCounts"].get("downloaded", 0), 0)
            self.assertFalse(any(shared.rglob("*.jar")))


if __name__ == "__main__":
    unittest.main()
