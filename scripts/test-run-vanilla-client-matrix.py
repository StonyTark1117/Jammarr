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

    def test_resource_lock_key_groups_one_minecraft_cache_family(self) -> None:
        fabric = {"minecraft": "1.20.1"}
        quilt = {"minecraft": "1.20.1"}
        newer = {"minecraft": "1.20.2"}
        self.assertEqual(
            MATRIX.resource_lock_environment(fabric, True),
            MATRIX.resource_lock_environment(quilt, True),
        )
        self.assertNotEqual(
            MATRIX.resource_lock_environment(fabric, True),
            MATRIX.resource_lock_environment(newer, True),
        )
        self.assertEqual(MATRIX.resource_lock_environment(fabric, False), {})

    def test_release_attestation_reuses_only_unaffected_prior_rows(self) -> None:
        prior_immediate = {
            "connectionDelaySeconds": 0,
        }
        prior_fixed_delay = {
            "connectionDelaySeconds": 12,
        }
        current_ready = {
            "connectionReleaseCondition": "initial-resource-atlas-ready",
            "connectionReadinessTimeoutSeconds": 120,
            "connectionReleasedOnReadiness": True,
        }
        self.assertTrue(
            MATRIX.valid_connection_release_attestation(prior_immediate, False)
        )
        self.assertFalse(
            MATRIX.valid_connection_release_attestation(prior_fixed_delay, True)
        )
        self.assertTrue(
            MATRIX.valid_connection_release_attestation(current_ready, True)
        )

    def test_accepted_attempt_pointer_is_scoped_and_atomic(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "runtime"
            attempt = output / "attempts/current"
            attempt.mkdir(parents=True)
            runtime = {"name": "1.20.1-fabric", "minecraft": "1.20.1"}
            with mock.patch.object(MATRIX, "accepted_evidence", return_value=True):
                MATRIX.write_accepted_attempt(output, attempt, runtime, 10)
                self.assertEqual(
                    MATRIX.resumable_evidence_root(output, runtime, 10),
                    attempt.resolve(),
                )
            pointer = output / "accepted-attempt.json"
            value = json.loads(pointer.read_text("utf-8"))
            value["evidenceRoot"] = "../../outside"
            pointer.write_text(json.dumps(value), encoding="utf-8")
            with mock.patch.object(MATRIX, "accepted_evidence", return_value=True):
                self.assertIsNone(
                    MATRIX.resumable_evidence_root(output, runtime, 10)
                )

    def test_failed_attempt_cannot_replace_prior_accepted_pointer(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "runtime"
            accepted = output / "attempts/accepted"
            failed = output / "attempts/failed"
            accepted.mkdir(parents=True)
            failed.mkdir(parents=True)
            runtime = {"name": "1.18.2-fabric", "minecraft": "1.18.2"}
            MATRIX.write_accepted_attempt(output, accepted, runtime, 10)
            pointer_before = (output / "accepted-attempt.json").read_bytes()
            # A failed gate remains diagnostic output only; promotion is never
            # called for it and therefore cannot alter the resume pointer.
            (failed / "partial.log").write_text("failed\n", encoding="utf-8")
            self.assertEqual(
                (output / "accepted-attempt.json").read_bytes(), pointer_before
            )

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
                            "deferredInitialConnection": False,
                            "connectionReleaseCondition": "immediate",
                            "connectionReadinessTimeoutSeconds": 0,
                            "connectionReleasedOnReadiness": False,
                            "offlinePrivilegesStub": False,
                            "artifactSourceCounts": {"shared-cache": 4},
                        },
                    }
                ),
                encoding="utf-8",
            )
            evidence = output / "1.20.1-fabric.vanilla-client.evidence.txt"
            evidence.write_text(
                '"connectionReleaseCondition": "immediate"\n'
                '"connectionReadinessTimeoutSeconds": 0\n'
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
                '"connectionReleaseCondition": "immediate"\n'
                '"connectionReadinessTimeoutSeconds": 0\n'
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
