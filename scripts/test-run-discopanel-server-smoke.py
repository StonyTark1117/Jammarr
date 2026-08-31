#!/usr/bin/env python3
"""Unit tests for the headless DiscPanel server smoke gate."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


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


if __name__ == "__main__":
    unittest.main()
