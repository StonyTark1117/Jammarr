#!/usr/bin/env bash
set -uo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output_root=${JAMMARR_GATE_OUTPUT_ROOT:-"$repo_root/build/dedicated-server-gate"}
mkdir -p "$output_root"
output_root=$(cd "$output_root" && pwd)
mkdir -p "$repo_root/build"
gate_lock="$repo_root/build/.dedicated-server-gate.lock"
forge_client_bootstrap_lock="$repo_root/build/.dedicated-server-gate.forge-client-bootstrap.lock"
forgegradle_gate_lock="$repo_root/build/.dedicated-server-gate.forgegradle.lock"
exec 9>"$gate_lock"
gate_lock_scope=${JAMMARR_GATE_LOCK_SCOPE:-exclusive}
case "$gate_lock_scope" in
  exclusive)
    if ! flock -n 9; then
      echo "Another Jammarr runtime gate is already using the shared Minecraft workspace" >&2
      exit 2
    fi
    ;;
  resource)
    gate_lock_key=${JAMMARR_GATE_LOCK_KEY:-}
    if [[ ! "$gate_lock_key" =~ ^[a-zA-Z0-9._-]+$ ]]; then
      echo "JAMMARR_GATE_LOCK_KEY must be a non-empty filesystem-safe resource key" >&2
      exit 2
    fi
    if ! flock -n -s 9; then
      echo "An exclusive Jammarr runtime gate is already active" >&2
      exit 2
    fi
    exec 8>"$repo_root/build/.dedicated-server-gate.$gate_lock_key.lock"
    if ! flock -n 8; then
      echo "Another Jammarr runtime gate is already using resource $gate_lock_key" >&2
      exit 2
    fi
    ;;
  *)
    echo "JAMMARR_GATE_LOCK_SCOPE must be exclusive or resource" >&2
    exit 2
    ;;
esac
fake_plex_token="jammarr-dedicated-gate-token"
fake_plex_port_file="$output_root/fake-plex.port"
fake_plex_request_log="$output_root/fake-plex.requests.tsv"
fake_plex_audio="$output_root/fake-plex-tone.mp3"
fake_plex_reference="$output_root/fake-plex-timing-reference.s16le"
fake_plex_state="$output_root/fake-plex.state"
fake_audio_duration_seconds=${JAMMARR_GATE_AUDIO_DURATION_SECONDS:-600}
client_log_limit_blocks=${JAMMARR_GATE_CLIENT_LOG_LIMIT_BLOCKS:-131072}
fake_plex_pid=""
active_client_pid=""
active_server_pid=""
active_server_group=""
active_game_port=""
active_rcon_port=""
active_audio_client_pids=()
active_audio_recorder_pids=()
active_audio_modules=()
active_audio_base_modules=()
active_audio_keepalive_pids=()
active_private_audio_pids=()
active_audio_runtime_dir=""
active_audio_default_sink=""
active_client_display=""
active_client_display_pid=""
active_audio_environment_saved=0
active_audio_original_xdg_runtime_dir=""
active_audio_original_xdg_runtime_dir_set=0
active_audio_original_pipewire_runtime_dir=""
active_audio_original_pipewire_runtime_dir_set=0
active_audio_original_pulse_server=""
active_audio_original_pulse_server_set=0
active_audio_original_dbus_session_bus_address=""
active_audio_original_dbus_session_bus_address_set=0
active_proxy_pid=""
active_config=""
active_config_backup=""
active_config_existed=0
active_properties=""
active_properties_backup=""
active_cache_dir=""
active_cache_backup_root=""
active_cache_existed=0
active_world_dir=""
active_world_backup_root=""
active_world_existed=0

java21_home=${JAMMARR_JAVA21_HOME:-/usr/lib/jvm/java-21-openjdk}
java26_home=${JAMMARR_JAVA26_HOME:-/usr/lib/jvm/java-26-openjdk}
java8_home=${JAMMARR_JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk}
java17_home=${JAMMARR_JAVA17_HOME:-/usr/lib/jvm/java-17-openjdk}
delayed_hello_gate=${JAMMARR_DELAYED_HELLO_GATE:-false}
delayed_hello_ms=${JAMMARR_DELAYED_HELLO_MS:-12000}
hello_timeout_ms=${JAMMARR_GATE_HELLO_TIMEOUT_MS:-5000}

if [[ ! "$client_log_limit_blocks" =~ ^[0-9]+$ ]] || (( client_log_limit_blocks < 16384 )); then
  echo "JAMMARR_GATE_CLIENT_LOG_LIMIT_BLOCKS must be an integer of at least 16384" >&2
  exit 2
fi
if [[ ! "$hello_timeout_ms" =~ ^[0-9]+$ ]] || (( hello_timeout_ms < 1 || hello_timeout_ms > 60000 )); then
  echo "JAMMARR_GATE_HELLO_TIMEOUT_MS must be an integer from 1 through 60000" >&2
  exit 2
fi
if [[ ! "$delayed_hello_ms" =~ ^[0-9]+$ ]] || (( delayed_hello_ms < 1 || delayed_hello_ms >= 60000 )); then
  echo "JAMMARR_DELAYED_HELLO_MS must be an integer from 1 through 59999" >&2
  exit 2
fi
if [[ "$delayed_hello_gate" == "true" ]] && (( delayed_hello_ms >= hello_timeout_ms )); then
  echo "JAMMARR_GATE_HELLO_TIMEOUT_MS must exceed JAMMARR_DELAYED_HELLO_MS" >&2
  exit 2
fi

declare -A target_control=()
declare -A target_command_markers=()
declare -A target_audio_profile=()
declare -A target_log_profile=()
declare -A target_disable_configuration_cache=()
declare -A target_client_task=()
declare -A target_server_task=()
declare -A target_stress_profile=()
declare -A target_optional_client_profile=()
declare -A target_companion_label=()
declare -A target_companion_path=()
declare -A target_companion_java=()
declare -A target_companion_runtime_java=()
declare -A target_companion_task=()
targets=()
while IFS='|' read -r label target_dir build_java port control command_markers audio_profile log_profile disable_configuration_cache client_task server_task stress_profile optional_client_profile; do
  case "$build_java" in
    8) java_home=$java8_home ;;
    17) java_home=$java17_home ;;
    21) java_home=$java21_home ;;
    26) java_home=$java26_home ;;
    *)
      echo "No runtime-gate JDK is configured for Java $build_java ($label)" >&2
      exit 2
      ;;
  esac
  targets+=("$label|$target_dir|$java_home|$port")
  target_control["$label"]=$control
  target_command_markers["$label"]=$command_markers
  target_audio_profile["$label"]=$audio_profile
  target_log_profile["$label"]=$log_profile
  target_disable_configuration_cache["$label"]=$disable_configuration_cache
  target_client_task["$label"]=$client_task
  target_server_task["$label"]=$server_task
  target_stress_profile["$label"]=$stress_profile
  target_optional_client_profile["$label"]=$optional_client_profile
done < <(python3 "$repo_root/scripts/target-matrix.py" gate-lines "$repo_root/gradle/targets.json")
if (( ${#targets[@]} == 0 )); then
  echo "Target manifest generated no dedicated-server runtimes" >&2
  exit 2
fi
while IFS='|' read -r companion_label paired_runtime companion_path companion_java companion_runtime_java companion_task; do
  if [[ -n ${target_companion_label[$paired_runtime]:-} ]]; then
    echo "Multiple client companions target $paired_runtime; the gate requires an unambiguous pair" >&2
    exit 2
  fi
  target_companion_label["$paired_runtime"]=$companion_label
  target_companion_path["$paired_runtime"]=$companion_path
  target_companion_java["$paired_runtime"]=$companion_java
  target_companion_runtime_java["$paired_runtime"]=$companion_runtime_java
  target_companion_task["$paired_runtime"]=$companion_task
done < <(python3 "$repo_root/scripts/target-matrix.py" companion-lines "$repo_root/gradle/targets.json")

uses_console_control() {
  [[ ${target_control[$1]} == "console" ]]
}

uses_legacy_command_markers() {
  [[ ${target_command_markers[$1]} == "legacy-response" ]]
}

requires_hover_help_probe() {
  [[ $1 == "1.7.10-forge" || $1 == "1.18.2-fabric" || $1 == "1.20.1-fabric" ]]
}

requires_legacy_search_edit_probe() {
  [[ $1 == "1.7.10-forge" ]]
}

requires_config_screen_probe() {
  [[ $1 != "1.16.5-fabric" && $1 != "1.16.5-quilt" && $1 != "1.16.5-forge" ]]
}

uses_legacy_audio_profile() {
  [[ ${target_audio_profile[$1]} == "legacy-openal" ]]
}

uses_legacy_fml_log() {
  [[ ${target_log_profile[$1]} == legacy-fml* ]]
}

uses_legacy_fml16_log() {
  [[ ${target_log_profile[$1]} == "legacy-fml16" ]]
}

uses_legacy_fabric16_log() {
  [[ ${target_log_profile[$1]} == "legacy-fabric16" ]]
}

uses_legacy_ornithe16_log() {
  [[ ${target_log_profile[$1]} == "legacy-ornithe16" ]]
}

uses_legacy_babric_log() {
  [[ ${target_log_profile[$1]} == "legacy-babric" ]]
}

mod_log_path() {
  local label=$1
  local run_dir=$2
  if uses_legacy_fml16_log "$label"; then
    printf '%s/ForgeModLoader-server-0.log\n' "$run_dir"
  elif uses_legacy_fml_log "$label"; then
    printf '%s/logs/fml-server-latest.log\n' "$run_dir"
  else
    printf '%s/logs/latest.log\n' "$run_dir"
  fi
}

disables_configuration_cache() {
  [[ ${target_disable_configuration_cache[$1]} == "true" ]]
}

uses_loom_client_launcher() {
  [[ $1 == *-fabric || $1 == *-quilt || $1 == *-ornithe || $1 == b1.7.3-babric ]]
}

prepare_loom_client_launcher() {
  local label=$1 target_dir=$2 java_home=$3
  local -a cache_args=() runtime_args=()
  uses_loom_client_launcher "$label" || return 0
  # The init script changes Loom's execution-only launcher classpath.  Do not
  # reuse a graph cached by its preceding verifier invocation.
  cache_args+=(--no-configuration-cache)
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] \
    && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")
  (
    cd "$target_dir" || exit 1
    JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      ./gradlew --init-script "$repo_root/gradle/verify-loom-dev-launcher.init.gradle" \
      verifyLoomDevLauncher --no-daemon --max-workers=2 --console=plain \
      "${cache_args[@]}" "${runtime_args[@]}"
  )
}

requested=${1:-all}
protocol_client_gate=${JAMMARR_PROTOCOL_CLIENT_GATE:-false}
command_client_gate=${JAMMARR_COMMAND_CLIENT_GATE:-false}
audio_client_gate=${JAMMARR_AUDIO_CLIENT_GATE:-false}
audio_scenario_gate=${JAMMARR_AUDIO_SCENARIO_GATE:-false}
client_companion_gate=${JAMMARR_CLIENT_COMPANION_GATE:-false}
vanilla_client_gate=${JAMMARR_VANILLA_CLIENT_GATE:-false}
vanilla_connected_seconds=${JAMMARR_VANILLA_CONNECTED_SECONDS:-10}
vanilla_churn_cycles=${JAMMARR_VANILLA_CHURN_CYCLES:-1}
vanilla_churn_min_seconds=${JAMMARR_VANILLA_CHURN_MIN_SECONDS:-0}
legacy_persistence_gate=${JAMMARR_LEGACY_PERSISTENCE_GATE:-false}
legacy_cold_start_count=${JAMMARR_LEGACY_COLD_STARTS:-0}
legacy_browse_stress_gate=${JAMMARR_LEGACY_BROWSE_STRESS_GATE:-false}
network_profile=${JAMMARR_NETWORK_PROFILE:-direct}
fabric_loader_version=${JAMMARR_FABRIC_LOADER_VERSION:-}
quilt_modmenu_gate=${JAMMARR_QUILT_MODMENU_GATE:-false}
openal_driver=${JAMMARR_OPENAL_DRIVER:-auto}
openal_loglevel=${JAMMARR_OPENAL_LOGLEVEL:-0}
prism_shared_root=${JAMMARR_PRISM_SHARED_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/PrismLauncher}
vanilla_cache_root=${JAMMARR_VANILLA_CACHE_ROOT:-$repo_root/build/vanilla-client-cache}

if [[ "$vanilla_client_gate" == "true" ]]; then
  for prism_cache in assets libraries meta java; do
    if [[ ! -e "$prism_shared_root/$prism_cache" ]]; then
      echo "JAMMARR_PRISM_SHARED_ROOT is missing $prism_cache: $prism_shared_root" >&2
      exit 2
    fi
  done
fi
if [[ ! "$vanilla_connected_seconds" =~ ^[0-9]+$ ]] \
    || (( vanilla_connected_seconds < 10 || vanilla_connected_seconds > 300 )); then
  echo "JAMMARR_VANILLA_CONNECTED_SECONDS must be an integer from 10 through 300" >&2
  exit 2
fi
if [[ ! "$vanilla_churn_cycles" =~ ^[0-9]+$ ]] \
    || (( vanilla_churn_cycles < 1 || vanilla_churn_cycles > 10000 )); then
  echo "JAMMARR_VANILLA_CHURN_CYCLES must be an integer from 1 through 10000" >&2
  exit 2
fi
if [[ ! "$vanilla_churn_min_seconds" =~ ^[0-9]+$ ]] \
    || (( vanilla_churn_min_seconds > 21600 )); then
  echo "JAMMARR_VANILLA_CHURN_MIN_SECONDS must be an integer from 0 through 21600" >&2
  exit 2
fi
if (( vanilla_churn_min_seconds > 0 || vanilla_churn_cycles > 1 )) \
    && [[ "$vanilla_client_gate" != "true" || "$audio_client_gate" != "true" ]]; then
  echo "Vanilla churn requires both JAMMARR_VANILLA_CLIENT_GATE=true and JAMMARR_AUDIO_CLIENT_GATE=true" >&2
  exit 2
fi

if [[ ! "$legacy_cold_start_count" =~ ^[0-9]+$ ]] || (( legacy_cold_start_count > 100 )); then
  echo "JAMMARR_LEGACY_COLD_STARTS must be an integer from 0 through 100" >&2
  exit 2
fi
if (( legacy_cold_start_count > 0 )) && [[ "$audio_client_gate" != "true" ]]; then
  echo "JAMMARR_LEGACY_COLD_STARTS requires JAMMARR_AUDIO_CLIENT_GATE=true" >&2
  exit 2
fi
case "$openal_driver" in
  auto|alsa|pulse|pipewire) ;;
  *) echo "JAMMARR_OPENAL_DRIVER must be auto, alsa, pulse, or pipewire" >&2; exit 2 ;;
esac
if [[ ! "$openal_loglevel" =~ ^[0-3]$ ]]; then
  echo "JAMMARR_OPENAL_LOGLEVEL must be an integer from 0 through 3" >&2
  exit 2
fi
if [[ "$legacy_browse_stress_gate" == "true" && "$audio_client_gate" != "true" ]]; then
  echo "JAMMARR_LEGACY_BROWSE_STRESS_GATE=true requires JAMMARR_AUDIO_CLIENT_GATE=true" >&2
  exit 2
fi

restore_server_config() {
  if [[ -z "$active_config" ]]; then return; fi
  if (( active_config_existed )); then
    cp -- "$active_config_backup" "$active_config"
  else
    rm -f -- "$active_config"
  fi
  rm -f -- "$active_config_backup"
  active_config=""
  active_config_backup=""
  active_config_existed=0
}

restore_server_properties() {
  if [[ -z "$active_properties" ]]; then return; fi
  cp -- "$active_properties_backup" "$active_properties"
  rm -f -- "$active_properties_backup"
  active_properties=""
  active_properties_backup=""
}

restore_audio_cache() {
  if [[ -z "$active_cache_dir" ]]; then return; fi
  if [[ -d "$active_cache_dir" ]]; then
    mv -- "$active_cache_dir" "$active_cache_backup_root/generated-cache"
  fi
  if (( active_cache_existed )); then
    mv -- "$active_cache_backup_root/original-cache" "$active_cache_dir"
  fi
  active_cache_dir=""
  active_cache_backup_root=""
  active_cache_existed=0
}

restore_gate_world() {
  if [[ -z "$active_world_dir" ]]; then return; fi
  if [[ -d "$active_world_dir" ]]; then
    mv -- "$active_world_dir" "$active_world_backup_root/generated-world"
  fi
  if (( active_world_existed )); then
    mv -- "$active_world_backup_root/original-world" "$active_world_dir"
  fi
  active_world_dir=""
  active_world_backup_root=""
  active_world_existed=0
}

isolate_gate_world() {
  local run_dir=$1
  local label=$2
  local level_name=$3
  active_world_dir="$run_dir/$level_name"
  active_world_backup_root=$(mktemp -d "$output_root/$label.world.XXXXXX")
  active_world_existed=0
  if [[ -d "$active_world_dir" ]]; then
    mv -- "$active_world_dir" "$active_world_backup_root/original-world"
    active_world_existed=1
  fi
}

isolate_audio_cache() {
  local run_dir=$1
  local label=$2
  active_cache_dir="$run_dir/jammarr-cache"
  active_cache_backup_root=$(mktemp -d "$output_root/$label.audio-cache.XXXXXX")
  active_cache_existed=0
  if [[ -d "$active_cache_dir" ]]; then
    mv -- "$active_cache_dir" "$active_cache_backup_root/original-cache"
    active_cache_existed=1
  fi
}

cleanup_all() {
  cleanup_audio_processes
  shutdown_private_client_environment
  if [[ -n "$active_client_pid" ]]; then
    terminate_client_launch "$active_client_pid" 10 || true
    active_client_pid=""
  fi
  if [[ -n "$active_server_pid" ]]; then
    stop_process_tree "$active_server_pid" TERM
    wait_for_process_tree_exit "$active_server_pid" 10 || stop_process_tree "$active_server_pid" KILL
    active_server_pid=""
  fi
  if [[ -n "$active_server_group" ]]; then
    stop_group "$active_server_group" TERM
    wait_for_group_exit "$active_server_group" 10 || stop_group "$active_server_group" KILL
    active_server_group=""
  fi
  stop_listening_port "$active_game_port"
  stop_listening_port "$active_rcon_port"
  active_game_port=""
  active_rcon_port=""
  restore_server_config
  restore_server_properties
  restore_audio_cache
  restore_gate_world
  if [[ -n "$active_proxy_pid" ]]; then
    kill -TERM "$active_proxy_pid" 2>/dev/null || true
    wait "$active_proxy_pid" 2>/dev/null || true
    active_proxy_pid=""
  fi
  if [[ -n "$fake_plex_pid" ]]; then
    kill "$fake_plex_pid" 2>/dev/null || true
    wait "$fake_plex_pid" 2>/dev/null || true
  fi
  rm -f -- "$fake_plex_port_file"
  rm -f -- "$fake_plex_state"
}

cleanup_audio_processes() {
  local pid module
  for pid in "${active_audio_client_pids[@]}"; do
    terminate_client_launch "$pid" 10 || true
  done
  for pid in "${active_audio_recorder_pids[@]}"; do
    kill -TERM "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  done
  for pid in "${active_audio_keepalive_pids[@]}"; do
    kill -TERM "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  done
  for module in "${active_audio_modules[@]}"; do
    pactl unload-module "$module" > /dev/null 2>&1 || true
  done
  active_audio_client_pids=()
  active_audio_recorder_pids=()
  active_audio_keepalive_pids=()
  active_audio_modules=()
}

