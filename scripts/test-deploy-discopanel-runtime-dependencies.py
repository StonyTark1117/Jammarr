#!/usr/bin/env python3
"""Tests for exact DiscPanel runtime dependency deployment."""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("deploy-discopanel-runtime-dependencies.py")
SPEC = importlib.util.spec_from_file_location(
    "deploy_discopanel_runtime_dependencies", SCRIPT
)
assert SPEC and SPEC.loader
dependency_deployment = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = dependency_deployment
SPEC.loader.exec_module(dependency_deployment)


class DiscPanelRuntimeDependencyTests(unittest.TestCase):
    def test_checked_in_manifest_has_pinned_babric_dependency(self) -> None:
        dependencies = dependency_deployment.load_dependencies(
            Path("gradle/discopanel-runtime-dependencies.json"), "b1.7.3-babric"
        )
        self.assertEqual(len(dependencies), 1)
        self.assertEqual(dependencies[0].dependency_id, "stationapi")
        self.assertEqual(len(dependencies[0].sha256), 64)
        self.assertTrue(dependencies[0].url.startswith("https://"))

    def test_checked_in_manifest_covers_every_external_runtime_family(self) -> None:
        path = Path("gradle/discopanel-runtime-dependencies.json")
        manifest = json.loads(path.read_text("utf-8"))
        self.assertEqual(len(manifest["runtimes"]), 53)
        self.assertEqual(
            sum(len(dependencies) for dependencies in manifest["runtimes"].values()),
            88,
        )
        for runtime, count in {
            "1.6.4-fabric": 1,
            "1.8.9-fabric": 14,
            "1.6.4-ornithe": 9,
            "1.8.9-ornithe": 15,
            "1.21.11-quilt": 1,
            "26.2-fabric": 1,
        }.items():
            self.assertEqual(
                len(dependency_deployment.load_dependencies(path, runtime)), count
            )

    def test_legacy_fabric_manifest_uses_official_nested_bundle(self) -> None:
        dependencies = dependency_deployment.load_dependencies(
            Path("gradle/discopanel-runtime-dependencies.json"), "1.6.4-fabric"
        )
        self.assertEqual(len(dependencies), 1)
        bundle = dependencies[0]
        self.assertEqual(bundle.filename, "legacy-fabric-api-1.13.2.jar")
        self.assertTrue(bundle.replaces_multiple_active)
        self.assertIn(
            "legacy-fabric-command-api-v1-1.1.1+0b2a4bcd8772.jar",
            bundle.owned_prefixes,
        )

    def test_legacy_fabric_1_8_9_uses_only_jammarr_api_closure(self) -> None:
        dependencies = dependency_deployment.load_dependencies(
            Path("gradle/discopanel-runtime-dependencies.json"), "1.8.9-fabric"
        )
        ids = {dependency.dependency_id for dependency in dependencies}
        self.assertEqual(len(dependencies), 14)
        self.assertIn("legacy-fabric-networking-api-v1", ids)
        self.assertIn("legacy-fabric-resource-loader-v1", ids)
        self.assertNotIn("legacy-fabric-registry-sync-api-v2", ids)
        descriptor = dependencies[0]
        self.assertEqual(
            descriptor.filename, "legacy-fabric-api-1.13.2+1.8.9.jar"
        )
        self.assertTrue(descriptor.replaces_multiple_active)

    def test_exact_disabled_dependency_is_reenabled_and_verified(self) -> None:
        dependency = dependency_deployment.Dependency(
            "api",
            "api-1.jar",
            "https://example.invalid/api.jar",
            "0" * 64,
            ("api-1.jar", "api-bundle.jar"),
            True,
        )
        exact = {"id": "new", "fileName": "api-1.jar", "enabled": False}
        old = {"id": "old", "fileName": "api-bundle.jar", "enabled": True}

        class Panel:
            def __init__(self) -> None:
                self.mods = [exact, old]

            def get_server(self, server_id: str) -> dict[str, str]:
                return {"id": server_id, "status": dependency_deployment.reconciler.STATUS_STOPPED}

            def get_file(self, server_id: str, path: str) -> bytes:
                self.assert_path = path
                return b""

            def call(self, service: str, method: str, payload: dict) -> dict:
                if method == "UpdateMod":
                    mod = next(item for item in self.mods if item["id"] == payload["modId"])
                    mod["enabled"] = payload["enabled"]
                    return {}
                if method == "ListMods":
                    return {"mods": self.mods}
                raise AssertionError((service, method))

        panel = Panel()
        empty_digest = dependency_deployment.hashlib.sha256(b"").hexdigest()
        dependency = dependency_deployment.Dependency(
            dependency.dependency_id,
            dependency.filename,
            dependency.url,
            empty_digest,
            dependency.owned_prefixes,
            dependency.replaces_multiple_active,
        )
        dependency_deployment.enable_existing_dependency(
            panel, {"id": "server"}, dependency, exact, [old]
        )
        self.assertTrue(exact["enabled"])
        self.assertFalse(old["enabled"])
        self.assertEqual(panel.assert_path, "mods/api-1.jar")

    def test_dependency_ownership_is_case_insensitive_and_not_jammarr(self) -> None:
        dependency = dependency_deployment.Dependency(
            "stationapi",
            "StationAPI-2.0.0-alpha.6.2.jar",
            "https://example.invalid/stationapi.jar",
            "0" * 64,
            ("stationapi-",),
        )
        self.assertTrue(
            dependency_deployment.owns(
                dependency, {"fileName": "StationAPI-2.0.0-alpha.6.2.jar"}
            )
        )
        self.assertFalse(
            dependency_deployment.owns(
                dependency, {"fileName": "jammarr-1.1.0.jar"}
            )
        )

    def test_manifest_rejects_unpinned_or_non_https_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "dependencies.json"
            path.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "runtimes": {
                            "test": [
                                {
                                    "id": "bad",
                                    "filename": "bad.jar",
                                    "url": "http://example.invalid/bad.jar",
                                    "sha256": "short",
                                    "ownedPrefixes": ["bad-"],
                                }
                            ]
                        },
                    }
                ),
                "utf-8",
            )
            with self.assertRaises(SystemExit):
                dependency_deployment.load_dependencies(path, "test")

    def test_manifest_rejects_overlapping_ownership_prefixes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "dependencies.json"
            path.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "runtimes": {
                            "test": [
                                {
                                    "id": "networking",
                                    "filename": "networking-1.jar",
                                    "url": "https://example.invalid/networking.jar",
                                    "sha256": "0" * 64,
                                    "ownedPrefixes": ["networking-"],
                                },
                                {
                                    "id": "networking-impl",
                                    "filename": "networking-impl-1.jar",
                                    "url": "https://example.invalid/networking-impl.jar",
                                    "sha256": "1" * 64,
                                    "ownedPrefixes": ["networking-impl-"],
                                },
                            ]
                        },
                    }
                ),
                "utf-8",
            )
            with self.assertRaisesRegex(SystemExit, "ownership is ambiguous"):
                dependency_deployment.load_dependencies(path, "test")


if __name__ == "__main__":
    unittest.main()
