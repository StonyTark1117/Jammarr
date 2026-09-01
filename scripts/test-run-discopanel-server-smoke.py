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

    def test_client_hold_writes_sanitized_ready_file_and_waits_for_release(self) -> None:
        class Panel:
            def __init__(self, release: Path) -> None:
                self.start_calls = 0
                self.stopped = False
                self.release = release
                self.running_polls = 0
                self.properties = b"motd=existing\r\nlevel-name=world\r\nonline-mode=false\r\n"
                self.files = {
                    "world/serverconfig/jammarr-server.toml": b'plexToken = "secret"\n',
                    "jammarr-live-acceptance/serverconfig/jammarr-server.toml": b'plexToken = ""\n',
                }
                self.mods = [
                    {"id": "jammarr", "fileName": "jammarr-1.1.0.jar", "enabled": True},
                    {"id": "cinemarr", "fileName": "cinemarr.jar", "enabled": True},
                ]

            def call(self, service: str, method: str, payload: dict[str, object]) -> dict[str, object]:
                if method == "GetServerLogs":
                    messages = []
                    if self.start_calls:
                        messages = [
                            "[init] Running as uid=1000 gid=1000",
                            'Done (1.0s)! For help, type "help"',
                            "Initializing Jammarr 1.1.0 for Forge 1.7.10 protocol 6",
                            "Jammarr connected to Plex",
                        ]
                    return {"logs": [{"message": message} for message in messages]}
                if method == "StartServer":
                    self.start_calls += 1
                    return {}
                if method == "StopServer":
                    self.stopped = True
                    return {}
                if method == "ListMods":
                    return {"mods": [dict(mod) for mod in self.mods]}
                if method == "UpdateMod":
                    for mod in self.mods:
                        if mod["id"] == payload["modId"]:
                            mod["enabled"] = payload["enabled"]
                            return {}
                    raise AssertionError(payload)
                raise AssertionError((service, method, payload))

            def get_server(self, server_id: str) -> dict[str, object]:
                if self.stopped:
                    return {"id": server_id, "status": smoke.reconciler.STATUS_STOPPED}
                self.running_polls += 1
                if self.running_polls >= 3:
                    self.release.touch()
                return {"id": server_id, "status": "SERVER_STATUS_RUNNING"}

            def get_file(self, server_id: str, path: str) -> bytes:
                if path == "server.properties":
                    return self.properties
                if path not in self.files:
                    raise RuntimeError("not found")
                return self.files[path]

            def update_file(self, server_id: str, path: str, content: bytes) -> None:
                if path == "server.properties":
                    self.properties = content
                else:
                    self.files[path] = content

            def create_folder(self, server_id: str, path: str) -> None:
                pass

            def delete_file(self, server_id: str, path: str) -> None:
                del self.files[path]

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            ready = root / "ready.json"
            release = root / "release"
            panel = Panel(release)
            args = Namespace(
                apply=True,
                confirm_runtime="1.7.10-forge",
                runtime="1.7.10-forge",
                start_timeout=2,
                stop_timeout=2,
                poll_interval=0,
                log_tail=100,
                evidence_dir=root / "evidence",
                hold_ready_file=ready,
                hold_release_file=release,
                hold_timeout=2,
                hold_level_name="jammarr-live-acceptance",
                hold_config_source_world="world",
                hold_disable_non_jammarr_mods=True,
                hold_bootstrap_level=False,
            )
            target = Namespace(
                runtime="1.7.10-forge",
                filename="jammarr.jar",
                sha256="a" * 64,
            )
            with mock.patch.object(smoke, "preflight", return_value={"id": "server"}):
                self.assertEqual(smoke.run_target(args, panel, "1.1.0", target, object()), 0)
            ready_payload = json.loads(ready.read_text())
            self.assertEqual(ready_payload["runtime"], "1.7.10-forge")
            self.assertNotIn("token", json.dumps(ready_payload).lower())
            evidence = json.loads((root / "evidence" / "1.7.10-forge.json").read_text())
            self.assertTrue(evidence["clientHoldCompleted"])
            self.assertTrue(evidence["clientHoldLevelIsolated"])
            self.assertTrue(evidence["clientHoldPropertiesRestored"])
            self.assertTrue(evidence["clientHoldConfigIsolated"])
            self.assertTrue(evidence["clientHoldConfigRestored"])
            self.assertEqual(evidence["clientHoldNonJammarrModsDisabled"], 1)
            self.assertTrue(evidence["clientHoldNonJammarrModsRestored"])
            self.assertTrue(evidence["stoppedCleanly"])
            self.assertEqual(
                panel.properties,
                b"motd=existing\r\nlevel-name=world\r\nonline-mode=false\r\n",
            )
            self.assertEqual(
                panel.files["jammarr-live-acceptance/serverconfig/jammarr-server.toml"],
                b'plexToken = ""\n',
            )
            self.assertTrue(next(mod for mod in panel.mods if mod["id"] == "cinemarr")["enabled"])

    def test_client_hold_rejects_stale_paths_before_start(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            ready = Path(temporary) / "ready"
            ready.touch()
            args = Namespace(
                apply=True,
                confirm_runtime="1.7.10-forge",
                runtime="1.7.10-forge",
                hold_ready_file=ready,
                hold_release_file=Path(temporary) / "release",
                hold_timeout=1,
                hold_level_name=None,
                hold_config_source_world=None,
                hold_disable_non_jammarr_mods=False,
                hold_bootstrap_level=False,
            )
            with self.assertRaises(SystemExit):
                smoke.run_target(args, object(), "1.1.0", object(), object())

    def test_isolated_level_bootstrap_requires_readiness_and_stops(self) -> None:
        class Panel:
            def __init__(self) -> None:
                self.started = False
                self.stopped = False

            def call(self, service: str, method: str, payload: dict[str, object]) -> dict[str, object]:
                if method == "GetServerLogs":
                    messages = [] if not self.started else [
                        "[init] Running as uid=1000 gid=1000",
                        'Done (1.0s)! For help, type "help"',
                    ]
                    return {"logs": [{"message": message} for message in messages]}
                if method == "StartServer":
                    self.started = True
                    return {}
                if method == "StopServer":
                    self.stopped = True
                    return {}
                raise AssertionError(method)

            def get_server(self, server_id: str) -> dict[str, object]:
                return {
                    "id": server_id,
                    "status": smoke.reconciler.STATUS_STOPPED
                    if self.stopped
                    else "SERVER_STATUS_RUNNING",
                }

        panel = Panel()
        args = Namespace(
            runtime="1.7.10-forge",
            start_timeout=2,
            stop_timeout=2,
            poll_interval=0,
            log_tail=100,
        )
        smoke.bootstrap_isolated_level(panel, "server", args, "1.1.0")
        self.assertTrue(panel.started)
        self.assertTrue(panel.stopped)


if __name__ == "__main__":
    unittest.main()