shutdown_private_client_environment() {
  local pid module index
  # Per-scenario cleanup deliberately leaves this base graph and its X server
  # alive. Optional, delayed-hello, vanilla, companion, and command clients
  # must all use the same verified private environment as the audio clients.
  for module in "${active_audio_base_modules[@]}"; do
    pactl unload-module "$module" > /dev/null 2>&1 || true
  done
  for ((index = ${#active_private_audio_pids[@]} - 1; index >= 0; index--)); do
    pid=${active_private_audio_pids[$index]}
    kill -TERM "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  done
  if [[ -n "$active_audio_runtime_dir" && -d "$active_audio_runtime_dir" ]]; then
    rm -r -- "$active_audio_runtime_dir"
  fi
  if (( active_audio_environment_saved )); then
    if (( active_audio_original_xdg_runtime_dir_set )); then
      export XDG_RUNTIME_DIR="$active_audio_original_xdg_runtime_dir"
    else
      unset XDG_RUNTIME_DIR
    fi
    if (( active_audio_original_pipewire_runtime_dir_set )); then
      export PIPEWIRE_RUNTIME_DIR="$active_audio_original_pipewire_runtime_dir"
    else
      unset PIPEWIRE_RUNTIME_DIR
    fi
    if (( active_audio_original_pulse_server_set )); then
      export PULSE_SERVER="$active_audio_original_pulse_server"
    else
      unset PULSE_SERVER
    fi
    if (( active_audio_original_dbus_session_bus_address_set )); then
      export DBUS_SESSION_BUS_ADDRESS="$active_audio_original_dbus_session_bus_address"
    else
      unset DBUS_SESSION_BUS_ADDRESS
    fi
  fi
  active_audio_base_modules=()
  active_private_audio_pids=()
  active_audio_runtime_dir=""
  active_audio_default_sink=""
  if [[ "$active_client_display_pid" =~ ^[0-9]+$ ]]; then
    kill "$active_client_display_pid" 2>/dev/null || true
    wait "$active_client_display_pid" 2>/dev/null || true
  fi
  active_client_display=""
  active_client_display_pid=""
  active_audio_environment_saved=0
}

start_private_client_display() {
  local display_number pid deadline
  [[ -n "$active_client_display" ]] && return 0
  command -v Xvfb > /dev/null || { echo "private client display requires Xvfb" >&2; return 1; }
  for display_number in $(seq $((90 + BASHPID % 100)) $((189 + BASHPID % 100))); do
    [[ -S "/tmp/.X11-unix/X$display_number" ]] && continue
    Xvfb ":$display_number" -screen 0 1280x720x24 +extension GLX +render -noreset \
      > "$output_root/private-xvfb.log" 2>&1 &
    pid=$!
    deadline=$((SECONDS + 10))
    while [[ ! -S "/tmp/.X11-unix/X$display_number" ]]; do
      if ! kill -0 "$pid" 2>/dev/null || (( SECONDS >= deadline )); then
        wait "$pid" 2>/dev/null || true
        break
      fi
      sleep .1
    done
    if kill -0 "$pid" 2>/dev/null && [[ -S "/tmp/.X11-unix/X$display_number" ]]; then
      active_client_display=":$display_number"
      active_client_display_pid=$pid
      return 0
    fi
  done
  echo "private client Xvfb did not become ready" >&2
  return 1
}

start_private_audio_graph() {
  local label=$1 command pid deadline private_dbus_address private_dbus_pid
  local -a private_dbus_output=()
  local -a wireplumber_args=()
  # The hardware-free WirePlumber policy profile needs D-Bus session services.
  # Supply a fresh bus so neither it nor any acceptance client can reach the
  # user's desktop session or reserve a physical audio device.
  for command in dbus-daemon pipewire wireplumber pipewire-pulse pactl pacat parec; do
    if ! command -v "$command" > /dev/null; then
      echo "$label: private audio acceptance requires $command" >&2
      return 1
    fi
  done

  active_audio_environment_saved=1
  active_audio_original_xdg_runtime_dir_set=0
  active_audio_original_pipewire_runtime_dir_set=0
  active_audio_original_pulse_server_set=0
  active_audio_original_dbus_session_bus_address_set=0
  [[ ${XDG_RUNTIME_DIR+x} ]] && active_audio_original_xdg_runtime_dir_set=1
  active_audio_original_xdg_runtime_dir=${XDG_RUNTIME_DIR-}
  [[ ${PIPEWIRE_RUNTIME_DIR+x} ]] && active_audio_original_pipewire_runtime_dir_set=1
  active_audio_original_pipewire_runtime_dir=${PIPEWIRE_RUNTIME_DIR-}
  [[ ${PULSE_SERVER+x} ]] && active_audio_original_pulse_server_set=1
  active_audio_original_pulse_server=${PULSE_SERVER-}
  [[ ${DBUS_SESSION_BUS_ADDRESS+x} ]] && active_audio_original_dbus_session_bus_address_set=1
  active_audio_original_dbus_session_bus_address=${DBUS_SESSION_BUS_ADDRESS-}

  active_audio_runtime_dir=$(mktemp -d /tmp/jammarr-dedicated-gate-audio.XXXXXX)
  chmod 700 "$active_audio_runtime_dir"
  mapfile -t private_dbus_output < <(dbus-daemon --session --fork --print-address=1 --print-pid=1)
  private_dbus_address=${private_dbus_output[0]:-}
  private_dbus_pid=${private_dbus_output[1]:-}
  if [[ -z "$private_dbus_address" || ! "$private_dbus_pid" =~ ^[0-9]+$ ]] \
      || ! kill -0 "$private_dbus_pid" 2>/dev/null; then
    echo "$label: private D-Bus session did not become ready" >&2
    return 1
  fi
  active_private_audio_pids+=("$private_dbus_pid")
  export DBUS_SESSION_BUS_ADDRESS="$private_dbus_address"
  env DBUS_SESSION_BUS_ADDRESS="$private_dbus_address" \
    XDG_RUNTIME_DIR="$active_audio_runtime_dir" \
    PIPEWIRE_RUNTIME_DIR="$active_audio_runtime_dir" pipewire \
    > "$output_root/$label.private-pipewire.log" 2>&1 &
  pid=$!
  active_private_audio_pids+=("$pid")
  deadline=$((SECONDS + 10))
  while [[ ! -S "$active_audio_runtime_dir/pipewire-0" ]]; do
    if ! kill -0 "$pid" 2>/dev/null || (( SECONDS >= deadline )); then
      echo "$label: private PipeWire core did not become ready" >&2
      return 1
    fi
    sleep .1
  done

  # WirePlumber 0.5 supports profiles; Ubuntu's 0.4 package does not. Both
  # modes are confined to this fresh D-Bus and PipeWire runtime directory.
  {
    printf 'wireplumber-version: '; wireplumber --version 2>&1 || true
    printf 'wireplumber-profile: '
  } > "$output_root/$label.private-audio-runtime.txt"
  if wireplumber --help 2>&1 | grep -q -- '-p'; then
    wireplumber_args=(-p policy)
    printf '%s\n' 'policy' >> "$output_root/$label.private-audio-runtime.txt"
  else
    printf '%s\n' 'default' >> "$output_root/$label.private-audio-runtime.txt"
  fi
  env DBUS_SESSION_BUS_ADDRESS="$private_dbus_address" \
    XDG_RUNTIME_DIR="$active_audio_runtime_dir" \
    PIPEWIRE_RUNTIME_DIR="$active_audio_runtime_dir" wireplumber "${wireplumber_args[@]}" \
    > "$output_root/$label.private-wireplumber.log" 2>&1 &
  pid=$!
  active_private_audio_pids+=("$pid")
  sleep 1
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "$label: private WirePlumber policy manager exited during startup" >&2
    return 1
  fi

  env DBUS_SESSION_BUS_ADDRESS="$private_dbus_address" \
    XDG_RUNTIME_DIR="$active_audio_runtime_dir" \
    PIPEWIRE_RUNTIME_DIR="$active_audio_runtime_dir" pipewire-pulse \
    > "$output_root/$label.private-pipewire-pulse.log" 2>&1 &
  pid=$!
  active_private_audio_pids+=("$pid")
  deadline=$((SECONDS + 10))
  while [[ ! -S "$active_audio_runtime_dir/pulse/native" ]]; do
    if ! kill -0 "$pid" 2>/dev/null || (( SECONDS >= deadline )); then
      echo "$label: private PipeWire-Pulse server did not become ready" >&2
      return 1
    fi
    sleep .1
  done

  export XDG_RUNTIME_DIR="$active_audio_runtime_dir"
  export PIPEWIRE_RUNTIME_DIR="$active_audio_runtime_dir"
  export PULSE_SERVER="unix:$active_audio_runtime_dir/pulse/native"
  pactl info > /dev/null
}

prepare_private_client_audio() {
  local label=$1 module deadline
  if [[ -n "$active_audio_default_sink" ]]; then return 0; fi
  start_private_client_display || return 1
  start_private_audio_graph "$label" || return 1
  active_audio_default_sink="jammarr_${BASHPID}_${label//[^a-zA-Z0-9_]/_}_control"
  module=$(pactl load-module module-null-sink sink_name="$active_audio_default_sink" \
    sink_properties=device.description="$active_audio_default_sink" rate=48000 channels=2) || return 1
  active_audio_base_modules+=("$module")
  deadline=$((SECONDS + 10))
  while ! pactl list short sinks | awk -v sink="$active_audio_default_sink" '$2 == sink { found = 1 } END { exit !found }'; do
    if (( SECONDS >= deadline )); then
      echo "$label: private default client sink did not become ready" >&2
      return 1
    fi
    sleep .1
  done
}

write_private_client_audio_config() {
  local client_dir=$1 sink=${2:-$active_audio_default_sink}
  if [[ -z "$sink" ]]; then
    echo "private client audio was not prepared" >&2
    return 1
  fi
  printf '%s\n' \
    '[general]' \
    'frequency = 48000' \
    'period_size = 1024' \
    'periods = 8' > "$client_dir/alsoft.conf"
  printf '%s\n' \
    'pcm.!default {' \
    '  type pulse' \
    "  device \"$sink\"" \
    '}' \
    'ctl.!default {' \
    '  type pulse' \
    '}' > "$client_dir/alsa.conf"
}

# Every graphical launch, including clients that deliberately use OpenAL's
# null backend, must enter the same private X/PipeWire environment.  Some
# narrow gates (notably the minimum-Fabric-loader probe) begin with an
# optional client instead of a Jammarr audio client; lazily preparing the
# graph only in the latter left those clients with an unset DISPLAY.
prepare_graphical_client_environment() {
  local label=$1 client_dir=$2
  prepare_private_client_audio "$label" || return 1
  write_private_client_loader_config "$label" "$client_dir" || return 1
  write_private_client_audio_config "$client_dir"
}

write_private_client_loader_config() {
  local label=$1 client_dir=$2
  if [[ "$label" == 1.20.3-forge ]]; then
    # Forge 49.0.2's asynchronous splash renderer can read FMLConfig while
    # NightConfig reloads it, throwing in DisplayWindow.initRender before
    # Minecraft creates its window. Use the loader's supported splash opt-out
    # in this disposable game directory; Minecraft still renders every UI
    # probe and both audio clients still use the measured private sinks.
    mkdir -p "$client_dir/config"
    printf '%s\n' 'earlyWindowControl = false' > "$client_dir/config/fml.toml"
  fi
}

preserve_client_attempt() {
  local console_log=$1 attempt=$2
  if [[ -f "$console_log" ]]; then
    cp -- "$console_log" "${console_log%.log}.attempt-$attempt.log"
  fi
}

headless_client_openal_driver() {
  local label=${1:-} driver=${JAMMARR_HEADLESS_OPENAL_DRIVER:-}
  # LWJGL 2's bundled OpenAL used by the legacy targets remains reliable
  # through PipeWire-Pulse. Modern OpenAL Soft can address the isolated
  # PipeWire graph directly, avoiding the Pulse backend failure seen during
  # 1.18.2 command acceptance.
  if [[ -z "$driver" ]]; then
    if uses_legacy_audio_profile "$label"; then driver=pulse; else driver=pipewire; fi
  fi
  case "$driver" in
    pulse|pipewire|alsa|null) printf '%s\n' "$driver" ;;
    *)
      echo "Unsupported JAMMARR_HEADLESS_OPENAL_DRIVER: $driver" >&2
      return 1
      ;;
  esac
}

# Protocol, delayed-hello, command-tree, and no-mod-client probes exercise
# rendering, networking, and UI permission behavior—not audible playback.
# Giving them a physical PipeWire/Pulse driver turns an unavailable runner
# audio device into a false client-runtime failure. Audio acceptance clients
# use launch_audio_client() instead, where their selected private sinks are
# required and measured. Keep an override for targeted backend diagnosis.
non_audio_client_openal_driver() {
  local driver=${JAMMARR_NON_AUDIO_OPENAL_DRIVER:-null}
  case "$driver" in
    pulse|pipewire|alsa|null) printf '%s\n' "$driver" ;;
    *)
      echo "Unsupported JAMMARR_NON_AUDIO_OPENAL_DRIVER: $driver" >&2
      return 1
      ;;
  esac
}

activate_shared_audio_sinks() {
  local label=$1 sink_master=$2 sink_leader=$3 sink_follower=$4
  local module sink pid deadline running_sinks keepalives_alive
  module=$(pactl load-module module-null-sink sink_name="$sink_master" \
    sink_properties=device.description="$sink_master" rate=48000 channels=4 \
    channel_map=front-left,front-right,rear-left,rear-right) || return 1
  active_audio_modules+=("$module")
  module=$(pactl load-module module-remap-sink sink_name="$sink_leader" master="$sink_master" \
    sink_properties=device.description="$sink_leader" \
    channels=2 channel_map=front-left,front-right \
    master_channel_map=front-left,front-right remix=no) || return 1
  active_audio_modules=("$module" "${active_audio_modules[@]}")
  module=$(pactl load-module module-remap-sink sink_name="$sink_follower" master="$sink_master" \
    sink_properties=device.description="$sink_follower" \
    channels=2 channel_map=front-left,front-right \
    master_channel_map=rear-left,rear-right remix=no) || return 1
  active_audio_modules=("$module" "${active_audio_modules[@]}")

  for sink in "$sink_leader" "$sink_follower"; do
    pacat --raw --playback --device="$sink" --format=s16le --rate=48000 --channels=2 \
      --latency-msec=50 --client-name=jammarr-dedicated-gate \
      --stream-name="${sink}_keepalive" < /dev/zero > /dev/null 2>&1 &
    active_audio_keepalive_pids+=("$!")
  done
  # Do not attach a permanent monitor to the four-channel master.  On the
  # GitHub runner PipeWire can retain its source-port buffers after a monitor
  # handoff, then emit "out of buffers" and make the harness kill healthy
  # clients.  The null/remap sinks and their writers keep this graph active;
  # only the bounded evidence recorder consumes the monitor.
  deadline=$((SECONDS + 10))
  while true; do
    keepalives_alive=true
    for pid in "${active_audio_keepalive_pids[@]}"; do
      if ! kill -0 "$pid" 2>/dev/null; then keepalives_alive=false; fi
    done
    running_sinks=$(pactl list short sinks \
      | awk -v master="$sink_master" -v leader="$sink_leader" -v follower="$sink_follower" \
        '($2 == master || $2 == leader || $2 == follower) && $NF == "RUNNING" \
          { count++ } END { print count + 0 }')
    if [[ "$keepalives_alive" == true && "$running_sinks" == 3 ]]; then return 0; fi
    if [[ "$keepalives_alive" == false || SECONDS -ge deadline ]]; then
      echo "$label: private shared-clock sinks did not become active" >&2
      return 1
    fi
    sleep .2
  done
}

stop_listening_port() {
  local port=$1
  local pid deadline
  if [[ -z "$port" ]]; then return; fi
  pid=$(ss -ltnp "sport = :$port" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -n 1)
  if [[ -z "$pid" ]]; then return; fi
  kill -TERM "$pid" 2>/dev/null || true
  deadline=$((SECONDS + 10))
  while kill -0 "$pid" 2>/dev/null && (( SECONDS < deadline )); do sleep 1; done
  if kill -0 "$pid" 2>/dev/null; then kill -KILL "$pid" 2>/dev/null || true; fi
}

trap cleanup_all EXIT
trap 'exit 130' INT TERM

start_fake_plex() {
  rm -f -- "$fake_plex_port_file"
  local -a audio_args=()
  if [[ "$audio_client_gate" == "true" || "$client_companion_gate" == "true" ]]; then
    if ! command -v ffmpeg > /dev/null; then
      echo "Audio acceptance requires ffmpeg" >&2
      return 1
    fi
    if [[ "$audio_client_gate" == "true" ]] \
        && { ! command -v pactl > /dev/null || ! command -v parec > /dev/null; }; then
      echo "Two-client audio acceptance requires pactl and parec" >&2
      return 1
    fi
    if [[ ! "$fake_audio_duration_seconds" =~ ^[0-9]+$ ]] \
        || (( fake_audio_duration_seconds < 300 )); then
      echo "JAMMARR_GATE_AUDIO_DURATION_SECONDS must be an integer of at least 300" >&2
      return 1
    fi
    python3 "$repo_root/scripts/generate-audio-fixture.py" \
      --duration "$fake_audio_duration_seconds" --sample-rate 44100 \
      | ffmpeg -hide_banner -loglevel error -y -f s16le -ar 44100 -ac 2 -i pipe:0 \
      -codec:a libmp3lame -b:a 160k -write_xing 0 "$fake_plex_audio" || return 1
    ffmpeg -hide_banner -loglevel error -y -t 12 -i "$fake_plex_audio" \
      -f s16le -ar 48000 -ac 2 "$fake_plex_reference" || return 1
    python3 "$repo_root/scripts/analyze-audio-timing.py" "$fake_plex_reference" \
      --minimum-duration-ms 10000 > "$output_root/fake-plex-timing-reference.json" || return 1
    audio_args+=(--audio-file "$fake_plex_audio" \
      --track-duration-ms "$((fake_audio_duration_seconds * 1000))")
  fi
  python3 "$repo_root/scripts/fake-plex-server.py" \
    --port-file "$fake_plex_port_file" --request-log "$fake_plex_request_log" \
    --token "$fake_plex_token" --state-file "$fake_plex_state" "${audio_args[@]}" &
  fake_plex_pid=$!
  local deadline=$((SECONDS + 10))
  while [[ ! -s "$fake_plex_port_file" ]]; do
    if ! kill -0 "$fake_plex_pid" 2>/dev/null; then
      echo "Fake Plex service exited before publishing its port" >&2
      return 1
    fi
    if (( SECONDS >= deadline )); then
      echo "Fake Plex service did not become ready" >&2
      return 1
    fi
    sleep 0.1
  done
}

fake_plex_requests_complete() {
  local first_line=$1
  awk -F '\t' -v first="$first_line" -v token="$fake_plex_token" '
    NR > first && $3 == token && $2 == "/library/sections" { sections = 1 }
    NR > first && $3 == token && $2 == "/" { identity = 1 }
    NR > first && $3 == token && $2 == "/library/sections/1/all" { analysis = 1 }
    END { exit !(sections && identity && analysis) }
  ' "$fake_plex_request_log"
}

client_bootstrap_failed() {
  local console_log=$1
  grep -Eq 'Timed out trying to setup the Game Window|Failed to initialize the mod loading system and display|ArrayIndexOutOfBoundsException: 0|Invalid paths argument, contained no existing paths|mismatched mod (channel )?list|shaderInstance.* is null|Failed to load texture: minecraft:textures/atlas/blocks\.png' \
    "$console_log" 2>/dev/null
}

client_runtime_failed() {
  local console_log=$1
  grep -Eq 'Could not find or load main class net\.fabricmc\.devlaunchinjector\.Main|RunGameTask is missing dev-launch-injector at execution|Failed to open OpenAL device|Error starting SoundSystem\. Turning off sounds|shaderInstance.* is null|Failed to load texture: minecraft:textures/atlas/blocks\.png|Unreported exception thrown|#@!@# Game crashed!|Description: Unexpected error' \
    "$console_log" 2>/dev/null
}

client_rejection_logged() {
  local console_log=$1
  local rejection=$2
  local allow_generic=$3
  if grep -Fq "Client disconnected with reason: $rejection" "$console_log" 2>/dev/null; then
    return 0
  fi
  [[ "$allow_generic" == true ]] \
    && grep -Eq 'Client disconnected with reason: (Disconnected|Internal Exception: java\.io\.IOException: Error while read\(\.\.\.\): Connection reset by peer)' "$console_log" 2>/dev/null
}

exact_client_rejection_logged() {
  local console_log=$1
  local rejection=$2
  grep -Fq "Client disconnected with reason: $rejection" "$console_log" 2>/dev/null
}

rejection_observed() {
  local server_console=$1
  local client_console=$2
  local rejection=$3
  local allow_generic=$4
  # The exact client reason is sent by the server and is authoritative even
  # when a loader only records a generic disconnect in its server console.
  exact_client_rejection_logged "$client_console" "$rejection" && return 0
  grep -Fq "$rejection" "$server_console" 2>/dev/null || return 1
  client_rejection_logged "$client_console" "$rejection" "$allow_generic" && return 0
  # Some legacy and transitional loaders replace a server kick reason with a
  # generic disconnect/reset.  The server's exact rejection remains required;
  # the bounded generic client outcome proves that this is the same connection,
  # rather than treating any transport loss as a successful protocol gate.
  grep -Fq "lost connection: $rejection" "$server_console" 2>/dev/null
}

run_wrong_protocol_client() {
  local label=$1
  local target_dir=$2
  local java_home=$3
  local port=$4
  local server_console=$5
  run_acceptance_client "$label" "$target_dir" "$java_home" "$port" "$server_console" \
    wrong-protocol-client JammarrMismatch \
    '-Djammarr.acceptance.enabled=true -Djammarr.acceptance.clientProtocol=4 -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true' \
    'Jammarr protocol mismatch: server requires' true
}

optional_client_joined() {
  local label=$1
  local server_log=$2
  local client_log=$3
  local username=$4
  local first_server_line=${5:-1}
  grep -Fq "$username joined the game" \
    < <(tail -n "+$first_server_line" "$server_log" 2>/dev/null) && return 0
  uses_legacy_babric_log "$label" \
    && grep -Eq "${username} \[/[^]]+\] logged in with entity id" \
      < <(tail -n "+$first_server_line" "$server_log" 2>/dev/null) \
    && return 0
  uses_legacy_fml_log "$label" \
    && grep -Fq 'Server side modded connection established' \
      < <(tail -n "+$first_server_line" "$server_log" 2>/dev/null) \
    && grep -Fq 'Client side modded connection established' "$client_log" 2>/dev/null
}

run_optional_client() {
  local label=$1
  local target_dir=$2
  local java_home=$3
  local port=$4
  local server_console=$5
  local server_debug_log=$6
  local scenario=optional-client username=JammarrVanilla
  local client_dir="$output_root/$label.$scenario"
  local client_console="$output_root/$label.$scenario.console.log"
  local evidence="$output_root/$label.$scenario.evidence.txt"
  local pid deadline result=0 received_mod_list="" serialize_forge_bootstrap=false
  local -a runtime_args=() cache_args=()
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-quilt && "$quilt_modmenu_gate" == true ]] && runtime_args+=(-PjammarrIncludeModMenu=true)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")
  uses_loom_client_launcher "$label" \
    && runtime_args+=(--init-script "$repo_root/gradle/verify-loom-dev-launcher.init.gradle")
  uses_loom_client_launcher "$label" && cache_args+=(--no-configuration-cache)
  disables_configuration_cache "$label" && cache_args+=(--no-configuration-cache)

  # Forge-family development clients load their client config and bake models
  # on separate worker pools. Forge 49.0.2 can let model baking observe its own
  # config before it is attached when several development clients saturate the
  # host together; production clients use defaults in that window, but the
  # development-only strict check turns every failed bake into a missing model
  # and eventually crashes chunk compilation. Keep independent servers and
  # exact vanilla clients parallel, while serializing only this loader-specific
  # development bootstrap and bounding the worker pool it exposes to the host.
  if [[ ${target_optional_client_profile[$label]} == "mod-suppressed" ]] \
      && [[ "$label" == *-forge || "$label" == *-neoforge ]]; then
    serialize_forge_bootstrap=true
    exec 7>"$forge_client_bootstrap_lock"
    if ! flock 7; then
      echo "$label: interrupted while waiting for the Forge client bootstrap lock" >&2
      exec 7>&-
      return 1
    fi
  fi

  mkdir -p "$client_dir"
  prepare_graphical_client_environment "$label" "$client_dir" || return 1
  : > "$client_console"
  printf '%s\n' 'onboardAccessibility:false' 'skipMultiplayerWarning:true' \
    'joinedFirstServer:true' 'narrator:0' > "$client_dir/options.txt"
  (
    cd "$target_dir" || exit 1
    ulimit -f "$client_log_limit_blocks"
    exec setsid env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 DISPLAY="$active_client_display" \
      JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Djammarr.acceptance.enabled=true -Djammarr.acceptance.suppressClientHello=true -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true' \
      ALSA_CONFIG_PATH="$client_dir/alsa.conf" ALSOFT_CONF="$client_dir/alsoft.conf" \
      ALSOFT_DRIVERS="$(non_audio_client_openal_driver)" \
      PULSE_SERVER="unix:$active_audio_runtime_dir/pulse/native" \
      PIPEWIRE_RUNTIME_DIR="$active_audio_runtime_dir" XDG_RUNTIME_DIR="$active_audio_runtime_dir" \
      PULSE_SINK="$active_audio_default_sink" \
      LIBGL_ALWAYS_SOFTWARE=1 \
      ./gradlew "${runtime_args[@]}" --no-daemon --max-workers=2 --console=plain "${cache_args[@]}" \
      "${target_client_task[$label]}" \
      -PjammarrAcceptanceUsername="$username" \
      -PjammarrAcceptanceServer="127.0.0.1:${port}" \
      -PjammarrAcceptanceGameDir="$client_dir" \
      > "$client_console" 2>&1
  ) &
  pid=$!
  active_client_pid=$pid
  if ! wait_for_client_launch "$pid" "$client_console"; then
    active_client_pid=""
    return 1
  fi

  deadline=$((SECONDS + 600))
  while ! optional_client_joined "$label" "$server_console" "$client_console" "$username"; do
    if client_bootstrap_failed "$client_console" || ! group_alive "$pid" || (( SECONDS >= deadline )); then
      echo "$label: client without a Jammarr hello did not join; see $client_console" >&2
      result=1
      break
    fi
    sleep 1
  done
  if (( result == 0 )); then
    sleep 10
    if ! group_alive "$pid" \
        || grep -Eiq 'Jammarr protocol (handshake )?timed out|required on the client' "$client_console"; then
      echo "$label: client without a Jammarr hello did not remain connected" >&2
      result=1
    elif grep -Fq 'received server hello' "$client_console"; then
      echo "$label: suppressed client unexpectedly negotiated Jammarr capability" >&2
      result=1
    elif [[ ${target_optional_client_profile[$label]} == "loader-no-jammarr-mod" ]] \
        && { [[ -e "$client_dir/config/jammarr-client.toml" ]] \
          || grep -Fq "$target_dir/build/classes/java/main" "$client_console" \
          || grep -Fq "$target_dir/build/resources/main" "$client_console" \
          || grep -Eiq 'Found valid mod file .*\{jammarr\}|Creating FMLModContainer instance for .*jammarr' \
            "$client_console" "$client_dir/logs/debug.log" 2>/dev/null; }; then
      echo "$label: no-Jammarr acceptance client still registered Jammarr" >&2
      result=1
    elif [[ ${target_optional_client_profile[$label]} == "loader-no-jammarr-mod" ]]; then
      received_mod_list=$(grep -F 'Received client connection with modlist [' "$server_debug_log" \
        2>/dev/null | tail -n 1 || true)
      if [[ -z "$received_mod_list" || ${received_mod_list,,} == *jammarr* ]]; then
        echo "$label: server did not prove a client mod list without Jammarr" >&2
        result=1
      else
        {
          grep -F "$username joined the game" "$server_console" | tail -n 1
          printf '%s\n' "$received_mod_list"
          printf '%s\n' 'Loader client launched with no registered Jammarr mod.'
          printf '%s\n' 'Client remained connected for 10 seconds without sending a Jammarr hello.'
        } > "$evidence"
      fi
    else
      {
        if [[ ${target_optional_client_profile[$label]} == "loader-only" ]]; then
          grep -F "$username joined the game" "$server_console" | tail -n 1
          printf '%s\n' 'Production loader client launched without the Jammarr artifact.'
        elif uses_legacy_fml_log "$label"; then
          grep -F 'Server side modded connection established' "$server_console" | tail -n 1
          grep -F 'Client side modded connection established' "$client_console" | tail -n 1
        elif uses_legacy_babric_log "$label"; then
          grep -E "${username} \[/[^]]+\] logged in with entity id" "$server_console" | tail -n 1
        else
          grep -F "$username joined the game" "$server_console" | tail -n 1
        fi
        printf '%s\n' 'Client remained connected for 10 seconds without sending a Jammarr hello.'
      } > "$evidence"
    fi
  fi
  terminate_client_launch "$pid" 20 || result=1
  active_client_pid=""
  if [[ "$serialize_forge_bootstrap" == true ]]; then
    flock -u 7
    exec 7>&-
  fi
  return "$result"
}

