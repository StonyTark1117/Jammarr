#!/usr/bin/env python3
"""Unit tests for the headless DiscPanel server smoke gate."""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("run-discopanel-server-smoke.py")
SPEC = importlib.util.spec_from_file_location("run_discopanel_server_smoke", SCRIPT)
assert SPEC and SPEC.loader
smoke = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = smoke
SPEC.loader.exec_module(smoke)


class DiscPanelServerSmokeTests(unittest.TestCase):
    def test_append_delta_uses_exact_prefix(self) -> None:
        self.assertEqual(
            smoke.appended_messages(["old-a", "old-b"], ["old-a", "old-b", "new"]),
            ["new"],
        )

    def test_append_delta_tolerates_rotated_tail_window(self) -> None:
        self.assertEqual(
            smoke.appended_messages(
                ["old-a", "old-b", "old-c"], ["old-b", "old-c", "new-a", "new-b"]
            ),
            ["new-a", "new-b"],
        )

    def test_active_run_ignores_replayed_completed_segment_after_rotation(self) -> None:
        baseline = ["old prelude", "old failure", "mc-server-runner Done"]
        current = [
            "old failure with reformatted prefix",
            "mc-server-runner Done",
            "[init] Running as uid=1000 gid=1000",
            "Loading Minecraft 1.8.9",
        ]
        messages, anchor_seen = smoke.active_run_messages(baseline, current, False)
        self.assertTrue(anchor_seen)
        self.assertEqual(
            messages,
            ["[init] Running as uid=1000 gid=1000", "Loading Minecraft 1.8.9"],
        )

    def test_active_run_waits_when_only_historical_failure_is_replayed(self) -> None:
        messages, anchor_seen = smoke.active_run_messages(
            ["old"],
            ["[init] Running as uid=1000 gid=1000", "Minecraft server failed"],
            False,
        )
        self.assertFalse(anchor_seen)
        self.assertEqual(messages, [])

    def test_acceptance_requires_all_appended_server_markers(self) -> None:
        messages = [
            "Initializing Jammarr 1.1.0 for Forge 1.12.2 protocol 6",
            "Jammarr connected to Plex; sonic capability is true",
            'Done (2.345s)! For help, type "help"',
        ]
        self.assertEqual(
            smoke.startup_evidence(messages, "1.1.0"),
            {
                "minecraft_ready": True,
                "jammarr_initialized": True,
                "plex_connected": True,
                "installer_failure": False,
                "server_failure": False,
            },
        )

    def test_installer_failure_is_classified_in_appended_logs(self) -> None:
        result = smoke.startup_evidence(
            ["Failed to download version manifest", "SocketTimeoutException"],
            "1.1.0",
        )
        self.assertTrue(result["installer_failure"])
        self.assertFalse(result["jammarr_initialized"])

    def test_terminal_server_failure_is_detected_before_timeout(self) -> None:
        result = smoke.startup_evidence(
            ["MixinApplyError", "Minecraft server failed.", "mc-server-runner Done"],
            "1.1.0",
        )
        self.assertTrue(result["server_failure"])
        self.assertFalse(result["minecraft_ready"])

    def test_custom_launcher_rejection_is_detected_before_timeout(self) -> None:
        result = smoke.startup_evidence(
            [
                "[mc-image-helper] ERROR: 'install-fabric-loader' command failed",
                "Failed to locate install.properties from launcher",
                "[init] [ERROR] Failed to use provided Fabric launcher",
            ],
            "1.1.0",
        )
        self.assertTrue(result["server_failure"])

    def test_modern_plex_marker_proves_exact_preflighted_candidate_active(self) -> None:
        result = smoke.startup_evidence(
            [
                'Done (6.495s)! For help, type "help"',
                "Jammarr connected to Plex; sonic capability is READY",
            ],
            "1.1.0",
        )
        self.assertTrue(result["minecraft_ready"])
        self.assertTrue(result["jammarr_initialized"])
        self.assertTrue(result["plex_connected"])

    def test_stopped_start_recovery_retries_exactly_once_and_still_requires_evidence(self) -> None:
        class Panel:
            def __init__(self) -> None:
                self.start_calls = 0
                self.stopped = False

            def call(self, service: str, method: str, payload: dict[str, object]) -> dict[str, object]:
                if method == "GetServerLogs":
                    messages = []
                    if self.start_calls >= 2:
                        messages = [
                            "[init] Running as uid=1000 gid=1000",
                            'Done (1.0s)! For help, type "help"',
                            "Initializing Jammarr 1.1.0 for Fabric 1.18.2 protocol 6",
                            "Jammarr connected to Plex",
                        ]
                    return {"logs": [{"message": message} for message in messages]}
                if method == "StartServer":
                    self.start_calls += 1
                    return {}
                if method == "StopServer":
                    self.stopped = True
                    return {}
                raise AssertionError((service, method, payload))

            def get_server(self, server_id: str) -> dict[str, object]:
                if self.stopped:
                    return {"id": server_id, "status": smoke.reconciler.STATUS_STOPPED}
                status = (
                    "SERVER_STATUS_RUNNING"
                    if self.start_calls >= 2
                    else smoke.reconciler.STATUS_STOPPED
                )
                return {"id": server_id, "status": status}

        with tempfile.TemporaryDirectory() as temporary:
            panel = Panel()
            args = Namespace(
                apply=True,
                confirm_runtime="1.18.2-fabric",
                runtime="1.18.2-fabric",
                start_timeout=2,
                stop_timeout=2,
                poll_interval=0,
                log_tail=100,
                evidence_dir=Path(temporary),
            )
            target = Namespace(
                runtime="1.18.2-fabric",
                filename="jammarr.jar",
                sha256="a" * 64,
            )
            with (
                mock.patch.object(smoke, "preflight", return_value={"id": "server"}),
                mock.patch.object(smoke, "START_RECOVERY_RETRY_DELAY_SECONDS", 0),
            ):
                self.assertEqual(smoke.run_target(args, panel, "1.1.0", target, object()), 0)
            self.assertEqual(panel.start_calls, 2)
            evidence = json.loads((Path(temporary) / "1.18.2-fabric.json").read_text())
            self.assertTrue(evidence["startRecoveryRetry"])
            self.assertTrue(evidence["stoppedCleanly"])

    def test_stopped_start_recovery_predicate_is_single_use(self) -> None:
        self.assertTrue(
            smoke.should_retry_stopped_start(
                smoke.reconciler.STATUS_STOPPED, False, False, 6.0
            )
        )
        self.assertFalse(
            smoke.should_retry_stopped_start(
                smoke.reconciler.STATUS_STOPPED, False, True, 60.0
            )
        )
        self.assertFalse(
            smoke.should_retry_stopped_start("SERVER_STATUS_STARTING", True, False, 60.0)
        )


if __name__ == "__main__":
    unittest.main()
