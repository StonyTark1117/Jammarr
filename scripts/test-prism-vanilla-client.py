#!/usr/bin/env python3

from __future__ import annotations

import json
import importlib.util
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock
from urllib.request import urlopen


SCRIPT = Path(__file__).with_name("run-prism-vanilla-client.py")
SPEC = importlib.util.spec_from_file_location("run_prism_vanilla_client", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
CLIENT_HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT_HELPER)


class PrismVanillaClientTest(unittest.TestCase):
    def test_legacy_releases_use_server_and_port_arguments(self) -> None:
        arguments, mode = CLIENT_HELPER.server_connection_arguments(
            "1.19.4", "127.0.0.1:26000"
        )
        self.assertEqual(arguments, ["--server", "127.0.0.1", "--port", "26000"])
        self.assertEqual(mode, "legacy-server-port")

    def test_modern_releases_use_quick_play_multiplayer(self) -> None:
        for version in ("1.20", "1.20.1", "1.21.11", "26.2"):
            with self.subTest(version=version):
                arguments, mode = CLIENT_HELPER.server_connection_arguments(
                    version, "127.0.0.1:26000"
                )
                self.assertEqual(
                    arguments, ["--quickPlayMultiplayer", "127.0.0.1:26000"]
                )
                self.assertEqual(mode, "quick-play-multiplayer")

    def test_direct_launch_delegates_to_verified_cache_runtime(self) -> None:
        source = SCRIPT.read_text("utf-8")
        launch = source[source.index("def direct_launch_command(") :]
        launch = launch[: launch.index("def prepare_instance(")]
        self.assertIn("from prism_vanilla_runtime import direct_launch_command", launch)
        self.assertIn("return verified_launch_command(", launch)
        self.assertIn("args, game_dir, sys.modules[__name__]", launch)
        self.assertIn("launch_server=launch_server", launch)

    def test_connection_arguments_reject_invalid_port(self) -> None:
        with self.assertRaisesRegex(SystemExit, "port is invalid"):
            CLIENT_HELPER.server_connection_arguments("1.20.1", "127.0.0.1:70000")

    def test_offline_privileges_service_allows_servers_without_credentials(self) -> None:
        with CLIENT_HELPER.offline_privileges_service() as base_url:
            with urlopen(base_url + "/privileges") as response:
                value = json.loads(response.read())
        privileges = value["privileges"]
        self.assertTrue(privileges["onlineChat"]["enabled"])
        self.assertTrue(privileges["multiplayerServer"]["enabled"])
        self.assertFalse(privileges["multiplayerRealms"]["enabled"])

    def test_delayed_connection_relay_holds_then_forwards_loopback_traffic(self) -> None:
        upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        upstream.bind(("127.0.0.1", 0))
        upstream.listen(1)
        accepted = threading.Event()

        def echo_once() -> None:
            connection, _ = upstream.accept()
            accepted.set()
            with connection:
                connection.sendall(connection.recv(16))

        thread = threading.Thread(target=echo_once, daemon=True)
        thread.start()
        target = f"127.0.0.1:{upstream.getsockname()[1]}"
        try:
            with CLIENT_HELPER.DelayedConnectionRelay(target) as relay:
                with socket.create_connection(
                    ("127.0.0.1", int(relay.endpoint.rpartition(":")[2]))
                ) as client:
                    client.settimeout(0.1)
                    client.sendall(b"ready")
                    with self.assertRaises(socket.timeout):
                        client.recv(5)
                    self.assertFalse(accepted.is_set())
                    relay.release()
                    self.assertEqual(client.recv(5), b"ready")
                    self.assertTrue(accepted.wait(1))
        finally:
            upstream.close()
            thread.join(timeout=2)

    def test_initial_resource_wait_uses_current_atlas_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            latest_log = Path(temporary) / "latest.log"
            latest_log.write_text(
                "Created: 256x128x0 minecraft:textures/atlas/mob_effects.png-atlas\n",
                encoding="utf-8",
            )
            process = mock.Mock()
            process.poll.return_value = None
            self.assertTrue(
                CLIENT_HELPER.wait_for_initial_resources(
                    latest_log, process, timeout_seconds=1
                )
            )

    def test_initial_resource_wait_fails_closed_after_client_exit(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            process = mock.Mock()
            process.poll.return_value = 1
            self.assertFalse(
                CLIENT_HELPER.wait_for_initial_resources(
                    Path(temporary) / "latest.log", process, timeout_seconds=1
                )
            )

    def test_chat_trigger_targets_the_private_minecraft_window(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            trigger = Path(temporary) / "chat.trigger"
            trigger.touch()
            process = mock.Mock()
            process.poll.return_value = None
            search = subprocess.CompletedProcess(
                args=[], returncode=0, stdout="17\n23\n", stderr=""
            )
            success = subprocess.CompletedProcess(args=[], returncode=0, stdout="", stderr="")
            with mock.patch.object(
                CLIENT_HELPER.subprocess,
                "run",
                side_effect=[search, success, success, success, success, success, success],
            ) as run:
                CLIENT_HELPER.send_chat_when_triggered(
                    trigger, "JammarrVanillaChat_Test", process
                )
            self.assertEqual(run.call_count, 7)
            self.assertEqual(run.call_args_list[1].args[0][-1], "23")
            self.assertEqual(
                run.call_args_list[4].args[0],
                ["xclip", "-selection", "clipboard", "-loops", "1"],
            )
            self.assertEqual(
                run.call_args_list[4].kwargs["input"], "JammarrVanillaChat_Test"
            )
            self.assertEqual(run.call_args_list[5].args[0][-1], "ctrl+v")

    def test_private_client_does_not_open_pause_menu_when_focus_changes(self) -> None:
        source = SCRIPT.read_text("utf-8")
        self.assertIn('"narrator:0\\npauseOnLostFocus:false\\n"', source)

    def test_chat_arguments_must_be_supplied_together(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--minecraft",
                    "1.20.1",
                    "--server",
                    "127.0.0.1:26000",
                    "--username",
                    "VanillaProbe",
                    "--workspace",
                    str(root / "workspace"),
                    "--shared-root",
                    str(root / "shared"),
                    "--chat-message",
                    "MissingTrigger",
                    "--prepare-only",
                ],
                check=False,
                capture_output=True,
                text=True,
            )
        self.assertEqual(result.returncode, 2)
        self.assertIn("must be supplied together", result.stderr)

    def test_shutdown_trigger_closes_the_private_minecraft_window(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            trigger = Path(temporary) / "shutdown.trigger"
            trigger.touch()
            process = mock.Mock()
            process.poll.return_value = None
            search = subprocess.CompletedProcess(
                args=[], returncode=0, stdout="17\n23\n", stderr=""
            )
            success = subprocess.CompletedProcess(args=[], returncode=0, stdout="", stderr="")
            with mock.patch.object(
                CLIENT_HELPER.subprocess, "run", side_effect=[search, success]
            ) as run:
                CLIENT_HELPER.close_when_triggered(trigger, process, "1.20.1")
            self.assertEqual(run.call_count, 2)
            self.assertEqual(
                run.call_args_list[1].args[0], ["xdotool", "windowclose", "23"]
            )

    def test_lwjgl2_shutdown_terminates_jvm_without_destroying_drawable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            trigger = Path(temporary) / "shutdown.trigger"
            trigger.touch()
            process = mock.Mock()
            process.poll.return_value = None
            with mock.patch.object(CLIENT_HELPER.subprocess, "run") as run:
                CLIENT_HELPER.close_when_triggered(trigger, process, "1.12.2")
            process.terminate.assert_called_once_with()
            run.assert_not_called()

    def make_shared_root(self, root: Path, version: str = "1.20.1") -> Path:
        shared = root / "shared"
        for name in ("assets", "libraries", "java"):
            (shared / name).mkdir(parents=True)
        metadata_dir = shared / "meta" / "net.minecraft"
        metadata_dir.mkdir(parents=True)
        (metadata_dir / f"{version}.json").write_text(
            json.dumps(
                {
                    "formatVersion": 1,
                    "name": "Minecraft",
                    "requires": [{"suggests": "3.3.1", "uid": "org.lwjgl3"}],
                    "uid": "net.minecraft",
                    "version": version,
                }
            ),
            encoding="utf-8",
        )
        return shared

    def run_prepare(
        self,
        shared: Path,
        workspace: Path,
        version: str = "1.20.1",
        metadata_base_url: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        command = [
                sys.executable,
                str(SCRIPT),
                "--minecraft",
                version,
                "--server",
                "127.0.0.1:26000",
                "--username",
                "VanillaProbe",
                "--workspace",
                str(workspace),
                "--shared-root",
                str(shared),
                "--prepare-only",
            ]
        if metadata_base_url is not None:
            command.extend(["--metadata-base-url", metadata_base_url])
        return subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
        )

    def test_prepare_creates_only_minecraft_and_lwjgl_components(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shared = self.make_shared_root(root)
            workspace = root / "workspace"
            result = self.run_prepare(shared, workspace)
            self.assertEqual(result.returncode, 0, result.stderr)

            instance = workspace / "instances" / "jammarr-vanilla-1.20.1"
            pack = json.loads((instance / "mmc-pack.json").read_text("utf-8"))
            self.assertEqual(
                [component["uid"] for component in pack["components"]],
                ["org.lwjgl3", "net.minecraft"],
            )
            attestation = json.loads((instance / "vanilla-attestation.json").read_text("utf-8"))
            self.assertFalse(attestation["jammarrComponentPresent"])
            self.assertEqual(attestation["mods"], [])
            self.assertEqual(attestation["accountMode"], "direct-offline")
            self.assertEqual(attestation["offlineUsername"], "VanillaProbe")
            self.assertFalse((instance / "minecraft" / "mods").exists())
            self.assertFalse((workspace / "accounts.json").exists())
            settings = (workspace / "prismlauncher.cfg").read_text("utf-8")
            self.assertIn("ApplicationTheme=system\n", settings)
            self.assertIn("IconTheme=pe_colored\n", settings)

    def test_prepare_fetches_missing_exact_minecraft_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shared = self.make_shared_root(root)
            remote = root / "remote"
            remote.mkdir()
            (remote / "1.20.2.json").write_text(
                json.dumps(
                    {
                        "formatVersion": 1,
                        "name": "Minecraft",
                        "requires": [{"suggests": "3.3.2", "uid": "org.lwjgl3"}],
                        "uid": "net.minecraft",
                        "version": "1.20.2",
                    }
                ),
                encoding="utf-8",
            )
            result = self.run_prepare(
                shared,
                root / "workspace",
                version="1.20.2",
                metadata_base_url=remote.as_uri(),
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            pack = json.loads(
                (
                    root
                    / "workspace/instances/jammarr-vanilla-1.20.2/mmc-pack.json"
                ).read_text("utf-8")
            )
            self.assertEqual(pack["components"][-1]["version"], "1.20.2")

    def test_prepare_rejects_non_lwjgl_component_requirements(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shared = self.make_shared_root(root)
            metadata = shared / "meta" / "net.minecraft" / "1.20.1.json"
            value = json.loads(metadata.read_text("utf-8"))
            value["requires"].append({"suggests": "1.0", "uid": "example.injected"})
            metadata.write_text(json.dumps(value), encoding="utf-8")
            result = self.run_prepare(shared, root / "workspace")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Unsupported Minecraft component requirement", result.stderr)

    def test_prepare_rejects_username_longer_than_minecraft_limit(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shared = self.make_shared_root(root)
            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--minecraft",
                    "1.20.1",
                    "--server",
                    "127.0.0.1:26000",
                    "--username",
                    "SeventeenLettersXX",
                    "--workspace",
                    str(root / "workspace"),
                    "--shared-root",
                    str(shared),
                    "--prepare-only",
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("1 through 16 characters", result.stderr)


if __name__ == "__main__":
    unittest.main()