run_vanilla_client() {
  local label=$1
  local port=$2
  local server_console=$3
  local rcon_port=$4
  local rcon_password=$5
  local fifo_fd=$6
  local expected_capable=${7:-0}
  local expected_vanilla=${8:-1}
  local expected_listener_stats=${9:-$expected_capable}
  local scenario=${10:-vanilla-client}
  local username=${11:-PureVanilla}
  local connected_seconds=${12:-$vanilla_connected_seconds}
  local capture_sink=${13:-}
  local capture_path=${14:-}
  local capture_follower_path=${15:-}
  local interaction_gate=${16:-true}
  local minecraft_version=${label%-*}
  local prism_workspace="$output_root/$label.$scenario.prism"
  local instance_dir="$prism_workspace/instances/jammarr-vanilla-$minecraft_version"
  local client_console="$output_root/$label.$scenario.console.log"
  local attestation="$instance_dir/vanilla-attestation.json"
  local evidence="$output_root/$label.$scenario.evidence.txt"
  local diagnostics="$output_root/$label.$scenario.diagnostics.txt"
  local post_diagnostics="$output_root/$label.$scenario.post-disconnect-diagnostics.txt"
  local chat_evidence="$output_root/$label.$scenario.chat.evidence.txt"
  local chat_trigger="$output_root/$label.$scenario.chat.trigger"
  local shutdown_trigger="$output_root/$label.$scenario.shutdown.trigger"
  local chat_message="JammarrVanillaChat_$username"
  local expected_diagnostics="capableListeners=$expected_capable, vanillaListeners=$expected_vanilla, listenerStats=$expected_listener_stats"
  local expected_after_disconnect="capableListeners=$expected_capable, vanillaListeners=$((expected_vanilla - 1)), listenerStats=$expected_listener_stats"
  local pid deadline result=0 first_line join_start_line request_start request_end recorder_pid expected_bytes captured_bytes
  local combined_capture
  local reconnect_evidence="$output_root/$label.${scenario}-reconnect.evidence.txt"
  local -a vanilla_audio_env=(ALSOFT_DRIVERS=null) interaction_args=()

  # A failed or interrupted retry must not leave a prior success looking
  # current. Matrix runs additionally isolate every execution in an immutable
  # attempt directory, while direct gate reuse clears its scenario outputs.
  rm -f -- "$attestation" "$evidence" "$diagnostics" "$post_diagnostics" \
    "$chat_evidence" "$chat_trigger" "$shutdown_trigger" "$reconnect_evidence"

  # Artifact-free clients are not audio subjects. Route their otherwise normal
  # Minecraft sound engine through OpenAL Soft's null output so they cannot
  # touch the active desktop or the private graph measuring Jammarr clients.
  # A churn cycle previously broke a measured follower backend immediately
  # after its short-lived vanilla sound engine failed to open that graph.
  if [[ "$interaction_gate" == "true" ]]; then
    if ! command -v xdotool > /dev/null; then
      echo "$label: exact vanilla chat acceptance requires xdotool" >&2
      return 1
    fi
    if ! command -v xclip > /dev/null; then
      echo "$label: exact vanilla chat acceptance requires xclip" >&2
      return 1
    fi
    interaction_args+=(--chat-trigger-file "$chat_trigger" --chat-message "$chat_message")
  fi

  mkdir -p "$prism_workspace"
  prepare_graphical_client_environment "$label" "$instance_dir" || return 1
  : > "$client_console"
  : > "$diagnostics"
  request_start=$(wc -l < "$fake_plex_request_log")
  join_start_line=$(( $(wc -l < "$server_console") + 1 ))
  (
    cd "$repo_root" || exit 1
    ulimit -f "$client_log_limit_blocks"
    exec setsid env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 DISPLAY="$active_client_display" \
      "${vanilla_audio_env[@]}" \
      JAVA_TOOL_OPTIONS='-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true' \
      ALSA_CONFIG_PATH="$instance_dir/alsa.conf" ALSOFT_CONF="$instance_dir/alsoft.conf" \
      PULSE_SERVER="unix:$active_audio_runtime_dir/pulse/native" \
      PIPEWIRE_RUNTIME_DIR="$active_audio_runtime_dir" XDG_RUNTIME_DIR="$active_audio_runtime_dir" \
      PULSE_SINK="$active_audio_default_sink" \
      LIBGL_ALWAYS_SOFTWARE=1 \
      python3 "$repo_root/scripts/run-prism-vanilla-client.py" \
      --minecraft "$minecraft_version" \
      --server "127.0.0.1:$port" \
      --username "$username" \
      --workspace "$prism_workspace" \
      --shared-root "$prism_shared_root" \
      --fallback-cache-root "$vanilla_cache_root" \
      --shutdown-trigger-file "$shutdown_trigger" \
      "${interaction_args[@]}" \
      > "$client_console" 2>&1
  ) &
  pid=$!
  active_client_pid=$pid

  # The background subshell inherits the gate's process group until `setsid`
  # executes. Under parallel matrix load the join loop can otherwise inspect
  # the PID during that brief handoff, find no group whose PGID equals the
  # future session leader, and report an immediate false client exit while the
  # verifier is still preparing its exact libraries and natives.
  if ! wait_for_group_start "$pid" 10; then
    echo "$label: exact vanilla client did not establish its private process group" >&2
    result=1
  fi

  deadline=$((SECONDS + 600))
  while (( result == 0 )) \
      && ! optional_client_joined "$label" "$server_console" "$client_console" "$username" \
      "$join_start_line"; do
    if client_bootstrap_failed "$client_console" || ! group_alive "$pid" || (( SECONDS >= deadline )); then
      echo "$label: pure vanilla client did not join; see $client_console" >&2
      result=1
      break
    fi
    sleep 1
  done

  if (( result == 0 )) && [[ "$interaction_gate" == "true" ]]; then
    first_line=$(wc -l < "$server_console")
    # The server's join marker can precede the client's first fully rendered
    # world frame, especially for LWJGL 2 clients using llvmpipe. Give the
    # private-X window time to enter gameplay before the key press so a terrain
    # loading screen cannot consume the chat shortcut.
    sleep 8
    if ! group_alive "$pid"; then
      echo "$label: exact vanilla client exited before chat interaction" >&2
      result=1
    fi
  fi
  if (( result == 0 )) && [[ "$interaction_gate" == "true" ]]; then
    : > "$chat_trigger"
    deadline=$((SECONDS + 30))
    while ! grep -Fq "$chat_message" \
        < <(tail -n "+$((first_line + 1))" "$server_console"); do
      if grep -Fq 'VANILLA_CHAT_FAILED' "$client_console" \
          || ! group_alive "$pid" || (( SECONDS >= deadline )); then
        echo "$label: exact vanilla client did not send player-originated chat" >&2
        result=1
        break
      fi
      sleep .2
    done
    if (( result == 0 )); then
      tail -n "+$((first_line + 1))" "$server_console" \
        | grep -F "$chat_message" | tail -n 1 > "$chat_evidence"
    fi
  fi

  if (( result == 0 )); then
    if [[ -n "$capture_sink" && -n "$capture_path" ]]; then
      : > "$capture_path"
      if [[ -n "$capture_follower_path" ]]; then
        : > "$capture_follower_path"
        combined_capture="${capture_path%.s16le}.shared-clock.s16le"
        : > "$combined_capture"
        parec --raw --latency-msec=200 --device="${capture_sink}.monitor" \
          --format=s16le --rate=48000 --channels=4 \
          --channel-map=front-left,front-right,rear-left,rear-right \
          > "$combined_capture" &
        expected_bytes=$((connected_seconds * 48000 * 4 * 2))
      else
        parec --raw --latency-msec=50 --device="${capture_sink}.monitor" \
          --format=s16le --rate=48000 --channels=2 > "$capture_path" &
        expected_bytes=$((connected_seconds * 48000 * 2 * 2))
      fi
      recorder_pid=$!
      active_audio_recorder_pids=("$recorder_pid")
      deadline=$((SECONDS + connected_seconds + 12))
      while :; do
        if [[ -n "$capture_follower_path" ]]; then
          captured_bytes=$(stat -c %s "$combined_capture" 2>/dev/null || printf '0')
        else
          captured_bytes=$(stat -c %s "$capture_path" 2>/dev/null || printf '0')
        fi
        if (( captured_bytes >= expected_bytes )); then break; fi
        if ! group_alive "$pid" || ! kill -0 "$recorder_pid" 2>/dev/null \
            || (( SECONDS >= deadline )); then
          echo "$label: vanilla coexistence recorder did not retain the requested audio window" >&2
          result=1
          break
        fi
        sleep 0.2
      done
      kill -TERM "$recorder_pid" 2>/dev/null || true
      wait "$recorder_pid" 2>/dev/null || true
      active_audio_recorder_pids=()
      if (( result == 0 )) && [[ -n "$capture_follower_path" ]]; then
        if ! ffmpeg -hide_banner -loglevel error -y \
            -f s16le -ar 48000 -ac 4 -i "$combined_capture" \
            -filter_complex \
              '[0:a]pan=stereo|c0=c0|c1=c1[leader];[0:a]pan=stereo|c0=c2|c1=c3[follower]' \
            -map '[leader]' -f s16le "$capture_path" \
            -map '[follower]' -f s16le "$capture_follower_path"; then
          echo "$label: could not split the shared-clock vanilla coexistence capture" >&2
          result=1
        fi
      fi
    else
      sleep "$connected_seconds"
    fi
  fi

  if (( result == 0 )); then
    if ! group_alive "$pid" \
        || grep -Eiq 'mismatched mod (channel )?list|required on the client|protocol mismatch' "$client_console"; then
      echo "$label: pure vanilla client did not remain connected" >&2
      result=1
    elif ! python3 - "$instance_dir/mmc-pack.json" "$attestation" "$minecraft_version" \
        "127.0.0.1:$port" <<'PY'
import json
from pathlib import Path
import sys

pack_path = Path(sys.argv[1])
attestation_path = Path(sys.argv[2])
expected_version = sys.argv[3]
expected_target = sys.argv[4]
pack = json.loads(pack_path.read_text("utf-8"))
attestation = json.loads(attestation_path.read_text("utf-8"))
components = pack.get("components", [])
uids = [component.get("uid") for component in components]
allowed = {"net.minecraft", "org.lwjgl", "org.lwjgl3"}
if len(components) != 2 or set(uids) - allowed or "net.minecraft" not in uids:
    raise SystemExit("Prism pack contains a non-vanilla component")
minecraft = next(component for component in components if component.get("uid") == "net.minecraft")
if minecraft.get("version") != expected_version:
    raise SystemExit("Prism pack Minecraft version does not match the server")
if attestation.get("launcher") != "Direct Mojang client from verified Prism caches":
    raise SystemExit("Vanilla attestation does not describe the direct verified-cache launcher")
if attestation.get("componentUids") != uids or attestation.get("jammarrComponentPresent") is not False:
    raise SystemExit("Vanilla attestation does not match the launched Prism pack")
if attestation.get("mods") != []:
    raise SystemExit("Vanilla Prism instance contains a mod artifact")
if attestation.get("accountMode") != "direct-offline":
    raise SystemExit("Vanilla attestation does not describe the isolated offline identity")
runtime = attestation.get("runtime", {})
if runtime.get("allArtifactSha1Verified") is not True:
    raise SystemExit("Vanilla runtime artifacts were not all SHA-1 verified")
if runtime.get("allArtifactSha1AndSizeVerified") is not True:
    raise SystemExit("Vanilla runtime artifacts were not all SHA-1 and size verified")
if runtime.get("sharedCacheMutated") is not False:
    raise SystemExit("Vanilla runtime did not attest immutable shared-cache use")
source_counts = runtime.get("artifactSourceCounts")
if not isinstance(source_counts, dict) or not source_counts or sum(source_counts.values()) < 1:
    raise SystemExit("Vanilla runtime artifact source counts are missing")
if runtime.get("connectionTarget") != expected_target:
    raise SystemExit("Vanilla runtime connection target does not match the server")
client_sha1 = runtime.get("clientJarSha1", "")
if len(client_sha1) != 40 or any(character not in "0123456789abcdef" for character in client_sha1):
    raise SystemExit("Vanilla runtime client JAR SHA-1 is missing or malformed")
PY
    then
      echo "$label: Prism client was not an exact artifact-free Minecraft instance" >&2
      result=1
    fi
  fi

  if (( result == 0 )); then
    if uses_console_control "$label"; then
      first_line=$(wc -l < "$server_console")
      printf 'jammarr diagnostics\n' >&"$fifo_fd"
      deadline=$((SECONDS + 30))
      while ! tail -n "+$((first_line + 1))" "$server_console" \
          | grep -Fq "$expected_diagnostics"; do
        if ! group_alive "$pid" || (( SECONDS >= deadline )); then
          echo "$label: diagnostics did not classify the pure client as vanilla-only" >&2
          result=1
          break
        fi
        sleep 1
      done
      if (( result == 0 )); then
        tail -n "+$((first_line + 1))" "$server_console" \
          | grep -F "$expected_diagnostics" \
          | tail -n 1 > "$diagnostics"
      fi
    elif ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
        'jammarr diagnostics' > "$diagnostics" \
        || ! grep -Fq "$expected_diagnostics" "$diagnostics"; then
      echo "$label: diagnostics did not classify the pure client as vanilla-only" >&2
      result=1
    fi
  fi

  request_end=$(wc -l < "$fake_plex_request_log")
  if (( result == 0 )) && (( request_end != request_start )); then
    echo "$label: pure vanilla join caused unexpected Plex traffic ($request_start -> $request_end)" >&2
    result=1
  fi

  if (( result == 0 )); then
    {
      grep -F "$username joined the game" "$server_console" | tail -n 1 || true
      cat "$attestation"
      cat "$diagnostics"
      [[ ! -s "$chat_evidence" ]] || cat "$chat_evidence"
      printf 'Artifact-free vanilla client remained connected for %s seconds.\n' "$connected_seconds"
      printf 'Plex request count remained unchanged at %s.\n' "$request_end"
    } > "$evidence"
  fi
  # Let Minecraft stop its OpenAL mixer before tearing down the private Xvfb
  # and launcher group. A simultaneous group TERM can otherwise crash inside
  # libopenal even though the client uses the isolated null backend.
  : > "$shutdown_trigger"
  deadline=$((SECONDS + 15))
  while group_alive "$pid" && (( SECONDS < deadline )); do sleep .2; done
  if grep -Fq 'VANILLA_SHUTDOWN_FAILED' "$client_console" 2>/dev/null; then
    echo "$label: exact vanilla client could not perform a graceful private-X shutdown" >&2
    result=1
  fi
  terminate_client_launch "$pid" 20 || result=1
  active_client_pid=""

  if (( result == 0 )); then
    : > "$post_diagnostics"
    if uses_console_control "$label"; then
      first_line=$(wc -l < "$server_console")
      printf 'jammarr diagnostics\n' >&"$fifo_fd"
      deadline=$((SECONDS + 30))
      while ! tail -n "+$((first_line + 1))" "$server_console" \
          | grep -Fq "$expected_after_disconnect"; do
        if (( SECONDS >= deadline )); then
          echo "$label: vanilla listener state remained allocated after disconnect" >&2
          result=1
          break
        fi
        sleep 1
      done
      if (( result == 0 )); then
        tail -n "+$((first_line + 1))" "$server_console" \
          | grep -F "$expected_after_disconnect" | tail -n 1 > "$post_diagnostics"
      fi
    else
      deadline=$((SECONDS + 30))
      while (( SECONDS < deadline )); do
        if run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
            'jammarr diagnostics' > "$post_diagnostics" \
            && grep -Fq "$expected_after_disconnect" "$post_diagnostics"; then
          break
        fi
        sleep 1
      done
      if ! grep -Fq "$expected_after_disconnect" "$post_diagnostics"; then
        echo "$label: vanilla listener state remained allocated after disconnect" >&2
        result=1
      fi
    fi
    if (( result == 0 )); then
      cat "$post_diagnostics" >> "$evidence"
      printf 'Vanilla listener state was removed after disconnect.\n' >> "$evidence"
    fi
  fi
  if (( result == 0 )) && [[ "$interaction_gate" == "true" ]]; then
    if ! run_vanilla_client "$label" "$port" "$server_console" \
        "$rcon_port" "$rcon_password" "$fifo_fd" \
        "$expected_capable" "$expected_vanilla" "$expected_listener_stats" \
        "${scenario}-reconnect" "$username" "$connected_seconds" "" "" "" false; then
      echo "$label: exact vanilla client did not reconnect cleanly" >&2
      result=1
    else
      {
        printf 'Artifact-free vanilla client sent player-originated chat: %s.\n' "$chat_message"
        printf 'Artifact-free vanilla client reconnected and completed a second clean lifecycle.\n'
        cat "$reconnect_evidence"
      } >> "$evidence"
    fi
  fi
  return "$result"
}

run_client_companion() {
  local server_label=$1
  local companion_label=${target_companion_label[$server_label]:-}
  local relative_dir=${target_companion_path[$server_label]:-}
  local build_java=${target_companion_java[$server_label]:-}
  local runtime_java=${target_companion_runtime_java[$server_label]:-}
  local client_task=${target_companion_task[$server_label]:-runClient}
  local port=$2
  local companion_dir="$repo_root/$relative_dir"
  local scenario=paired-client username=LiteCompanion
  local client_dir="$output_root/$companion_label.$scenario"
  local client_console="$output_root/$companion_label.$scenario.console.log"
  local evidence="$output_root/$companion_label.$scenario.evidence.txt"
  local java_home runtime_java_home pid deadline result=0

  [[ -n "$companion_label" ]] || return 0
  case "$build_java" in
    8) java_home=$java8_home ;;
    17) java_home=$java17_home ;;
    21) java_home=$java21_home ;;
    26) java_home=$java26_home ;;
    *)
      echo "$companion_label: no client-companion JDK is configured for Java $build_java" >&2
      return 1
      ;;
  esac
  case "$runtime_java" in
    8) runtime_java_home=$java8_home ;;
    17) runtime_java_home=$java17_home ;;
    21) runtime_java_home=$java21_home ;;
    26) runtime_java_home=$java26_home ;;
    *)
      echo "$companion_label: no client-companion runtime JDK is configured for Java $runtime_java" >&2
      return 1
      ;;
  esac
  if [[ ! -x "$java_home/bin/java" || ! -x "$runtime_java_home/bin/java" \
      || ! -x "$companion_dir/gradlew" ]]; then
    echo "$companion_label: paired-client runtime prerequisites are missing" >&2
    return 1
  fi

  mkdir -p "$client_dir/liteconfig/common"
  prepare_graphical_client_environment "$server_label" "$client_dir" || return 1
  : > "$client_console"
  printf '%s\n' \
    'onboardAccessibility:false' \
    'skipMultiplayerWarning:true' \
    'joinedFirstServer:true' \
    'narrator:0' \
    'soundCategory_master:1.0' \
    'soundCategory_music:1.0' > "$client_dir/options.txt"
  printf '%s\n' \
    '# Generated by the client-companion acceptance gate.' \
    'enabled = true' \
    'volume = 1.0' > "$client_dir/liteconfig/common/jammarr-client.toml"

  (
    cd "$companion_dir" || exit 1
    ulimit -f "$client_log_limit_blocks"
    exec setsid env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 DISPLAY="$active_client_display" \
      JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAVA_TOOL_OPTIONS='-Djammarr.acceptance.enabled=true -Djammarr.acceptance.audioProbe=true -Djammarr.acceptance.audioLeader=true -Djammarr.acceptance.commandProbe=true -Djammarr.acceptance.clientHelloDelayMs=1 -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true' \
      ALSA_CONFIG_PATH="$client_dir/alsa.conf" ALSOFT_CONF="$client_dir/alsoft.conf" \
      ALSOFT_DRIVERS="$(headless_client_openal_driver "$server_label")" \
      PULSE_SERVER="unix:$active_audio_runtime_dir/pulse/native" \
      PIPEWIRE_RUNTIME_DIR="$active_audio_runtime_dir" XDG_RUNTIME_DIR="$active_audio_runtime_dir" \
      PULSE_SINK="$active_audio_default_sink" \
      LIBGL_ALWAYS_SOFTWARE=1 \
      ./gradlew "$client_task" --no-daemon --max-workers=2 --console=plain \
      -PjammarrAcceptanceUsername="$username" \
      -PjammarrAcceptanceServer="127.0.0.1:${port}" \
      -PjammarrAcceptanceGameDir="$client_dir" \
      > "$client_console" 2>&1
  ) &
  pid=$!
  active_client_pid=$pid
  if ! wait_for_client_launch "$pid" "$client_console"; then
    active_client_pid=""
    return 1
  fi

  deadline=$((SECONDS + 600))
  while ! grep -Fq 'Acceptance client received server hello after delayed handshake' "$client_console" \
      || ! grep -Fq 'Acceptance audio manifest:' "$client_console" \
      || ! grep -Fq 'Acceptance audio state: PLAYING' "$client_console" \
      || ! grep -Fq 'Acceptance legacy Jammarr screen remained open across client ticks' "$client_console" \
      || ! grep -Fq 'Acceptance Jammarr title/status/notice rendered with opaque alpha' "$client_console" \
      || { requires_legacy_search_edit_probe "$paired_runtime" && ! grep -Fq 'Acceptance legacy search edit survived click, typing, backspace, and screen rebuilds' "$client_console"; } \
      || ! grep -Fq 'Acceptance legacy Jammarr config screen remained open across client ticks' "$client_console"; do
    if client_bootstrap_failed "$client_console" \
        || grep -Eq 'ExceptionInInitializerError|Unreported exception thrown|#@!@# Game crashed!|Description: Unexpected error' "$client_console" \
        || ! group_alive "$pid" \
        || (( SECONDS >= deadline )); then
      echo "$companion_label: production client companion did not complete paired runtime acceptance; see $client_console" >&2
      result=1
      break
    fi
    sleep 1
  done

  if (( result == 0 )); then
    sleep 10
    if ! group_alive "$pid" \
        || grep -Eiq 'Rejected malformed Jammarr packet|Jammarr protocol (handshake )?timed out|Unreported exception thrown' "$client_console"; then
      echo "$companion_label: production client companion did not remain healthy after acceptance" >&2
      result=1
    else
      {
        grep -F 'Initializing Jammarr 1.1.0 client companion' "$client_console" | tail -n 1
        grep -F 'Acceptance client received server hello after delayed handshake' "$client_console" | tail -n 1
        grep -F 'Acceptance audio manifest:' "$client_console" | tail -n 1
        grep -F 'Acceptance audio state: PLAYING' "$client_console" | tail -n 1
        grep -F 'Acceptance legacy Jammarr screen remained open across client ticks' "$client_console" | tail -n 1
        grep -F 'Acceptance legacy search edit survived click, typing, backspace, and screen rebuilds' "$client_console" | tail -n 1
        grep -F 'Acceptance legacy Jammarr config screen remained open across client ticks' "$client_console" | tail -n 1
        printf '%s\n' 'Production client companion remained connected for 10 seconds after acceptance.'
      } > "$evidence"
    fi
  fi

  terminate_client_launch "$pid" 20 || result=1
  active_client_pid=""
  return "$result"
}

run_delayed_hello_client() {
  local label=$1
  local target_dir=$2
  local java_home=$3
  local port=$4
  local scenario=delayed-hello-client
  local username=JammarrDelayed
  local client_dir="$output_root/$label.$scenario"
  local client_console="$output_root/$label.$scenario.console.log"
  local evidence="$output_root/$label.$scenario.evidence.txt"
  local pid deadline result=0
  local -a runtime_args=() cache_args=()
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-quilt && "$quilt_modmenu_gate" == true ]] && runtime_args+=(-PjammarrIncludeModMenu=true)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")
  uses_loom_client_launcher "$label" \
    && runtime_args+=(--init-script "$repo_root/gradle/verify-loom-dev-launcher.init.gradle")
  uses_loom_client_launcher "$label" && cache_args+=(--no-configuration-cache)
  disables_configuration_cache "$label" && cache_args+=(--no-configuration-cache)

  mkdir -p "$client_dir"
  prepare_graphical_client_environment "$label" "$client_dir" || return 1
  : > "$client_console"
  printf '%s\n' \
    'onboardAccessibility:false' \
    'skipMultiplayerWarning:true' \
    'joinedFirstServer:true' \
    'narrator:0' > "$client_dir/options.txt"
  (
    cd "$target_dir" || exit 1
    ulimit -f "$client_log_limit_blocks"
    exec setsid env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 DISPLAY="$active_client_display" \
      JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAVA_TOOL_OPTIONS="-Djammarr.acceptance.enabled=true -Djammarr.acceptance.clientHelloDelayMs=${delayed_hello_ms} -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true" \
      ALSA_CONFIG_PATH="$client_dir/alsa.conf" ALSOFT_CONF="$client_dir/alsoft.conf" \
      ALSOFT_DRIVERS="$(non_audio_client_openal_driver)" \
      PULSE_SERVER="unix:$active_audio_runtime_dir/pulse/native" \
      PIPEWIRE_RUNTIME_DIR="$active_audio_runtime_dir" XDG_RUNTIME_DIR="$active_audio_runtime_dir" \
      PULSE_SINK="$active_audio_default_sink" \
      LIBGL_ALWAYS_SOFTWARE=1 \
      ./gradlew "${runtime_args[@]}" --no-daemon --max-workers=2 --console=plain "${cache_args[@]}" \
      "${target_client_task[$label]}" \
      -PjammarrAcceptanceUsername="$username" \
      -PjammarrAcceptanceServer="127.0.0.1:${port}" \
      -PjammarrAcceptanceGameDir="$client_dir" \
      > "$client_console" 2>&1
  ) &
  pid=$!
  active_client_pid=$pid
  if ! wait_for_client_launch "$pid" "$client_console"; then
    active_client_pid=""
    return 1
  fi

  deadline=$((SECONDS + 600))
  while ! grep -Fq 'Acceptance client received server hello after delayed handshake' "$client_console"; do
    if client_bootstrap_failed "$client_console"; then
      echo "$label: delayed-hello client could not initialize its headless display; see $client_console" >&2
      result=1
      break
    fi
    if ! group_alive "$pid"; then
      echo "$label: delayed-hello client exited before handshake acceptance; see $client_console" >&2
      result=1
      break
    fi
    if grep -Fq 'Jammarr protocol handshake timed out' "$client_console"; then
      echo "$label: delayed hello was incorrectly rejected; see $client_console" >&2
      result=1
      break
    fi
    if (( SECONDS >= deadline )); then
      echo "$label: delayed hello was not accepted within 600 seconds; see $client_console" >&2
      result=1
      break
    fi
    sleep 1
  done

  if (( result == 0 )); then
    sleep 10
    if ! group_alive "$pid" || grep -Fq 'Jammarr protocol handshake timed out' "$client_console"; then
      echo "$label: delayed-hello client did not remain connected after acceptance" >&2
      result=1
    else
      grep -F 'Acceptance client' "$client_console" \
        | grep -E 'delaying Jammarr hello|sent delayed Jammarr hello|received server hello' \
        | tail -n 3 > "$evidence"
    fi
  fi
  terminate_client_launch "$pid" 20 || result=1
  active_client_pid=""
  return "$result"
}

