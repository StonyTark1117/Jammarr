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

    def test_runtime_lock_supports_exclusive_and_workspace_scopes(self) -> None:
        source = self.source
        self.assertIn("JAMMARR_GATE_LOCK_SCOPE", source)
        self.assertIn("JAMMARR_GATE_LOCK_KEY", source)
        self.assertIn("flock -n -s 9", source)
        self.assertIn('.dedicated-server-gate.$gate_lock_key.lock', source)

    def test_modern_openal_defaults_to_the_qualified_pulse_route(self) -> None:
        source = self.source
        audio_client = source[source.index("start_audio_client()") :]
        audio_client = audio_client[: audio_client.index("wait_for_audio_playing()")]
        self.assertIn("local pcm_type=${JAMMARR_ALSA_PCM_TYPE:-pulse}", audio_client)
        self.assertIn("pcm.!default {", audio_client)
        self.assertIn("'  type pulse'", audio_client)
        self.assertIn('ALSOFT_DRIVERS="$alsoft_drivers"', audio_client)

    def test_sustained_audio_rejects_a_backend_break_immediately(self) -> None:
        source = self.source
        self.assertIn("audio_log_has_terminal_backend_failure()", source)
        self.assertIn("available update failed: Broken pipe", source)
        churn = source[source.index("run_mixed_vanilla_audio()") :]
        churn = churn[: churn.index("run_two_client_audio()")]
        self.assertIn("audio_log_has_terminal_backend_failure", churn)
        self.assertIn("private_audio_graph_has_buffer_starvation()", source)
        self.assertIn("out of buffers", source)
        self.assertIn("private_audio_graph_has_buffer_starvation", churn)

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

    def test_shared_master_monitor_stays_drained_between_captures(self) -> None:
        source = self.source
        activate = source[source.index("activate_shared_audio_sinks()") :]
        activate = activate[: activate.index("stop_listening_port()")]
        self.assertIn('--device="${sink_master}.monitor"', activate)
        self.assertIn('> /dev/null 2>&1 &', activate)
        self.assertGreaterEqual(
            activate.count('active_audio_keepalive_pids+=("$!")'), 2
        )

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

    def test_mixed_vanilla_client_cannot_perturb_measured_audio_graph(self) -> None:
        source = self.source
        vanilla = source[source.index("run_vanilla_client()") :]
        vanilla = vanilla[: vanilla.index("run_client_companion()")]
        self.assertIn("vanilla_audio_env=(ALSOFT_DRIVERS=null)", vanilla)
        self.assertIn('"${vanilla_audio_env[@]}"', vanilla)
        self.assertLess(
            vanilla.index("vanilla_audio_env=(ALSOFT_DRIVERS=null)"),
            vanilla.index('"${vanilla_audio_env[@]}"'),
        )

    def test_standalone_vanilla_gate_requires_chat_and_reconnect(self) -> None:
        source = self.source
        vanilla = source[source.index("run_vanilla_client()") :]
        vanilla = vanilla[: vanilla.index("run_client_companion()")]
        self.assertIn('--chat-trigger-file "$chat_trigger"', vanilla)
        self.assertIn('--shutdown-trigger-file "$shutdown_trigger"', vanilla)
        self.assertIn(': > "$shutdown_trigger"', vanilla)
        self.assertIn("VANILLA_SHUTDOWN_FAILED", vanilla)
        self.assertIn("exact vanilla client did not send player-originated chat", vanilla)
        self.assertIn('"${scenario}-reconnect"', vanilla)
        self.assertIn("completed a second clean lifecycle", vanilla)
        self.assertIn('join_start_line=$(( $(wc -l < "$server_console") + 1 ))', vanilla)
        self.assertIn('"$join_start_line"; do', vanilla)
        churn = source[source.index("run_mixed_vanilla_audio()") :]
        churn = churn[: churn.index("run_two_client_audio()")]
        self.assertIn('"$sink_master" "$raw_leader" "$raw_follower" false', churn)


if __name__ == "__main__":
    unittest.main()
