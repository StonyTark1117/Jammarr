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
        self.assertIn(
            "for command in dbus-daemon pipewire wireplumber pipewire-pulse pactl pacat parec; do",
            source,
        )
        self.assertIn("dbus-daemon --session --fork --print-address=1 --print-pid=1", source)
        self.assertIn("wireplumber_args=(-p policy)", source)
        self.assertIn('wireplumber "${wireplumber_args[@]}"', source)
        self.assertIn('export DBUS_SESSION_BUS_ADDRESS="$private_dbus_address"', source)
        self.assertIn("pipewire-pulse", source)
        self.assertIn('sink_properties=device.description="$sink_leader"', source)

    def test_all_graphical_client_gates_prepare_private_audio_first(self) -> None:
        source = self.source
        target = source[source.index("run_target()") :]
        prepare = target.index('prepare_private_client_audio "$label"')
        protocol = target.index('run_wrong_protocol_client "$label"')
        command = target.index('run_command_client "$label"')
        audio = target.index('run_two_client_audio "$label"')
        self.assertLess(prepare, protocol)
        self.assertLess(prepare, command)
        self.assertLess(prepare, audio)
        self.assertIn('ALSA_CONFIG_PATH="$client_dir/alsa.conf"', source)
        self.assertIn('PULSE_SINK="$active_audio_default_sink"', source)

    def test_clients_share_one_private_x_server(self) -> None:
        source = self.source
        self.assertIn("start_private_client_display()", source)
        self.assertIn('Xvfb ":$display_number"', source)
        self.assertIn('DISPLAY="$active_client_display"', source)
        self.assertNotIn("xvfb-run -a", source)

    def test_loom_preflight_retains_execution_classpath_validation(self) -> None:
        init_script = (ROOT / "gradle/verify-loom-dev-launcher.init.gradle").read_text("utf-8")
        self.assertIn("net.fabricmc.loom.task.RunGameTask", init_script)
        self.assertIn("task.classpath(launcherConfiguration)", init_script)
        self.assertIn("RunGameTask is missing dev-launch-injector at execution", init_script)

    def test_command_gate_rejects_post_marker_client_crashes(self) -> None:
        source = self.source
        command = source[source.index("run_command_client_once()") : source.index("start_audio_client()")]
        self.assertIn("client_runtime_failed", command)
        self.assertIn("command probe did not remain healthy after command-tree evidence", command)
        self.assertIn('sink_properties=device.description="$sink_follower"', source)
        self.assertIn(
            'export PULSE_SERVER="unix:$active_audio_runtime_dir/pulse/native"', source
        )

    def test_runtime_lock_supports_exclusive_and_workspace_scopes(self) -> None:
        source = self.source
        self.assertIn("JAMMARR_GATE_LOCK_SCOPE", source)
        self.assertIn("JAMMARR_GATE_LOCK_KEY", source)
        self.assertIn("flock -n -s 9", source)
        self.assertIn('.dedicated-server-gate.$gate_lock_key.lock', source)

    def test_forge_development_client_bootstrap_is_bounded_and_serialized(self) -> None:
        source = self.source
        optional = source[source.index("run_optional_client()") :]
        optional = optional[: optional.index("run_vanilla_client()")]
        self.assertIn("forge_client_bootstrap_lock=", source)
        self.assertIn('[[ "$label" == *-forge || "$label" == *-neoforge ]]', optional)
        self.assertIn('exec 7>"$forge_client_bootstrap_lock"', optional)
        self.assertIn("flock 7", optional)
        self.assertIn("-XX:ActiveProcessorCount=4", optional)
        self.assertIn("flock -u 7", optional)
        self.assertIn('./gradlew "${runtime_args[@]}"', optional)
        self.assertLess(
            optional.index("flock 7"),
            optional.index('./gradlew "${runtime_args[@]}"'),
        )
        self.assertLess(
            optional.index("terminate_client_launch"),
            optional.index("flock -u 7"),
        )

    def test_complete_forge_gates_serialize_the_shared_mavenizer_cache(self) -> None:
        source = self.source
        dispatch = source[source.index('for target in "${targets[@]}"') :]
        self.assertIn("forgegradle_gate_lock=", source)
        self.assertIn('if [[ "$label" == *-forge ]]', dispatch)
        self.assertIn('exec 6>"$forgegradle_gate_lock"', dispatch)
        self.assertIn("flock 6", dispatch)
        self.assertIn("flock -u 6", dispatch)
        self.assertLess(
            dispatch.index("flock 6"),
            dispatch.index('run_target "$label" "$relative_dir" "$java_home" "$port"'),
        )
        self.assertLess(
            dispatch.index('run_target "$label" "$relative_dir" "$java_home" "$port"'),
            dispatch.index("flock -u 6"),
        )

    def test_openal_backend_is_selectable_for_short_diagnostics(self) -> None:
        source = self.source
        audio_client = source[source.index("start_audio_client()") :]
        audio_client = audio_client[: audio_client.index("wait_for_audio_playing()")]
        self.assertIn("local pcm_type=${JAMMARR_ALSA_PCM_TYPE:-pulse}", audio_client)
        self.assertIn("openal_driver=${JAMMARR_OPENAL_DRIVER:-auto}", source)
        self.assertIn('case "$openal_driver" in', audio_client)
        self.assertIn("alsoft_drivers=pipewire", audio_client)
        self.assertIn("sound_device=$sink", audio_client)
        self.assertIn("'soundDevice:", audio_client)
        self.assertIn("pcm.!default {", audio_client)
        self.assertIn("'  type pulse'", audio_client)
        self.assertIn('ALSOFT_DRIVERS="$alsoft_drivers"', audio_client)
        self.assertIn('ALSOFT_LOGLEVEL="$openal_loglevel"', audio_client)
        launch = source[source.index("launch_audio_client()") :]
        launch = launch[: launch.index("audio_capture_is_audible()")]
        self.assertIn('[[ "$openal_driver" == pipewire ]]', launch)
        self.assertIn('"OpenAL initialized on device $sink"', launch)

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

    def test_mixed_churn_retains_timing_evidence_for_unhealthy_playback(self) -> None:
        source = self.source
        churn = source[source.index("run_mixed_vanilla_audio()") :]
        churn = churn[: churn.index("run_two_client_audio()")]
        unhealthy = churn.index("playback_unhealthy=1")
        analyzer = churn.index("analyze-audio-timing.py")
        deferred_failure = churn.index("if (( playback_unhealthy != 0 )); then")
        self.assertLess(unhealthy, analyzer)
        self.assertLess(analyzer, deferred_failure)
        self.assertIn("backend interruption from transport or chunk-order corruption", churn)

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

    def test_invalid_config_allows_the_loader_started_callback_to_reject(self) -> None:
        source = self.source
        probe = source[source.index("run_invalid_config_check_once()") :]
        probe = probe[: probe.index("run_invalid_config_check()")]
        self.assertIn("ready_marker_deadline=0", probe)
        self.assertIn("ready_marker_deadline=$((SECONDS + 5))", probe)
        self.assertIn("SECONDS >= ready_marker_deadline", probe)
        self.assertIn(
            "invalid Jammarr configuration remained live after the server-started callback",
            probe,
        )
        self.assertLess(
            probe.index("Invalid Jammarr configuration value for plexUrl"),
            probe.index("ready_marker_deadline=$((SECONDS + 5))"),
        )

    def test_runtime_uses_and_restores_the_manifest_port(self) -> None:
        source = self.source
        target = source[source.index("run_target()") :]
        target = target[: target.index("matched=0")]
        self.assertIn("port=$default_port", target)
        self.assertIn(
            'set_property "$run_dir/server.properties" server-port "$port"', target
        )
        self.assertNotIn(
            "port=$(sed -n 's/^server-port=", target
        )
        self.assertLess(
            target.index('backup_server_properties "$run_dir/server.properties"'),
            target.index(
                'set_property "$run_dir/server.properties" server-port "$port"'
            ),
        )
        self.assertLess(
            target.index(
                'set_property "$run_dir/server.properties" server-port "$port"'
            ),
            target.rindex("restore_server_properties"),
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
        self.assertIn('wait_for_group_start "$pid" 10', vanilla)
        self.assertIn(
            "exact vanilla client did not establish its private process group",
            vanilla,
        )
        self.assertLess(
            vanilla.index('wait_for_group_start "$pid" 10'),
            vanilla.index("deadline=$((SECONDS + 600))"),
        )
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