run_acceptance_client() {
  local label=$1
  local scenario=$6
  local client_console="$output_root/$label.$scenario.console.log"
  local attempt
  for attempt in 1 2; do
    if (( attempt == 1 )); then
      if run_acceptance_client_once "$@"; then return 0; fi
    else
      if JAMMARR_ACCEPTANCE_RERUN_TASKS=true run_acceptance_client_once "$@"; then return 0; fi
    fi
    if (( attempt == 1 )) && grep -Eq \
        'Timed out trying to setup the Game Window|Failed to initialize the mod loading system and display|Failed to download .*\.ogg|HttpTimeoutException: request timed out|DownloadException: Failed to download' \
        "$client_console" 2>/dev/null; then
      echo "$label: retrying $scenario after a transient client bootstrap failure" >&2
      preserve_client_attempt "$client_console" "$attempt"
      continue
    fi
    return 1
  done
  return 1
}

run_acceptance_client_once() {
  local label=$1
  local target_dir=$2
  local java_home=$3
  local port=$4
  local server_console=$5
  local scenario=$6
  local username=$7
  local java_tool_options=$8
  local rejection=$9
  local allow_generic_client_rejection=${10:-false}
  local client_dir="$output_root/$label.$scenario"
  local client_console="$output_root/$label.$scenario.console.log"
  local evidence="$output_root/$label.$scenario.server.txt"
  local pid deadline exit_grace_deadline result=0 openal_driver
  local -a runtime_args=() cache_args=()
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-quilt && "$quilt_modmenu_gate" == true ]] && runtime_args+=(-PjammarrIncludeModMenu=true)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")
  uses_loom_client_launcher "$label" \
    && runtime_args+=(--init-script "$repo_root/gradle/verify-loom-dev-launcher.init.gradle")
  uses_loom_client_launcher "$label" && cache_args+=(--no-configuration-cache)
  disables_configuration_cache "$label" && cache_args+=(--no-configuration-cache)
  [[ ${JAMMARR_ACCEPTANCE_RERUN_TASKS:-false} == true ]] \
    && cache_args+=(--rerun-tasks --refresh-dependencies)

  mkdir -p "$client_dir"
  write_private_client_loader_config "$label" "$client_dir" || return 1
  write_private_client_audio_config "$client_dir" || return 1
  openal_driver=$(non_audio_client_openal_driver) || return 1
  : > "$client_console"
  printf '%s\n' \
    'onboardAccessibility:false' \
    'skipMultiplayerWarning:true' \
    'joinedFirstServer:true' \
    'narrator:0' > "$client_dir/options.txt"
  (
    cd "$target_dir" || exit 1
    ulimit -f "$client_log_limit_blocks"
    exec setsid env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 DISPLAY="$active_client_display" \
      JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAVA_TOOL_OPTIONS="$java_tool_options" \
      ALSA_CONFIG_PATH="$client_dir/alsa.conf" ALSOFT_CONF="$client_dir/alsoft.conf" \
      ALSOFT_DRIVERS="$openal_driver" PULSE_SERVER="unix:$active_audio_runtime_dir/pulse/native" \
      PIPEWIRE_RUNTIME_DIR="$active_audio_runtime_dir" XDG_RUNTIME_DIR="$active_audio_runtime_dir" \
      PULSE_SINK="$active_audio_default_sink" \
      LIBGL_ALWAYS_SOFTWARE=1 \
      ./gradlew "${runtime_args[@]}" --no-daemon --max-workers=2 --console=plain "${cache_args[@]}" \
      "${target_client_task[$label]}" \
      -PjammarrAcceptanceUsername="$username" \
      -PjammarrAcceptanceServer="127.0.0.1:${port}" \
      -PjammarrAcceptanceGameDir="$client_dir" \
      > "$client_console" 2>&1
  ) &
  pid=$!
  active_client_pid=$pid
  if ! wait_for_client_launch "$pid" "$client_console"; then
    active_client_pid=""
    return 1
  fi

  deadline=$((SECONDS + 600))
  while ! rejection_observed "$server_console" "$client_console" "$rejection" \
      "$allow_generic_client_rejection"; do
    if client_bootstrap_failed "$client_console"; then
      echo "$label: $scenario could not initialize its headless display; see $client_console" >&2
      result=1
      break
    fi
    if ! group_alive "$pid"; then
      # The cold Forge 1.7.10 client can terminate immediately after receiving
      # the disconnect while its client and server log writers are still
      # flushing. Give both exact rejection markers a short bounded grace
      # period; a genuine launcher crash still fails once the window expires.
      exit_grace_deadline=$((SECONDS + 60))
      while (( SECONDS < exit_grace_deadline )); do
        if rejection_observed "$server_console" "$client_console" "$rejection" \
            "$allow_generic_client_rejection"; then
          break 2
        fi
        sleep 1
      done
      echo "$label: $scenario exited before the server rejected it; see $client_console" >&2
      result=1
      break
    fi
    if (( SECONDS >= deadline )); then
      echo "$label: $scenario was not rejected within 600 seconds; see $client_console" >&2
      result=1
      break
    fi
    sleep 1
  done

  if (( result == 0 )); then
    {
      grep -F "$rejection" "$server_console" | tail -n 1 || true
      if ! grep -F "Client disconnected with reason: $rejection" "$client_console" | tail -n 1; then
        grep -F 'Client disconnected with reason: Disconnected' "$client_console" | tail -n 1
      fi
    } > "$evidence"
  fi
  terminate_client_launch "$pid" 20 || result=1
  active_client_pid=""
  return "$result"
}

run_command_client() {
  local label=$1
  local client_console="$output_root/$label.command-client.console.log"
  local attempt
  for attempt in 1 2; do
    if run_command_client_once "$@"; then return 0; fi
    if (( attempt == 1 )) && client_bootstrap_failed "$client_console"; then
      echo "$label: retrying command client after a transient renderer bootstrap failure" >&2
      preserve_client_attempt "$client_console" "$attempt"
      continue
    fi
    return 1
  done
  return 1
}

run_command_client_once() {
  local label=$1
  local target_dir=$2
  local java_home=$3
  local port=$4
  local server_console=$5
  local rcon_port=$6
  local rcon_password=$7
  local fifo_fd=$8
  local scenario=command-client username=JammarrCommand
  local client_dir="$output_root/$label.$scenario"
  local client_console="$output_root/$label.$scenario.console.log"
  local diagnostics="$output_root/$label.$scenario.diagnostics.txt"
  local evidence="$output_root/$label.$scenario.evidence.txt"
  local pid deadline result=0 openal_driver
  local -a runtime_args=() cache_args=()
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-quilt && "$quilt_modmenu_gate" == true ]] && runtime_args+=(-PjammarrIncludeModMenu=true)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")
  uses_loom_client_launcher "$label" \
    && runtime_args+=(--init-script "$repo_root/gradle/verify-loom-dev-launcher.init.gradle")
  uses_loom_client_launcher "$label" && cache_args+=(--no-configuration-cache)
  disables_configuration_cache "$label" && cache_args+=(--no-configuration-cache)

  mkdir -p "$client_dir"
  write_private_client_loader_config "$label" "$client_dir" || return 1
  write_private_client_audio_config "$client_dir" || return 1
  openal_driver=$(non_audio_client_openal_driver) || return 1
  : > "$client_console"
  : > "$diagnostics"
  printf '%s\n' \
    'onboardAccessibility:false' \
    'skipMultiplayerWarning:true' \
    'joinedFirstServer:true' \
    'narrator:0' > "$client_dir/options.txt"

  if uses_console_control "$label"; then
    printf 'deop %s\n' "$username" >&"$fifo_fd"
  elif ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
      "deop $username" > /dev/null 2>&1; then
    # A never-before-seen player is not in ops.json, which some versions report
    # as a command failure. The real non-operator command tree below is authority.
    true
  fi

  (
    cd "$target_dir" || exit 1
    ulimit -f "$client_log_limit_blocks"
    exec setsid env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 DISPLAY="$active_client_display" \
      JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAVA_TOOL_OPTIONS='-Djammarr.acceptance.enabled=true -Djammarr.acceptance.commandProbe=true -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true' \
      ALSA_CONFIG_PATH="$client_dir/alsa.conf" ALSOFT_CONF="$client_dir/alsoft.conf" \
      ALSOFT_DRIVERS="$openal_driver" PULSE_SERVER="unix:$active_audio_runtime_dir/pulse/native" \
      PIPEWIRE_RUNTIME_DIR="$active_audio_runtime_dir" XDG_RUNTIME_DIR="$active_audio_runtime_dir" \
      PULSE_SINK="$active_audio_default_sink" \
      LIBGL_ALWAYS_SOFTWARE=1 \
      ./gradlew "${runtime_args[@]}" --no-daemon --max-workers=2 --console=plain "${cache_args[@]}" \
      "${target_client_task[$label]}" \
      -PjammarrAcceptanceUsername="$username" \
      -PjammarrAcceptanceServer="127.0.0.1:${port}" \
      -PjammarrAcceptanceGameDir="$client_dir" \
      > "$client_console" 2>&1
  ) &
  pid=$!
  active_client_pid=$pid
  if ! wait_for_client_launch "$pid" "$client_console"; then
    active_client_pid=""
    return 1
  fi

  deadline=$((SECONDS + 600))
  if uses_legacy_command_markers "$label"; then
    while ! grep -Fq 'Acceptance command response: Queue is empty' "$client_console" 2>/dev/null \
        || ! grep -Fq 'Acceptance command response: Operator permission is required' "$client_console" 2>/dev/null; do
      if client_bootstrap_failed "$client_console" || client_runtime_failed "$client_console"; then
        echo "$label: command client could not initialize its headless display; see $client_console" >&2
        result=1
        break
      fi
      if ! group_alive "$pid" || (( SECONDS >= deadline )); then
        echo "$label: legacy non-operator command responses were not observed; see $client_console" >&2
        result=1
        break
      fi
      sleep 1
    done
  else
    while ! grep -Fq 'Acceptance command permissions: non-operator public=true operator=false' \
        "$client_console" 2>/dev/null; do
      if client_bootstrap_failed "$client_console" || client_runtime_failed "$client_console"; then
        echo "$label: command client could not initialize its headless display; see $client_console" >&2
        result=1
        break
      fi
      if ! group_alive "$pid" || (( SECONDS >= deadline )); then
        echo "$label: non-operator command tree was not observed; see $client_console" >&2
        result=1
        break
      fi
      sleep 1
    done
  fi

  # The permissions marker is emitted on the render thread. Keep the process
  # alive briefly after observing it so a subsequent renderer or audio crash
  # cannot turn a partial command-tree observation into a false pass.
  if (( result == 0 )); then
    sleep 3
    if ! group_alive "$pid" || client_runtime_failed "$client_console"; then
      echo "$label: command probe did not remain healthy after command-tree evidence; see $client_console" >&2
      result=1
    fi
  fi

  if (( result == 0 )); then
    if uses_legacy_command_markers "$label"; then
      if uses_console_control "$label"; then
        printf 'op %s\n' "$username" >&"$fifo_fd"
      elif ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
          "op $username" > /dev/null; then
        echo "$label: unable to promote the real legacy command-probe client" >&2
        result=1
      fi
      sleep 1
      if uses_console_control "$label"; then
        printf 'tell %s JAMMARR_ACCEPTANCE_OPERATOR_READY\n' "$username" >&"$fifo_fd"
      else
        run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
          "tell $username JAMMARR_ACCEPTANCE_OPERATOR_READY" > /dev/null || result=1
      fi
      deadline=$((SECONDS + 60))
      while (( result == 0 )) && ! grep -Fq 'Acceptance command response: Plex=' "$client_console" 2>/dev/null; do
        if ! group_alive "$pid" || (( SECONDS >= deadline )); then
          echo "$label: operator diagnostics response was not observed; see $client_console" >&2
          result=1
          break
        fi
        sleep 1
      done
      if (( result == 0 )); then
        grep -F 'Acceptance command response:' "$client_console" > "$diagnostics"
      fi
      deadline=$((SECONDS + 60))
      while (( result == 0 )) \
          && { ! grep -Fq 'Acceptance legacy Jammarr screen remained open across client ticks' "$client_console" \
            || ! grep -Fq 'Acceptance Jammarr title/status/notice rendered with opaque alpha' "$client_console" \
            || { requires_hover_help_probe "$label" && ! grep -Fq 'Acceptance legacy hover help rendered on a real control' "$client_console"; } \
            || { requires_legacy_search_edit_probe "$label" && ! grep -Fq 'Acceptance legacy search edit survived click, typing, backspace, and screen rebuilds' "$client_console"; } \
            || ! grep -Fq 'Acceptance legacy Jammarr config screen remained open across client ticks' "$client_console"; }; do
        if ! group_alive "$pid" || (( SECONDS >= deadline )); then
          echo "$label: legacy Jammarr player/config/search-edit UI gate did not complete; see $client_console" >&2
          result=1
          break
        fi
        sleep 1
      done
      if uses_console_control "$label"; then
        printf 'deop %s\n' "$username" >&"$fifo_fd"
      else
        run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
          "deop $username" > /dev/null 2>&1 || true
      fi
    else
      if ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
          "op $username" > /dev/null; then
        echo "$label: unable to promote the real command-probe client" >&2
        result=1
      fi
      deadline=$((SECONDS + 60))
      while (( result == 0 )) && ! grep -Fq \
          'Acceptance command permissions: operator public=true operator=true' "$client_console" 2>/dev/null; do
        if ! group_alive "$pid" || (( SECONDS >= deadline )); then
          echo "$label: operator command tree was not observed; see $client_console" >&2
          result=1
          break
        fi
        sleep 1
      done
      if (( result == 0 )); then
        deadline=$((SECONDS + 30))
        while ! grep -Fq '[CHAT] Plex=' "$client_console" 2>/dev/null; do
          if ! group_alive "$pid" || (( SECONDS >= deadline )); then
            echo "$label: real operator client did not receive sanitized /jammarr diagnostics output" >&2
            result=1
            break
          fi
          sleep 1
        done
      fi
      if (( result == 0 )); then
        grep -F '[CHAT] Plex=' "$client_console" | tail -n 1 > "$diagnostics"
        if ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
            'jammarr diagnostics' >> "$diagnostics"; then
          echo "$label: diagnostics command failed over authenticated server administration" >&2
          result=1
        fi
      fi
      if (( result == 0 )); then
        deadline=$((SECONDS + 60))
        while ! grep -Fq 'Acceptance Jammarr screen remained open across rendered frames' \
            "$client_console" 2>/dev/null \
            || ! grep -Fq 'Acceptance Jammarr title/status/notice rendered with opaque alpha' "$client_console" 2>/dev/null \
            || { requires_hover_help_probe "$label" && ! grep -Fq 'Acceptance hover help rendered on a real control' "$client_console" 2>/dev/null; } \
            || { requires_config_screen_probe "$label" \
              && ! grep -Fq 'Acceptance Jammarr config screen remained open across rendered frames' \
                "$client_console" 2>/dev/null; }; do
          if ! group_alive "$pid" || (( SECONDS >= deadline )); then
            echo "$label: Jammarr player/config screens did not remain open across rendered frames; see $client_console" >&2
            result=1
            break
          fi
          sleep 1
        done
      fi
      run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
        "deop $username" > /dev/null 2>&1 || true
    fi
  fi

  if (( result == 0 )) && ! grep -Fq 'Plex=' "$diagnostics"; then
    echo "$label: diagnostics output is missing the expected sanitized health summary" >&2
    result=1
  fi
  if grep -Fq "$fake_plex_token" "$diagnostics" \
      || grep -Eq 'https?://|127\.0\.0\.1|localhost|X-Plex-Token' "$diagnostics"; then
    echo "$label: player/operator diagnostics exposed a credential or server address" >&2
    result=1
  fi
  if (( result == 0 )); then
    {
      grep -E 'Acceptance command permissions:|Acceptance command response: (Queue is empty|Operator permission is required|Plex=)' \
        "$client_console" || true
      grep -E 'Acceptance Jammarr (config )?screen remained open across rendered frames' "$client_console" || true
      grep -E 'Acceptance legacy Jammarr (config )?screen remained open across client ticks' "$client_console" || true
      grep -E 'Acceptance (legacy )?hover help rendered on a real control' "$client_console" || true
      grep -F 'Acceptance legacy search edit survived click, typing, backspace, and screen rebuilds' "$client_console" || true
      cat "$diagnostics"
    } > "$evidence"
  fi

  terminate_client_launch "$pid" 20 || result=1
  active_client_pid=""
  return "$result"
}

started_audio_client_pid=""
ready_audio_client_pid=""

start_audio_client() {
  local label=$1
  local target_dir=$2
  local java_home=$3
  local port=$4
  local role=$5
  local username=$6
  local sink=$7
  # The direct ALSA PipeWire plugin can initialize against a Pulse-created
  # remap sink and then lose that stream after prolonged churn. Keep ALSA's
  # Pulse plugin as the default modern route while allowing diagnostic runs to
  # select OpenAL Soft's native PipeWire backend. Native PipeWire needs the
  # exact enumerated sink name so both clients stay on separate branches of
  # the shared recorder clock.
  local pcm_type=${JAMMARR_ALSA_PCM_TYPE:-pulse}
  local alsoft_drivers=alsa pulse_sink= sound_device=
  local client_dir="$output_root/$label.audio-$role"
  local client_console="$output_root/$label.audio-$role.console.log"
  local control_file="$output_root/$label.audio-$role.control"
  local leader=false
  local -a cache_args=()
  local -a runtime_args=()
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-quilt && "$quilt_modmenu_gate" == true ]] && runtime_args+=(-PjammarrIncludeModMenu=true)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")
  uses_loom_client_launcher "$label" \
    && runtime_args+=(--init-script "$repo_root/gradle/verify-loom-dev-launcher.init.gradle")
  uses_loom_client_launcher "$label" && cache_args+=(--no-configuration-cache)
  [[ "$role" == "leader" || "$role" == cold-* ]] && leader=true
  if disables_configuration_cache "$label"; then
    cache_args+=(--no-configuration-cache)
  fi

  mkdir -p "$client_dir/config" "$client_dir/pcm-trace"
  write_private_client_loader_config "$label" "$client_dir" || return 1
  # A target's acceptance game directory is intentionally reusable, but its
  # pre-backend PCM traces are evidence for this launch only. Leaving an older
  # larger trace here can make the strict analyzer select stale audio while
  # the current client is still appending its trace.
  rm -f -- "$client_dir/pcm-trace/"*.s16le
  : > "$client_console"
  : > "$control_file"
  printf '%s\n' \
    'onboardAccessibility:false' \
    'skipMultiplayerWarning:true' \
    'joinedFirstServer:true' \
    'narrator:0' \
    'soundCategory_master:1.0' \
    'soundCategory_music:1.0' > "$client_dir/options.txt"
  printf '%s\n' '# Generated by the two-client audio acceptance gate.' \
    'enabled = true' 'volume = 1.0' > "$client_dir/config/jammarr-client.toml"
  # Headless CI's ALSA-to-Pulse bridge can otherwise starve OpenAL Soft's
  # default ~32 ms output buffer and silently advance the rendered waveform.
  # Keep enough mix-ahead to test Jammarr's PCM timing rather than runner
  # scheduling jitter; captures begin only after both clients report PLAYING.
  printf '%s\n' \
    '[general]' \
    'frequency = 48000' \
    'period_size = 1024' \
    'periods = 8' > "$client_dir/alsoft.conf"
  case "$pcm_type" in
    pipewire)
      printf '%s\n' \
        'pcm.!default {' \
        '  type pipewire' \
        "  playback_node \"$sink\"" \
        '}' \
        'ctl.!default {' \
        '  type pipewire' \
        '}' > "$client_dir/alsa.conf"
      ;;
    pulse)
      printf '%s\n' \
        'pcm.!default {' \
        '  type pulse' \
        "  device \"$sink\"" \
        '}' \
        'ctl.!default {' \
        '  type pulse' \
        '}' > "$client_dir/alsa.conf"
      ;;
    *)
      echo "Unsupported JAMMARR_ALSA_PCM_TYPE '$pcm_type'" >&2
      return 1
      ;;
  esac
  case "$openal_driver" in
    auto)
      if uses_legacy_audio_profile "$label" && [[ "$pcm_type" == pulse ]]; then
        alsoft_drivers=pulse
        pulse_sink=$sink
      fi
      ;;
    alsa)
      alsoft_drivers=alsa
      ;;
    pulse)
      alsoft_drivers=pulse
      pulse_sink=$sink
      ;;
    pipewire)
      if uses_legacy_audio_profile "$label"; then
        echo "$label: native PipeWire OpenAL diagnostics are not available for legacy audio" >&2
        return 1
      fi
      alsoft_drivers=pipewire
      sound_device=$sink
      printf 'soundDevice:"%s"\n' "$sound_device" >> "$client_dir/options.txt"
      ;;
  esac
  (
    cd "$target_dir" || exit 1
    ulimit -f "$client_log_limit_blocks"
    exec setsid env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 DISPLAY="$active_client_display" \
      JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAVA_TOOL_OPTIONS="-Djammarr.acceptance.enabled=true -Djammarr.acceptance.audioProbe=true -Djammarr.acceptance.audioLeader=$leader -Djammarr.acceptance.audioControlFile=$control_file -Djammarr.acceptance.pcmTraceDir=$client_dir/pcm-trace -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true" \
      ALSA_CONFIG_PATH="$client_dir/alsa.conf" ALSOFT_CONF="$client_dir/alsoft.conf" \
      ALSOFT_DRIVERS="$alsoft_drivers" ALSOFT_LOGLEVEL="$openal_loglevel" \
      PULSE_SERVER="unix:$active_audio_runtime_dir/pulse/native" \
      PIPEWIRE_RUNTIME_DIR="$active_audio_runtime_dir" XDG_RUNTIME_DIR="$active_audio_runtime_dir" \
      PULSE_SINK="$pulse_sink" LIBGL_ALWAYS_SOFTWARE=1 \
      ./gradlew "${runtime_args[@]}" --no-daemon --max-workers=2 --console=plain "${cache_args[@]}" \
      "${target_client_task[$label]}" \
      -PjammarrAcceptanceUsername="$username" \
      -PjammarrAcceptanceServer="127.0.0.1:${port}" \
      -PjammarrAcceptanceGameDir="$client_dir" \
      > "$client_console" 2>&1
  ) &
  started_audio_client_pid=$!
  active_audio_client_pids+=("$started_audio_client_pid")
}

wait_for_audio_playing() {
  local label=$1
  local role=$2
  local pid=$3
  local client_console="$output_root/$label.audio-$role.console.log"
  local initialized=0
  local initialization_deadline=$((SECONDS + 180))
  local deadline=$((SECONDS + 600))
  # start_audio_client returns the PID of a subshell that immediately execs
  # setsid. Under load, the first poll can run before setsid establishes the
  # matching process group, which previously made a healthy launch look dead
  # and could consume both retries before either JVM started. The server path
  # already has this spawn barrier; apply the same bounded barrier to every
  # audio-client launch and reconnect.
  if ! wait_for_group_start "$pid" 10; then
    echo "$label: $role client process group did not start; see $client_console" >&2
    return 1
  fi
  while ! grep -Fq 'Acceptance audio state: PLAYING' "$client_console" 2>/dev/null; do
    if grep -Fq 'Acceptance audio state:' "$client_console" 2>/dev/null; then initialized=1; fi
    if client_bootstrap_failed "$client_console"; then
      echo "$label: $role client could not initialize its headless display; see $client_console" >&2
      return 1
    fi
    if audio_log_has_terminal_backend_failure "$client_console"; then
      echo "$label: $role client failed before playback; see $client_console" >&2
      return 2
    fi
    if ! group_alive "$pid"; then
      echo "$label: $role client did not reach real Jammarr PLAYING state; see $client_console" >&2
      (( initialized == 0 )) && return 1 || return 2
    fi
    if (( initialized == 0 && SECONDS >= initialization_deadline )); then
      echo "$label: $role client did not initialize Jammarr within 180 seconds; see $client_console" >&2
      return 1
    fi
    if (( SECONDS >= deadline )); then
      echo "$label: $role client initialized but did not reach real Jammarr PLAYING state; see $client_console" >&2
      return 2
    fi
    sleep 1
  done
}

