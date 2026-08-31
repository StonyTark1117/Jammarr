#!/usr/bin/env python3
"""Unit tests for the resumable DiscPanel server-smoke matrix runner."""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path


SCRIPT = Path(__file__).with_name("run-discopanel-server-smoke-matrix.py")
SPEC = importlib.util.spec_from_file_location(
    "run_discopanel_server_smoke_matrix", SCRIPT
)
assert SPEC and SPEC.loader
matrix = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = matrix
SPEC.loader.exec_module(matrix)


@dataclass(frozen=True)
class Target:
    runtime: str
    server_name: str
    filename: str = "jammarr.jar"
    sha256: str = "a" * 64


class Panel:
    def __init__(self, servers: list[dict[str, object]]) -> None:
        self.servers = servers

    def list_servers(self) -> list[dict[str, object]]:
        return self.servers


class DiscPanelServerSmokeMatrixTests(unittest.TestCase):
    def test_selection_preserves_explicit_order_and_rejects_unknown(self) -> None:
        targets = [Target("a", "A"), Target("b", "B")]
        self.assertEqual(
            [target.runtime for target in matrix.select_targets(targets, ["b", "a"])],
            ["b", "a"],
        )
        with self.assertRaisesRegex(SystemExit, "unknown runtime"):
            matrix.select_targets(targets, ["missing"])
        with self.assertRaisesRegex(SystemExit, "unique"):
            matrix.select_targets(targets, ["a", "a"])

    def test_matrix_state_requires_exactly_one_stopped_non_autostart_server(self) -> None:
        targets = [Target("a", "A"), Target("b", "B"), Target("c", "C")]
        errors = matrix.matrix_state(
            Panel(
                [
                    {"name": "A", "status": "SERVER_STATUS_STOPPED"},
                    {"name": "B", "status": "SERVER_STATUS_RUNNING"},
                    {
                        "name": "C",
                        "status": "SERVER_STATUS_STOPPED",
                        "autoStart": True,
                    },
                ]
            ),
            targets,
        )
        self.assertEqual(len(errors), 2)
        self.assertIn("b status", errors[0])
        self.assertIn("c autostart", errors[1])

    def test_resume_requires_exact_successful_candidate_evidence(self) -> None:
        target = Target("a", "A")
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "a.json"
            evidence = {
                "schemaVersion": 1,
                "runtime": "a",
                "version": "1.1.0",
                "artifact": target.filename,
                "sha256": target.sha256,
                "runtimeStatusAtAcceptance": "SERVER_STATUS_RUNNING",
                "stoppedCleanly": True,
                "evidence": {
                    "minecraft_ready": True,
                    "jammarr_initialized": True,
                    "plex_connected": True,
                },
            }
            path.write_text(json.dumps(evidence), "utf-8")
            self.assertTrue(matrix.accepted_evidence(path, "1.1.0", target))
            evidence["sha256"] = "b" * 64
            path.write_text(json.dumps(evidence), "utf-8")
            self.assertFalse(matrix.accepted_evidence(path, "1.1.0", target))


if __name__ == "__main__":
    unittest.main()
