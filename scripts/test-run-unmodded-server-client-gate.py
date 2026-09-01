#!/usr/bin/env python3

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
GATE = ROOT / "scripts/run-unmodded-server-client-gate.sh"


class UnmoddedServerClientGateSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = GATE.read_text("utf-8")

    def test_uses_verified_official_server_and_shared_runtime_lock(self) -> None:
        self.assertIn("prepare-mojang-server.py", self.source)
        self.assertIn("server-attestation.json", (ROOT / "scripts/prepare-mojang-server.py").read_text("utf-8"))
        self.assertIn(".dedicated-server-gate.lock", self.source)
        self.assertIn('"jammarrPresent": False', (ROOT / "scripts/prepare-mojang-server.py").read_text("utf-8"))

    def test_client_gui_is_private_and_audio_isolated(self) -> None:
        self.assertIn("env -u WAYLAND_DISPLAY", self.source)
        self.assertIn("xvfb-run -a", self.source)
        self.assertIn("ALSOFT_DRIVERS=null", self.source)
        self.assertIn("jammarr.acceptance.unmoddedServerProbe=true", self.source)

    def test_requires_authoritative_join_ui_duration_and_cleanup(self) -> None:
        self.assertIn('$username joined the game', self.source)
        self.assertIn("Acceptance Jammarr unsupported-server screen remained open", self.source)
        self.assertIn('sleep "$connected_seconds"', self.source)
        self.assertIn("modded-client-to-unmodded-server gate passed", self.source)
        self.assertIn('ss -ltnH "sport = :$port"', self.source)


if __name__ == "__main__":
    unittest.main()