audio_log_has_terminal_backend_failure() {
  local client_console=$1
  grep -Eq 'Acceptance audio state: ERROR|Failed to open OpenAL device|Error starting SoundSystem|available update failed: Broken pipe|NoClassDefFoundError: (javazoom|de/sciss)|SIGSEGV.*libopenal' \
    "$client_console" 2>/dev/null
}

report_private_audio_graph_warnings() {
  local label=$1
  # PipeWire can recover from an unavailable output buffer. A warning anywhere
  # in this graph's lifetime does not establish that a client disconnected.
  # Keep the warning visible and require the captured PCM to pass the same
  # continuity, overlap and synchronization checks as every other capture.
  if grep -Eq 'out of buffers' "$output_root/$label.private-pipewire-pulse.log" 2>/dev/null; then
    echo "$label: PipeWire reported unavailable buffers; validating the recorded audio" >&2
  fi
}

latest_audio_state_is() {
  local client_console=$1
  local expected=$2
  grep -F 'Acceptance audio state:' "$client_console" 2>/dev/null \
    | tail -n 1 | grep -Fq "Acceptance audio state: $expected"
}

wait_for_audio_pair_playing() {
  local label=$1
  local leader_pid=$2
  local follower_pid=$3
  local leader_console="$output_root/$label.audio-leader.console.log"
  local follower_console="$output_root/$label.audio-follower.console.log"
  local deadline=$((SECONDS + 120))
  local stable_checks=0
  while (( SECONDS < deadline )); do
    if ! group_alive "$leader_pid" || ! group_alive "$follower_pid"; then
      echo "$label: an audio client exited before synchronized capture" >&2
      return 1
    fi
    if audio_log_has_terminal_backend_failure "$leader_console" \
        || audio_log_has_terminal_backend_failure "$follower_console"; then
      echo "$label: an audio client reported a terminal backend failure before synchronized capture" >&2
      return 1
    fi
    if latest_audio_state_is "$leader_console" PLAYING \
        && latest_audio_state_is "$follower_console" PLAYING; then
      stable_checks=$((stable_checks + 1))
      if (( stable_checks >= 2 )); then return 0; fi
    else
      stable_checks=0
    fi
    sleep 1
  done
  echo "$label: both clients did not settle in PLAYING before synchronized capture" >&2
  return 1
}

audio_log_has_rapid_duplicate_channel_start() {
  local client_console=$1
  awk '
    /JAMMARR_AUDIO_TIMING stage=channel_started/ {
      if (match($0, /monotonicNanos=[0-9]+/)) {
        current = substr($0, RSTART + 15, RLENGTH - 15) + 0
        if (previous > 0 && current - previous < 500000000) duplicate = 1
        previous = current
      }
    }
    END { exit !duplicate }
  ' "$client_console"
}

launch_audio_client() {
  local label=$1
  local target_dir=$2
  local java_home=$3
  local port=$4
  local role=$5
  local username=$6
  local sink=$7
  local attempt pid status existing
  local -a remaining=()

  ready_audio_client_pid=""
  for attempt in 1 2; do
    start_audio_client "$label" "$target_dir" "$java_home" "$port" "$role" "$username" "$sink"
    pid=$started_audio_client_pid
    wait_for_audio_playing "$label" "$role" "$pid"
    status=$?
    if (( status == 0 )) && [[ "$openal_driver" == pipewire ]] \
        && ! grep -Fq "OpenAL initialized on device $sink" \
          "$output_root/$label.audio-$role.console.log"; then
      echo "$label: $role client fell back from its selected PipeWire sink $sink" >&2
      status=2
    fi
    if (( status == 0 )); then
      ready_audio_client_pid=$pid
      return 0
    fi
    if (( attempt == 2 )); then return 1; fi

    # Headless OpenAL and the client bootstrap can fail transiently on a loaded
    # hosted runner. Retry the complete clean client launch once, but still
    # require the replacement process to reach real Jammarr PLAYING state.
    echo "$label: retrying $role client once after a pre-playback failure" >&2
    terminate_client_launch "$pid" 20 || return 1
    preserve_client_attempt "$output_root/$label.audio-$role.console.log" "$attempt"
    remaining=()
    for existing in "${active_audio_client_pids[@]}"; do
      if [[ "$existing" != "$pid" ]]; then remaining+=("$existing"); fi
    done
    active_audio_client_pids=("${remaining[@]}")
  done
  return 1
}

audio_capture_is_audible() {
  local raw=$1
  local metrics=$2
  local mean samples
  ffmpeg -hide_banner -loglevel info -f s16le -ar 48000 -ac 2 -i "$raw" \
    -af 'highpass=f=970,lowpass=f=1025,silenceremove=start_periods=1:start_duration=1:start_threshold=-55dB:stop_periods=-1:stop_duration=1:stop_threshold=-55dB,volumedetect' \
    -f null - > /dev/null 2> "$metrics" || return 1
  mean=$(sed -n 's/.*mean_volume: \([^ ]*\) dB.*/\1/p' "$metrics" | tail -n 1)
  samples=$(sed -n 's/.*n_samples: \([0-9][0-9]*\).*/\1/p' "$metrics" | tail -n 1)
  if [[ -z "$mean" || "$mean" == "-inf" ]]; then return 1; fi
  if [[ -z "$samples" ]]; then return 1; fi
  awk -v value="$mean" -v samples="$samples" \
    'BEGIN { exit !(value > -45.0 && samples >= 192000) }'
}

audio_capture_is_silent() {
  local raw=$1
  local metrics=$2
  local samples
  # Ignore the recorder's bounded startup tail and isolate the synthetic Plex
  # program tone. Require a sustained tone before declaring a leak so bounded
  # transition tails and ordinary broadband game sounds cannot masquerade as playback.
  ffmpeg -hide_banner -loglevel info -ss 1 -f s16le -ar 48000 -ac 2 -i "$raw" \
    -af 'bandpass=f=1000:w=10,silenceremove=start_periods=1:start_duration=1.5:start_threshold=-50dB,volumedetect' -f null - \
    > /dev/null 2> "$metrics" || return 1
  samples=$(sed -n 's/.*n_samples: \([0-9][0-9]*\).*/\1/p' "$metrics" | tail -n 1)
  [[ "$samples" == "0" ]]
}

audio_capture_is_attenuated() {
  local raw=$1
  local metrics=$2
  local reference_metrics=$3
  local mean reference samples
  ffmpeg -hide_banner -loglevel info -f s16le -ar 48000 -ac 2 -i "$raw" \
    -af 'highpass=f=970,lowpass=f=1025,silenceremove=start_periods=1:start_duration=1:start_threshold=-60dB:stop_periods=-1:stop_duration=1:stop_threshold=-60dB,volumedetect' \
    -f null - > /dev/null 2> "$metrics" || return 1
  mean=$(sed -n 's/.*mean_volume: \([^ ]*\) dB.*/\1/p' "$metrics" | tail -n 1)
  reference=$(sed -n 's/.*mean_volume: \([^ ]*\) dB.*/\1/p' "$reference_metrics" | tail -n 1)
  samples=$(sed -n 's/.*n_samples: \([0-9][0-9]*\).*/\1/p' "$metrics" | tail -n 1)
  if [[ -z "$mean" || -z "$reference" || -z "$samples" || "$mean" == "-inf" ]]; then return 1; fi
  awk -v value="$mean" -v reference="$reference" -v samples="$samples" \
    'BEGIN { attenuation = reference - value; exit !(value > -60.0 && samples >= 192000 && attenuation >= 8.0 && attenuation <= 20.0) }'
}

capture_audio_sink() {
  local sink=$1
  local raw=$2
  local seconds=${3:-3}
  local recorder expected_bytes deadline captured_bytes
  : > "$raw"
  parec --raw --latency-msec=50 --device="${sink}.monitor" \
    --format=s16le --rate=48000 --channels=2 > "$raw" &
  recorder=$!
  # A heavily loaded hosted runner can deliver PulseAudio monitor samples at
  # less than wall-clock speed. Wait for the requested amount of stereo s16le
  # data instead of shortening the evidence window, but retain a bounded
  # timeout so a stalled recorder still fails the downstream sample check.
  expected_bytes=$((seconds * 48000 * 2 * 2))
  deadline=$((SECONDS + seconds + 12))
  while :; do
    captured_bytes=$(stat -c %s "$raw" 2>/dev/null || printf '0')
    if (( captured_bytes >= expected_bytes || SECONDS >= deadline )); then break; fi
    sleep 0.2
  done
  kill -TERM "$recorder" 2>/dev/null || true
  wait "$recorder" 2>/dev/null || true
}

# A newly-created legacy OpenAL stream can be PLAYING before PipeWire exposes
# its first monitor samples. Do not weaken the program-tone threshold: take
# one bounded replacement capture if the initial post-transition window was
# still mostly the graph's startup delay.
capture_audible_transition() {
  local sink=$1
  local raw=$2
  local metrics=$3
  local seconds=${4:-4}
  local attempt
  for attempt in 1 2; do
    capture_audio_sink "$sink" "$raw" "$seconds"
    if audio_capture_is_audible "$raw" "$metrics"; then return 0; fi
    if (( attempt == 1 )); then sleep 1; fi
  done
  return 1
}

audio_control_sequence=0
send_audio_control() {
  local label=$1
  local role=$2
  local command=$3
  audio_control_sequence=$((audio_control_sequence + 1))
  printf '%s|%s\n' "$audio_control_sequence" "$command" \
    > "$output_root/$label.audio-$role.control"
}

wait_for_marker_after() {
  local file=$1
  local first_line=$2
  local marker=$3
  local timeout=${4:-60}
  local deadline=$((SECONDS + timeout))
  while ! tail -n "+$((first_line + 1))" "$file" 2>/dev/null | grep -Fq "$marker"; do
    if (( SECONDS >= deadline )); then return 1; fi
    sleep 1
  done
}

wait_for_latest_marker() {
  local file=$1
  local marker_prefix=$2
  local expected=$3
  local timeout=${4:-60}
  local deadline=$((SECONDS + timeout))
  while ! grep -F "$marker_prefix" "$file" 2>/dev/null | tail -n 1 | grep -Fq "$expected"; do
    if (( SECONDS >= deadline )); then return 1; fi
    sleep 1
  done
}

scenario_follower_pid=""
run_audio_control_scenarios() {
  local label=$1
  local target_dir=$2
  local java_home=$3
  local port=$4
  local sink_leader=$5
  local sink_follower=$6
  local leader_pid=$7
  local follower_pid=$8
  local rcon_port=$9
  local rcon_password=${10}
  local fifo_fd=${11}
  local server_log=${12}
  local leader_log="$output_root/$label.audio-leader.console.log"
  local follower_log="$output_root/$label.audio-follower.console.log"
  local scenario_evidence="$output_root/$label.audio-scenarios.evidence.txt"
  local raw="$output_root/$label.audio-scenario.s16le"
  local metrics="$output_root/$label.audio-scenario.metrics.txt"
  local first result=0
  scenario_follower_pid=$follower_pid

  : > "$scenario_evidence"
  if uses_console_control "$label"; then
    # Writing to stdin only queues the command. The client's control packet can
    # otherwise reach the server before promotion and be rejected. A subsequent
    # server message acknowledges that the console queue has processed the op.
    first=$(wc -l < "$leader_log")
    printf 'op JammarrAudioA\ntell JammarrAudioA JAMMARR_ACCEPTANCE_AUDIO_OPERATOR_READY\n' >&"$fifo_fd"
    if ! wait_for_marker_after "$leader_log" "$first" 'JAMMARR_ACCEPTANCE_AUDIO_OPERATOR_READY' 60; then
      echo "$label: audio scenario operator promotion was not acknowledged" >&2
      return 1
    fi
  elif ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
      'op JammarrAudioA' > /dev/null; then
    echo "$label: unable to promote the audio scenario leader" >&2
    return 1
  fi

  # A transient first leader can enqueue the seed track before its local audio
  # backend fails. Normalize the shared queue after any clean-launch retry so
  # every subsequent assertion starts from one known track instead of silently
  # accepting duplicate state left by the failed client.
  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'control:clear'
  if ! wait_for_marker_after "$leader_log" "$first" 'Acceptance playback state: status=IDLE' 60; then
    echo "$label: could not normalize the shared queue before audio scenarios" >&2; return 1
  fi
  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'queue:42'
  if ! wait_for_marker_after "$leader_log" "$first" 'queue=42' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: PLAYING' 120; then
    echo "$label: normalized seed track did not return to audible playback" >&2; return 1
  fi
  printf 'Normalized the retry-safe scenario queue to one audible seed track.\n' >> "$scenario_evidence"

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'queue:43'
  if ! wait_for_marker_after "$leader_log" "$first" 'queue=42,43' 60; then
    echo "$label: queue scenario did not append track 43" >&2; return 1
  fi
  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'queue:44'
  if ! wait_for_marker_after "$leader_log" "$first" 'queue=42,43,44' 60; then
    echo "$label: queue scenario did not append track 44" >&2; return 1
  fi
  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'control:move_down:1:43'
  if ! wait_for_marker_after "$leader_log" "$first" 'queue=42,44,43' 60; then
    echo "$label: real-client reorder was not reflected by server state" >&2; return 1
  fi

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'control:pause'
  if ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: PAUSED' 60; then
    echo "$label: pause did not reach the client audio backend" >&2; return 1
  fi
  # Legacy Paulscode applies pause on its command thread. Exclude that bounded
  # transition from the silence window while keeping the silence threshold
  # strict for the complete capture.
  sleep 1
  capture_audio_sink "$sink_leader" "$raw" 4
  if ! audio_capture_is_silent "$raw" "$metrics"; then
    echo "$label: paused leader still emitted program audio" >&2; return 1
  fi
  printf 'Pause produced captured silence.\n' >> "$scenario_evidence"

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'control:resume'
  if ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: PLAYING' 60; then
    echo "$label: resume did not restore client playback" >&2; return 1
  fi
  # Legacy Paulscode keeps up to one second of already-scaled PCM queued.
  # Start capture only after that prior gain window has certainly drained.
  sleep 2
  capture_audio_sink "$sink_leader" "$raw" 4
  if ! audio_capture_is_audible "$raw" "$metrics"; then
    echo "$label: resumed leader did not emit program audio" >&2; return 1
  fi
  printf 'Resume restored captured program audio.\n' >> "$scenario_evidence"

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'volume:0.2'
  if ! wait_for_marker_after "$leader_log" "$first" 'Acceptance backend volume applied: 0.2' 60; then
    echo "$label: reduced local volume did not reach the audio backend" >&2; return 1
  fi
  sleep 1
  capture_audio_sink "$sink_leader" "$raw" 4
  if ! audio_capture_is_attenuated "$raw" "$metrics" \
      "$output_root/$label.audio-leader.metrics.txt"; then
    echo "$label: reduced local volume did not produce a sustained attenuated signal" >&2; return 1
  fi
  grep -E 'mean_volume:|max_volume:' "$metrics" | tail -n 2 >> "$scenario_evidence"

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'mute'
  if ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: DISABLED' 60; then
    echo "$label: local mute did not disable the client backend" >&2; return 1
  fi
  capture_audio_sink "$sink_leader" "$raw" 3
  if ! audio_capture_is_silent "$raw" "$metrics"; then
    echo "$label: locally muted leader still emitted program audio" >&2; return 1
  fi
  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'unmute'
  if ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: PLAYING' 60; then
    echo "$label: local unmute did not restore playback" >&2; return 1
  fi
  send_audio_control "$label" leader 'volume:1.0'
  sleep 1
  capture_audio_sink "$sink_leader" "$raw" 4
  if ! audio_capture_is_audible "$raw" "$metrics"; then
    echo "$label: unmuted leader did not emit program audio" >&2; return 1
  fi
  printf 'Local mute produced silence and unmute restored audio.\n' >> "$scenario_evidence"

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'reload'
  if ! wait_for_marker_after "$leader_log" "$first" 'Acceptance resource reload complete: success=true' 120 \
      || ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: PLAYING' 120; then
    echo "$label: sound/resource reload did not recover to PLAYING" >&2; return 1
  fi
  sleep 1
  capture_audio_sink "$sink_leader" "$raw" 4
  if ! audio_capture_is_audible "$raw" "$metrics"; then
    echo "$label: sound/resource reload recovered state without audible output" >&2; return 1
  fi
  printf 'Resource and sound-engine reload recovered audible playback.\n' >> "$scenario_evidence"

  local transcodes_before transcodes_after
  first=$(wc -l < "$server_log")
  printf 'offline\n' > "$fake_plex_state"
  if uses_console_control "$label"; then
    printf 'jammarr reload\n' >&"$fifo_fd"
  else
    if ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
        'jammarr reload' > /dev/null; then
      printf 'online\n' > "$fake_plex_state"
      return 1
    fi
  fi
  if ! wait_for_marker_after "$server_log" "$first" 'Jammarr Plex validation failed' 60; then
    printf 'online\n' > "$fake_plex_state"
    echo "$label: fake Plex outage was not observed by the live server" >&2; return 1
  fi
  transcodes_before=$(awk -F '\t' '$2 == "/music/:/transcode/universal/start.mp3" { count++ } END { print count + 0 }' \
    "$fake_plex_request_log")
  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'control:skip'
  if ! wait_for_marker_after "$leader_log" "$first" \
      'title=Gate Track 44 origin=MANUAL queue=44,43' 120 \
      || ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: PLAYING' 120; then
    printf 'online\n' > "$fake_plex_state"
    echo "$label: skip did not advance into the cached pending track during Plex outage" >&2; return 1
  fi
  transcodes_after=$(awk -F '\t' '$2 == "/music/:/transcode/universal/start.mp3" { count++ } END { print count + 0 }' \
    "$fake_plex_request_log")
  if [[ "$transcodes_after" != "$transcodes_before" ]]; then
    printf 'online\n' > "$fake_plex_state"
    echo "$label: cache-backed outage playback unexpectedly requested a new Plex transcode" >&2; return 1
  fi
  sleep 1
  capture_audio_sink "$sink_leader" "$raw" 4
  if ! audio_capture_is_audible "$raw" "$metrics"; then
    printf 'online\n' > "$fake_plex_state"
    echo "$label: cached outage track reached PLAYING without audible output" >&2; return 1
  fi
  printf 'Plex outage observed; skip used cached track with no new transcode and remained audible.\n' \
    >> "$scenario_evidence"

  first=$(wc -l < "$server_log")
  printf 'online\n' > "$fake_plex_state"
  if uses_console_control "$label"; then
    printf 'jammarr reload\n' >&"$fifo_fd"
  else
    run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
      'jammarr reload' > /dev/null || return 1
  fi
  if ! wait_for_marker_after "$server_log" "$first" 'Jammarr connected to Plex; sonic capability is READY' 60; then
    echo "$label: server did not recover Plex and sonic readiness after the controlled outage" >&2; return 1
  fi

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'control:clear'
  if ! wait_for_marker_after "$leader_log" "$first" \
      'Acceptance playback state: status=IDLE' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: NO_STREAM' 60; then
    echo "$label: clear did not stop shared playback and the client stream" >&2; return 1
  fi

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'station:library-shuffle'
  if ! wait_for_marker_after "$leader_log" "$first" \
      'Acceptance station state: type=LIBRARY_SHUFFLE active=true' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'origin=STATION' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: PLAYING' 60; then
    echo "$label: general Library Shuffle station did not generate audible playback" >&2; return 1
  fi
  sleep 1
  if ! capture_audible_transition "$sink_leader" "$raw" "$metrics" 4; then
    echo "$label: Library Shuffle reached PLAYING without audible output" >&2; return 1
  fi
  printf 'General Library Shuffle generated audible station playback.\n' >> "$scenario_evidence"

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'adventure:42:49'
  if ! wait_for_marker_after "$leader_log" "$first" \
      'Acceptance station state: type=SONIC_ADVENTURE active=true' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'origin=ADVENTURE' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: PLAYING' 60; then
    echo "$label: Sonic Adventure did not generate audible waypoint playback" >&2; return 1
  fi
  sleep 1
  if ! capture_audible_transition "$sink_leader" "$raw" "$metrics" 4; then
    echo "$label: Sonic Adventure reached PLAYING without audible output" >&2; return 1
  fi
  printf 'Sonic Adventure generated an audible analyzed-track path.\n' >> "$scenario_evidence"

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'fault:underrun'
  if ! wait_for_marker_after "$leader_log" "$first" 'acceptance decoder starvation' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: RECOVERING' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: PLAYING' 120; then
    echo "$label: deterministic decoder starvation did not recover through RECOVERING to PLAYING" >&2
    return 1
  fi
  sleep 1
  capture_audio_sink "$sink_leader" "$raw" 4
  if ! audio_capture_is_audible "$raw" "$metrics"; then
    echo "$label: underrun recovery reached PLAYING without audible output" >&2; return 1
  fi
  printf 'Injected decoder starvation recovered through RECOVERING to audible playback.\n' \
    >> "$scenario_evidence"

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'fault:drift'
  if ! wait_for_marker_after "$leader_log" "$first" 'Acceptance clock drift injected beyond' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'clock drift' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: RECOVERING' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: PLAYING' 120; then
    echo "$label: deterministic clock drift did not rebuffer and return to PLAYING" >&2
    return 1
  fi
  sleep 1
  capture_audio_sink "$sink_leader" "$raw" 4
  if ! audio_capture_is_audible "$raw" "$metrics"; then
    echo "$label: drift correction reached PLAYING without audible output" >&2; return 1
  fi
  printf 'Injected clock drift exceeded policy and recovered to audible synchronized playback.\n' \
    >> "$scenario_evidence"

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'fault:exhaust-retries'
  if ! wait_for_marker_after "$leader_log" "$first" 'acceptance forced recovery failure' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: ERROR' 60; then
    echo "$label: forced consecutive recovery failures did not reach final ERROR state" >&2
    return 1
  fi
  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'retry'
  if ! wait_for_marker_after "$leader_log" "$first" 'manual retry' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: PLAYING' 120; then
    echo "$label: manual retry did not recover final ERROR state to PLAYING" >&2
    return 1
  fi
  sleep 1
  capture_audio_sink "$sink_leader" "$raw" 4
  if ! audio_capture_is_audible "$raw" "$metrics"; then
    echo "$label: manual retry reached PLAYING without audible output" >&2; return 1
  fi
  printf 'Consecutive failures reached final ERROR; manual retry restored audible playback.\n' \
    >> "$scenario_evidence"

  terminate_client_launch "$follower_pid" 20 || result=1
  active_audio_client_pids=("$leader_pid")
  if ! launch_audio_client "$label" "$target_dir" "$java_home" "$port" follower JammarrAudioB "$sink_follower"; then return 1; fi
  follower_pid=$ready_audio_client_pid
  scenario_follower_pid=$follower_pid
  sleep 1
  capture_audio_sink "$sink_follower" "$raw" 4
  if ! audio_capture_is_audible "$raw" "$metrics"; then
    echo "$label: reconnected follower reached PLAYING without audible output" >&2; return 1
  fi
  printf 'Follower reconnect restored synchronized audible playback.\n' >> "$scenario_evidence"
  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'control:clear'
  if ! wait_for_marker_after "$leader_log" "$first" 'Acceptance playback state: status=IDLE' 60 \
      || ! wait_for_marker_after "$leader_log" "$first" 'Acceptance audio state: NO_STREAM' 60; then
    echo "$label: final clear did not leave the shared queue and audio stream idle" >&2; return 1
  fi
  printf 'Final clear left shared playback and client audio idle.\n' >> "$scenario_evidence"
  return "$result"
}

