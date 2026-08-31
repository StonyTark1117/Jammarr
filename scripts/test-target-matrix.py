#!/usr/bin/env python3
"""Unit tests for manifest-derived artifact and runtime matrices."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("target-matrix.py")
SPEC = importlib.util.spec_from_file_location("target_matrix", SCRIPT)
assert SPEC and SPEC.loader
target_matrix = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(target_matrix)


def fixture() -> dict:
    defaults = {
        loader: {
            "control": "rcon",
            "commandMarkers": "modern",
            "audioProfile": "modern",
            "logProfile": "latest",
            "disableConfigurationCache": loader in {"forge", "neoforge"},
        }
        for loader in ("fabric", "quilt", "forge", "neoforge")
    }
    return {
        "schemaVersion": 1,
        "runtimeGate": {"basePort": 26000, "defaults": defaults},
        "targets": [
            {
                "minecraft": "1.20.1",
                "java": {"build": 21, "runtime": 17},
                "loaders": [
                    {
                        "id": "fabric",
                        "implemented": True,
                        "quiltCompatible": True,
                        "path": "platforms/fabric",
                        "artifact": "fabric.jar",
                    },
                    {
                        "id": "forge",
                        "implemented": True,
                        "quiltCompatible": False,
                        "verificationTask": "verifySpecialForge",
                        "runtimeCapabilities": {
                            "control": "console", "commandMarkers": "legacy-response"
                        },
                        "path": "platforms/forge",
                        "artifact": "forge.jar",
                    },
                ],
            }
        ],
    }


class TargetMatrixTests(unittest.TestCase):
    def test_derives_artifacts_and_quilt_runtime(self) -> None:
        manifest = fixture()
        artifacts = target_matrix.implemented_artifacts(manifest)
        runtimes = target_matrix.runtimes(manifest)
        self.assertEqual([entry["task"] for entry in artifacts],
                         ["verifyFabric1201", "verifySpecialForge"])
        self.assertEqual([entry["name"] for entry in runtimes],
                         ["1.20.1-fabric", "1.20.1-quilt", "1.20.1-forge"])
        self.assertEqual([entry["port"] for entry in runtimes], [26000, 26001, 26002])
        self.assertEqual(runtimes[-1]["control"], "console")
        self.assertEqual(runtimes[-1]["commandMarkers"], "legacy-response")
        self.assertTrue(runtimes[-1]["disableConfigurationCache"])

    def test_duplicate_target_fails_closed(self) -> None:
        manifest = fixture()
        manifest["targets"][0]["loaders"].append(
            dict(manifest["targets"][0]["loaders"][0], artifact="other.jar")
        )
        with self.assertRaisesRegex(SystemExit, "duplicate implemented target"):
            target_matrix.implemented_artifacts(manifest)

    def test_missing_runtime_capability_fails_closed(self) -> None:
        manifest = fixture()
        del manifest["runtimeGate"]["defaults"]["fabric"]["audioProfile"]
        with self.assertRaisesRegex(SystemExit, "missing runtime capabilities"):
            target_matrix.runtimes(manifest)

    def test_client_companion_is_an_artifact_but_not_a_server_runtime(self) -> None:
        manifest = fixture()
        manifest["targets"][0]["loaders"].append(
            {
                "id": "liteloader",
                "implemented": True,
                "quiltCompatible": False,
                "runtimeMode": "client-companion",
                "pairedServerLoader": "forge",
                "path": "platforms/liteloader",
                "artifact": "client.litemod",
            }
        )
        artifacts = target_matrix.implemented_artifacts(manifest)
        runtimes = target_matrix.runtimes(manifest)
        companions = target_matrix.client_companions(manifest)
        self.assertEqual(artifacts[-1]["runtimeMode"], "client-companion")
        self.assertEqual(artifacts[-1]["pairedServerLoader"], "forge")
        self.assertNotIn("1.20.1-liteloader", [entry["name"] for entry in runtimes])
        self.assertEqual(companions, [{
            "name": "1.20.1-liteloader",
            "pairedRuntime": "1.20.1-forge",
            "path": "platforms/liteloader",
            "buildJava": 21,
            "runtimeJava": 17,
            "clientTask": "runClient",
        }])

    def test_client_companion_can_select_a_production_launch_task(self) -> None:
        manifest = fixture()
        manifest["targets"][0]["loaders"].append(
            {
                "id": "liteloader",
                "implemented": True,
                "quiltCompatible": False,
                "runtimeMode": "client-companion",
                "pairedServerLoader": "forge",
                "clientTask": "runLiteLoaderClient",
                "path": "platforms/liteloader",
                "artifact": "client.litemod",
            }
        )
        self.assertEqual(
            target_matrix.client_companions(manifest)[0]["clientTask"],
            "runLiteLoaderClient",
        )

    def test_client_companion_requires_a_full_paired_server(self) -> None:
        manifest = fixture()
        manifest["targets"][0]["loaders"].append(
            {
                "id": "liteloader",
                "implemented": True,
                "quiltCompatible": False,
                "runtimeMode": "client-companion",
                "pairedServerLoader": "neoforge",
                "path": "platforms/liteloader",
                "artifact": "client.litemod",
            }
        )
        with self.assertRaisesRegex(SystemExit, "missing full server target"):
            target_matrix.client_companions(manifest)


if __name__ == "__main__":
    unittest.main()
