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
                "java": {"build": 21},
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
                        "runtimeCapabilities": {"control": "console"},
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


if __name__ == "__main__":
    unittest.main()
