#!/usr/bin/env python3
"""Unit tests for the DiscPanel test-server reconciler."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("reconcile-discopanel-test-servers.py")
SPEC = importlib.util.spec_from_file_location("reconcile_discopanel", SCRIPT)
assert SPEC and SPEC.loader
reconcile_discopanel = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = reconcile_discopanel
SPEC.loader.exec_module(reconcile_discopanel)


class DiscPanelReconcilerTests(unittest.TestCase):
    def test_manifest_produces_complete_runtime_matrix(self) -> None:
        profiles = reconcile_discopanel.desired_profiles(Path("gradle/targets.json"))
        self.assertEqual(len(profiles), 99)
        self.assertEqual(len({profile.name for profile in profiles}), 99)
        self.assertEqual(
            [profile.runtime for profile in profiles if profile.provisioning == "custom"],
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
            "status": "SERVER_STATUS_STOPPED",
        }
        self.assertEqual(reconcile_discopanel.drift(profile, server), [])
        server["status"] = "SERVER_STATUS_RUNNING"
        self.assertRegex(reconcile_discopanel.drift(profile, server)[0], "status")


if __name__ == "__main__":
    unittest.main()
