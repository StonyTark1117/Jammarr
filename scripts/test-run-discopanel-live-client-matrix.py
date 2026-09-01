#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import signal
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path
from types import SimpleNamespace


SCRIPT = Path(__file__).with_name("run-discopanel-live-client-matrix.py")
LIVE_GATE_SCRIPT = Path(__file__).with_name("run-discopanel-live-client-gate.sh")
SPEC = importlib.util.spec_from_file_location("jammarr_live_client_matrix", SCRIPT)
assert SPEC and SPEC.loader
matrix = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = matrix
SPEC.loader.exec_module(matrix)


class LiveClientMatrixTests(unittest.TestCase):
    def test_live_gate_keeps_private_sinks_active_until_cleanup(self) -> None:
        source = LIVE_GATE_SCRIPT.read_text()
        self.assertIn("sink_keepalive_pids=()", source)
        self.assertIn('for sink in "$sink_leader" "$sink_follower"; do', source)
        self.assertIn('pacat --raw --playback --device="$sink"', source)
        self.assertIn('$NF == "RUNNING"', source)
        self.assertIn('for pid in "${sink_keepalive_pids[@]}"; do', source)
        self.assertLess(
            source.index('kill -TERM "$pid"', source.index("cleanup()")),
            source.index('pactl unload-module "$module"', source.index("cleanup()")),
        )

    def test_live_gate_retains_active_duration_after_jointly_inactive_lead(self) -> None:
        source = LIVE_GATE_SCRIPT.read_text()
        self.assertIn("capture_grace_seconds=${JAMMARR_LIVE_CAPTURE_GRACE_SECONDS:-6}", source)
        self.assertIn(
            "wall_capture_seconds=$((capture_seconds + capture_grace_seconds))", source
        )
        self.assertIn('sleep "$wall_capture_seconds"', source)
        self.assertIn(
            "--minimum-duration-ms $((capture_seconds * 1000 - 1500))", source
        )

    def test_live_gate_retries_a_capture_crossed_by_a_track_transition(self) -> None:
        source = LIVE_GATE_SCRIPT.read_text()
        self.assertIn(
            "capture_attempt_limit=${JAMMARR_LIVE_CAPTURE_ATTEMPTS:-3}", source
        )
        self.assertIn("wait_for_matching_audio_generation()", source)
        self.assertIn(
            "JAMMARR_AUDIO_TIMING stage=channel_started", source
        )
        self.assertIn(
            "JAMMARR_AUDIO_TIMING stage=manifest_received", source
        )
        self.assertIn(
            '[[ "$leader_generation" == "$capture_generation"', source
        )
        self.assertIn("reason=track-transition", source)
        self.assertLess(
            source.index("wait_for_matching_audio_generation 120"),
            source.index("parec --raw", source.index("leader_raw=")),
        )

    def test_live_gate_skips_only_synchronized_silence_and_reanalyzes(self) -> None:
        source = LIVE_GATE_SCRIPT.read_text()
        self.assertIn("wait_for_new_matching_audio_generation()", source)
        self.assertIn('.capture_retry_reason == "synchronized-silence"', source)
        self.assertIn("reason=synchronized-silence", source)
        self.assertIn("control:skip:-1:", source)
        self.assertIn('audio-analysis-attempt-${capture_attempt}.json', source)
        self.assertLess(
            source.index("reason=synchronized-silence"),
            source.index("control:skip:-1:"),
        )
        self.assertLess(
            source.index("control:skip:-1:"),
            source.index("wait_for_new_matching_audio_generation", source.index("control:skip:-1:")),
        )

    def test_live_gate_isolates_audio_server_from_active_desktop(self) -> None:
        source = LIVE_GATE_SCRIPT.read_text()
        self.assertIn(
            "audio_runtime_dir=$(mktemp -d /tmp/jammarr-live-gate-audio.XXXXXX)", source
        )
        self.assertIn('PIPEWIRE_RUNTIME_DIR="$audio_runtime_dir" pipewire', source)
        self.assertIn(
            'PIPEWIRE_RUNTIME_DIR="$audio_runtime_dir" wireplumber --profile=policy',
            source,
        )
        self.assertIn('PIPEWIRE_RUNTIME_DIR="$audio_runtime_dir" pipewire-pulse', source)
        self.assertNotIn("env -u DBUS_SESSION_BUS_ADDRESS", source)
        self.assertIn(
            'export PULSE_SERVER="unix:$audio_runtime_dir/pulse/native"', source
        )
        self.assertIn(
            'for pid in "$private_pulse_pid" "$private_wireplumber_pid" '
            '"$private_pipewire_pid"; do',
            source,
        )
        self.assertLess(
            source.index('pactl unload-module "$module"', source.index("cleanup()")),
            source.index('for pid in "$private_pulse_pid"', source.index("cleanup()")),
        )

    def test_live_gate_bounds_software_rendering_during_audio_capture(self) -> None:
        source = LIVE_GATE_SCRIPT.read_text()
        self.assertIn("'maxFps:30'", source)
        self.assertIn("'renderDistance:2'", source)
        self.assertIn("'simulationDistance:2'", source)
        self.assertIn("'graphicsMode:0'", source)
        self.assertIn("'particles:2'", source)
        self.assertIn("'entityShadows:false'", source)
        self.assertIn("'enableVsync:false'", source)
        self.assertIn("'soundCategory_music:1.0'", source)
        for category in (
            "record",
            "weather",
            "block",
            "hostile",
            "neutral",
            "player",
            "ambient",
            "voice",
        ):
            self.assertIn(f"'soundCategory_{category}:0.0'", source)
        self.assertIn("-screen 0 854x480x24", source)

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
                    "clientHoldNonJammarrModsDisabled": 0,
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

    def test_accepted_session_requires_restore_when_a_mod_was_disabled(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            session = self.write_session(root)
            path = session / "server-evidence" / f"{self.target().runtime}.json"
            evidence = json.loads(path.read_text())
            evidence["clientHoldNonJammarrModsDisabled"] = 1
            path.write_text(json.dumps(evidence))
            self.assertIsNone(matrix.accepted_session(root, "1.1.0", self.target()))
            evidence["clientHoldNonJammarrModsRestored"] = True
            path.write_text(json.dumps(evidence))
            self.assertEqual(
                matrix.accepted_session(root, "1.1.0", self.target()), session
            )

    def test_accepted_session_rejects_terminal_client_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            session = self.write_session(root)
            (session / "client-leader.console.log").write_text(
                "Only one OpenAL context may be instantiated at any one time.\n"
            )
            self.assertIsNone(matrix.accepted_session(root, "1.1.0", self.target()))

    def test_accepted_session_rejects_connection_failure_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            session = self.write_session(root)
            (session / "client-follower.console.log").write_text(
                "io.netty.channel.AbstractChannel$AnnotatedConnectException: "
                "Connection refused\n"
            )
            self.assertIsNone(matrix.accepted_session(root, "1.1.0", self.target()))

    def test_accepted_session_rejects_broken_audio_route(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            session = self.write_session(root)
            (session / "client-leader.console.log").write_text(
                "[ALSOFT] (EE) available update failed: Broken pipe\n"
            )
            self.assertIsNone(matrix.accepted_session(root, "1.1.0", self.target()))

    def test_accepted_session_allows_expected_disconnect_after_server_stop(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            session = self.write_session(root)
            (session / "client-follower.console.log").write_text(
                "Acceptance audio state: PLAYING\n"
                "Client disconnected with reason: Server closed\n"
            )
            self.assertEqual(
                matrix.accepted_session(root, "1.1.0", self.target()), session
            )

    def test_accepted_session_rejects_live_session_process(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_session(root)
            with mock.patch.object(matrix, "session_has_process", return_value=True):
                self.assertIsNone(
                    matrix.accepted_session(root, "1.1.0", self.target())
                )

    def test_wait_for_accepted_session_allows_bounded_process_settling(self) -> None:
        expected = Path("accepted-session")
        with mock.patch.object(
            matrix, "accepted_session", side_effect=[None, None, expected]
        ) as accepted, mock.patch.object(
            matrix.time, "monotonic", return_value=1.0
        ), mock.patch.object(matrix.time, "sleep") as sleep:
            actual = matrix.wait_for_accepted_session(
                Path("evidence"), "1.1.0", self.target(), timeout=15, interval=0.25
            )
        self.assertEqual(actual, expected)
        self.assertEqual(accepted.call_count, 3)
        self.assertEqual(sleep.call_count, 2)

    def test_run_live_gate_uses_isolated_session_and_returns_status(self) -> None:
        process = mock.Mock()
        process.wait.return_value = 7
        with mock.patch.object(matrix.subprocess, "Popen", return_value=process) as popen:
            self.assertEqual(
                matrix.run_live_gate(
                    ["gate", "1.12.2-forge"],
                    cwd=Path("/repo"),
                    env={"SAFE": "value"},
                ),
                7,
            )
        popen.assert_called_once_with(
            ["gate", "1.12.2-forge"],
            cwd=Path("/repo"),
            env={"SAFE": "value"},
            start_new_session=True,
        )
        process.wait.assert_called_once_with()

    def test_run_live_gate_waits_for_cleanup_before_reraising_interrupt(self) -> None:
        process = mock.Mock()
        process.wait.side_effect = [KeyboardInterrupt(), 130]
        with mock.patch.object(matrix.subprocess, "Popen", return_value=process):
            with self.assertRaises(KeyboardInterrupt):
                matrix.run_live_gate(
                    ["gate", "1.12.2-forge"],
                    cwd=Path("/repo"),
                    env={},
                )
        process.send_signal.assert_called_once_with(signal.SIGINT)
        self.assertEqual(
            process.wait.call_args_list,
            [mock.call(), mock.call(timeout=240)],
        )

    def test_run_live_gate_reports_cleanup_timeout(self) -> None:
        process = mock.Mock()
        process.wait.side_effect = [
            KeyboardInterrupt(),
            matrix.subprocess.TimeoutExpired(["gate"], 240),
        ]
        with mock.patch.object(matrix.subprocess, "Popen", return_value=process):
            with self.assertRaisesRegex(RuntimeError, "cleanup did not finish"):
                matrix.run_live_gate(
                    ["gate", "1.12.2-forge"],
                    cwd=Path("/repo"),
                    env={},
                )


if __name__ == "__main__":
    unittest.main()
