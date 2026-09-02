#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import io
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import URLError


SCRIPT = Path(__file__).with_name("prism_verified_cache.py")
SPEC = importlib.util.spec_from_file_location("prism_verified_cache", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
CACHE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CACHE)


def descriptor(path: Path) -> dict[str, object]:
    value = path.read_bytes()
    return {
        "url": path.as_uri(),
        "sha1": hashlib.sha1(value).hexdigest(),
        "size": len(value),
    }


class PrismVerifiedCacheTest(unittest.TestCase):
    def test_library_prefers_verified_shared_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            remote = root / "remote.jar"
            remote.write_bytes(b"verified")
            library = {
                "name": "example:library:1.0",
                "downloads": {"artifact": descriptor(remote)},
            }
            shared_path = CACHE.maven_path(root / "shared/libraries", library["name"])
            shared_path.parent.mkdir(parents=True)
            shared_path.write_bytes(remote.read_bytes())
            cache = CACHE.VerifiedCache(root / "shared", root / "isolated")
            self.assertEqual(cache.library(library), shared_path)
            self.assertEqual(cache.library(library), shared_path)
            self.assertEqual(cache.attestation()["artifactSourceCounts"], {"shared-cache": 1})

    def test_library_replaces_corrupt_isolated_bytes_without_mutating_shared(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            remote = root / "remote.jar"
            remote.write_bytes(b"verified")
            library = {
                "name": "example:library:1.0",
                "downloads": {"artifact": descriptor(remote)},
            }
            shared_path = CACHE.maven_path(root / "shared/libraries", library["name"])
            shared_path.parent.mkdir(parents=True)
            shared_path.write_bytes(b"bad")
            isolated = CACHE.maven_path(root / "isolated/libraries", library["name"])
            isolated.parent.mkdir(parents=True)
            isolated.write_bytes(b"also bad")
            cache = CACHE.VerifiedCache(root / "shared", root / "isolated")
            self.assertEqual(cache.library(library), isolated)
            self.assertEqual(isolated.read_bytes(), b"verified")
            self.assertEqual(shared_path.read_bytes(), b"bad")
            self.assertEqual(cache.attestation()["artifactSourceCounts"], {"downloaded": 1})
            self.assertEqual(len(list((root / "isolated/.locks").glob("*.lock"))), 1)

    def test_native_only_library_is_not_required_on_the_classpath(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            native = root / "jinput-platform-natives-linux.jar"
            native.write_bytes(b"native")
            library = {
                "name": "net.java.jinput:jinput-platform:2.0.5",
                "natives": {"linux": "natives-linux"},
                "downloads": {
                    "classifiers": {"natives-linux": descriptor(native)}
                },
            }
            cache = CACHE.VerifiedCache(root / "shared", root / "isolated")
            self.assertIsNone(cache.classpath_library(library))
            self.assertEqual(
                cache.native_library(library).read_bytes(), b"native"
            )

    def test_library_without_artifact_or_native_classifier_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cache = CACHE.VerifiedCache(root / "shared", root / "isolated")
            with self.assertRaisesRegex(SystemExit, "has no artifact download"):
                cache.classpath_library({"name": "example:missing:1.0"})

    def test_download_rejects_digest_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            remote = root / "remote.jar"
            remote.write_bytes(b"wrong")
            value = descriptor(remote)
            value["sha1"] = "0" * 40
            cache = CACHE.VerifiedCache(root / "shared", root / "isolated")
            with self.assertRaisesRegex(SystemExit, "failed verification"):
                cache.artifact(root / "missing", root / "isolated/file", value, "fixture")
            self.assertFalse((root / "isolated/file").exists())

    def test_download_retries_transport_failure_before_verification(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            value = b"verified"
            descriptor_value = {
                "url": "https://example.invalid/fixture.jar",
                "sha1": hashlib.sha1(value).hexdigest(),
                "size": len(value),
            }
            cache = CACHE.VerifiedCache(root / "shared", root / "isolated")
            with patch.object(
                CACHE,
                "urlopen",
                side_effect=[URLError("temporary reset"), io.BytesIO(value)],
            ) as open_mock, patch.object(CACHE.time, "sleep") as sleep_mock:
                result = cache.artifact(
                    root / "missing",
                    root / "isolated/fixture.jar",
                    descriptor_value,
                    "fixture",
                )
            self.assertEqual(result.read_bytes(), value)
            self.assertEqual(open_mock.call_count, 2)
            sleep_mock.assert_called_once_with(1)

    def test_component_metadata_download_is_isolated_and_identity_checked(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            remote = root / "remote/org.lwjgl3"
            remote.mkdir(parents=True)
            (remote / "3.3.3.json").write_text(
                json.dumps({"uid": "org.lwjgl3", "version": "3.3.3"}), encoding="utf-8"
            )
            cache = CACHE.VerifiedCache(
                root / "shared", root / "isolated", metadata_root_url=(root / "remote").as_uri()
            )
            self.assertEqual(cache.component_metadata("org.lwjgl3", "3.3.3")["version"], "3.3.3")
            self.assertTrue((root / "isolated/meta/org.lwjgl3/3.3.3.json").is_file())
            self.assertFalse((root / "shared/meta/org.lwjgl3/3.3.3.json").exists())

    def test_assets_materialize_verified_shared_and_downloaded_objects(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            object_remote = root / "objects-remote"
            shared = root / "shared"
            isolated = root / "isolated"
            first = b"shared object"
            second = b"downloaded object"
            first_hash = hashlib.sha1(first).hexdigest()
            second_hash = hashlib.sha1(second).hexdigest()
            first_path = shared / "assets/objects" / first_hash[:2] / first_hash
            first_path.parent.mkdir(parents=True)
            first_path.write_bytes(first)
            second_path = object_remote / second_hash[:2] / second_hash
            second_path.parent.mkdir(parents=True)
            second_path.write_bytes(second)
            index = root / "index.json"
            index.write_text(
                json.dumps(
                    {
                        "objects": {
                            "first": {"hash": first_hash, "size": len(first)},
                            "second": {"hash": second_hash, "size": len(second)},
                        }
                    }
                ),
                encoding="utf-8",
            )
            metadata = {"assetIndex": {"id": "fixture", **descriptor(index)}}
            cache = CACHE.VerifiedCache(
                shared,
                isolated,
                asset_objects_base_url=object_remote.as_uri(),
            )
            assets, index_id, count = cache.assets(metadata)
            self.assertEqual((index_id, count), ("fixture", 2))
            first_resolved = assets / "objects" / first_hash[:2] / first_hash
            second_resolved = assets / "objects" / second_hash[:2] / second_hash
            self.assertTrue(first_resolved.is_symlink())
            self.assertEqual(first_resolved.read_bytes(), first)
            self.assertEqual(second_resolved.read_bytes(), second)
            self.assertEqual(
                cache.attestation()["artifactSourceCounts"],
                {"downloaded": 2, "shared-cache": 1},
            )


if __name__ == "__main__":
    unittest.main()
