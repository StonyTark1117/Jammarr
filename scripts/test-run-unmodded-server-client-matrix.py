#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import hashlib
import json
from pathlib import Path
import signal
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("run-unmodded-server-client-matrix.py")
SPEC = importlib.util.spec_from_file_location("run_unmodded_server_client_matrix", SCRIPT)
assert SPEC and SPEC.loader
MATRIX = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MATRIX)


class UnmoddedServerClientMatrixTest(unittest.TestCase):
    def test_actual_manifest_defaults_to_every_full_runtime(self) -> None:
        manifest = MATRIX.target_matrix.load_manifest(Path("gradle/targets.json"))
        runtimes = MATRIX.target_matrix.runtimes(manifest)
        selected = MATRIX.select_runtimes(runtimes, [])
        self.assertEqual(len(selected), 99)
        self.assertIn("b1.7.3-babric", [runtime["name"] for runtime in selected])
        self.assertIn("26.2-neoforge", [runtime["name"] for runtime in selected])

    def test_select_rejects_unknown_or_duplicate_runtime(self) -> None:
        runtimes = [{"name": "1.20.1-fabric"}]
        with self.assertRaisesRegex(SystemExit, "unknown full runtime"):
            MATRIX.select_runtimes(runtimes, ["1.20.1-liteloader"])
        with self.assertRaisesRegex(SystemExit, "must be unique"):
            MATRIX.select_runtimes(runtimes, ["1.20.1-fabric", "1.20.1-fabric"])

    def test_attempt_numbers_preserve_failed_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "attempt-0001").mkdir()
            (root / "attempt-0003").mkdir()
            self.assertEqual(MATRIX.next_attempt(root).name, "attempt-0002")

    def test_resume_requires_current_server_digest_join_ui_and_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            attempt = Path(temporary)
            server = attempt / "official-server.jar"
            server.write_bytes(b"official-server")
            (attempt / "server-attestation.json").write_text(
                json.dumps(
                    {
                        "minecraftVersion": "1.20.1",
                        "jammarrPresent": False,
                        "unmoddedVanillaServer": True,
                        "serverJar": str(server),
                        "serverSize": server.stat().st_size,
                        "serverSha1": hashlib.sha1(server.read_bytes()).hexdigest(),
                    }
                ),
                encoding="utf-8",
            )
            (attempt / "server.console.log").write_text(
                "JammarrNoServer joined the game\n", encoding="utf-8"
            )
            (attempt / "client.console.log").write_text(
                "Acceptance Jammarr unsupported-server screen remained open across rendered frames\n",
                encoding="utf-8",
            )
            (attempt / "gate.evidence.txt").write_text(
                "Modded client remained connected to the attested unmodded server for 10 seconds.\n"
                "Attested unmodded server, modded client, private X server, and port cleaned up.\n",
                encoding="utf-8",
            )
            runtime = {"name": "1.20.1-fabric", "port": 26000}
            with mock.patch.object(MATRIX, "process_mentions", return_value=False), mock.patch.object(
                MATRIX, "port_listening", return_value=False
            ):
                self.assertTrue(MATRIX.accepted_evidence(attempt, runtime))
                server.write_bytes(b"corrupt")
                self.assertFalse(MATRIX.accepted_evidence(attempt, runtime))

    def test_run_gate_is_isolated_and_waits_for_interrupt_cleanup(self) -> None:
        process = mock.Mock()
        process.wait.side_effect = [KeyboardInterrupt(), 130]
        with mock.patch.object(MATRIX.subprocess, "Popen", return_value=process) as popen:
            with self.assertRaises(KeyboardInterrupt):
                MATRIX.run_gate(["gate", "runtime"], cwd=Path("/repo"), env={})
        popen.assert_called_once_with(
            ["gate", "runtime"], cwd=Path("/repo"), env={}, start_new_session=True
        )
        process.send_signal.assert_called_once_with(signal.SIGINT)
        self.assertEqual(
            process.wait.call_args_list, [mock.call(), mock.call(timeout=180)]
        )


if __name__ == "__main__":
    unittest.main()
