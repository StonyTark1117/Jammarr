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
        self.assertIn("read -r label minecraft_version relative_dir", self.source)
        self.assertNotIn('minecraft_version=${label%-*}', self.source)

    def test_matrix_resource_lock_preserves_global_exclusion(self) -> None:
        self.assertIn("JAMMARR_GATE_LOCK_SCOPE", self.source)
        self.assertIn("JAMMARR_GATE_LOCK_KEY", self.source)
        self.assertIn("flock -n -s 9", self.source)
        self.assertIn('.dedicated-server-gate.$gate_lock_key.lock', self.source)

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
        self.assertIn("gate.functional.evidence.txt", self.source)
        self.assertIn("gate.cleanup.evidence.txt", self.source)

    def test_shutdown_ignores_and_reaps_an_exited_group_leader(self) -> None:
        self.assertIn("ps -o stat= -g", self.source)
        self.assertIn("grep -Eqv '^[[:space:]]*Z'", self.source)
        self.assertIn('wait "$pid" 2>/dev/null || true', self.source)
        self.assertIn('if ! wait "$server_pid"; then', self.source)

    def test_disposable_server_bounds_generation_and_processor_pressure(self) -> None:
        self.assertIn("JAMMARR_UNMODDED_ACTIVE_PROCESSORS", self.source)
        self.assertIn('-XX:ActiveProcessorCount="$active_processors"', self.source)
        self.assertIn("printf 'level-type=flat", self.source)
        self.assertIn("printf 'generate-structures=false", self.source)
        self.assertIn("printf 'spawn-animals=false", self.source)

    def test_disposable_server_cleanup_is_bounded_and_classified(self) -> None:
        self.assertIn("JAMMARR_UNMODDED_GRACEFUL_STOP_SECONDS", self.source)
        self.assertIn("forced-after-timeout", self.source)
        self.assertIn("forcing fixture cleanup", self.source)
        self.assertIn('Official server shutdown mode: %s.', self.source)


if __name__ == "__main__":
    unittest.main()
