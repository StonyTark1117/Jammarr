#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import signal
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("run-vanilla-client-matrix.py")
SPEC = importlib.util.spec_from_file_location("run_vanilla_client_matrix", SCRIPT)
assert SPEC and SPEC.loader
MATRIX = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MATRIX)


class VanillaClientMatrixTest(unittest.TestCase):
    def test_release_tuple_excludes_non_release_legacy_versions(self) -> None:
        self.assertIsNone(MATRIX.release_tuple("b1.7.3"))
        self.assertEqual(MATRIX.release_tuple("1.20"), (1, 20, 0))
        self.assertEqual(MATRIX.release_tuple("26.2"), (26, 2, 0))

    def test_actual_manifest_selects_every_runtime_from_1122_forward(self) -> None:
        manifest = MATRIX.target_matrix.load_manifest(Path("gradle/targets.json"))
        runtimes = MATRIX.artifact_free_runtimes(manifest)
        names = [runtime["name"] for runtime in runtimes]
        self.assertIn("1.12.2-forge", names)
        self.assertIn("1.16.5-fabric", names)
        self.assertIn("1.20.1-quilt", names)
        self.assertIn("26.2-neoforge", names)
        self.assertNotIn("1.8.9-forge", names)
        self.assertEqual(len(names), len(set(names)))

    def test_select_runtimes_rejects_legacy_and_unknown_names(self) -> None:
        runtimes = [{"name": "1.20.1-fabric"}]
        with self.assertRaisesRegex(SystemExit, "not artifact-free-client targets"):
            MATRIX.select_runtimes(runtimes, ["1.7.10-forge"])

    def test_resume_requires_complete_attested_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            runtime = {
                "name": "1.20.1-fabric",
                "minecraft": "1.20.1",
                "port": 26000,
                "runtimeJava": 17,
            }
            instance = output / (
                "1.20.1-fabric.vanilla-client.prism/instances/"
                "jammarr-vanilla-1.20.1"
            )
            instance.mkdir(parents=True)
            (instance / "vanilla-attestation.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "launcher": "Direct Mojang client from verified Prism caches",
                        "minecraftVersion": "1.20.1",
                        "componentUids": ["org.lwjgl3", "net.minecraft"],
                        "jammarrComponentPresent": False,
                        "mods": [],
                        "accountMode": "direct-offline",
                        "offlineUsername": "PureVanilla",
                        "instanceDirectory": str(instance.resolve()),
                        "gameDirectory": str((instance / "minecraft").resolve()),
                        "runtime": {
                            "javaMajor": 17,
                            "clientJarSha1": "1" * 40,
                            "allArtifactSha1Verified": True,
                            "allArtifactSha1AndSizeVerified": True,
                            "sharedCacheMutated": False,
                            "connectionTarget": "127.0.0.1:26000",
                            "connectionMode": "quick-play-multiplayer",
                            "offlinePrivilegesStub": False,
                            "artifactSourceCounts": {"shared-cache": 4},
                        },
                    }
                ),
                encoding="utf-8",
            )
            evidence = output / "1.20.1-fabric.vanilla-client.evidence.txt"
            evidence.write_text(
                "capableListeners=0, vanillaListeners=1, listenerStats=0\n"
                "Artifact-free vanilla client remained connected for 10 seconds.\n"
                "Artifact-free vanilla client sent player-originated chat.\n"
                "Artifact-free vanilla client reconnected and completed a second clean lifecycle.\n"
                "Plex request count remained unchanged at 0.\n",
                encoding="utf-8",
            )
            with mock.patch.object(MATRIX, "process_mentions", return_value=False), mock.patch.object(
                MATRIX, "port_listening", return_value=False
            ):
                self.assertTrue(MATRIX.accepted_evidence(output, runtime))
                self.assertFalse(MATRIX.accepted_evidence(output, runtime, 30))
                value = json.loads((instance / "vanilla-attestation.json").read_text("utf-8"))
                value["runtime"]["javaMajor"] = 21
                (instance / "vanilla-attestation.json").write_text(json.dumps(value), "utf-8")
                self.assertFalse(MATRIX.accepted_evidence(output, runtime))
                value["runtime"]["javaMajor"] = 17
                value["componentUids"] = ["org.lwjgl", "net.minecraft"]
                (instance / "vanilla-attestation.json").write_text(json.dumps(value), "utf-8")
                self.assertFalse(MATRIX.accepted_evidence(output, runtime))
                value["componentUids"] = ["org.lwjgl3", "net.minecraft"]
                (instance / "vanilla-attestation.json").write_text(json.dumps(value), "utf-8")
            with mock.patch.object(MATRIX, "process_mentions", return_value=True), mock.patch.object(
                MATRIX, "port_listening", return_value=False
            ):
                self.assertFalse(MATRIX.accepted_evidence(output, runtime))
            with mock.patch.object(MATRIX, "process_mentions", return_value=False), mock.patch.object(
                MATRIX, "port_listening", return_value=True
            ):
                self.assertFalse(MATRIX.accepted_evidence(output, runtime))
            evidence.write_text(
                evidence.read_text("utf-8").replace(
                    "Artifact-free vanilla client reconnected and completed a second clean lifecycle.\n",
                    "",
                ),
                encoding="utf-8",
            )
            with mock.patch.object(MATRIX, "process_mentions", return_value=False), mock.patch.object(
                MATRIX, "port_listening", return_value=False
            ):
                self.assertFalse(MATRIX.accepted_evidence(output, runtime))
            evidence.write_text(
                "capableListeners=0, vanillaListeners=1, listenerStats=0\n"
                "Artifact-free vanilla client remained connected for 10 seconds.\n"
                "Artifact-free vanilla client sent player-originated chat.\n"
                "Artifact-free vanilla client reconnected and completed a second clean lifecycle.\n"
                "Plex request count remained unchanged at 0.\n",
                encoding="utf-8",
            )
            value = json.loads((instance / "vanilla-attestation.json").read_text("utf-8"))
            value["runtime"]["sharedCacheMutated"] = True
            (instance / "vanilla-attestation.json").write_text(json.dumps(value), "utf-8")
            with mock.patch.object(MATRIX, "process_mentions", return_value=False), mock.patch.object(
                MATRIX, "port_listening", return_value=False
            ):
                self.assertFalse(MATRIX.accepted_evidence(output, runtime))

    def test_resume_rejects_malformed_attestation_shapes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            runtime = {
                "name": "1.20.1-fabric",
                "minecraft": "1.20.1",
                "port": 26000,
                "runtimeJava": 17,
            }
            instance = output / (
                "1.20.1-fabric.vanilla-client.prism/instances/"
                "jammarr-vanilla-1.20.1"
            )
            instance.mkdir(parents=True)
            (output / "1.20.1-fabric.vanilla-client.evidence.txt").write_text(
                "not sufficient\n", encoding="utf-8"
            )
            attestation = instance / "vanilla-attestation.json"
            for value in ([], {"runtime": []}, {"runtime": {}, "instanceDirectory": None}):
                attestation.write_text(json.dumps(value), encoding="utf-8")
                self.assertFalse(MATRIX.accepted_evidence(output, runtime))

    def test_run_gate_uses_isolated_session_and_returns_status(self) -> None:
        process = mock.Mock()
        process.wait.return_value = 7
        with mock.patch.object(MATRIX.subprocess, "Popen", return_value=process) as popen:
            self.assertEqual(
                MATRIX.run_gate(
                    ["gate", "1.20.1-fabric"],
                    cwd=Path("/repo"),
                    env={"SAFE": "value"},
                ),
                7,
            )
        popen.assert_called_once_with(
            ["gate", "1.20.1-fabric"],
            cwd=Path("/repo"),
            env={"SAFE": "value"},
            start_new_session=True,
        )
        process.wait.assert_called_once_with()

    def test_run_gate_waits_for_cleanup_before_reraising_interrupt(self) -> None:
        process = mock.Mock()
        process.wait.side_effect = [KeyboardInterrupt(), 130]
        with mock.patch.object(MATRIX.subprocess, "Popen", return_value=process):
            with self.assertRaises(KeyboardInterrupt):
                MATRIX.run_gate(
                    ["gate", "1.20.1-fabric"],
                    cwd=Path("/repo"),
                    env={},
                )
        process.send_signal.assert_called_once_with(signal.SIGINT)
        self.assertEqual(
            process.wait.call_args_list,
            [mock.call(), mock.call(timeout=240)],
        )

    def test_run_gate_reports_cleanup_timeout(self) -> None:
        process = mock.Mock()
        process.wait.side_effect = [
            KeyboardInterrupt(),
            MATRIX.subprocess.TimeoutExpired(["gate"], 240),
        ]
        with mock.patch.object(MATRIX.subprocess, "Popen", return_value=process):
            with self.assertRaisesRegex(RuntimeError, "cleanup did not finish"):
                MATRIX.run_gate(
                    ["gate", "1.20.1-fabric"],
                    cwd=Path("/repo"),
                    env={},
                )


if __name__ == "__main__":
    unittest.main()
