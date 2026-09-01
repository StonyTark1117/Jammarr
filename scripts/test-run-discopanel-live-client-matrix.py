#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path
from types import SimpleNamespace


SCRIPT = Path(__file__).with_name("run-discopanel-live-client-matrix.py")
SPEC = importlib.util.spec_from_file_location("jammarr_live_client_matrix", SCRIPT)
assert SPEC and SPEC.loader
matrix = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = matrix
SPEC.loader.exec_module(matrix)


class LiveClientMatrixTests(unittest.TestCase):
    def target(self) -> SimpleNamespace:
        return SimpleNamespace(
            runtime="26.2-neoforge",
            filename="jammarr-1.1.0+mc26.2-neoforge.jar",
            sha256="a" * 64,
            server_name="Jammarr 26.2 NeoForge Test",
        )

    def write_session(self, root: Path, *, passed: bool = True, sha256: str | None = None) -> Path:
        target = self.target()
        session = root / f"{target.runtime}.ABC123"
        (session / "server-evidence").mkdir(parents=True)
        (session / "audio-analysis.json").write_text(
            json.dumps({"passed": passed, "failures": [] if passed else ["bad"]})
        )
        (session / "server-evidence" / f"{target.runtime}.json").write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "runtime": target.runtime,
                    "version": "1.1.0",
                    "artifact": target.filename,
                    "sha256": sha256 or target.sha256,
                    "runtimeStatusAtAcceptance": "SERVER_STATUS_RUNNING",
                    "stoppedCleanly": True,
                    "clientHoldCompleted": True,
                    "clientHoldConfigRestored": True,
                    "clientHoldDockerOverridesRestored": True,
                    "clientHoldNonJammarrModsRestored": True,
                    "clientHoldPropertiesRestored": True,
                    "evidence": {
                        "minecraft_ready": True,
                        "jammarr_initialized": True,
                        "plex_connected": True,
                    },
                }
            )
        )
        (session / "client-leader.console.log").write_text("healthy\n")
        (session / "client-follower.console.log").write_text("healthy\n")
        return session

    def test_select_targets_preserves_requested_order(self) -> None:
        first = SimpleNamespace(runtime="a")
        second = SimpleNamespace(runtime="b")
        self.assertEqual(
            [target.runtime for target in matrix.select_targets([first, second], ["b", "a"])],
            ["b", "a"],
        )

    def test_select_targets_rejects_duplicates(self) -> None:
        with self.assertRaises(SystemExit):
            matrix.select_targets([SimpleNamespace(runtime="a")], ["a", "a"])

    def test_accepted_session_requires_exact_candidate_and_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            session = self.write_session(root)
            self.assertEqual(
                matrix.accepted_session(root, "1.1.0", self.target()), session
            )

    def test_accepted_session_rejects_failed_audio_or_wrong_hash(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_session(root, passed=False)
            self.assertIsNone(matrix.accepted_session(root, "1.1.0", self.target()))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_session(root, sha256="b" * 64)
            self.assertIsNone(matrix.accepted_session(root, "1.1.0", self.target()))

    def test_accepted_session_rejects_terminal_client_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            session = self.write_session(root)
            (session / "client-leader.console.log").write_text(
                "Only one OpenAL context may be instantiated at any one time.\n"
            )
            self.assertIsNone(matrix.accepted_session(root, "1.1.0", self.target()))

    def test_accepted_session_rejects_live_session_process(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_session(root)
            with mock.patch.object(matrix, "session_has_process", return_value=True):
                self.assertIsNone(
                    matrix.accepted_session(root, "1.1.0", self.target())
                )


if __name__ == "__main__":
    unittest.main()