run_legacy_cold_start_stress() {
  local label=$1
  local target_dir=$2
  local java_home=$3
  local port=$4
  local sink=$5
  local leader_pid=$6
  local follower_pid=$7
  local rcon_port=$8
  local rcon_password=$9
  local fifo_fd=${10}
  local server_log=${11}
  local evidence="$output_root/$label.cold-start-stress.evidence.txt"
  local resources="$output_root/$label.cold-start-stress.resources.tsv"
  local raw="$output_root/$label.cold-start-stress.s16le"
  local metrics="$output_root/$label.cold-start-stress.metrics.txt"
  local index role username pid client_log first reload result=0 client_java server_java

  local leader_log="$output_root/$label.audio-leader.console.log"
  local follower_log="$output_root/$label.audio-follower.console.log"
  first=$(wc -l < "$leader_log")
  if uses_console_control "$label"; then
    printf 'jammarr clear\n' >&"$fifo_fd"
  elif ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
      'jammarr clear' > /dev/null; then
    return 1
  fi
  if ! wait_for_marker_after "$leader_log" "$first" \
      'Acceptance playback state: status=IDLE' 60; then
    echo "$label: cold-start precondition could not clear shared playback" >&2
    return 1
  fi
  first=$(wc -l < "$follower_log")
  printf 'stall\n' > "$fake_plex_state"
  send_audio_control "$label" follower 'browse:search:disconnect-cycle'
  if ! wait_for_marker_after "$follower_log" "$first" \
      'Acceptance control applied: browse:search:disconnect-cycle' 30; then
    printf 'online\n' > "$fake_plex_state"
    echo "$label: follower did not begin the pending browse disconnect cycle" >&2
    return 1
  fi
  if uses_console_control "$label"; then
    printf 'kick JammarrAudioB Jammarr acceptance disconnect cycle\n' >&"$fifo_fd"
  elif ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
      'kick JammarrAudioB Jammarr acceptance disconnect cycle' > /dev/null; then
    printf 'online\n' > "$fake_plex_state"
    return 1
  fi
  if ! wait_for_marker_after "$follower_log" "$first" \
      'Acceptance browse request completed: disconnect' 30; then
    printf 'online\n' > "$fake_plex_state"
    echo "$label: pending browse did not terminate on client disconnect" >&2
    return 1
  fi
  printf 'online\n' > "$fake_plex_state"
  terminate_client_launch "$follower_pid" 20 || return 1
  # The browse timeout/cancellation probes deliberately leave loopback HTTP
  # requests sleeping for 30 seconds. Drain that deterministic window before
  # the final validation so a late completion cannot overwrite READY after
  # the first cold client has sent its one automatic queue request.
  sleep 35
  first=$(wc -l < "$server_log")
  if uses_console_control "$label"; then
    printf 'jammarr reload\n' >&"$fifo_fd"
  elif ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
      'jammarr reload' > /dev/null; then
    return 1
  fi
  if ! wait_for_marker_after "$server_log" "$first" \
      'Jammarr connected to Plex; sonic capability is READY' 60; then
    echo "$label: Plex did not recover before the cold-start loop" >&2
    return 1
  fi
  terminate_client_launch "$leader_pid" 20 || return 1
  active_audio_client_pids=()
  : > "$evidence"
  printf 'cycle\trole\tpid\trss_kib\tthreads\tfd_count\telapsed_seconds\n' > "$resources"

  for ((index = 1; index <= legacy_cold_start_count; index++)); do
    printf -v role 'cold-%02d' "$index"
    printf -v username 'JammarrCold%02d' "$index"
    client_log="$output_root/$label.audio-$role.console.log"
    if ! launch_audio_client "$label" "$target_dir" "$java_home" "$port" \
        "$role" "$username" "$sink"; then
      echo "$label: cold-start client $index did not reach audible playback" >&2
      return 1
    fi
    pid=$ready_audio_client_pid
    sleep 1
    capture_audio_sink "$sink" "$raw" 4
    if ! audio_capture_is_audible "$raw" "$metrics"; then
      echo "$label: cold-start client $index reached PLAYING without audible output" >&2
      return 1
    fi

    if (( index == 1 )); then
      first=$(wc -l < "$client_log")
      if uses_console_control "$label"; then
        printf 'jammarr acceptance-dimension %s -1\n' "$username" >&"$fifo_fd"
      elif ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
          "jammarr acceptance-dimension $username -1" > /dev/null; then
        return 1
      fi
      if ! wait_for_latest_marker "$client_log" \
          'Acceptance lifecycle dimension active:' 'Acceptance lifecycle dimension active: -1' 60; then
        echo "$label: cold-start client did not enter the Nether during active playback" >&2
        return 1
      fi
      sleep 1
      capture_audio_sink "$sink" "$raw" 4
      if ! audio_capture_is_audible "$raw" "$metrics"; then
        echo "$label: Nether dimension transfer did not preserve audible playback" >&2
        return 1
      fi

      first=$(wc -l < "$client_log")
      if uses_console_control "$label"; then
        printf 'jammarr acceptance-dimension %s 0\n' "$username" >&"$fifo_fd"
      elif ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
          "jammarr acceptance-dimension $username 0" > /dev/null; then
        return 1
      fi
      if ! wait_for_latest_marker "$client_log" \
          'Acceptance lifecycle dimension active:' 'Acceptance lifecycle dimension active: 0' 60; then
        echo "$label: cold-start client did not return to the Overworld during active playback" >&2
        return 1
      fi
      sleep 1
      capture_audio_sink "$sink" "$raw" 4
      if ! audio_capture_is_audible "$raw" "$metrics"; then
        echo "$label: Overworld return did not preserve audible playback" >&2
        return 1
      fi

      first=$(wc -l < "$client_log")
      if uses_console_control "$label"; then
        printf 'jammarr acceptance-kill %s\n' "$username" >&"$fifo_fd"
      elif ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
          "jammarr acceptance-kill $username" > /dev/null; then
        return 1
      fi
      if ! wait_for_marker_after "$client_log" "$first" \
          'Acceptance lifecycle death screen reached' 60; then
        echo "$label: cold-start client did not reach the death state" >&2
        return 1
      fi
      send_audio_control "$label" "$role" 'lifecycle:respawn'
      if ! wait_for_marker_after "$client_log" "$first" \
          'Acceptance lifecycle respawn requested' 30; then
        echo "$label: cold-start client did not request respawn" >&2
        return 1
      fi
      sleep 3
      capture_audio_sink "$sink" "$raw" 4
      if ! audio_capture_is_audible "$raw" "$metrics"; then
        echo "$label: death/respawn cycle did not recover audible playback" >&2
        return 1
      fi
      printf 'Active playback survived Nether/Overworld and death/respawn cycles.\n' >> "$evidence"
    fi

    for reload in 1 2; do
      first=$(wc -l < "$client_log")
      send_audio_control "$label" "$role" reload
      if ! wait_for_marker_after "$client_log" "$first" \
          'Acceptance resource reload complete: success=true' 120 \
          || ! wait_for_marker_after "$client_log" "$first" \
          'Acceptance audio state: PLAYING' 120; then
        echo "$label: cold-start client $index did not recover from sound reload $reload" >&2
        return 1
      fi
      sleep 1
      capture_audio_sink "$sink" "$raw" 4
      if ! audio_capture_is_audible "$raw" "$metrics"; then
        echo "$label: cold-start client $index reload $reload recovered without audible output" >&2
        return 1
      fi
    done

    if grep -Eiq 'Only one OpenAL context|Switching to No Sound|Silent Mode|SoundSystem did not load|UnsatisfiedLinkError|Unreported exception thrown|#@!@# Game crashed!|Description: Unexpected error' \
        "$client_log"; then
      echo "$label: cold-start client $index logged an OpenAL, native-linkage, or crash failure" >&2
      return 1
    fi
    client_java=$(minecraft_java_pid "$pid" || true)
    server_java=$(ss -ltnp "sport = :$port" \
      | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -n 1)
    if [[ -z "$client_java" || -z "$server_java" ]]; then
      echo "$label: could not resolve the production client/server Java processes for resource evidence" >&2
      return 1
    fi
    read -r client_rss client_threads client_elapsed < <(ps -o rss=,nlwp=,etimes= -p "$client_java")
    read -r server_rss server_threads server_elapsed < <(ps -o rss=,nlwp=,etimes= -p "$server_java")
    printf '%d\tclient\t%s\t%s\t%s\t%s\t%s\n' "$index" "$client_java" \
      "$client_rss" "$client_threads" "$(find "/proc/$client_java/fd" -mindepth 1 -maxdepth 1 | wc -l)" \
      "$client_elapsed" >> "$resources"
    printf '%d\tserver\t%s\t%s\t%s\t%s\t%s\n' "$index" "$server_java" \
      "$server_rss" "$server_threads" "$(find "/proc/$server_java/fd" -mindepth 1 -maxdepth 1 | wc -l)" \
      "$server_elapsed" >> "$resources"
    first=$(wc -l < "$client_log")
    if uses_console_control "$label"; then
      printf 'jammarr clear\n' >&"$fifo_fd"
    elif ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
        'jammarr clear' > /dev/null; then
      echo "$label: server control could not clear playback after cold start $index" >&2
      return 1
    fi
    if ! wait_for_marker_after "$client_log" "$first" \
        'Acceptance playback state: status=IDLE' 60; then
      echo "$label: cold-start client $index could not clear playback before teardown" >&2
      return 1
    fi
    terminate_client_launch "$pid" 20 || return 1
    active_audio_client_pids=()
    if group_alive "$pid"; then
      echo "$label: cold-start client $index left a process group alive" >&2
      return 1
    fi
    {
      printf 'Cold start %d/%d: production client reached audible PLAYING and recovered two audible sound reloads.\n' \
        "$index" "$legacy_cold_start_count"
      grep -F 'Acceptance resource reload complete: success=true' "$client_log" | tail -n 2
    } >> "$evidence"
  done

  printf 'Completed %d production-client cold starts with %d audible sound reloads and clean teardown.\n' \
    "$legacy_cold_start_count" "$((legacy_cold_start_count * 2))" >> "$evidence"
  {
    printf 'Retained per-cycle client/server RSS, thread, file-descriptor, and elapsed-time samples.\n'
    printf 'Server keep-up warnings: %d\n' "$(grep -Eic "Can't keep up|server is overloaded" "$output_root/$label.console.log" || true)"
  } >> "$evidence"
  return "$result"
}

run_legacy_browse_stress() {
  local label=$1
  local leader_log="$output_root/$label.audio-leader.console.log"
  local server_log=$2
  local fifo_fd=$3
  local rcon_port=$4
  local rcon_password=$5
  local evidence="$output_root/$label.browse-stress.evidence.txt"
  local first
  : > "$evidence"

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'browse:search:Gate'
  if ! wait_for_marker_after "$leader_log" "$first" \
      'Acceptance browse request completed: kind=SEARCH query=Gate items=8' 60; then
    echo "$label: Search tab did not complete a successful request" >&2; return 1
  fi
  printf 'Search success reached a terminal state with eight results.\n' >> "$evidence"

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'browse:search:no-match'
  if ! wait_for_marker_after "$leader_log" "$first" \
      'Acceptance browse request completed: kind=SEARCH query=no-match items=0' 60; then
    echo "$label: Search tab did not complete an empty request" >&2; return 1
  fi
  printf 'Search empty result reached a terminal state.\n' >> "$evidence"

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'browse:queue'
  if ! wait_for_marker_after "$leader_log" "$first" \
      'Acceptance Queue tab completed locally:' 30; then
    echo "$label: Queue tab did not complete its local snapshot" >&2; return 1
  fi
  printf 'Queue snapshot completed without a network wait.\n' >> "$evidence"

  printf 'offline\n' > "$fake_plex_state"
  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'browse:search:failure-cycle'
  if ! wait_for_marker_after "$leader_log" "$first" \
      'Acceptance browse request completed: error' 60; then
    printf 'online\n' > "$fake_plex_state"
    echo "$label: Search tab did not terminate after a Plex failure" >&2; return 1
  fi
  printf 'Search Plex failure reached a terminal error state.\n' >> "$evidence"

  first=$(wc -l < "$server_log")
  printf 'online\n' > "$fake_plex_state"
  if uses_console_control "$label"; then
    printf 'jammarr reload\n' >&"$fifo_fd"
  else
    run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" 'jammarr reload' > /dev/null || return 1
  fi
  if ! wait_for_marker_after "$server_log" "$first" \
      'Jammarr connected to Plex; sonic capability is READY' 60; then
    echo "$label: Plex did not recover after browse failure" >&2; return 1
  fi

  printf 'stall\n' > "$fake_plex_state"
  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'browse:search:timeout-cycle'
  if ! wait_for_marker_after "$leader_log" "$first" \
      'Acceptance control applied: browse:search:timeout-cycle' 30; then
    printf 'online\n' > "$fake_plex_state"; return 1
  fi
  send_audio_control "$label" leader 'browse:expire'
  if ! wait_for_marker_after "$leader_log" "$first" \
      'Acceptance browse request completed: timeout' 30; then
    printf 'online\n' > "$fake_plex_state"
    echo "$label: Search tab remained pending after its timeout" >&2; return 1
  fi
  printf 'Search timeout reached a terminal retryable state.\n' >> "$evidence"

  first=$(wc -l < "$leader_log")
  send_audio_control "$label" leader 'browse:search:cancellation-cycle'
  if ! wait_for_marker_after "$leader_log" "$first" \
      'Acceptance control applied: browse:search:cancellation-cycle' 30; then
    printf 'online\n' > "$fake_plex_state"; return 1
  fi
  send_audio_control "$label" leader 'browse:cancel'
  if ! wait_for_marker_after "$leader_log" "$first" \
      'Acceptance browse request completed: cancellation' 30; then
    printf 'online\n' > "$fake_plex_state"
    echo "$label: Search tab remained pending after cancellation" >&2; return 1
  fi
  printf 'online\n' > "$fake_plex_state"
  printf 'Search cancellation reached a terminal state.\n' >> "$evidence"
}

run_mixed_vanilla_audio() {
  local label=$1
  local port=$2
  local rcon_port=$3
  local rcon_password=$4
  local fifo_fd=$5
  local server_log=$6
  local sink_master=$7
  local leader_pid=$8
  local follower_pid=$9
  local vanilla_evidence="$output_root/$label.mixed-vanilla-client.evidence.txt"
  local default_evidence="$output_root/$label.mixed-client-audio.evidence.txt"
  local churn_evidence="$output_root/$label.mixed-client-churn.evidence.txt"
  local resource_evidence="$output_root/$label.mixed-client-churn-resources.tsv"
  local post_diagnostics="$output_root/$label.mixed-vanilla-client.post-disconnect-diagnostics.txt"
  local churn_requested=0 cycle=1 completed=0 result=0 playback_unhealthy=0
  local started=$SECONDS deadline=$((SECONDS + vanilla_churn_min_seconds)) padded username
  local raw_leader raw_follower metrics_leader metrics_follower timing classification evidence
  local leader_trace_dir follower_trace_dir leader_trace_tail follower_trace_tail
  local leader_feed_timing follower_feed_timing trace_bytes
  local server_pid rss_kib descriptors
  local baseline_rss_kib=0 baseline_descriptors=0 egress_items egress_bytes work_active work_queued
  local keep_up_before keep_up_after

  if (( vanilla_churn_cycles > 1 || vanilla_churn_min_seconds > 0 )); then
    churn_requested=1
    : > "$churn_evidence"
    printf 'cycle\telapsed_seconds\tserver_rss_kib\tserver_fds\tegress_items\tegress_bytes\twork_active\twork_queued\n' \
      > "$resource_evidence"
  fi
  keep_up_before=$(grep -Fc "Can't keep up!" "$server_log" 2>/dev/null || true)

  while (( cycle <= vanilla_churn_cycles || SECONDS < deadline )); do
    playback_unhealthy=0
    printf -v padded '%05d' "$cycle"
    if (( churn_requested )); then
      raw_leader="$output_root/$label.mixed-client-churn-$padded.leader.s16le"
      raw_follower="$output_root/$label.mixed-client-churn-$padded.follower.s16le"
      metrics_leader="$output_root/$label.mixed-client-churn-$padded.leader.metrics.txt"
      metrics_follower="$output_root/$label.mixed-client-churn-$padded.follower.metrics.txt"
      timing="$output_root/$label.mixed-client-churn-$padded.timing.json"
      classification="$output_root/$label.mixed-client-churn-$padded.classification.json"
      evidence="$output_root/$label.mixed-client-churn-$padded.evidence.txt"
      username="MixVan$padded"
    else
      raw_leader="$output_root/$label.mixed-client-audio.leader.s16le"
      raw_follower="$output_root/$label.mixed-client-audio.follower.s16le"
      metrics_leader="$output_root/$label.mixed-client-audio.leader.metrics.txt"
      metrics_follower="$output_root/$label.mixed-client-audio.follower.metrics.txt"
      timing="$output_root/$label.mixed-client-audio-timing.json"
      classification="$output_root/$label.mixed-client-audio-classification.json"
      evidence=$default_evidence
      username=MixedVanilla
    fi

    if ! run_vanilla_client "$label" "$port" "$server_log" \
        "$rcon_port" "$rcon_password" "$fifo_fd" \
        2 1 2 mixed-vanilla-client "$username" "$vanilla_connected_seconds" \
        "$sink_master" "$raw_leader" "$raw_follower" false; then
      result=1
    fi

    report_private_audio_graph_warnings "$label"
    if (( result == 0 )) \
        && { audio_log_has_terminal_backend_failure "$output_root/$label.audio-leader.console.log" \
          || audio_log_has_terminal_backend_failure "$output_root/$label.audio-follower.console.log" \
          || ! group_alive "$leader_pid" || ! group_alive "$follower_pid" \
          || ! latest_audio_state_is "$output_root/$label.audio-leader.console.log" PLAYING \
          || ! latest_audio_state_is "$output_root/$label.audio-follower.console.log" PLAYING; }; then
      echo "$label: a matching client stopped playback during vanilla coexistence cycle $cycle" >&2
      playback_unhealthy=1
    fi
    if (( result == 0 )) && ! audio_capture_is_audible "$raw_leader" "$metrics_leader"; then
      echo "$label: leader emitted no program audio during vanilla coexistence cycle $cycle" >&2
      result=1
    fi
    if (( result == 0 )) && ! audio_capture_is_audible "$raw_follower" "$metrics_follower"; then
      echo "$label: follower emitted no program audio during vanilla coexistence cycle $cycle" >&2
      result=1
    fi
    if (( result == 0 )); then
      if ! python3 "$repo_root/scripts/analyze-audio-timing.py" \
          "$raw_leader" --reference "$raw_follower" --minimum-duration-ms 10000 \
          > "$timing"; then
        leader_trace_dir="$output_root/$label.audio-leader/pcm-trace"
        follower_trace_dir="$output_root/$label.audio-follower/pcm-trace"
        if [[ -d "$leader_trace_dir" && -d "$follower_trace_dir" ]]; then
          leader_trace_tail="${timing%.json}.leader-fed-tail.s16le"
          follower_trace_tail="${timing%.json}.follower-fed-tail.s16le"
          leader_feed_timing="${timing%.json}.leader-fed-timing.json"
          follower_feed_timing="${timing%.json}.follower-fed-timing.json"
          # Retain a bounded recent window from each acceptance-only PCM feed.
          # At 44.1 kHz stereo s16le, 30 seconds is 5,292,000 bytes. This
          # captures the failing rendered interval plus backend queue lead
          # without repeatedly analyzing an hours-long trace.
          trace_bytes=$((30 * 44100 * 2 * 2))
          if python3 "$repo_root/scripts/extract-pcm-trace-tail.py" \
              "$leader_trace_dir" "$leader_trace_tail" --bytes "$trace_bytes" \
              > "${leader_feed_timing%.json}.extract.json" \
              && python3 "$repo_root/scripts/extract-pcm-trace-tail.py" \
              "$follower_trace_dir" "$follower_trace_tail" --bytes "$trace_bytes" \
              > "${follower_feed_timing%.json}.extract.json"; then
            python3 "$repo_root/scripts/analyze-audio-timing.py" "$leader_trace_tail" \
              --sample-rate 44100 --minimum-duration-ms 10000 \
              > "$leader_feed_timing" || true
            python3 "$repo_root/scripts/analyze-audio-timing.py" "$follower_trace_tail" \
              --sample-rate 44100 --minimum-duration-ms 10000 \
              > "$follower_feed_timing" || true
            python3 "$repo_root/scripts/classify-shared-clock-audio.py" "$timing" \
              --leader-feed-report "$leader_feed_timing" \
              --follower-feed-report "$follower_feed_timing" \
              > "$classification" || true
          else
            python3 "$repo_root/scripts/classify-shared-clock-audio.py" "$timing" \
              > "$classification" || true
          fi
        else
          python3 "$repo_root/scripts/classify-shared-clock-audio.py" "$timing" \
            > "$classification" || true
        fi
        echo "$label: shared-clock client audio timing failed during vanilla coexistence cycle $cycle; see $classification" >&2
        result=1
      else
        python3 "$repo_root/scripts/classify-shared-clock-audio.py" "$timing" \
          > "$classification" || result=1
      fi
    fi
    if (( result == 0 )); then
      {
        cat "$vanilla_evidence"
        printf 'Leader rendered metrics:\n'
        grep -E 'mean_volume:|max_volume:' "$metrics_leader" | tail -n 2
        printf 'Follower rendered metrics:\n'
        grep -E 'mean_volume:|max_volume:' "$metrics_follower" | tail -n 2
        sed -n '/"duration_ms"\|"marker_count"\|"max_marker_interval_error_ms"\|"marker_sequence_mismatches"\|"max_marker_overlap_ms"\|"max_silence_ms"/p' "$timing"
        sed -n '/"classification"\|"independent_recorder_ambiguity_removed"/p' "$classification"
        printf 'Two matching clients remained PLAYING while the artifact-free vanilla client was connected.\n'
      } > "$evidence"
    fi

    # Preserve the rendered and pre-backend timing classification even when a
    # client ends the capture in RECOVERING or otherwise unhealthy. The state
    # remains a hard failure, but its audio evidence is what distinguishes a
    # backend interruption from transport or chunk-order corruption.
    if (( playback_unhealthy != 0 )); then result=1; fi

    if (( result == 0 && churn_requested )); then
      server_pid=$(minecraft_java_pid "$active_server_group" || true)
      if [[ -z "$server_pid" || ! -r "/proc/$server_pid/status" ]]; then
        echo "$label: cannot inspect the live server process during mixed-client churn" >&2
        result=1
      else
        rss_kib=$(awk '/^VmRSS:/ { print $2 }' "/proc/$server_pid/status")
        descriptors=$(find "/proc/$server_pid/fd" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l)
        egress_items=$(sed -n 's/.*egressItems=\([0-9][0-9]*\).*/\1/p' "$post_diagnostics")
        egress_bytes=$(sed -n 's/.*egressBytes=\([0-9][0-9]*\).*/\1/p' "$post_diagnostics")
        work_active=$(sed -n 's/.*workActive=\([0-9][0-9]*\).*/\1/p' "$post_diagnostics")
        work_queued=$(sed -n 's/.*workQueued=\([0-9][0-9]*\).*/\1/p' "$post_diagnostics")
        if [[ -z "$rss_kib" || -z "$egress_items" || -z "$egress_bytes" \
            || -z "$work_active" || -z "$work_queued" ]]; then
          echo "$label: incomplete server resource sample during mixed-client churn" >&2
          result=1
        elif (( egress_items > 1024 || egress_bytes > 16 * 1024 * 1024 \
            || work_active > 3 || work_queued > 64 )); then
          echo "$label: bounded server resource limit exceeded during mixed-client churn" >&2
          result=1
        else
          if (( completed == 0 )); then
            baseline_rss_kib=$rss_kib
            baseline_descriptors=$descriptors
          elif (( rss_kib > baseline_rss_kib + 1048576 \
              || descriptors > baseline_descriptors + 64 )); then
            echo "$label: server resources grew beyond the churn allowance" >&2
            result=1
          fi
          printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$cycle" "$((SECONDS - started))" "$rss_kib" "$descriptors" \
            "$egress_items" "$egress_bytes" "$work_active" "$work_queued" \
            >> "$resource_evidence"
        fi
      fi
      keep_up_after=$(grep -Fc "Can't keep up!" "$server_log" 2>/dev/null || true)
      if (( keep_up_after != keep_up_before )); then
        echo "$label: server logged a new tick-overload warning during mixed-client churn" >&2
        result=1
      fi
      if (( result == 0 )); then
        printf 'cycle=%s elapsed=%ss username=%s evidence=%s\n' \
          "$cycle" "$((SECONDS - started))" "$username" "$(basename "$evidence")" \
          >> "$churn_evidence"
      fi
    fi

    if (( result != 0 )); then break; fi
    completed=$((completed + 1))
    cycle=$((cycle + 1))
  done

  if (( result == 0 && churn_requested )); then
    if (( completed < vanilla_churn_cycles || SECONDS - started < vanilla_churn_min_seconds )); then
      echo "$label: mixed-client churn ended before its cycle/duration contract" >&2
      result=1
    else
      printf 'completed_cycles=%s elapsed_seconds=%s minimum_seconds=%s\n' \
        "$completed" "$((SECONDS - started))" "$vanilla_churn_min_seconds" \
        >> "$churn_evidence"
    fi
  fi
  return "$result"
}

