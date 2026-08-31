#!/usr/bin/env python3
"""Unit tests for the resumable DiscPanel server-smoke matrix runner."""

from __future__ import annotations

import importlib.util
import json
import os
import sys
import tempfile
import unittest
from argparse import Namespace
from dataclasses import dataclass
from pathlib import Path
from unittest import mock


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

    def test_keyboard_interrupt_is_not_swallowed_by_continue_on_error(self) -> None:
        target = Target("a", "A")
        panel = Panel(
            [
                {
                    "name": "A",
                    "status": matrix.reconciler.STATUS_STOPPED,
                    "autoStart": False,
                }
            ]
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = Namespace(
                runtime=[],
                manifest=root / "targets.json",
                release_dir=root / "releases",
                project_properties=root / "gradle.properties",
                expected_version="1.1.0",
                url="http://invalid.test",
                token_env="TEST_DISCOPANEL_TOKEN",
                request_timeout=1,
                start_timeout=1,
                stop_timeout=1,
                poll_interval=0,
                log_tail=1,
                evidence_dir=root / "evidence",
                matrix_evidence=root / "matrix.json",
                apply=True,
                confirm_version="1.1.0",
                resume=False,
                continue_on_error=True,
            )
            patches = (
                mock.patch.dict(os.environ, {args.token_env: "secret"}),
                mock.patch.object(matrix.deployment, "verified_release_artifacts", return_value=[]),
                mock.patch.object(matrix.deployment, "deployment_targets", return_value=[target]),
                mock.patch.object(
                    matrix.reconciler,
                    "desired_profiles",
                    return_value=[Namespace(runtime="a")],
                ),
                mock.patch.object(matrix.reconciler, "DiscPanel", return_value=panel),
                mock.patch.object(matrix.smoke, "run_target", side_effect=KeyboardInterrupt()),
            )
            with patches[0], patches[1], patches[2], patches[3], patches[4], patches[5]:
                with self.assertRaises(KeyboardInterrupt):
                    matrix.run(args)
            evidence = json.loads(args.matrix_evidence.read_text("utf-8"))
            self.assertTrue(evidence["allProfilesStopped"])
            self.assertEqual(evidence["failures"], [])


if __name__ == "__main__":
    unittest.main()
