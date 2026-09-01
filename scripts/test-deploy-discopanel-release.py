#!/usr/bin/env python3
"""Unit tests for exact DiscPanel release deployment mapping."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("deploy-discopanel-release.py")
SPEC = importlib.util.spec_from_file_location("deploy_discopanel_release", SCRIPT)
assert SPEC and SPEC.loader
deployment = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = deployment
SPEC.loader.exec_module(deployment)


class DiscPanelReleaseDeploymentTests(unittest.TestCase):
    def release_artifacts(self) -> dict[str, dict[str, object]]:
        manifest = deployment.target_matrix.load_manifest(Path("gradle/targets.json"))
        return {
            Path(artifact["artifact"]).name: {
                "filename": Path(artifact["artifact"]).name,
                "sha256": deployment.hashlib.sha256(artifact["name"].encode()).hexdigest(),
                "dependencies": {
                    "fabric-loader": "0.19.3",
                    "forge": "forge-version",
                    "neoforge": "neoforge-version",
                    "quilt-loader": "0.30.0",
                },
            }
            for artifact in deployment.target_matrix.implemented_artifacts(manifest)
        }

    def test_deployment_mapping_covers_all_runtime_profiles(self) -> None:
        targets = deployment.deployment_targets(
            Path("gradle/targets.json"), Path("build/releases"), self.release_artifacts()
        )
        self.assertEqual(len(targets), 99)
        self.assertEqual(len({target.runtime for target in targets}), 99)
        self.assertEqual(len({target.filename for target in targets}), 75)

    def test_quilt_runtime_uses_matching_fabric_artifact(self) -> None:
        targets = {
            target.runtime: target
            for target in deployment.deployment_targets(
                Path("gradle/targets.json"), Path("build/releases"), self.release_artifacts()
            )
        }
        self.assertEqual(
            targets["1.20.1-quilt"].filename,
            targets["1.20.1-fabric"].filename,
        )
        self.assertIn("-fabric.jar", targets["26.2-quilt"].filename)
        self.assertEqual(
            dict(targets["1.20.1-quilt"].loader_environment),
            {"QUILT_LOADER_VERSION": "0.30.0"},
        )

    def test_native_loader_versions_come_from_release_metadata(self) -> None:
        artifacts = self.release_artifacts()
        manifest = deployment.target_matrix.load_manifest(Path("gradle/targets.json"))
        for artifact in deployment.target_matrix.implemented_artifacts(manifest):
            filename = Path(artifact["artifact"]).name
            artifacts[filename]["dependencies"] = {
                "fabric-loader": "fabric-pin",
                "forge": "forge-pin",
                "neoforge": "neoforge-pin",
                "quilt-loader": "quilt-pin",
            }
        targets = {
            target.runtime: target
            for target in deployment.deployment_targets(
                Path("gradle/targets.json"), Path("build/releases"), artifacts
            )
        }
        self.assertEqual(
            dict(targets["1.12.2-forge"].loader_environment),
            {"FORGE_VERSION": "forge-pin"},
        )
        self.assertEqual(
            dict(targets["1.20.1-fabric"].loader_environment),
            {"FABRIC_LOADER_VERSION": "fabric-pin"},
        )
        self.assertEqual(
            dict(targets["1.20.1-neoforge"].loader_environment),
            {"NEOFORGE_VERSION": "neoforge-pin"},
        )
        self.assertEqual(targets["1.6.4-fabric"].loader_environment, ())

    def test_client_companions_are_not_server_deployments(self) -> None:
        filenames = {
            target.filename
            for target in deployment.deployment_targets(
                Path("gradle/targets.json"), Path("build/releases"), self.release_artifacts()
            )
        }
        self.assertFalse(any("liteloader" in filename for filename in filenames))

    def test_legacy_forge_coordinate_suffix_is_not_passed_to_server_image(self) -> None:
        artifacts = self.release_artifacts()
        filename = "jammarr-1.1.0+mc1.7.10-forge.jar"
        artifacts[filename]["dependencies"]["forge"] = "10.13.4.1614-1.7.10"
        targets = {
            target.runtime: target
            for target in deployment.deployment_targets(
                Path("gradle/targets.json"), Path("build/releases"), artifacts
            )
        }
        self.assertEqual(
            dict(targets["1.7.10-forge"].loader_environment),
            {"FORGE_VERSION": "10.13.4.1614"},
        )

    def test_only_jammarr_filename_prefix_is_owned(self) -> None:
        self.assertTrue(deployment.is_jammarr_mod({"fileName": "jammarr-1.1.0.jar"}))
        self.assertFalse(deployment.is_jammarr_mod({"fileName": "cinemarr-1.0.0.jar"}))
        self.assertFalse(deployment.is_jammarr_mod({"displayName": "Jammarr"}))

    def test_apply_verifies_new_bytes_and_retains_disabled_rollback(self) -> None:
        payload = b"exact release candidate bytes"
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "jammarr-1.1.0+mc1.20.1-fabric.jar"
            source.write_bytes(payload)
            target = deployment.DeploymentTarget(
                runtime="1.20.1-fabric",
                server_name="Jammarr 1.20.1 Fabric Test",
                filename=source.name,
                sha256=deployment.hashlib.sha256(payload).hexdigest(),
                source=source,
            )
            previous = {
                "id": "old",
                "fileName": "jammarr-1.0.2+mc1.20.1-fabric.jar",
                "displayName": "Jammarr 1.0.2",
                "enabled": True,
            }

            class FakePanel:
                def __init__(self) -> None:
                    self.data = bytearray()
                    self.mods = [previous.copy()]
                    self.calls: list[tuple[str, str]] = []

                def call(self, service: str, method: str, body: dict[str, object]):
                    self.calls.append((service, method))
                    if method == "ImportUploadedMod":
                        self.mods.append(
                            {
                                "id": "new",
                                "fileName": target.filename,
                                "displayName": target.filename,
                                "enabled": True,
                            }
                        )
                        return {}
                    if method == "UpdateMod":
                        mod = next(item for item in self.mods if item["id"] == body["modId"])
                        mod["enabled"] = body["enabled"]
                        return {}
                    if method == "ListMods":
                        return {"mods": self.mods}
                    raise AssertionError(f"unexpected API call {service}/{method}")

                def create_upload_session(self, source: Path) -> str:
                    self.calls.append(("UploadService", "CreateUploadSession"))
                    self.data.extend(source.read_bytes())
                    return "upload"

                def get_file(self, server_id: str, path: str) -> bytes:
                    self.calls.append(("FileService", "GetFile"))
                    return bytes(self.data)

                def get_server(self, server_id: str) -> dict[str, object]:
                    self.calls.append(("ServerService", "GetServer"))
                    return {"status": deployment.reconciler.STATUS_STOPPED}

            panel = FakePanel()
            deployment.deploy_target(
                panel, target, {"id": "server"}, [previous.copy()], "1.1.0"
            )
            active = [mod for mod in panel.mods if mod.get("enabled") is True]
            self.assertEqual([mod["fileName"] for mod in active], [target.filename])
            self.assertFalse(panel.mods[0]["enabled"])
            self.assertNotIn(("ServerService", "StartServer"), panel.calls)

    def test_same_filename_candidate_refreshes_bytes_in_place(self) -> None:
        payload = b"rebuilt candidate bytes"
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "jammarr-1.1.0+mc1.8.9-ornithe.jar"
            source.write_bytes(payload)
            target = deployment.DeploymentTarget(
                runtime="1.8.9-ornithe",
                server_name="Jammarr 1.8.9 Ornithe Test",
                filename=source.name,
                sha256=deployment.hashlib.sha256(payload).hexdigest(),
                source=source,
            )
            previous = {
                "id": "old",
                "fileName": target.filename,
                "displayName": target.filename,
                "enabled": True,
            }

            class FakePanel:
                def __init__(self) -> None:
                    self.data = b"old candidate bytes"
                    self.mods = [previous.copy()]
                    self.operations: list[str] = []

                def call(self, service: str, method: str, body: dict[str, object]):
                    if method == "ListMods":
                        return {"mods": self.mods}
                    raise AssertionError(f"unexpected API call {service}/{method}")

                def update_file(self, server_id: str, path: str, content: bytes) -> None:
                    self.operations.append(f"update:{path}")
                    self.data = content

                def get_file(self, server_id: str, path: str) -> bytes:
                    return self.data

                def get_server(self, server_id: str) -> dict[str, object]:
                    return {"status": deployment.reconciler.STATUS_STOPPED}

            panel = FakePanel()
            deployment.deploy_target(
                panel, target, {"id": "server"}, [previous.copy()], "1.1.0"
            )
            self.assertEqual(panel.operations, [f"update:mods/{target.filename}"])
            self.assertEqual(
                [mod["id"] for mod in panel.mods if mod.get("enabled")], ["old"]
            )

    def test_matching_loader_environment_is_not_rewritten(self) -> None:
        target = deployment.DeploymentTarget(
            runtime="1.21.7-neoforge",
            server_name="Jammarr 1.21.7 NeoForge Test",
            filename="jammarr-1.1.0+mc1.21.7-neoforge.jar",
            sha256="a" * 64,
            source=Path("unused.jar"),
            loader_environment=(("NEOFORGE_VERSION", "21.7.25-beta"),),
        )

        class FakePanel:
            def get_server(self, server_id: str) -> dict[str, object]:
                return {
                    "id": server_id,
                    "status": deployment.reconciler.STATUS_STOPPED,
                    "dockerOverrides": {
                        "environment": {"NEOFORGE_VERSION": "21.7.25-beta"}
                    },
                }

            def update_server_environment(self, server, additions) -> None:
                raise AssertionError("matching loader environment must not be rewritten")

        deployment.update_loader_environment(FakePanel(), target, {"id": "server"})


if __name__ == "__main__":
    unittest.main()
