#!/usr/bin/env python3

from __future__ import annotations

import json
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


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

    def test_connection_arguments_reject_invalid_port(self) -> None:
        with self.assertRaisesRegex(SystemExit, "port is invalid"):
            CLIENT_HELPER.server_connection_arguments("1.20.1", "127.0.0.1:70000")

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