run_two_client_audio() {
  local label=$1
  local target_dir=$2
  local java_home=$3
  local port=$4
  local rcon_port=$5
  local rcon_password=$6
  local fifo_fd=$7
  local server_log=$8
  local sink_prefix="jammarr_${BASHPID}_${label//[^a-zA-Z0-9]/_}"
  local sink_master="${sink_prefix}_capture"
  local sink_leader="${sink_prefix}_leader" sink_follower="${sink_prefix}_follower"
  local raw_leader="$output_root/$label.audio-leader.s16le"
  local raw_follower="$output_root/$label.audio-follower.s16le"
  local raw_combined="$output_root/$label.audio-shared-clock.s16le"
  local metrics_leader="$output_root/$label.audio-leader.metrics.txt"
  local metrics_follower="$output_root/$label.audio-follower.metrics.txt"
  local evidence="$output_root/$label.two-client-audio.evidence.txt"
  local leader_pid follower_pid recorder_pid result=0 client_port="$port"
  local proxy_port_file="$output_root/$label.audio-proxy.port"
  local proxy_event_log="$output_root/$label.audio-proxy.jsonl"
  local capture_seconds=11 minimum_duration_ms=10000
  local -a rendered_timing_args=()
  if uses_legacy_audio_profile "$label"; then
    # The legacy backend can carry several processed OpenAL buffers before a
    # raw-stream restart exposes stale-buffer replay. Cover sustained playback,
    # not only the first two compressed transfer windows.
    capture_seconds=31
    minimum_duration_ms=30000
    rendered_timing_args+=(--maximum-marker-error-ms 120 --maximum-skew-ms 250)
  fi

  if ! prepare_private_client_audio "$label" \
      || ! activate_shared_audio_sinks "$label" "$sink_master" "$sink_leader" "$sink_follower"; then
    return 1
  fi
  if [[ "$network_profile" != "direct" ]]; then
    rm -f -- "$proxy_port_file"
    python3 "$repo_root/scripts/tcp-impairment-proxy.py" --target-port "$port" \
      --profile "$network_profile" --port-file "$proxy_port_file" --event-log "$proxy_event_log" &
    active_proxy_pid=$!
    local proxy_deadline=$((SECONDS + 10))
    while [[ ! -s "$proxy_port_file" ]]; do
      if ! kill -0 "$active_proxy_pid" 2>/dev/null || (( SECONDS >= proxy_deadline )); then
        echo "$label: audio impairment proxy failed to start" >&2
        return 1
      fi
      sleep 0.1
    done
    client_port=$(<"$proxy_port_file")
  fi
  if launch_audio_client "$label" "$target_dir" "$java_home" "$client_port" leader JammarrAudioA "$sink_leader"; then
    leader_pid=$ready_audio_client_pid
  else
    result=1
  fi
  if (( result == 0 )); then
    if launch_audio_client "$label" "$target_dir" "$java_home" "$client_port" follower JammarrAudioB "$sink_follower"; then
      follower_pid=$ready_audio_client_pid
    else
      result=1
    fi
  fi
  if (( result == 0 )); then
    if ! wait_for_audio_pair_playing "$label" "$leader_pid" "$follower_pid"; then
      result=1
    elif audio_log_has_rapid_duplicate_channel_start "$output_root/$label.audio-leader.console.log" \
        || audio_log_has_rapid_duplicate_channel_start "$output_root/$label.audio-follower.console.log"; then
      echo "$label: an audio client created duplicate channels within 500 ms" >&2
      result=1
    fi
  fi
  if (( result == 0 )); then
    : > "$raw_leader"
    : > "$raw_follower"
    : > "$raw_combined"
    # The bounded evidence recorder is the sole consumer of the master monitor.
    parec --raw --latency-msec=200 --device="${sink_master}.monitor" --format=s16le \
      --rate=48000 --channels=4 \
      --channel-map=front-left,front-right,rear-left,rear-right \
      > "$raw_combined" &
    recorder_pid=$!; active_audio_recorder_pids+=("$recorder_pid")
    sleep "$capture_seconds"
    report_private_audio_graph_warnings "$label"
    if ! ss -ltnH "sport = :$port" | grep -q . \
        || ! group_alive "$leader_pid" || ! group_alive "$follower_pid" \
        || ! kill -0 "$recorder_pid" 2>/dev/null \
        || audio_log_has_terminal_backend_failure "$output_root/$label.audio-leader.console.log" \
        || audio_log_has_terminal_backend_failure "$output_root/$label.audio-follower.console.log" \
        || grep -Fq 'Client disconnected with reason:' \
          "$output_root/$label.audio-leader.console.log" \
          "$output_root/$label.audio-follower.console.log"; then
      echo "$label: server or client disconnected during deterministic audio capture; refusing to classify trailing recorder silence as an audio-timing defect" >&2
      result=1
    fi
  fi

  for recorder_pid in "${active_audio_recorder_pids[@]}"; do
    kill -TERM "$recorder_pid" 2>/dev/null || true
    wait "$recorder_pid" 2>/dev/null || true
  done
  active_audio_recorder_pids=()
  if (( result == 0 )) && ! ffmpeg -hide_banner -loglevel error -y \
      -f s16le -ar 48000 -ac 4 -i "$raw_combined" \
      -filter_complex \
        '[0:a]pan=stereo|c0=c0|c1=c1[leader];[0:a]pan=stereo|c0=c2|c1=c3[follower]' \
      -map '[leader]' -f s16le "$raw_leader" \
      -map '[follower]' -f s16le "$raw_follower"; then
    echo "$label: could not split the shared-clock two-client capture" >&2
    result=1
  fi
  if (( result == 0 )) && ! audio_capture_is_audible "$raw_leader" "$metrics_leader"; then
    echo "$label: leader sink did not contain observable 997 Hz program audio" >&2
    result=1
  fi
  if (( result == 0 )) && ! audio_capture_is_audible "$raw_follower" "$metrics_follower"; then
    echo "$label: late-join follower sink did not contain observable 997 Hz program audio" >&2
    result=1
  fi
  if (( result == 0 )) && ! python3 "$repo_root/scripts/analyze-audio-timing.py" \
      "$raw_leader" --reference "$raw_follower" --minimum-duration-ms "$minimum_duration_ms" \
      "${rendered_timing_args[@]}" \
      > "$output_root/$label.audio-timing.json"; then
    echo "$label: deterministic audio timing thresholds failed" >&2
    result=1
  fi
  if (( result == 0 )) && uses_legacy_audio_profile "$label"; then
    local role trace trace_dir trace_bytes
    trace_bytes=$((40 * 44100 * 2 * 2))
    for role in leader follower; do
      trace_dir="$output_root/$label.audio-$role/pcm-trace"
      trace="$output_root/$label.audio-$role-fed-tail.s16le"
      if ! python3 "$repo_root/scripts/extract-pcm-trace-tail.py" \
          "$trace_dir" "$trace" --bytes "$trace_bytes" \
          > "$output_root/$label.audio-$role-fed-extract.json" \
          || ! python3 "$repo_root/scripts/analyze-audio-timing.py" \
          "$trace" --sample-rate 44100 --minimum-duration-ms "$minimum_duration_ms" \
          > "$output_root/$label.audio-$role-fed-timing.json"; then
        echo "$label: $role PCM supplied to OpenAL failed strict timing thresholds" >&2
        result=1
        break
      fi
    done
  fi
  if (( result == 0 )) && ! awk -F '\t' '$2 == "/music/:/transcode/universal/start.mp3" { found = 1 } END { exit !found }' \
      "$fake_plex_request_log"; then
    echo "$label: fake Plex did not serve the real MP3 transcode" >&2
    result=1
  fi
  if (( result == 0 )); then
    {
      grep -F 'Acceptance audio state: PLAYING' "$output_root/$label.audio-leader.console.log" | tail -n 1
      grep -F 'Acceptance audio state: PLAYING' "$output_root/$label.audio-follower.console.log" | tail -n 1
      grep -E 'mean_volume:|max_volume:' "$metrics_leader" | tail -n 2
      grep -E 'mean_volume:|max_volume:' "$metrics_follower" | tail -n 2
      sed -n '/"duration_ms"\|"marker_count"\|"max_marker_interval_error_ms"\|"marker_sequence_mismatches"\|"max_marker_overlap_ms"\|"max_silence_ms"\|"inter_client_skew_ms"/p' \
        "$output_root/$label.audio-timing.json"
      if uses_legacy_audio_profile "$label"; then
        printf 'Strict pre-backend OpenAL feed timing:\n'
        sed -n '/"duration_ms"\|"marker_count"\|"max_marker_interval_error_ms"\|"marker_sequence_mismatches"\|"max_marker_overlap_ms"\|"max_silence_ms"/p' \
          "$output_root/$label.audio-"{leader,follower}"-fed-timing.json"
      fi
      printf 'Network profile: %s\n' "$network_profile"
      printf 'Fake Plex transcode served; follower joined after leader reached PLAYING.\n'
    } > "$evidence"
  fi
  if (( result == 0 )) && [[ "$vanilla_client_gate" == "true" ]]; then
    if ! run_mixed_vanilla_audio "$label" "$port" "$rcon_port" "$rcon_password" \
        "$fifo_fd" "$server_log" "$sink_master" "$leader_pid" "$follower_pid"; then
      result=1
    fi
  fi
  if (( result == 0 )) && [[ "$audio_scenario_gate" == "true" ]]; then
    if ! run_audio_control_scenarios "$label" "$target_dir" "$java_home" "$client_port" \
        "$sink_leader" "$sink_follower" "$leader_pid" "$follower_pid" \
        "$rcon_port" "$rcon_password" "$fifo_fd" "$server_log"; then
      result=1
    elif [[ -n "$scenario_follower_pid" ]]; then
      follower_pid=$scenario_follower_pid
    fi
  fi
  if (( result == 0 )) && [[ "$legacy_browse_stress_gate" == "true"
      && ${target_stress_profile[$label]} != "none" ]]; then
    if ! run_legacy_browse_stress "$label" "$server_log" "$fifo_fd" "$rcon_port" "$rcon_password"; then
      result=1
    fi
  fi
  if (( result == 0 && legacy_cold_start_count > 0 )) \
      && [[ ${target_stress_profile[$label]} != "none" ]]; then
    if ! run_legacy_cold_start_stress "$label" "$target_dir" "$java_home" "$client_port" \
        "$sink_leader" "$leader_pid" "$follower_pid" "$rcon_port" "$rcon_password" "$fifo_fd" \
        "$server_log"; then
      result=1
    fi
  fi
  cleanup_audio_processes
  if [[ -n "$active_proxy_pid" ]]; then
    kill -TERM "$active_proxy_pid" 2>/dev/null || true
    wait "$active_proxy_pid" 2>/dev/null || true
    active_proxy_pid=""
  fi
  return "$result"
}

install_fake_plex_config() {
  local run_dir=$1
  local label=$2
  local level_name=$3
  local fake_plex_port=$4
  active_config="$run_dir/$level_name/serverconfig/jammarr-server.toml"
  active_config_backup=$(mktemp "$output_root/$label.config.XXXXXX")
  active_config_existed=0
  mkdir -p "$(dirname "$active_config")"
  if [[ -f "$active_config" ]]; then
    cp -- "$active_config" "$active_config_backup"
    active_config_existed=1
  fi
  printf '%s\n' \
    '# Generated temporarily by the Jammarr dedicated-server gate.' \
    "plexUrl = \"http://127.0.0.1:${fake_plex_port}\"" \
    'plexToken = ""' \
    'musicLibrary = "Music"' \
    'restartMode = "RESTART_TRACK"' \
    'pauseWhenNoPlayers = true' \
    'operatorPermissionLevel = 2' \
    'queueLimit = 500' \
    'audioBitrateKbps = 160' \
    'cacheSizeMiB = 1024' \
    'stationMetadataFallbackEnabled = false' > "$active_config"
}

install_invalid_config() {
  local run_dir=$1
  local label=$2
  local level_name=$3
  active_config="$run_dir/$level_name/serverconfig/jammarr-server.toml"
  active_config_backup=$(mktemp "$output_root/$label.invalid-config.XXXXXX")
  active_config_existed=0
  mkdir -p "$(dirname "$active_config")"
  if [[ -f "$active_config" ]]; then
    cp -- "$active_config" "$active_config_backup"
    active_config_existed=1
  fi
  printf '%s\n' \
    '# Intentionally invalid; installed temporarily by the Jammarr dedicated-server gate.' \
    'plexUrl = "http://private-user:private-pass@127.0.0.1:32400"' > "$active_config"
}

group_alive() {
  local group_id=$1
  ps -eo pgid=,stat= | awk -v expected="$group_id" \
    '$1 == expected && $2 !~ /^Z/ { found = 1 } END { exit !found }'
}

wait_for_group_start() {
  local group_id=$1
  local seconds=$2
  local deadline=$((SECONDS + seconds))
  while ! group_alive "$group_id"; do
    if (( SECONDS >= deadline )); then return 1; fi
    sleep 0.1
  done
}

wait_for_client_launch() {
  local pid=$1 console_log=$2
  # The background shell can still share our group until it execs setsid.
  # Do not classify that scheduling window as a dead Minecraft client.
  if ! wait_for_group_start "$pid" 10; then
    echo "Client process group did not start; see $console_log" >&2
    terminate_client_launch "$pid" 10 || true
    return 1
  fi
}

stop_group() {
  local group_id=$1
  local signal=$2
  kill "-$signal" -- "-$group_id" 2>/dev/null || true
}

wait_for_group_exit() {
  local group_id=$1
  local seconds=$2
  local deadline=$((SECONDS + seconds))
  while group_alive "$group_id"; do
    if (( SECONDS >= deadline )); then return 1; fi
    sleep 1
  done
}

record_group_shutdown_diagnostics() {
  local group_id=$1 prefix=$2 java_home=$3
  local member_pid executable
  # Preserve the processes and Java stacks before cleanup destroys the evidence.
  # Avoid command lines (which can contain credentials) and bound attach waits.
  ps -eo pid=,ppid=,pgid=,sid=,stat=,wchan=,comm= \
    | awk -v expected="$group_id" '$3 == expected && $5 !~ /^Z/' \
    > "$prefix.processes.txt"
  while read -r member_pid _; do
    executable=$(readlink "/proc/$member_pid/exe" 2>/dev/null) || continue
    [[ "${executable##*/}" == java ]] || continue
    if [[ -x "$java_home/bin/jcmd" ]]; then
      timeout --kill-after=2s 10s "$java_home/bin/jcmd" "$member_pid" Thread.print \
        > "$prefix.$member_pid.threads.txt" 2>&1 || true
    fi
  done < "$prefix.processes.txt"
}

process_tree_pids() {
  local root=$1
  ps -eo pid=,ppid= | awk -v root="$root" '
    { parent[$1] = $2 }
    END {
      for (pid in parent) {
        current = pid
        while (current in parent && current != 1) {
          if (current == root) { print pid; break }
          current = parent[current]
        }
      }
    }
  ' | sort -rn
}

