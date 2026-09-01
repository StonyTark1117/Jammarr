#!/usr/bin/env python3
"""Unit tests for durable DiscPanel live-client transaction recovery."""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


SCRIPT = Path(__file__).with_name("recover-discopanel-live-client-sessions.py")
SPEC = importlib.util.spec_from_file_location("jammarr_live_recovery", SCRIPT)
assert SPEC and SPEC.loader
recovery = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = recovery
SPEC.loader.exec_module(recovery)


class FakePanel:
    def __init__(self, *, running: bool) -> None:
        self.server = {
            "id": "server-id",
            "name": "Jammarr Test",
            "status": "SERVER_STATUS_RUNNING"
            if running
            else recovery.reconciler.STATUS_STOPPED,
            "autoStart": False,
            "description": "managed",
            "dockerOverrides": {
                "environment": {
                    "JAMMARR_PLEX_TOKEN": "never-persist-this-token",
                    "ONLINE_MODE": "false" if running else "true",
                }
            },
        }
        self.properties = (
            b"motd=unchanged\r\nrcon.password=never-persist-this-password\r\n"
            + (
                b"level-name=jammarr-live-Ab12Cd\r\nonline-mode=false\r\n"
                b"enforce-secure-profile=false\r\n"
                if running
                else b"level-name=world\r\nonline-mode=true\r\n"
                b"enforce-secure-profile=true\r\n"
            )
        )
        self.roots = {"world", "jammarr-live-Ab12Cd"} if running else {"world"}
        self.mods = [
            {"id": "cinemarr", "fileName": "cinemarr-1.0.0.jar", "enabled": not running},
            {"id": "jammarr", "fileName": "jammarr-1.1.0.jar", "enabled": True},
        ]

    def get_server(self, server_id: str) -> dict[str, object]:
        self.assert_id(server_id)
        return json.loads(json.dumps(self.server))

    def list_servers(self) -> list[dict[str, object]]:
        return [self.get_server("server-id")]

    def list_files(self, server_id: str, path: str) -> list[dict[str, str]]:
        self.assert_id(server_id)
        return [{"name": name} for name in sorted(self.roots)]

    def get_file(self, server_id: str, path: str) -> bytes:
        self.assert_id(server_id)
        if path != "server.properties":
            raise AssertionError(path)
        return self.properties

    def update_file(self, server_id: str, path: str, data: bytes) -> None:
        self.assert_id(server_id)
        if path != "server.properties":
            raise AssertionError(path)
        self.properties = data

    def delete_file(self, server_id: str, path: str) -> None:
        self.assert_id(server_id)
        self.roots.discard(path)

    def update_server_description(
        self, server: dict[str, object], description: str
    ) -> None:
        self.server = json.loads(json.dumps(server))
        self.server["description"] = description

    def call(
        self, service: str, method: str, payload: dict[str, object]
    ) -> dict[str, object]:
        if method == "StopServer":
            self.server["status"] = recovery.reconciler.STATUS_STOPPED
            return {}
        if method == "ListMods":
            return {"mods": json.loads(json.dumps(self.mods))}
        raise AssertionError((service, method, payload))

    def assert_id(self, server_id: str) -> None:
        if server_id != "server-id":
            raise AssertionError(server_id)


class DurableLiveRecoveryTests(unittest.TestCase):
    def test_property_restore_reverses_only_transaction_keys(self) -> None:
        current = (
            b"motd=keep\r\nlevel-name=jammarr-live-Ab12Cd\r\n"
            b"online-mode=false\r\nenforce-secure-profile=false\r\n"
        )
        restored = recovery.restore_property_values(
            current,
            {
                "level-name": ["world"],
                "online-mode": ["true"],
                "enforce-secure-profile": [],
            },
        )
        self.assertEqual(
            restored,
            b"motd=keep\r\nlevel-name=world\r\nonline-mode=true\r\n",
        )

    def test_snapshot_never_persists_unrelated_secrets(self) -> None:
        panel = FakePanel(running=False)
        target = SimpleNamespace(
            filename="jammarr.jar", sha256="a" * 64, server_name="Jammarr Test"
        )
        with tempfile.TemporaryDirectory() as temporary:
            args = SimpleNamespace(
                level="jammarr-live-Ab12Cd",
                session_dir=Path(temporary),
                runtime="1.18.2-quilt",
                server_id="server-id",
            )
            with (
                mock.patch.object(recovery, "panel_client", return_value=panel),
                mock.patch.object(
                    recovery,
                    "resolve_server",
                    return_value=("1.1.0", target, panel.get_server("server-id")),
                ),
            ):
                self.assertEqual(recovery.snapshot(args), 0)
            journal_text = (Path(temporary) / recovery.PENDING_NAME).read_text()
            self.assertNotIn("never-persist-this-token", journal_text)
            self.assertNotIn("never-persist-this-password", journal_text)
            journal = json.loads(journal_text)
            self.assertEqual(journal["originalProperties"]["level-name"], ["world"])
            self.assertEqual(
                journal["originalOnlineModeOverride"],
                {"present": True, "value": "true"},
            )

    def test_recover_session_stops_and_restores_exact_snapshot(self) -> None:
        panel = FakePanel(running=True)
        target = SimpleNamespace(server_name="Jammarr Test")
        journal = {
            "schemaVersion": 1,
            "state": "pending",
            "createdAt": "2026-09-01T00:00:00+00:00",
            "runtime": "1.18.2-quilt",
            "version": "1.1.0",
            "artifact": "jammarr.jar",
            "sha256": "a" * 64,
            "serverId": "server-id",
            "serverName": "Jammarr Test",
            "level": "jammarr-live-Ab12Cd",
            "originalProperties": {
                "level-name": ["world"],
                "online-mode": ["true"],
                "enforce-secure-profile": ["true"],
            },
            "originalOnlineModeOverride": {"present": False, "value": None},
            "originalMatchingMods": [
                {
                    "id": "cinemarr",
                    "fileName": "cinemarr-1.0.0.jar",
                    "enabled": True,
                }
            ],
        }
        with tempfile.TemporaryDirectory() as temporary:
            session = Path(temporary)
            recovery.atomic_json(session / recovery.PENDING_NAME, journal)

            def update_mod(
                _panel: FakePanel,
                _server_id: str,
                mod: dict[str, object],
                enabled: bool,
            ) -> None:
                for current in panel.mods:
                    if current["id"] == mod["id"]:
                        current["enabled"] = enabled

            args = SimpleNamespace(stop_timeout=2, poll_interval=0)
            with (
                mock.patch.object(
                    recovery,
                    "resolve_server",
                    side_effect=lambda *_args, **_kwargs: (
                        "1.1.0",
                        target,
                        panel.get_server("server-id"),
                    ),
                ),
                mock.patch.object(recovery.deployment, "update_mod_enabled", update_mod),
            ):
                recovery.recover_session(args, panel, session, False)
            self.assertEqual(
                recovery.property_values(panel.properties),
                journal["originalProperties"],
            )
            self.assertEqual(panel.server["status"], recovery.reconciler.STATUS_STOPPED)
            self.assertNotIn(
                "ONLINE_MODE", panel.server["dockerOverrides"]["environment"]
            )
            self.assertTrue(panel.mods[0]["enabled"])
            self.assertNotIn("jammarr-live-Ab12Cd", panel.roots)
            self.assertFalse((session / recovery.PENDING_NAME).exists())
            self.assertTrue((session / recovery.COMPLETE_NAME).exists())


if __name__ == "__main__":
    unittest.main()
