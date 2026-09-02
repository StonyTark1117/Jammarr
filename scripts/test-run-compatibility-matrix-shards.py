#!/usr/bin/env python3

from __future__ import annotations

import argparse
import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).with_name("run-compatibility-matrix-shards.py")
SPEC = importlib.util.spec_from_file_location("run_compatibility_matrix_shards", SCRIPT)
assert SPEC and SPEC.loader
SHARDS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SHARDS)


class CompatibilityMatrixShardsTest(unittest.TestCase):
    def test_candidate_suffix_selects_fresh_confined_output_roots(self) -> None:
        suffix = SHARDS.output_suffix("audio-a8f7000")
        vanilla = SHARDS.matrix_output_root("vanilla", suffix)
        unmodded = SHARDS.matrix_output_root("unmodded", suffix)
        self.assertEqual(vanilla.name, "vanilla-client-matrix-audio-a8f7000")
        self.assertEqual(
            unmodded.name, "unmodded-server-client-matrix-audio-a8f7000"
        )
        self.assertEqual(vanilla.parent, SHARDS.REPO_ROOT / "build")
        self.assertEqual(unmodded.parent, SHARDS.REPO_ROOT / "build")

    def test_candidate_suffix_rejects_path_and_shell_syntax(self) -> None:
        for value in ("../old", "/tmp/reuse", "audio latest", "audio;false", ""):
            with self.subTest(value=value):
                with self.assertRaises(argparse.ArgumentTypeError):
                    SHARDS.output_suffix(value)

    def test_partition_keeps_minecraft_cache_family_in_one_lane(self) -> None:
        runtimes = [
            {"name": "a-fabric", "minecraft": "a", "path": "platforms/a"},
            {"name": "a-quilt", "minecraft": "a", "path": "platforms/a"},
            {"name": "b-forge", "minecraft": "b", "path": "platforms/b"},
            {"name": "c-forge", "minecraft": "c", "path": "platforms/c"},
        ]
        lanes = SHARDS.partition_runtimes(runtimes, 2)
        lane_for = {
            runtime["name"]: index
            for index, lane in enumerate(lanes)
            for runtime in lane
        }
        self.assertEqual(lane_for["a-fabric"], lane_for["a-quilt"])
        self.assertEqual(sorted(len(lane) for lane in lanes), [2, 2])

    def test_actual_four_lane_plans_are_complete_and_balanced(self) -> None:
        manifest = SHARDS.target_matrix.load_manifest(Path("gradle/targets.json"))
        for kind, expected in (("vanilla", 91), ("unmodded", 99)):
            runtimes = SHARDS.selected_runtimes(kind, manifest)
            lanes = SHARDS.partition_runtimes(runtimes, 4)
            names = [runtime["name"] for lane in lanes for runtime in lane]
            self.assertEqual(len(names), expected)
            self.assertEqual(len(names), len(set(names)))
            self.assertLessEqual(max(map(len, lanes)) - min(map(len, lanes)), 1)
            lane_by_path: dict[str, set[int]] = {}
            lane_by_version: dict[str, set[int]] = {}
            for index, lane in enumerate(lanes):
                for runtime in lane:
                    lane_by_path.setdefault(runtime["path"], set()).add(index)
                    lane_by_version.setdefault(runtime["minecraft"], set()).add(index)
            self.assertTrue(all(len(indices) == 1 for indices in lane_by_path.values()))
            self.assertTrue(all(len(indices) == 1 for indices in lane_by_version.values()))

    def test_shard_and_verifier_commands_are_distinct(self) -> None:
        runtime = {
            "name": "1.20.1-fabric",
            "minecraft": "1.20.1",
            "path": "platforms/mc1.20.1/fabric",
        }
        common = {
            "kind": "vanilla",
            "manifest": Path("manifest.json"),
            "output_root": Path("output"),
            "summary": Path("summary.json"),
            "connected_seconds": 10,
        }
        shard = SHARDS.matrix_command(
            **common, runtimes=[runtime], continue_on_error=True
        )
        verifier = SHARDS.matrix_command(**common, runtimes=None, verify_only=True)
        self.assertIn("--resource-locks", shard)
        self.assertIn("--continue-on-error", shard)
        self.assertIn("--runtime", shard)
        self.assertNotIn("--verify-only", shard)
        self.assertIn("--verify-only", verifier)
        self.assertNotIn("--resource-locks", verifier)
        self.assertNotIn("--continue-on-error", verifier)
        self.assertNotIn("--runtime", verifier)


if __name__ == "__main__":
    unittest.main()
