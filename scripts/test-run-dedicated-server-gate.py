#!/usr/bin/env python3

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
GATE = ROOT / "scripts/run-dedicated-server-gate.sh"


class DedicatedServerGateSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = GATE.read_text("utf-8")

    def test_audio_gate_uses_a_private_graph(self) -> None:
        source = self.source
        self.assertIn("start_private_audio_graph()", source)
        self.assertIn("/tmp/jammarr-dedicated-gate-audio.XXXXXX", source)
        self.assertIn('PIPEWIRE_RUNTIME_DIR="$active_audio_runtime_dir" pipewire', source)
        self.assertIn("wireplumber --profile=policy", source)
        self.assertIn("pipewire-pulse", source)
        self.assertIn(
            'export PULSE_SERVER="unix:$active_audio_runtime_dir/pulse/native"', source
        )

    def test_audio_gate_records_both_clients_on_one_clock(self) -> None:
        source = self.source
        self.assertIn("activate_shared_audio_sinks()", source)
        self.assertIn("module-remap-sink", source)
        self.assertIn("master_channel_map=front-left,front-right", source)
        self.assertIn("master_channel_map=rear-left,rear-right", source)
        initial = source[source.index('raw_combined="$output_root/$label.audio-shared-clock.s16le"') :]
        initial = initial[: initial.index("audio_capture_is_audible")]
        self.assertEqual(initial.count("parec --raw"), 1)
        self.assertIn('--device="${sink_master}.monitor"', initial)
        self.assertIn("--channels=4", initial)
        self.assertIn("pan=stereo|c0=c0|c1=c1[leader]", initial)
        self.assertIn("pan=stereo|c0=c2|c1=c3[follower]", initial)

    def test_mixed_churn_analyzes_and_classifies_both_clients(self) -> None:
        source = self.source
        churn = source[source.index("run_mixed_vanilla_audio()") :]
        churn = churn[: churn.index("run_two_client_audio()")]
        self.assertIn('"$sink_master" "$raw_leader" "$raw_follower"', churn)
        self.assertIn('"$raw_leader" --reference "$raw_follower"', churn)
        self.assertIn("classify-shared-clock-audio.py", churn)
        self.assertIn("--leader-feed-report", churn)
        self.assertIn("--follower-feed-report", churn)
        self.assertIn("30 * 44100 * 2 * 2", churn)
        self.assertNotIn('"$sink_leader" "$raw"', churn)

    def test_cleanup_preserves_audio_graph_dependency_order(self) -> None:
        cleanup = self.source[
            self.source.index("cleanup_audio_processes()") :
            self.source.index("start_private_audio_graph()")
        ]
        self.assertLess(
            cleanup.index('for pid in "${active_audio_keepalive_pids[@]}"'),
            cleanup.index('for module in "${active_audio_modules[@]}"'),
        )
        self.assertLess(
            cleanup.index('for module in "${active_audio_modules[@]}"'),
            cleanup.index('for ((index = ${#active_private_audio_pids[@]}'),
        )

    def test_invalid_config_waits_for_detached_minecraft_group(self) -> None:
        source = self.source
        probe = source[source.index("run_invalid_config_check_once()") :]
        probe = probe[: probe.index("run_invalid_config_check()")]
        self.assertIn('server_pid=$(ss -ltnp "sport = :$port"', probe)
        self.assertIn("active_server_group=$server_group", probe)
        self.assertIn('wait_for_group_exit "$server_group" 60', probe)
        self.assertLess(
            probe.index('wait_for_group_exit "$server_group" 60'),
            probe.rindex("restore_server_config"),
        )

    def test_vanilla_gate_requires_size_verified_fallback_attestation(self) -> None:
        source = self.source
        self.assertIn('--fallback-cache-root "$vanilla_cache_root"', source)
        self.assertIn('runtime.get("allArtifactSha1AndSizeVerified") is not True', source)
        self.assertIn('runtime.get("sharedCacheMutated") is not False', source)
        self.assertIn('source_counts = runtime.get("artifactSourceCounts")', source)


if __name__ == "__main__":
    unittest.main()
