#!/usr/bin/env python3
"""Unit tests for the DiscPanel test-server reconciler."""

from __future__ import annotations

import importlib.util
import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("reconcile-discopanel-test-servers.py")
SPEC = importlib.util.spec_from_file_location("reconcile_discopanel", SCRIPT)
assert SPEC and SPEC.loader
reconcile_discopanel = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = reconcile_discopanel
SPEC.loader.exec_module(reconcile_discopanel)


class DiscPanelReconcilerTests(unittest.TestCase):
    def test_managed_description_is_state_neutral(self) -> None:
        self.assertNotIn("No 1.1.0", reconcile_discopanel.MANAGED_DESCRIPTION)
        self.assertIn("ModService", reconcile_discopanel.MANAGED_DESCRIPTION)

    def test_manifest_produces_complete_runtime_matrix(self) -> None:
        profiles = reconcile_discopanel.desired_profiles(Path("gradle/targets.json"))
        self.assertEqual(len(profiles), 99)
        self.assertEqual(len({profile.name for profile in profiles}), 99)
        self.assertEqual(
            [profile.runtime for profile in profiles if profile.provisioning != "native"],
            [
                "b1.7.3-babric",
                "1.6.4-fabric",
                "1.6.4-ornithe",
                "1.8.9-fabric",
                "1.8.9-ornithe",
            ],
        )

    def test_runtime_java_selects_available_pinned_image(self) -> None:
        profiles = {
            profile.runtime: profile
            for profile in reconcile_discopanel.desired_profiles(Path("gradle/targets.json"))
        }
        self.assertEqual(profiles["1.7.10-forge"].docker_image, "java8")
        self.assertEqual(profiles["1.20.4-forge"].docker_image, "java17")
        self.assertEqual(profiles["1.21.11-neoforge"].docker_image, "java21")
        self.assertEqual(profiles["26.2-quilt"].docker_image, "java25")

    def test_custom_profiles_use_pinned_fabric_bootstraps(self) -> None:
        profiles = {
            profile.runtime: profile
            for profile in reconcile_discopanel.desired_profiles(Path("gradle/targets.json"))
        }
        babric = profiles["b1.7.3-babric"]
        self.assertEqual(babric.panel_loader, "MOD_LOADER_FABRIC")
        self.assertEqual(babric.provisioning, "custom-url")
        self.assertIn("0.16.9", dict(babric.environment)["FABRIC_LAUNCHER_URL"])
        self.assertRegex(babric.launcher_sha256, r"^[0-9a-f]{64}$")
        for runtime in ("1.6.4-fabric", "1.8.9-fabric"):
            self.assertRegex(profiles[runtime].launcher_sha256, r"^[0-9a-f]{64}$")
        ornithe = profiles["1.6.4-ornithe"]
        self.assertEqual(ornithe.provisioning, "custom-upload")
        self.assertEqual(
            dict(ornithe.environment),
            {
                "CUSTOM_SERVER": (
                    "/data/1.6.4-ornithe-server-bootstrap/fabric-server-launch.jar"
                ),
                "TYPE": "CUSTOM",
            },
        )
        for digest_name in ("sourceSha256", "runtimeSha256"):
            self.assertRegex(
                reconcile_discopanel.ORNITHE_SERVER_JARS["1.6.4"][digest_name],
                r"^[0-9a-f]{64}$",
            )

    def test_ornithe_bootstrap_requires_exact_transformed_server_jar(self) -> None:
        profile = next(
            profile
            for profile in reconcile_discopanel.desired_profiles(
                Path("gradle/targets.json")
            )
            if profile.runtime == "1.6.4-ornithe"
        )
        expected = reconcile_discopanel.ORNITHE_SERVER_JARS["1.6.4"][
            "runtimeSha256"
        ]

        class Panel:
            def list_files(self, server_id: str, path: str = "") -> list[dict[str, str]]:
                if path:
                    return [
                        {"name": "fabric-server-launch.jar"},
                        {"name": "libraries"},
                    ]
                return [
                    {"name": "1.6.4-ornithe-server-bootstrap"},
                    {"name": "server.jar"},
                ]

            def get_file(self, server_id: str, path: str) -> bytes:
                self.requested = path
                return b"server"

        panel = Panel()
        with mock.patch.object(
            reconcile_discopanel.hashlib,
            "sha256",
            return_value=mock.Mock(hexdigest=mock.Mock(return_value=expected)),
        ):
            self.assertTrue(
                reconcile_discopanel.has_ornithe_bootstrap(panel, "server", profile)
            )
        self.assertEqual(panel.requested, "server.jar")

    def test_ornithe_server_transform_is_deterministic_and_removes_library_paths(self) -> None:
        source = io.BytesIO()
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("net/minecraft/Server.class", b"game")
            archive.writestr(
                "org/apache/logging/log4j/Logger.class",
                b"legacy logger",
                compress_type=zipfile.ZIP_DEFLATED,
            )
            archive.writestr("META-INF/MANIFEST.MF", b"manifest")
        removed = {
            "org/apache/logging/log4j/Logger.class",
            "META-INF/MANIFEST.MF",
        }
        first = reconcile_discopanel._zip_entries_without_paths(
            source.getvalue(), removed
        )
        second = reconcile_discopanel._zip_entries_without_paths(
            source.getvalue(), removed
        )
        self.assertEqual(first, second)
        with tempfile.TemporaryDirectory() as temporary:
            result = Path(temporary) / "server.jar"
            result.write_bytes(first)
            with zipfile.ZipFile(result) as archive:
                self.assertEqual(archive.testzip(), None)
                self.assertEqual(
                    archive.namelist(), ["net/minecraft/Server.class"]
                )
                self.assertEqual(
                    archive.read("net/minecraft/Server.class"), b"game"
                )

    def test_ornithe_remap_cache_clear_is_scoped_to_exact_runtime(self) -> None:
        profile = next(
            profile
            for profile in reconcile_discopanel.desired_profiles(
                Path("gradle/targets.json")
            )
            if profile.runtime == "1.8.9-ornithe"
        )

        class Panel:
            def list_files(self, server_id: str, path: str = "") -> list[dict[str, str]]:
                self.listed = path
                return [
                    {"name": "minecraft-1.8.9-0.19.5"},
                    {"name": "unrelated-cache"},
                ]

            def delete_file(self, server_id: str, path: str) -> None:
                self.deleted = (server_id, path)

        panel = Panel()
        self.assertTrue(
            reconcile_discopanel.clear_ornithe_remap_cache(
                panel, "server", profile
            )
        )
        self.assertEqual(panel.listed, ".fabric/remappedJars")
        self.assertEqual(
            panel.deleted,
            ("server", ".fabric/remappedJars/minecraft-1.8.9-0.19.5"),
        )

    def test_port_allocator_skips_existing_ports(self) -> None:
        self.assertEqual(
            reconcile_discopanel.allocate_ports(25565, {25566, 25568}, 4),
            [25565, 25567, 25569, 25570],
        )

    def test_drift_requires_stopped_exact_profile(self) -> None:
        profile = reconcile_discopanel.Profile(
            runtime="1.20.1-fabric",
            minecraft="1.20.1",
            loader="fabric",
            name="Jammarr 1.20.1 Fabric Test",
            java=17,
            docker_image="java17",
            panel_loader="MOD_LOADER_FABRIC",
            provisioning="native",
        )
        server = {
            "mcVersion": "1.20.1",
            "modLoader": "MOD_LOADER_FABRIC",
            "dockerImage": "java17",
            "memory": 4096,
            "autoStart": False,
            "status": "SERVER_STATUS_STOPPED",
        }
        self.assertEqual(reconcile_discopanel.drift(profile, server), [])
        server["autoStart"] = True
        self.assertRegex(reconcile_discopanel.drift(profile, server)[0], "autoStart")
        server["autoStart"] = False
        server["status"] = "SERVER_STATUS_RUNNING"
        self.assertRegex(reconcile_discopanel.drift(profile, server)[0], "status")

    def test_custom_drift_requires_pinned_environment(self) -> None:
        profile = next(
            profile
            for profile in reconcile_discopanel.desired_profiles(Path("gradle/targets.json"))
            if profile.runtime == "1.6.4-fabric"
        )
        server = {
            "mcVersion": "1.6.4",
            "modLoader": "MOD_LOADER_FABRIC",
            "dockerImage": "java8",
            "memory": 4096,
            "autoStart": False,
            "status": "SERVER_STATUS_STOPPED",
            "dockerOverrides": {"environment": dict(profile.environment)},
        }
        self.assertEqual(reconcile_discopanel.drift(profile, server), [])
        server["dockerOverrides"]["environment"] = {}
        self.assertRegex(
            reconcile_discopanel.drift(profile, server)[0],
            "docker environment FABRIC_LAUNCHER_URL",
        )

    def test_custom_url_launcher_verifies_pinned_bytes(self) -> None:
        payload = b"pinned custom launcher"
        profile = reconcile_discopanel.Profile(
            runtime="test-fabric",
            minecraft="test",
            loader="fabric",
            name="Test",
            java=8,
            docker_image="java8",
            panel_loader="MOD_LOADER_FABRIC",
            provisioning="custom-url",
            environment=(("FABRIC_LAUNCHER_URL", "https://example.invalid/server.jar"),),
            launcher_sha256=reconcile_discopanel.hashlib.sha256(payload).hexdigest(),
        )
        with mock.patch.object(
            reconcile_discopanel.urllib.request,
            "urlopen",
            return_value=io.BytesIO(payload),
        ):
            self.assertEqual(
                reconcile_discopanel.verify_custom_url_launcher(profile),
                profile.launcher_sha256,
            )

    def test_custom_url_launcher_rejects_changed_bytes(self) -> None:
        profile = reconcile_discopanel.Profile(
            runtime="test-fabric",
            minecraft="test",
            loader="fabric",
            name="Test",
            java=8,
            docker_image="java8",
            panel_loader="MOD_LOADER_FABRIC",
            provisioning="custom-url",
            environment=(("FABRIC_LAUNCHER_URL", "https://example.invalid/server.jar"),),
            launcher_sha256="0" * 64,
        )
        with mock.patch.object(
            reconcile_discopanel.urllib.request,
            "urlopen",
            return_value=io.BytesIO(b"changed"),
        ):
            with self.assertRaisesRegex(RuntimeError, "digest mismatch"):
                reconcile_discopanel.verify_custom_url_launcher(profile)

    def test_upload_uses_raw_endpoint_and_stable_saved_name(self) -> None:
        panel = reconcile_discopanel.DiscPanel("http://example.invalid", "test")
        calls: list[str] = []
        uploaded = False

        def fake_call(service: str, method: str, payload: dict) -> dict:
            nonlocal uploaded
            calls.append(method)
            if method == "InitUpload":
                self.assertRegex(
                    payload["filename"], r"^jammarr-[0-9a-f]{32}-probe\.bin$"
                )
                self.assertEqual(payload["chunkSize"], 0)
                return {"sessionId": "session"}
            if method == "GetUploadStatus":
                return {
                    "completed": uploaded,
                    "bytesReceived": 7 if uploaded else 0,
                }
            if method == "SaveUploadedFile":
                self.assertEqual(payload["filename"], "probe.bin")
                return {}
            return {}

        def fake_urlopen(request, timeout):
            nonlocal uploaded
            self.assertEqual(request.full_url, "http://example.invalid/api/v1/upload/session")
            self.assertEqual(request.method, "PUT")
            self.assertEqual(request.data, b"payload")
            uploaded = True
            return io.BytesIO(b"{}")

        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "probe.bin"
            source.write_bytes(b"payload")
            with mock.patch.object(panel, "call", side_effect=fake_call), mock.patch.object(
                reconcile_discopanel.urllib.request,
                "urlopen",
                side_effect=fake_urlopen,
            ):
                panel.upload_file("server", source, "")
        self.assertNotIn("UploadChunk", calls)
        self.assertEqual(calls[-2:], ["GetUploadStatus", "SaveUploadedFile"])

    def test_description_update_preserves_server_overrides(self) -> None:
        panel = reconcile_discopanel.DiscPanel("http://example.invalid", "test")
        server = {
            "id": "server",
            "name": "Jammarr Test",
            "description": "old",
            "port": 25565,
            "maxPlayers": 5,
            "memory": 4096,
            "modLoader": "MOD_LOADER_FORGE",
            "mcVersion": "1.7.10",
            "dockerImage": "java8",
            "status": "SERVER_STATUS_STOPPED",
            "dockerOverrides": {"environment": {"PRIVATE_SENTINEL": "preserve-me"}},
        }
        with mock.patch.object(panel, "call", return_value={}) as call:
            panel.update_server_description(server, "new")
        service, method, payload = call.call_args.args
        self.assertEqual((service, method), ("ServerService", "UpdateServer"))
        self.assertEqual(payload["description"], "new")
        self.assertEqual(payload["modLoader"], "forge")
        self.assertEqual(payload["dockerOverrides"], server["dockerOverrides"])
        self.assertFalse(payload["autoStart"])

    def test_environment_update_preserves_existing_overrides(self) -> None:
        panel = reconcile_discopanel.DiscPanel("http://example.invalid", "test")
        server = {
            "id": "server",
            "name": "Jammarr Test",
            "description": "managed",
            "port": 25565,
            "maxPlayers": 5,
            "memory": 4096,
            "modLoader": "MOD_LOADER_FORGE",
            "mcVersion": "1.7.10",
            "dockerImage": "java8",
            "autoStart": False,
            "detached": True,
            "dockerOverrides": {
                "environment": {"PRIVATE_SENTINEL": "preserve-me"},
                "networkMode": "host",
            },
        }
        with mock.patch.object(panel, "call", return_value={}) as call:
            panel.update_server_environment(server, {"NEW_SETTING": "new-value"})
        service, method, payload = call.call_args.args
        self.assertEqual((service, method), ("ServerService", "UpdateServer"))
        self.assertEqual(
            payload["dockerOverrides"]["environment"],
            {"PRIVATE_SENTINEL": "preserve-me", "NEW_SETTING": "new-value"},
        )
        self.assertEqual(payload["dockerOverrides"]["networkMode"], "host")
        self.assertEqual(
            server["dockerOverrides"]["environment"],
            {"PRIVATE_SENTINEL": "preserve-me"},
        )

    def test_custom_repair_preserves_private_environment_and_removes_old_launcher(self) -> None:
        panel = reconcile_discopanel.DiscPanel("http://example.invalid", "test")
        profile = next(
            profile
            for profile in reconcile_discopanel.desired_profiles(
                Path("gradle/targets.json")
            )
            if profile.runtime == "1.6.4-ornithe"
        )
        server = {
            "id": "server",
            "name": profile.name,
            "description": "managed",
            "port": 25565,
            "maxPlayers": 5,
            "memory": 4096,
            "modLoader": profile.panel_loader,
            "mcVersion": profile.minecraft,
            "dockerImage": profile.docker_image,
            "autoStart": False,
            "detached": True,
            "dockerOverrides": {
                "environment": {
                    "FABRIC_LAUNCHER": "obsolete.jar",
                    "JAMMARR_PLEX_TOKEN": "private-token",
                },
                "networkMode": "host",
            },
        }
        with mock.patch.object(panel, "call", return_value={}) as call:
            panel.update_server(profile, server)
        payload = call.call_args.args[2]
        environment = payload["dockerOverrides"]["environment"]
        self.assertEqual(environment["JAMMARR_PLEX_TOKEN"], "private-token")
        self.assertNotIn("FABRIC_LAUNCHER", environment)
        self.assertEqual(environment["TYPE"], "CUSTOM")
        self.assertEqual(payload["dockerOverrides"]["networkMode"], "host")


if __name__ == "__main__":
    unittest.main()
