#!/usr/bin/env python3

from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
GATE = ROOT / "scripts/run-dedicated-server-gate.sh"


class DedicatedServerGateSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = GATE.read_text("utf-8")

    def test_download_retry_recognizes_rate_limits_without_retrying_bad_artifacts(self) -> None:
        function = re.search(
            r"^runtime_download_failed_transiently\(\) \{\n.*?^\}\n",
            self.source, re.MULTILINE | re.DOTALL,
        ).group()
        cases = (
            ("Could not HEAD 'https://repo.maven.apache.org/maven2/de/sciss/jump3r/1.0.5/jump3r-1.0.5.pom'. Received status code 429 from server: Too Many Requests", True),
            ("Received status code 503 from server: Service Unavailable", True),
            ("Received status code 404 from server: Not Found", False),
            ("Invalid Jammarr configuration value for plexUrl", False),
            ("Compilation failed; see the compiler error output for details.", False),
        )
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "console.log"
            for message, transient in cases:
                with self.subTest(message=message):
                    log.write_text(message)
                    result = subprocess.run(
                        ["bash", "-c", function + '\nruntime_download_failed_transiently "$1"', "test", str(log)],
                        check=False,
                    )
                    self.assertEqual(result.returncode, 0 if transient else 1)

    def test_capture_warning_does_not_replace_client_health_or_pcm_validation(self) -> None:
        capture = self.source[self.source.index("run_two_client_audio()") :]
        health_check = re.search(r'    if ! ss -ltnH .*?\n    fi', capture, re.DOTALL).group()
        backend_check = re.search(
            r"^audio_log_has_terminal_backend_failure\(\) \{\n.*?^\}\n",
            self.source, re.MULTILINE | re.DOTALL,
        ).group()
        warning_check = re.search(
            r"^(?:private_audio_graph_has_buffer_starvation|report_private_audio_graph_warnings)\(\) \{\n.*?^\}\n",
            self.source, re.MULTILINE | re.DOTALL,
        ).group()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "replay.private-pipewire-pulse.log").write_text(
                "spa.audioconvert: (0 suppressed) out of buffers on port 0 2\n"
            )
            (root / "replay.audio-follower.console.log").write_text("Acceptance audio state: PLAYING\n")
            for state, expected in (("PLAYING", 0), ("ERROR", 1)):
                with self.subTest(state=state):
                    (root / "replay.audio-leader.console.log").write_text(f"Acceptance audio state: {state}\n")
                    result = subprocess.run([
                        "bash", "-c", backend_check + warning_check + '''
output_root=$1; label=replay; port=1; leader_pid=1; follower_pid=2; recorder_pid=$BASHPID; result=0
# The recorder and clients are live in this replay. Only their log evidence varies.
ss() { echo listening; }
group_alive() { return 0; }
''' + health_check + '\nexit "$result"', "test", directory,
                    ], capture_output=True, text=True)
                    self.assertNotIn("command not found", result.stderr)
                    self.assertEqual(result.returncode, expected, result.stderr)

    def test_audio_controls_wait_for_console_promotion_acknowledgement(self) -> None:
        scenario = self.source[self.source.index("run_audio_control_scenarios()") :]
        promotion = scenario[scenario.index('  : > "$scenario_evidence"') : scenario.index('  # A transient first leader')]
        wait = re.search(r'^wait_for_marker_after\(\) \{\n.*?^\}\n', self.source, re.MULTILINE | re.DOTALL).group()
        # Keep the real polling logic, with a short deadline for a missing acknowledgement.
        wait = wait.replace('wait_for_marker_after()', 'poll_for_marker()')
        wait += 'wait_for_marker_after() { poll_for_marker "$1" "$2" "$3" 2; }\n'
        for client_logs_chat, separate_mod_log in (("true", "false"), ("false", "false"), ("true", "true")):
            with self.subTest(client_logs_chat=client_logs_chat, separate_mod_log=separate_mod_log), tempfile.TemporaryDirectory() as directory:
                result = subprocess.run(["bash", "-c", wait + '''
set -eu
label=replay; output_root=$1; leader_log=$1/leader.log; scenario_evidence=$1/evidence.txt; fifo_fd=9
server_console=$output_root/$label.console.log; server_log=$server_console
# Forge's FML log excludes vanilla command messages that appear in its console.
if [[ "$3" == true ]]; then server_log=$1/mod.log; fi
# A marker from a previous launch must not satisfy the new promotion.
echo JAMMARR_ACCEPTANCE_AUDIO_OPERATOR_READY > "$leader_log"
echo JAMMARR_ACCEPTANCE_AUDIO_OPERATOR_READY > "$server_log"
echo JAMMARR_ACCEPTANCE_AUDIO_OPERATOR_READY > "$server_console"
mkfifo "$1/console"
exec 9<>"$1/console"
(
  while IFS= read -r line; do
    case "$line" in
      'op JammarrAudioA') sleep .25; touch "$1/operator" ;;
      'tell JammarrAudioA '*)
        echo "$line" >> "$server_console"
        if [[ "$2" == true ]]; then echo "$line" >> "$leader_log"; fi
        break ;;
    esac
  done < "$1/console"
) & server=$!
trap 'kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true' EXIT
uses_console_control() { return 0; }
promote() {
''' + promotion + '''
}
promote
# This is the permission check that rejects the first client control packet.
test -f "$1/operator" || { echo 'Control arrived before operator promotion' >&2; exit 42; }
''', "test", directory, client_logs_chat, separate_mod_log], capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode, 0, result.stderr)

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
        self.assertNotIn("start_shared_audio_monitor()", source)
        self.assertNotIn("stop_shared_audio_monitor()", source)

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
        self.assertIn("JAMMARR_HEADLESS_OPENAL_DRIVER", source)
        self.assertIn("headless_client_openal_driver", source)
        self.assertIn("JAMMARR_NON_AUDIO_OPENAL_DRIVER", source)
        self.assertIn("non_audio_client_openal_driver", source)
        self.assertIn("uses_legacy_audio_profile", source)

        # Minimum-loader coverage can start at the optional-client branch,
        # without reaching protocol/command/audio dispatch.  Each graphical
        # entry point must therefore bootstrap the shared environment itself.
        boundaries = {
            "run_optional_client()": "run_vanilla_client()",
            "run_vanilla_client()": "run_client_companion()",
            "run_client_companion()": "run_delayed_hello_client()",
            "run_delayed_hello_client()": "run_acceptance_client()",
        }
        for start, end in boundaries.items():
            function = source[source.index(start) : source.index(end)]
            self.assertIn("prepare_graphical_client_environment", function)
            self.assertIn('PULSE_SERVER="unix:$active_audio_runtime_dir/pulse/native"', function)

    def test_clients_share_one_private_x_server(self) -> None:
        source = self.source
        self.assertIn("start_private_client_display()", source)
        self.assertIn('Xvfb ":$display_number"', source)
        self.assertIn('DISPLAY="$active_client_display"', source)
        self.assertNotIn("xvfb-run -a", source)

    def test_loom_preflight_retains_execution_classpath_validation(self) -> None:
        init_script = (ROOT / "gradle/verify-loom-dev-launcher.init.gradle").read_text("utf-8")
        self.assertIn("net.fabricmc.loom.task.RunGameTask", init_script)
        self.assertIn("isLoomRunGameTask(task)", init_script)
        self.assertIn("type = type.superclass", init_script)
        self.assertIn("task.useXvfb.set(false)", init_script)
        self.assertIn("task.hasProperty('useXvfb')", init_script)
        self.assertIn("RunGameTask must not start a nested CI Xvfb launcher", init_script)
        self.assertIn("getInternalClasspath", init_script)
        self.assertIn("internalClasspath.from(launcherConfiguration)", init_script)
        self.assertIn("RunGameTask internalClasspath is missing dev-launch-injector at execution", init_script)

    def test_loom_runtime_launches_do_not_reuse_execution_classpaths(self) -> None:
        self.assertGreaterEqual(
            self.source.count('uses_loom_client_launcher "$label" && cache_args+=(--no-configuration-cache)'),
            5,
        )

    def test_legacy_fabric_acceptance_uses_the_ready_client_connector(self) -> None:
        for loader in ("fabric", "forge"):
            build = (ROOT / f"platforms/mc1.16.5/{loader}/build.gradle").read_text("utf-8")
            self.assertIn("property 'jammarr.acceptance.server'", build)
            self.assertIn("LegacyClient connects through its initialized-client tick.", build)
            self.assertNotIn("'--server'", build)
            self.assertNotIn("'--port'", build)

    def test_legacy_1165_acceptance_waits_for_the_title_screen(self) -> None:
        fabric = (ROOT / "platforms/mc1.16.5/fabric/src/main/java/stonytark/jammarr/client/LegacyClient.java").read_text("utf-8")
        forge = (ROOT / "platforms/mc1.16.5/forge/src/main/java/stonytark/jammarr/client/LegacyClient.java").read_text("utf-8")
        self.assertIn("minecraft.screen instanceof TitleScreen", fabric)
        self.assertIn("minecraft.screen instanceof MainMenuScreen", forge)
        self.assertIn("minecraft.getModelManager().getMissingModel() == null", fabric)
        self.assertIn("minecraft.getModelManager().getMissingModel() == null", forge)

    def test_modern_acceptance_clients_wait_for_model_readiness(self) -> None:
        for version in ("mc1.18.2", "mc1.19.2"):
            for loader in ("fabric", "forge"):
                source = (ROOT / f"platforms/{version}/{loader}/src/main/java/stonytark/jammarr/client/JammarrClient.java").read_text("utf-8")
                self.assertIn("minecraft.screen instanceof TitleScreen", source)
                self.assertIn("minecraft.getModelManager().getMissingModel() == null", source)

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

    def test_non_audio_clients_default_to_the_null_openal_backend(self) -> None:
        source = self.source
        helper = source[
            source.index("non_audio_client_openal_driver()") : source.index("activate_shared_audio_sinks()")
        ]
        self.assertIn('JAMMARR_NON_AUDIO_OPENAL_DRIVER:-null', helper)
        self.assertIn('pulse|pipewire|alsa|null', helper)

        # These probes validate client startup, protocol, permissions, and UI;
        # real playback remains exclusively in the measured audio launcher.
        for start, end in (
            ("run_optional_client()", "run_vanilla_client()"),
            ("run_delayed_hello_client()", "run_acceptance_client()"),
            ("run_acceptance_client_once()", "run_command_client()"),
        ):
            function = source[source.index(start) : source.index(end)]
            self.assertIn("non_audio_client_openal_driver", function)

        command_client = source[source.index("run_command_client_once()") :]
        self.assertIn("non_audio_client_openal_driver", command_client)

        audio_client = source[source.index("\nlaunch_audio_client()") :]
        self.assertNotIn("non_audio_client_openal_driver", audio_client)

    def test_audio_capture_uses_a_single_bounded_recorder(self) -> None:
        source = self.source
        audio = source[source.index("run_two_client_audio()") :]
        capture = audio[audio.index(": > \"$raw_combined\"") : audio.index("active_audio_recorder_pids=()")]
        self.assertIn("sole consumer of the master monitor", capture)
        self.assertIn("parec --raw", capture)
        self.assertNotIn("start_shared_audio_monitor", audio)

    def test_station_audio_capture_retries_without_lowering_audibility_threshold(self) -> None:
        source = self.source
        self.assertIn("capture_audible_transition()", source)
        helper = source[source.index("capture_audible_transition()") : source.index("audio_control_sequence=0")]
        self.assertIn("for attempt in 1 2", helper)
        self.assertIn('audio_capture_is_audible "$raw" "$metrics"', helper)
        sonic = source[source.index("send_audio_control \"$label\" leader 'adventure:42:49'") : source.index("send_audio_control \"$label\" leader 'fault:underrun'")]
        self.assertIn('capture_audible_transition "$sink_leader" "$raw" "$metrics" 4', sonic)

    def test_sustained_audio_rejects_a_backend_break_immediately(self) -> None:
        source = self.source
        self.assertIn("audio_log_has_terminal_backend_failure()", source)
        self.assertIn("available update failed: Broken pipe", source)
        churn = source[source.index("run_mixed_vanilla_audio()") :]
        churn = churn[: churn.index("run_two_client_audio()")]
        self.assertIn("audio_log_has_terminal_backend_failure", churn)
        self.assertIn("report_private_audio_graph_warnings()", source)
        self.assertIn("out of buffers", source)
        self.assertIn("report_private_audio_graph_warnings", churn)
        self.assertNotIn("private_audio_graph_has_buffer_starvation", source)
        self.assertIn('! group_alive "$leader_pid" || ! group_alive "$follower_pid"', churn)

    def test_protocol_rejection_requires_an_authoritative_server_reason(self) -> None:
        source = self.source
        rejection = source[source.index("rejection_observed()") : source.index("run_wrong_protocol_client()")]
        self.assertIn('grep -Fq "$rejection" "$server_console"', rejection)
        self.assertIn("Connection reset by peer", source)

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

    def test_shared_master_has_no_permanent_monitor_consumer(self) -> None:
        source = self.source
        activate = source[source.index("activate_shared_audio_sinks()") :]
        activate = activate[: activate.index("stop_listening_port()")]
        self.assertNotIn("start_shared_audio_monitor", activate)
        self.assertIn('for sink in "$sink_leader" "$sink_follower"; do', activate)
        self.assertIn('active_audio_keepalive_pids+=("$!")', activate)

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
        start = self.source.index("cleanup_audio_processes()")
        cleanup = self.source[start : self.source.index("shutdown_private_client_environment()", start)]
        self.assertLess(
            cleanup.index('for pid in "${active_audio_keepalive_pids[@]}"'),
            cleanup.index('for module in "${active_audio_modules[@]}"'),
        )
        self.assertNotIn("active_private_audio_pids", cleanup)
        self.assertIn("active_audio_base_modules", self.source)
        self.assertIn("shutdown_private_client_environment", self.source)

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