minecraft_java_pid() {
  local root=$1
  local candidate command
  while read -r candidate; do
    [[ -r "/proc/$candidate/cmdline" ]] || continue
    command=$(tr '\0' ' ' < "/proc/$candidate/cmdline")
    if [[ "$command" == *java* && ( "$command" == *net.minecraft* \
        || "$command" == *launchwrapper* \
        || "$command" == *net.fabricmc.devlaunchinjector.Main* \
        || "$command" == *cpw.mods.bootstraplauncher.BootstrapLauncher* \
        || "$command" == *net.neoforged.devlaunch.Main* ) ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done < <({ printf '%s\n' "$root"; process_tree_pids "$root"; } | sort -un)
  return 1
}

stop_process_tree() {
  local root=$1
  local signal=$2
  local -a tree=()
  mapfile -t tree < <(process_tree_pids "$root")
  if (( ${#tree[@]} != 0 )); then kill "-$signal" -- "${tree[@]}" 2>/dev/null || true; fi
}

wait_for_process_tree_exit() {
  local root=$1
  local seconds=$2
  local deadline=$((SECONDS + seconds))
  while [[ -n "$(process_tree_pids "$root")" ]]; do
    if (( SECONDS >= deadline )); then return 1; fi
    sleep 1
  done
}

terminate_client_launch() {
  local root=$1
  local seconds=$2
  local pid group deadline live result=0 caller_pid=$BASHPID caller_group
  local -a pids=() groups=() shared_group_pids=()
  [[ "$root" =~ ^[0-9]+$ ]] && (( root > 1 && root != caller_pid )) || return 1
  caller_group=$(ps -o pgid= -p "$caller_pid" | tr -d ' ')
  mapfile -t pids < <({ printf '%s\n' "$root"; process_tree_pids "$root"; } | sort -un)
  for pid in "${pids[@]}"; do
    group=$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ')
    if [[ "$group" == "$caller_group" ]]; then
      # Cancellation can beat setsid. Signal only our launch's descendants,
      # never the shared supervisor group (server, fixtures and sibling gates).
      shared_group_pids+=("$pid")
    elif [[ "$group" =~ ^[0-9]+$ ]] && (( group > 1 )); then
      groups+=("$group")
    fi
  done
  mapfile -t groups < <(printf '%s\n' "${groups[@]}" | sed '/^$/d' | sort -unr)

  for group in "${groups[@]}"; do stop_group "$group" TERM; done
  for pid in "${shared_group_pids[@]}"; do kill -TERM "$pid" 2>/dev/null || true; done
  deadline=$((SECONDS + seconds))
  while true; do
    live=0
    for group in "${groups[@]}"; do group_alive "$group" && live=1; done
    for pid in "${shared_group_pids[@]}"; do
      if ps -o stat= -p "$pid" | grep -q '^[^ Z]'; then live=1; fi
    done
    if (( live == 0 )); then break; fi
    if (( SECONDS >= deadline )); then result=1; break; fi
    sleep 1
  done
  if (( result != 0 )); then
    for group in "${groups[@]}"; do stop_group "$group" KILL; done
    for pid in "${shared_group_pids[@]}"; do kill -KILL "$pid" 2>/dev/null || true; done
    deadline=$((SECONDS + 10))
    while true; do
      live=0
      for group in "${groups[@]}"; do group_alive "$group" && live=1; done
      for pid in "${shared_group_pids[@]}"; do
        if ps -o stat= -p "$pid" | grep -q '^[^ Z]'; then live=1; fi
      done
      if (( live == 0 )); then result=0; break; fi
      if (( SECONDS >= deadline )); then break; fi
      sleep 1
    done
  fi
  wait "$root" 2>/dev/null || true
  return "$result"
}

ensure_runtime_files() {
  local run_dir=$1
  local default_port=$2
  mkdir -p "$run_dir"
  if [[ ! -f "$run_dir/eula.txt" ]]; then
    printf 'eula=true\n' > "$run_dir/eula.txt"
  elif ! grep -Eq '^eula=true$' "$run_dir/eula.txt"; then
    printf 'eula=true\n' > "$run_dir/eula.txt"
  fi
  if [[ ! -f "$run_dir/server.properties" ]]; then
    printf 'allow-flight=true\nonline-mode=false\nserver-port=%s\nlevel-name=world\nmotd=Jammarr dedicated-server gate\n' \
      "$default_port" > "$run_dir/server.properties"
  elif ! grep -Eq '^server-port=[0-9]+$' "$run_dir/server.properties"; then
    printf '\nserver-port=%s\n' "$default_port" >> "$run_dir/server.properties"
  fi
}

runtime_download_failed_transiently() {
  grep -Eq 'Failed to get asset:|SocketTimeoutException|HttpTimeoutException|Could not download|Received status code (429|502|503|504) from server:' "$1" 2>/dev/null
}

run_invalid_config_check_once() {
  local label=$1
  local target_dir=$2
  local run_dir=$3
  local java_home=$4
  local port=$5
  local level_name=$6
  local console_log="$output_root/$label.invalid-config.console.log"
  local validation_log="$console_log"
  local pid server_pid="" server_group="" result=0 marker_seen=0 ready_marker_deadline=0
  local -a cache_args=()
  local -a runtime_args=(-PjammarrServerGameDir="$run_dir")
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")
  if disables_configuration_cache "$label"; then
    cache_args+=(--no-configuration-cache)
  fi
  if uses_legacy_fml_log "$label"; then
    validation_log=$(mod_log_path "$label" "$run_dir")
    mkdir -p "$(dirname "$validation_log")"
    : > "$validation_log"
  fi

  install_invalid_config "$run_dir" "$label" "$level_name"
  (
    cd "$target_dir" || exit 1
    exec setsid env JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAMMARR_PLEX_TOKEN="$fake_plex_token" \
      JAVA_TOOL_OPTIONS="-Djammarr.acceptance.enabled=true -Djammarr.acceptance.audioProbe=true -Djammarr.acceptance.helloTimeoutMs=${hello_timeout_ms}" \
      ./gradlew "${target_server_task[$label]}" --no-daemon --max-workers=2 --console=plain "${cache_args[@]}" \
      "${runtime_args[@]}" \
      < /dev/null > "$console_log" 2>&1
  ) &
  pid=$!
  active_server_pid=$pid

  # setsid can publish its process group just after the background launcher
  # returns. Attach to that group before deciding whether the cold server has
  # already exited; otherwise a fast first poll can skip the rejection wait and
  # misclassify a healthy but still-starting server as a fail-closed timeout.
  if ! wait_for_group_start "$pid" 10; then
    echo "$label: invalid-configuration server process group did not start" >&2
    stop_process_tree "$pid" TERM
    wait_for_process_tree_exit "$pid" 10 || stop_process_tree "$pid" KILL
    active_server_pid=""
    restore_server_config
    return 1
  fi

  # A Gradle JavaExec task may run inside a single-use daemon which is not a
  # descendant of the wrapper's setsid process. Consequently the wrapper's
  # process group can disappear while Minecraft is still starting. Only this
  # launch's freshly truncated console and the actual listening socket are
  # authoritative here; latest.log and the FML log can contain rejection text
  # from an earlier probe.
  # A completely cold ForgeGradle server can spend several minutes resolving,
  # transforming, and generating its first world before the server-started
  # callback validates the per-world config. Keep this bounded but above the
  # observed clean-run startup envelope.
  local deadline=$((SECONDS + 600))
  while (( SECONDS < deadline )); do
    if [[ -z "$server_pid" ]]; then
      server_pid=$(ss -ltnp "sport = :$port" \
        | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -n 1)
      if [[ -n "$server_pid" ]]; then
        server_group=$(ps -o pgid= -p "$server_pid" 2>/dev/null | tr -d '[:space:]')
        if [[ "$server_group" =~ ^[0-9]+$ ]] && (( server_group > 1 )); then
          active_server_group=$server_group
        else
          server_group=""
        fi
      fi
    fi
    if grep -Fq 'Invalid Jammarr configuration value for plexUrl' "$console_log" "$validation_log" 2>/dev/null; then
      marker_seen=1
      break
    fi
    if grep -Eq 'Done \([^)]*\)! For help' "$console_log" 2>/dev/null; then
      # Fabric, Forge, and NeoForge print their vanilla Done marker immediately
      # before invoking the server-started callback where the per-world
      # serverconfig path first exists. Give that same-thread callback a small,
      # bounded opportunity to reject the config; no client is launched, and
      # acceptance still requires the exact validation marker and a closed port.
      if (( ready_marker_deadline == 0 )); then
        ready_marker_deadline=$((SECONDS + 5))
      elif (( SECONDS >= ready_marker_deadline )); then
        echo "$label: invalid Jammarr configuration remained live after the server-started callback" >&2
        result=1
        break
      fi
    fi
    if runtime_download_failed_transiently "$console_log" && ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid" 2>/dev/null || true
      active_server_pid=""
      restore_server_config
      return 75
    fi
    sleep 1
  done
  if (( marker_seen == 0 && result == 0 )); then
    echo "$label: invalid-configuration rejection timed out" >&2
    result=1
  fi

  local shutdown_deadline=$((SECONDS + 60))
  while ss -ltnH "sport = :$port" | grep -q .; do
    if [[ -z "$server_pid" ]]; then
      server_pid=$(ss -ltnp "sport = :$port" \
        | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -n 1)
      if [[ -n "$server_pid" ]]; then
        server_group=$(ps -o pgid= -p "$server_pid" 2>/dev/null | tr -d '[:space:]')
        if [[ "$server_group" =~ ^[0-9]+$ ]] && (( server_group > 1 )); then
          active_server_group=$server_group
        else
          server_group=""
        fi
      fi
    fi
    if (( SECONDS >= shutdown_deadline )); then
      echo "$label: server did not fail closed after rejecting invalid Jammarr configuration" >&2
      result=1
      break
    fi
    sleep 1
  done
  stop_listening_port "$port"
  # The listening Minecraft process is hosted by Gradle's single-use daemon,
  # which may already have detached from the setsid wrapper. A closed socket is
  # not enough: Minecraft can still be saving and retain session.lock. Wait for
  # the actual listener's process group before restoring config or starting the
  # valid server, and retain that group in the outer cleanup trap as well.
  if [[ -n "$server_group" ]]; then
    if ! wait_for_group_exit "$server_group" 60; then
      record_group_shutdown_diagnostics "$server_group" \
        "$output_root/$label.invalid-config.shutdown-timeout" "$java_home"
      stop_group "$server_group" TERM
      if ! wait_for_group_exit "$server_group" 10; then
        stop_group "$server_group" KILL
        wait_for_group_exit "$server_group" 10 || true
      fi
      echo "$label: invalid-configuration process group required forced cleanup" >&2
      result=1
    fi
  else
    stop_group "$pid" TERM
    if ! wait_for_group_exit "$pid" 10; then
      stop_group "$pid" KILL
      wait_for_group_exit "$pid" 10 || true
    fi
  fi
  wait "$pid" 2>/dev/null || true
  active_server_pid=""
  active_server_group=""
  if (( marker_seen == 0 )); then
    echo "$label: invalid configuration failure did not identify the rejected key" >&2
    result=1
  fi
  if grep -Fq 'private-pass' "$console_log" "$validation_log" 2>/dev/null; then
    echo "$label: invalid configuration diagnostics leaked a credential" >&2
    result=1
  fi
  if ss -ltnH "sport = :$port" | grep -q .; then
    echo "$label: port $port remains open after invalid-configuration rejection" >&2
    result=1
  fi
  restore_server_config
  if (( result == 0 )); then
    echo "$label: invalid configuration rejected without leaking its value"
  fi
  return "$result"
}

run_invalid_config_check() {
  local attempt status
  for attempt in 1 2; do
    if run_invalid_config_check_once "$@"; then
      return 0
    else
      status=$?
    fi
    if (( status != 75 || attempt == 2 )); then
      return "$status"
    fi
    preserve_client_attempt "$output_root/$1.invalid-config.console.log" "$attempt"
    echo "$1: retrying invalid-configuration launch after a transient runtime download failure" >&2
    sleep 10
  done
  return 1
}

set_property() {
  local file=$1
  local key=$2
  local value=$3
  if grep -q "^${key}=" "$file"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$file"
  else
    printf '%s=%s\n' "$key" "$value" >> "$file"
  fi
}

run_minecraft_rcon() {
  local attempt status=1
  for attempt in 1 2 3; do
    if python3 "$repo_root/scripts/minecraft-rcon.py" "$@"; then
      return 0
    else
      status=$?
    fi
    if (( attempt < 3 )); then sleep 1; fi
  done
  return "$status"
}

backup_server_properties() {
  local properties=$1
  local label=$2
  active_properties="$properties"
  active_properties_backup=$(mktemp "$output_root/$label.server-properties.XXXXXX")
  cp -- "$active_properties" "$active_properties_backup"
}

run_legacy_persistence_reload() {
  local label=$1
  local target_dir=$2
  local run_dir=$3
  local java_home=$4
  local port=$5
  local console_log="$output_root/$label.persistence-reload.console.log"
  local evidence="$output_root/$label.persistence-reload.evidence.txt"
  local mod_log
  mod_log=$(mod_log_path "$label" "$run_dir")
  local fifo_dir fifo fifo_fd pid server_pid server_group deadline result=0
  local -a cache_args=()
  local -a runtime_args=(-PjammarrServerGameDir="$run_dir")
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] \
    && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")
  disables_configuration_cache "$label" && cache_args+=(--no-configuration-cache)

  fifo_dir=$(mktemp -d "$output_root/$label.persistence-fifo.XXXXXX")
  fifo="$fifo_dir/stdin"
  mkfifo "$fifo"
  exec {fifo_fd}<>"$fifo"
  : > "$console_log"
  (
    cd "$target_dir" || exit 1
    exec setsid env JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAMMARR_PLEX_TOKEN="$fake_plex_token" \
      JAVA_TOOL_OPTIONS="-Djammarr.acceptance.enabled=true -Djammarr.acceptance.audioProbe=true -Djammarr.acceptance.persistenceRead=true -Djammarr.acceptance.helloTimeoutMs=${hello_timeout_ms}" \
      ./gradlew "${target_server_task[$label]}" --no-daemon --max-workers=2 --console=plain "${cache_args[@]}" \
      "${runtime_args[@]}" < "$fifo" > "$console_log" 2>&1
  ) &
  pid=$!
  active_server_pid=$pid

  deadline=$((SECONDS + 180))
  while ! grep -Fq 'Acceptance schema-4 persistence fixture reloaded from the production world' \
      "$console_log" "$mod_log" 2>/dev/null; do
    if ! kill -0 "$pid" 2>/dev/null || (( SECONDS >= deadline )); then
      echo "$label: production persistence reload did not verify the saved schema-4 fixture" >&2
      result=1
      break
    fi
    sleep 1
  done

  if (( result == 0 )); then
    printf 'stop\n' >&"$fifo_fd"
  else
    stop_process_tree "$pid" TERM
  fi
  server_pid=$(ss -ltnp "sport = :$port" \
    | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -n 1)
  server_group=""
  if [[ -n "$server_pid" ]]; then
    server_group=$(ps -o pgid= -p "$server_pid" | tr -d '[:space:]')
  fi
  if [[ "$server_group" =~ ^[0-9]+$ ]] && (( server_group > 1 )); then
    active_server_group=$server_group
    wait_for_group_exit "$server_group" 60 || result=1
  else
    wait_for_process_tree_exit "$pid" 60 || result=1
  fi
  if (( result != 0 )); then
    [[ -n "$server_group" ]] && stop_group "$server_group" KILL
    stop_process_tree "$pid" KILL
  fi
  wait "$pid" 2>/dev/null || true
  active_server_pid=""
  active_server_group=""
  exec {fifo_fd}>&-
  rm -f -- "$fifo"
  rmdir -- "$fifo_dir"

  if grep -Eq 'AbstractMethodError|NoSuchMethodError|production Forge 1\.7\.10 did not reload|Encountered an unexpected exception' \
      "$console_log" "$mod_log" 2>/dev/null; then
    echo "$label: production persistence reload hit a linkage or state failure" >&2
    result=1
  fi
  if ! grep -q 'FMLServerStoppedEvent' "$mod_log" 2>/dev/null; then
    echo "$label: production persistence reload did not shut down cleanly" >&2
    result=1
  fi
  if ss -ltnH "sport = :$port" | grep -q .; then
    echo "$label: production persistence reload left port $port open" >&2
    result=1
  fi
  if (( result == 0 )); then
    {
      grep -F 'Acceptance schema-4 persistence fixture reloaded from the production world' \
        "$console_log" "$mod_log" | tail -n 1
      grep -F 'FMLServerStoppedEvent' "$mod_log" | tail -n 1
      printf '%s\n' 'Final reobfuscated artifact persisted and reloaded schema-4 state across a clean server restart.'
    } > "$evidence"
  fi
  return "$result"
}

run_target() {
  local label=$1
  local relative_dir=$2
  local java_home=$3
  local default_port=$4
  local target_dir="$repo_root/$relative_dir"
  local run_dir="$target_dir/run"
  [[ "$label" == *-quilt ]] && run_dir="$target_dir/run-quilt"
  local latest_log="$run_dir/logs/latest.log"
  local mod_log
  mod_log=$(mod_log_path "$label" "$run_dir")
  local console_log="$output_root/$label.console.log"
  local server_evidence_log="$console_log"
  if uses_legacy_fml16_log "$label"; then
    # Java util logging rotates the numbered FML file between the invalid and
    # normal startup probes. The captured console is stable for live joins.
    server_evidence_log="$console_log"
  elif uses_legacy_fml_log "$label"; then
    # Forge 1.8.9's development Log4j configuration does not attach its
    # Minecraft/FML console appenders. The same authoritative events are
    # still written to latest.log and fml-server-latest.log.
    server_evidence_log="$mod_log"
  fi
  local fifo_dir fifo fifo_fd pid server_pid server_group port rcon_port rcon_password result=0
  local fake_plex_port fake_request_start plex_deadline level_name
  local -a cache_args=()
  local -a runtime_args=(-PjammarrServerGameDir="$run_dir")
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")

  # Every target must start from a healthy fake Plex service. In particular,
  # an earlier audio assertion may have failed during its intentional outage;
  # never allow that scoped failure to poison the rest of the release matrix.
  printf 'online\n' > "$fake_plex_state"

  if [[ ! -x "$java_home/bin/java" ]]; then
    echo "$label: missing Java runtime $java_home" >&2
    return 1
  fi
  ensure_runtime_files "$run_dir" "$default_port"
  # The manifest owns acceptance ports. Persistent Gradle run directories may
  # contain an older gate allocation or a developer-selected port; using it
  # would invalidate evidence identity and could collide with another lane.
  # Install the manifest value only after checking availability, then restore
  # the original server.properties through the normal transaction cleanup.
  port=$default_port
  if ss -ltnH "sport = :$port" | grep -q .; then
    echo "$label: port $port is already in use" >&2
    return 1
  fi
  rcon_port=$((default_port + 1000))
  rcon_password="jammarr-gate-${default_port}"
  if ! uses_console_control "$label" && ss -ltnH "sport = :$rcon_port" | grep -q .; then
    echo "$label: RCON port $rcon_port is already in use" >&2
    return 1
  fi
  active_game_port=$port
  if ! uses_console_control "$label"; then active_rcon_port=$rcon_port; fi
  backup_server_properties "$run_dir/server.properties" "$label"
  set_property "$run_dir/server.properties" server-port "$port"
  # Keep the gate isolated from developer worlds and from damage left by an
  # interrupted prior run. The run directories are ignored build state.
  level_name=jammarr-gate-world
  set_property "$run_dir/server.properties" level-name "$level_name"
  set_property "$run_dir/server.properties" online-mode false
  set_property "$run_dir/server.properties" enforce-secure-profile false
  set_property "$run_dir/server.properties" sync-chunk-writes false
  isolate_gate_world "$run_dir" "$label" "$level_name"
  if uses_console_control "$label"; then
    # Vanilla 1.7.10 closes an RCON connection after its authentication packet,
    # so use its working console input instead of weakening clean-shutdown proof.
    set_property "$run_dir/server.properties" enable-rcon false
  else
    set_property "$run_dir/server.properties" enable-rcon true
    set_property "$run_dir/server.properties" rcon.port "$rcon_port"
    set_property "$run_dir/server.properties" rcon.password "$rcon_password"
  fi
  if disables_configuration_cache "$label"; then
    cache_args+=(--no-configuration-cache)
  fi

  if [[ "$protocol_client_gate" == "true" || "$command_client_gate" == "true"
      || "$audio_client_gate" == "true" || "$vanilla_client_gate" == "true" ]] \
      && ! prepare_loom_client_launcher "$label" "$target_dir" "$java_home"; then
    echo "$label: unable to materialize the Loom development client launcher" >&2
    restore_server_properties
    restore_gate_world
    return 1
  fi

  if ! run_invalid_config_check "$label" "$target_dir" "$run_dir" "$java_home" "$port" "$level_name"; then
    restore_server_properties
    restore_gate_world
    return 1
  fi

  fake_plex_port=$(<"$fake_plex_port_file")
  fake_request_start=$(wc -l < "$fake_plex_request_log")
  install_fake_plex_config "$run_dir" "$label" "$level_name" "$fake_plex_port"
  if [[ "$audio_client_gate" == "true" ]]; then
    isolate_audio_cache "$run_dir" "$label"
  fi

  # The invalid-config probe has just populated the loader logs. Clear those
  # generated run-directory files before the valid boot so a slow Gradle
  # startup cannot satisfy readiness from the previous process's Done marker.
  : > "$latest_log"
  if [[ "$mod_log" != "$latest_log" ]]; then : > "$mod_log"; fi

  fifo_dir=$(mktemp -d "$output_root/$label.fifo.XXXXXX")
  fifo="$fifo_dir/stdin"
  mkfifo "$fifo"
  exec {fifo_fd}<>"$fifo"
  echo "$label: starting on port $port"
  (
    cd "$target_dir" || exit 1
    exec setsid env JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAMMARR_PLEX_TOKEN="$fake_plex_token" \
      JAVA_TOOL_OPTIONS="-Djammarr.acceptance.enabled=true -Djammarr.acceptance.audioProbe=true -Djammarr.acceptance.helloTimeoutMs=${hello_timeout_ms}" \
      ./gradlew "${target_server_task[$label]}" --no-daemon --max-workers=2 --console=plain "${cache_args[@]}" \
      "${runtime_args[@]}" \
      < "$fifo" > "$console_log" 2>&1
  ) &
  pid=$!
  active_server_pid=$pid

  local startup_deadline=$((SECONDS + 180))
  while :; do
    if grep -Eq 'Done \([^)]*\)! For help' "$console_log" "$server_evidence_log" "$latest_log" 2>/dev/null; then
      break
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "$label: server process exited before readiness" >&2
      result=1
      break
    fi
    if (( SECONDS >= startup_deadline )); then
      echo "$label: server did not become ready within 180 seconds" >&2
      result=1
      break
    fi
    sleep 1
  done

  if (( result == 0 )); then
    plex_deadline=$((SECONDS + 30))
    while ! fake_plex_requests_complete "$fake_request_start"; do
      if ! kill -0 "$pid" 2>/dev/null || (( SECONDS >= plex_deadline )); then
        echo "$label: did not complete authenticated Plex library and sonic validation" >&2
        result=1
        break
      fi
      sleep 1
    done
  fi

  # Gradle's --no-daemon mode still launches a single-use daemon. Depending on
  # setsid's fork behavior, that daemon's process group can differ from the
  # background launcher PID. Record the group that actually owns Minecraft's
  # listening socket so shutdown and interrupt cleanup cannot strand it.
  server_pid=$(ss -ltnp "sport = :$port" \
    | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -n 1)
  server_group=""
  if [[ -n "$server_pid" ]]; then
    server_group=$(ps -o pgid= -p "$server_pid" | tr -d '[:space:]')
  fi
  if [[ "$server_group" =~ ^[0-9]+$ ]] && (( server_group > 1 )); then
    active_server_group=$server_group
  else
    server_group=""
  fi

  # Every graphical client—including protocol and command probes—must use the
  # same private Pulse graph. Starting it here prevents those earlier probes
  # from falling back to the runner's nonexistent ALSA default device.
  if (( result == 0 )) && [[ "$protocol_client_gate" == "true" || "$command_client_gate" == "true"
      || "$audio_client_gate" == "true" || "$vanilla_client_gate" == "true" ]]; then
    if ! prepare_private_client_audio "$label"; then
      echo "$label: unable to prepare the isolated client audio environment" >&2
      result=1
    fi
  fi

  if (( result == 0 )) && [[ "$protocol_client_gate" == "true" ]]; then
    if ! run_wrong_protocol_client "$label" "$target_dir" "$java_home" "$port" "$server_evidence_log"; then
      result=1
    fi
  fi

  if (( result == 0 )) && [[ "$delayed_hello_gate" == "true" ]]; then
    if ! run_delayed_hello_client "$label" "$target_dir" "$java_home" "$port"; then
      result=1
    fi
  fi

  if (( result == 0 )) && [[ "$command_client_gate" == "true" ]]; then
    if ! run_command_client "$label" "$target_dir" "$java_home" "$port" "$server_evidence_log" \
        "$rcon_port" "$rcon_password" "$fifo_fd"; then
      result=1
    fi
  fi

  if (( result == 0 )) && [[ "$audio_client_gate" == "true" ]]; then
    if ! run_two_client_audio "$label" "$target_dir" "$java_home" "$port" \
        "$rcon_port" "$rcon_password" "$fifo_fd" "$server_evidence_log"; then
      result=1
    fi
  fi

  if (( result == 0 )) && [[ "$client_companion_gate" == "true"
      && -n ${target_companion_label[$label]:-} ]]; then
    if ! run_client_companion "$label" "$port"; then
      result=1
    fi
  fi

  if (( result == 0 )); then
    if ! run_optional_client "$label" "$target_dir" "$java_home" "$port" "$server_evidence_log" \
        "$run_dir/logs/debug.log"; then
      result=1
    fi
    # Let the server finish its disconnect/player-removal tick before the
    # shutdown probe begins.
    sleep 2
  fi

  if (( result == 0 )) && [[ "$vanilla_client_gate" == "true" ]]; then
    if ! run_vanilla_client "$label" "$port" "$server_evidence_log" \
        "$rcon_port" "$rcon_password" "$fifo_fd"; then
      result=1
    fi
    # Preserve the same disconnect/player-removal boundary before shutdown.
    sleep 2
  fi

  if (( result == 0 )) && [[ "$legacy_persistence_gate" == "true"
      && ${target_stress_profile[$label]} != "none" ]]; then
    if uses_console_control "$label"; then
      printf 'jammarr acceptance-persistence-fixture\n' >&"$fifo_fd"
      printf 'save-all\n' >&"$fifo_fd"
    elif ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" \
        'jammarr acceptance-persistence-fixture' > /dev/null \
        || ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" save-all > /dev/null; then
      result=1
    fi
    local persistence_deadline=$((SECONDS + 30))
    while ! grep -Fq 'Acceptance schema-4 persistence fixture marked dirty' \
        "$server_evidence_log" "$console_log" 2>/dev/null; do
      if (( SECONDS >= persistence_deadline )); then
        echo "$label: production persistence fixture was not marked dirty" >&2
        result=1
        break
      fi
      sleep 1
    done
  fi

  if uses_console_control "$label"; then
    printf 'stop\n' >&"$fifo_fd"
  elif ! run_minecraft_rcon 127.0.0.1 "$rcon_port" "$rcon_password" stop \
      >> "$console_log" 2>&1; then
    printf 'stop\n' >&"$fifo_fd"
  fi
  if [[ -n "$server_group" ]]; then
    wait_for_group_exit "$server_group" 60
  else
    wait_for_process_tree_exit "$pid" 60
  fi
  if (( $? != 0 )); then
    if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
      if [[ -x "$java_home/bin/jcmd" ]]; then
        "$java_home/bin/jcmd" "$server_pid" Thread.print \
          > "$output_root/$label.shutdown-timeout.threads.txt" 2>&1 || true
      elif [[ -x "$java_home/bin/jstack" ]]; then
        "$java_home/bin/jstack" "$server_pid" \
          > "$output_root/$label.shutdown-timeout.threads.txt" 2>&1 || true
      fi
    fi
    if [[ -z "$server_pid" ]] || ! kill -0 "$server_pid" 2>/dev/null; then
      server_pid=$(ss -ltnp "sport = :$port or sport = :$rcon_port" \
        | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -n 1)
    fi
    if [[ -n "$server_pid" ]]; then
      kill -TERM "$server_pid" 2>/dev/null || true
    else
      echo "$label: console stop timed out and the listening server process could not be identified" >&2
      stop_process_tree "$pid" TERM
      result=1
    fi
    if [[ -n "$server_group" ]]; then
      wait_for_group_exit "$server_group" 30
    else
      wait_for_process_tree_exit "$pid" 30
    fi
    if (( $? != 0 )); then
      echo "$label: graceful shutdown timed out" >&2
      if [[ -n "$server_group" ]]; then
        stop_group "$server_group" KILL
        wait_for_group_exit "$server_group" 10 || true
      else
        stop_process_tree "$pid" KILL
        wait_for_process_tree_exit "$pid" 10 || true
      fi
      result=1
    fi
  fi
  wait "$pid" 2>/dev/null || true
  active_server_pid=""
  active_server_group=""
  exec {fifo_fd}>&-
  rm -f -- "$fifo"
  rmdir -- "$fifo_dir"

  if uses_legacy_fabric16_log "$label"; then
    if ! grep -Eq 'Initializing Jammarr 1\.1\.0 for Legacy Fabric 1\.6\.4 protocol 6' "$console_log" \
        || ! grep -Eq 'Stopping (the )?server' "$console_log" \
        || ! grep -q 'Saving players' "$console_log"; then
      echo "$label: console log does not prove Legacy Fabric initialization and clean lifecycle shutdown" >&2
      result=1
    fi
  elif uses_legacy_ornithe16_log "$label"; then
    # Minecraft 1.6.4 writes its vanilla lifecycle markers through JUL to the
    # process console while Ornithe/Jammarr writes its own messages to Log4j.
    # Require both streams so a build-only exit cannot masquerade as a clean
    # server shutdown.
    if ! grep -Eq 'Initializing Jammarr 1\.1\.0 for Ornithe 1\.6\.4 protocol 6' "$latest_log" \
        || ! grep -Eq 'Stopping (the )?server' "$console_log" \
        || ! grep -q 'Saving players' "$console_log"; then
      echo "$label: logs do not prove Ornithe initialization and clean Minecraft shutdown" >&2
      result=1
    fi
  elif uses_legacy_babric_log "$label"; then
    # Beta 1.7.3 writes Jammarr/StationAPI through Log4j and vanilla lifecycle
    # messages through JUL, so require both streams.
    if ! grep -Eq 'Initializing Jammarr 1\.1\.0 for Babric/StationAPI Beta 1\.7\.3 protocol 6' "$latest_log" \
        || ! grep -Eq 'Stopping server' "$console_log" \
        || ! grep -q 'Saving chunks' "$console_log"; then
      echo "$label: logs do not prove Babric initialization and clean Beta 1.7.3 shutdown" >&2
      result=1
    fi
  elif uses_legacy_fml_log "$label"; then
    if [[ ! -f "$mod_log" ]] \
        || ! grep -Eq 'Initializing Jammarr 1\.1\.0 for Forge [^ ]+ protocol 6' "$mod_log" \
        || ! grep -q 'FMLServerStoppingEvent' "$mod_log" \
        || ! grep -q 'FMLServerStoppedEvent' "$mod_log"; then
      echo "$label: FML log does not prove initialization and clean lifecycle shutdown" >&2
      result=1
    fi
  else
    if [[ ! -f "$latest_log" ]] || ! grep -Eq 'Stopping (the )?server' "$latest_log" \
        || ! grep -q 'Saving players' "$latest_log"; then
      echo "$label: log does not prove a clean Minecraft shutdown" >&2
      result=1
    fi
    if ! grep -Eiq 'jammarr' "$latest_log"; then
      echo "$label: server log does not prove Jammarr loaded" >&2
      result=1
    fi
  fi
  if grep -Eiq 'Failed to start the minecraft server|ModLoadingException|Preparing crash report|Encountered an unexpected exception' \
      "$latest_log" "$mod_log" "$console_log" 2>/dev/null; then
    echo "$label: fatal startup marker found; see $console_log" >&2
    result=1
  fi
  if ss -ltnH "sport = :$port" | grep -q .; then
    echo "$label: port $port remains open after shutdown" >&2
    result=1
  fi
  if [[ -n "$server_group" ]] && group_alive "$server_group"; then
    echo "$label: process group $server_group remains alive after shutdown" >&2
    result=1
  fi
  if (( result == 0 )) && [[ "$legacy_persistence_gate" == "true"
      && ${target_stress_profile[$label]} != "none" ]]; then
    if ! run_legacy_persistence_reload "$label" "$target_dir" "$run_dir" "$java_home" "$port"; then
      result=1
    fi
  fi
  active_game_port=""
  active_rcon_port=""

  restore_server_config
  restore_server_properties
  restore_audio_cache
  restore_gate_world

  if (( result == 0 )); then
    echo "$label: ready, clean shutdown, no lingering process or port"
  fi
  return "$result"
}

matched=0
failed=0
if [[ "$requested" == "list" ]]; then
  printf '%s\n' "${targets[@]%%|*}"
  exit 0
fi
start_fake_plex || exit 1
for target in "${targets[@]}"; do
  IFS='|' read -r label relative_dir java_home port <<< "$target"
  if [[ "$requested" != "all" && "$requested" != "$label"
      && !( "$requested" == "quilt" && "$label" == *-quilt )
      && !( "$requested" == "fabric" && "$label" == *-fabric ) ]]; then
    continue
  fi
  matched=1
  # Modern ForgeGradle Mavenizer projects share a cache outside every
  # project/runtime lock. Concurrent versions can otherwise overwrite the same
  # launcher_manifest.json while another process parses it. Keep complete
  # Forge gates serialized so both server launches and their development
  # client use one cache writer; Fabric, Quilt, NeoForge, and exact Mojang
  # client work in other lanes remain parallel.
  if [[ "$label" == *-forge ]]; then
    exec 6>"$forgegradle_gate_lock"
    if ! flock 6; then
      echo "$label: interrupted while waiting for the ForgeGradle cache lock" >&2
      failed=1
      exec 6>&-
      continue
    fi
    run_target "$label" "$relative_dir" "$java_home" "$port" || failed=1
    flock -u 6
    exec 6>&-
  else
    run_target "$label" "$relative_dir" "$java_home" "$port" || failed=1
  fi
done

if (( matched == 0 )); then
  echo "Unknown target '$requested'" >&2
  exit 2
fi
if (( failed != 0 )); then
  exit 1
fi
echo "Dedicated-server gate passed for $requested"
