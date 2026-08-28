#!/usr/bin/env bash
set -uo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output_root=${JAMMARR_GATE_OUTPUT_ROOT:-"$repo_root/build/dedicated-server-gate"}
mkdir -p "$output_root"
gate_lock="${output_root}.lock"
exec 9>"$gate_lock"
if ! flock -n 9; then
  echo "Another Jammarr dedicated-server gate is already using the shared runtime evidence directory" >&2
  exit 2
fi
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

targets=(
  "1.7.10-forge|platforms/mc1.7.10/forge|$java26_home|25695"
  "1.20.1-fabric|platforms/mc1.20.1/fabric|$java21_home|25571"
  "1.20.1-quilt|platforms/mc1.20.1/fabric|$java21_home|25648"
  "1.20.1-forge|platforms/mc1.20.1/forge|$java21_home|25572"
  "1.20.1-neoforge|platforms/mc1.20.1/neoforge|$java21_home|25574"
  "1.20.2-fabric|platforms/mc1.20.2/fabric|$java21_home|25576"
  "1.20.2-quilt|platforms/mc1.20.2/fabric|$java21_home|25649"
  "1.20.2-forge|platforms/mc1.20.2/forge|$java21_home|25578"
  "1.20.2-neoforge|platforms/mc1.20.2/neoforge|$java21_home|25580"
  "1.21.1-fabric|platforms/mc1.21.1/fabric|$java21_home|25581"
  "1.21.1-quilt|platforms/mc1.21.1/fabric|$java21_home|25650"
  "1.21.1-forge|platforms/mc1.21.1/forge|$java21_home|25582"
  "1.21.1-neoforge|.|$java21_home|25566"
  "26.1.2-fabric|platforms/mc26.1.2/fabric|$java26_home|25642"
  "26.1.2-quilt|platforms/mc26.1.2/fabric|$java26_home|25651"
  "26.1.2-forge|platforms/mc26.1.2/forge|$java26_home|25643"
  "26.1.2-neoforge|platforms/mc26.1.2/neoforge|$java26_home|25644"
  "26.2-fabric|platforms/mc26.2/fabric|$java26_home|25645"
  "26.2-quilt|platforms/mc26.2/fabric|$java26_home|25652"
  "26.2-forge|platforms/mc26.2/forge|$java26_home|25646"
  "26.2-neoforge|platforms/mc26.2/neoforge|$java26_home|25647"
)

requested=${1:-all}
protocol_client_gate=${JAMMARR_PROTOCOL_CLIENT_GATE:-false}
command_client_gate=${JAMMARR_COMMAND_CLIENT_GATE:-false}
audio_client_gate=${JAMMARR_AUDIO_CLIENT_GATE:-false}
audio_scenario_gate=${JAMMARR_AUDIO_SCENARIO_GATE:-false}
network_profile=${JAMMARR_NETWORK_PROFILE:-direct}
fabric_loader_version=${JAMMARR_FABRIC_LOADER_VERSION:-}
quilt_modmenu_gate=${JAMMARR_QUILT_MODMENU_GATE:-false}

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
  for module in "${active_audio_modules[@]}"; do
    pactl unload-module "$module" > /dev/null 2>&1 || true
  done
  active_audio_client_pids=()
  active_audio_recorder_pids=()
  active_audio_modules=()
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
  if [[ "$audio_client_gate" == "true" ]]; then
    if ! command -v ffmpeg > /dev/null || ! command -v pactl > /dev/null \
        || ! command -v parec > /dev/null; then
      echo "Two-client audio acceptance requires ffmpeg, pactl, and parec" >&2
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

missing_client_rejection_logged() {
  local latest_log=$1
  local console_log=$2
  grep -Eiq 'Jammarr is required on the client|Jammarr protocol (handshake )?timed out|Disconnecting VANILLA connection attempt:.*require (Forge|NeoForge)|incompatible.*Jammarr' \
    "$latest_log" "$console_log" 2>/dev/null
}

client_bootstrap_failed() {
  local console_log=$1
  grep -Eq 'Timed out trying to setup the Game Window|Failed to initialize the mod loading system and display|ArrayIndexOutOfBoundsException: 0' \
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
    && grep -Fq 'Client disconnected with reason: Disconnected' "$console_log" 2>/dev/null
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
  # Transitional NeoForge 1.20.1 can omit its disconnected-screen log while
  # the server still records both the exact rejection and the completed
  # connection teardown. That pair is stronger evidence than waiting for a
  # loader-specific client log line which will never be emitted.
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

run_missing_hello_client() {
  local label=$1
  local target_dir=$2
  local java_home=$3
  local port=$4
  local server_console=$5
  run_acceptance_client "$label" "$target_dir" "$java_home" "$port" "$server_console" \
    missing-client JammarrMissing \
    '-Djammarr.acceptance.enabled=true -Djammarr.acceptance.suppressClientHello=true -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true' \
    'Jammarr protocol handshake timed out'
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
  local -a runtime_args=()
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-quilt && "$quilt_modmenu_gate" == true ]] && runtime_args+=(-PjammarrIncludeModMenu=true)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")

  mkdir -p "$client_dir"
  : > "$client_console"
  printf '%s\n' \
    'onboardAccessibility:false' \
    'skipMultiplayerWarning:true' \
    'joinedFirstServer:true' \
    'narrator:0' > "$client_dir/options.txt"
  (
    cd "$target_dir" || exit 1
    ulimit -f "$client_log_limit_blocks"
    exec setsid env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 \
      xvfb-run -a -s '-screen 0 1280x720x24 +extension GLX +render -noreset' env \
      JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAVA_TOOL_OPTIONS="-Djammarr.acceptance.enabled=true -Djammarr.acceptance.clientHelloDelayMs=${delayed_hello_ms} -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true" \
      LIBGL_ALWAYS_SOFTWARE=1 \
      ./gradlew runClient --no-daemon --max-workers=1 --console=plain \
      "${runtime_args[@]}" \
      -PjammarrAcceptanceUsername="$username" \
      -PjammarrAcceptanceServer="127.0.0.1:${port}" \
      -PjammarrAcceptanceGameDir="$client_dir" \
      > "$client_console" 2>&1
  ) &
  pid=$!
  active_client_pid=$pid

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
    if run_acceptance_client_once "$@"; then return 0; fi
    if (( attempt == 1 )) && grep -Eq \
        'Timed out trying to setup the Game Window|Failed to initialize the mod loading system and display|Failed to download .*\.ogg|HttpTimeoutException: request timed out' \
        "$client_console" 2>/dev/null; then
      echo "$label: retrying $scenario after a transient client bootstrap failure" >&2
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
  local pid deadline exit_grace_deadline result=0
  local -a runtime_args=()
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-quilt && "$quilt_modmenu_gate" == true ]] && runtime_args+=(-PjammarrIncludeModMenu=true)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")

  mkdir -p "$client_dir"
  : > "$client_console"
  printf '%s\n' \
    'onboardAccessibility:false' \
    'skipMultiplayerWarning:true' \
    'joinedFirstServer:true' \
    'narrator:0' > "$client_dir/options.txt"
  (
    cd "$target_dir" || exit 1
    ulimit -f "$client_log_limit_blocks"
    exec setsid env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 \
      xvfb-run -a -s '-screen 0 1280x720x24 +extension GLX +render -noreset' env \
      JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAVA_TOOL_OPTIONS="$java_tool_options" \
      LIBGL_ALWAYS_SOFTWARE=1 \
      ./gradlew runClient --no-daemon --max-workers=1 --console=plain \
      "${runtime_args[@]}" \
      -PjammarrAcceptanceUsername="$username" \
      -PjammarrAcceptanceServer="127.0.0.1:${port}" \
      -PjammarrAcceptanceGameDir="$client_dir" \
      > "$client_console" 2>&1
  ) &
  pid=$!
  active_client_pid=$pid

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
  local pid deadline result=0
  local -a runtime_args=()
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-quilt && "$quilt_modmenu_gate" == true ]] && runtime_args+=(-PjammarrIncludeModMenu=true)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")

  mkdir -p "$client_dir"
  : > "$client_console"
  : > "$diagnostics"
  printf '%s\n' \
    'onboardAccessibility:false' \
    'skipMultiplayerWarning:true' \
    'joinedFirstServer:true' \
    'narrator:0' > "$client_dir/options.txt"

  if [[ "$label" == "1.7.10-forge" ]]; then
    printf 'deop %s\n' "$username" >&"$fifo_fd"
  elif ! python3 "$repo_root/scripts/minecraft-rcon.py" 127.0.0.1 "$rcon_port" "$rcon_password" \
      "deop $username" > /dev/null 2>&1; then
    # A never-before-seen player is not in ops.json, which some versions report
    # as a command failure. The real non-operator command tree below is authority.
    true
  fi

  (
    cd "$target_dir" || exit 1
    ulimit -f "$client_log_limit_blocks"
    exec setsid env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 \
      xvfb-run -a -s '-screen 0 1280x720x24 +extension GLX +render -noreset' env \
      JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAVA_TOOL_OPTIONS='-Djammarr.acceptance.enabled=true -Djammarr.acceptance.commandProbe=true -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true' \
      LIBGL_ALWAYS_SOFTWARE=1 \
      ./gradlew runClient --no-daemon --max-workers=1 --console=plain \
      "${runtime_args[@]}" \
      -PjammarrAcceptanceUsername="$username" \
      -PjammarrAcceptanceServer="127.0.0.1:${port}" \
      -PjammarrAcceptanceGameDir="$client_dir" \
      > "$client_console" 2>&1
  ) &
  pid=$!
  active_client_pid=$pid

  deadline=$((SECONDS + 600))
  if [[ "$label" == "1.7.10-forge" ]]; then
    while ! grep -Fq 'Acceptance command response: Queue is empty' "$client_console" 2>/dev/null \
        || ! grep -Fq 'Acceptance command response: Operator permission is required' "$client_console" 2>/dev/null; do
      if client_bootstrap_failed "$client_console"; then
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
      if client_bootstrap_failed "$client_console"; then
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

  if (( result == 0 )); then
    if [[ "$label" == "1.7.10-forge" ]]; then
      printf 'op %s\n' "$username" >&"$fifo_fd"
      sleep 1
      printf 'tell %s JAMMARR_ACCEPTANCE_OPERATOR_READY\n' "$username" >&"$fifo_fd"
      deadline=$((SECONDS + 60))
      while ! grep -Fq 'Acceptance command response: Plex=' "$client_console" 2>/dev/null; do
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
      printf 'deop %s\n' "$username" >&"$fifo_fd"
    else
      if ! python3 "$repo_root/scripts/minecraft-rcon.py" 127.0.0.1 "$rcon_port" "$rcon_password" \
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
        if ! python3 "$repo_root/scripts/minecraft-rcon.py" 127.0.0.1 "$rcon_port" "$rcon_password" \
            'jammarr diagnostics' >> "$diagnostics"; then
          echo "$label: diagnostics command failed over authenticated server administration" >&2
          result=1
        fi
      fi
      if (( result == 0 )); then
        deadline=$((SECONDS + 60))
        while ! grep -Fq 'Acceptance Jammarr screen remained open across rendered frames' \
            "$client_console" 2>/dev/null \
            || ! grep -Fq 'Acceptance Jammarr config screen remained open across rendered frames' \
            "$client_console" 2>/dev/null; do
          if ! group_alive "$pid" || (( SECONDS >= deadline )); then
            echo "$label: Jammarr player/config screens did not remain open across rendered frames; see $client_console" >&2
            result=1
            break
          fi
          sleep 1
        done
      fi
      python3 "$repo_root/scripts/minecraft-rcon.py" 127.0.0.1 "$rcon_port" "$rcon_password" \
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
  local pcm_type=${JAMMARR_ALSA_PCM_TYPE:-pipewire}
  local alsoft_drivers=alsa pulse_sink=
  local client_dir="$output_root/$label.audio-$role"
  local client_console="$output_root/$label.audio-$role.console.log"
  local control_file="$output_root/$label.audio-$role.control"
  local leader=false
  local -a cache_args=()
  local -a runtime_args=()
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-quilt && "$quilt_modmenu_gate" == true ]] && runtime_args+=(-PjammarrIncludeModMenu=true)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")
  [[ "$role" == "leader" ]] && leader=true
  case "$label" in
    1.20.1-forge|1.20.1-neoforge|1.20.2-forge|1.20.2-neoforge)
      cache_args+=(--no-configuration-cache)
      ;;
  esac

  mkdir -p "$client_dir/config"
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
      # Prefer OpenAL Soft's native Pulse backend. The ALSA Pulse plugin can
      # silently drop/resample frames while correcting its bridge clock,
      # which makes a healthy client look like it skipped an audio chunk.
      if [[ "$label" == "1.7.10-forge" ]]; then
        alsoft_drivers=pulse
        pulse_sink=$sink
      fi
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
  (
    cd "$target_dir" || exit 1
    ulimit -f "$client_log_limit_blocks"
    exec setsid env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 \
      xvfb-run -a -s '-screen 0 1280x720x24 +extension GLX +render -noreset' env \
      JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAVA_TOOL_OPTIONS="-Djammarr.acceptance.enabled=true -Djammarr.acceptance.audioProbe=true -Djammarr.acceptance.audioLeader=$leader -Djammarr.acceptance.audioControlFile=$control_file -Djammarr.acceptance.pcmTraceDir=$client_dir/pcm-trace -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true" \
      ALSA_CONFIG_PATH="$client_dir/alsa.conf" ALSOFT_CONF="$client_dir/alsoft.conf" \
      ALSOFT_DRIVERS="$alsoft_drivers" PULSE_SINK="$pulse_sink" LIBGL_ALWAYS_SOFTWARE=1 \
      ./gradlew runClient --no-daemon --max-workers=1 --console=plain "${cache_args[@]}" \
      "${runtime_args[@]}" \
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
  while ! grep -Fq 'Acceptance audio state: PLAYING' "$client_console" 2>/dev/null; do
    if grep -Fq 'Acceptance audio state:' "$client_console" 2>/dev/null; then initialized=1; fi
    if client_bootstrap_failed "$client_console"; then
      echo "$label: $role client could not initialize its headless display; see $client_console" >&2
      return 1
    fi
    if grep -Eq 'Acceptance audio state: ERROR|Failed to open OpenAL device|Error starting SoundSystem|NoClassDefFoundError: (javazoom|de/sciss)' \
        "$client_console" 2>/dev/null; then
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
  local leader_log="$output_root/$label.audio-leader.console.log"
  local follower_log="$output_root/$label.audio-follower.console.log"
  local scenario_evidence="$output_root/$label.audio-scenarios.evidence.txt"
  local raw="$output_root/$label.audio-scenario.s16le"
  local metrics="$output_root/$label.audio-scenario.metrics.txt"
  local first result=0

  : > "$scenario_evidence"
  if [[ "$label" == "1.7.10-forge" ]]; then
    printf 'op JammarrAudioA\n' >&"$fifo_fd"
  elif ! python3 "$repo_root/scripts/minecraft-rcon.py" 127.0.0.1 "$rcon_port" "$rcon_password" \
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
  sleep 1
  capture_audio_sink "$sink_leader" "$raw" 4
  if ! audio_capture_is_audible "$raw" "$metrics"; then
    echo "$label: resumed leader did not emit program audio" >&2; return 1
  fi
  printf 'Resume restored captured program audio.\n' >> "$scenario_evidence"

  send_audio_control "$label" leader 'volume:0.2'
  sleep 2
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

  local server_log="$output_root/$label.console.log"
  local transcodes_before transcodes_after
  first=$(wc -l < "$server_log")
  printf 'offline\n' > "$fake_plex_state"
  if [[ "$label" == "1.7.10-forge" ]]; then
    printf 'jammarr reload\n' >&"$fifo_fd"
  else
    if ! python3 "$repo_root/scripts/minecraft-rcon.py" 127.0.0.1 "$rcon_port" "$rcon_password" \
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
  if [[ "$label" == "1.7.10-forge" ]]; then
    printf 'jammarr reload\n' >&"$fifo_fd"
  else
    python3 "$repo_root/scripts/minecraft-rcon.py" 127.0.0.1 "$rcon_port" "$rcon_password" \
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
  capture_audio_sink "$sink_leader" "$raw" 4
  if ! audio_capture_is_audible "$raw" "$metrics"; then
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
  capture_audio_sink "$sink_leader" "$raw" 4
  if ! audio_capture_is_audible "$raw" "$metrics"; then
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

run_two_client_audio() {
  local label=$1
  local target_dir=$2
  local java_home=$3
  local port=$4
  local rcon_port=$5
  local rcon_password=$6
  local fifo_fd=$7
  local sink_prefix="jammarr_${BASHPID}_${label//[^a-zA-Z0-9]/_}"
  local sink_leader="${sink_prefix}_leader" sink_follower="${sink_prefix}_follower"
  local raw_leader="$output_root/$label.audio-leader.s16le"
  local raw_follower="$output_root/$label.audio-follower.s16le"
  local metrics_leader="$output_root/$label.audio-leader.metrics.txt"
  local metrics_follower="$output_root/$label.audio-follower.metrics.txt"
  local evidence="$output_root/$label.two-client-audio.evidence.txt"
  local module leader_pid follower_pid recorder_pid result=0 client_port="$port"
  local proxy_port_file="$output_root/$label.audio-proxy.port"
  local proxy_event_log="$output_root/$label.audio-proxy.jsonl"
  local -a rendered_timing_args=()
  if [[ "$label" == "1.7.10-forge" ]]; then
    rendered_timing_args+=(--maximum-marker-error-ms 120 --maximum-skew-ms 250)
  fi

  module=$(pactl load-module module-null-sink sink_name="$sink_leader" rate=48000 channels=2) || return 1
  active_audio_modules+=("$module")
  module=$(pactl load-module module-null-sink sink_name="$sink_follower" rate=48000 channels=2) || return 1
  active_audio_modules+=("$module")
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
    fi
  fi
  if (( result == 0 )); then
    : > "$raw_leader"
    : > "$raw_follower"
    parec --raw --latency-msec=50 --device="${sink_leader}.monitor" --format=s16le --rate=48000 --channels=2 \
      > "$raw_leader" &
    recorder_pid=$!; active_audio_recorder_pids+=("$recorder_pid")
    parec --raw --latency-msec=50 --device="${sink_follower}.monitor" --format=s16le --rate=48000 --channels=2 \
      > "$raw_follower" &
    recorder_pid=$!; active_audio_recorder_pids+=("$recorder_pid")
    sleep 11
  fi

  for recorder_pid in "${active_audio_recorder_pids[@]}"; do
    kill -TERM "$recorder_pid" 2>/dev/null || true
    wait "$recorder_pid" 2>/dev/null || true
  done
  active_audio_recorder_pids=()
  if (( result == 0 )) && ! audio_capture_is_audible "$raw_leader" "$metrics_leader"; then
    echo "$label: leader sink did not contain observable 997 Hz program audio" >&2
    result=1
  fi
  if (( result == 0 )) && ! audio_capture_is_audible "$raw_follower" "$metrics_follower"; then
    echo "$label: late-join follower sink did not contain observable 997 Hz program audio" >&2
    result=1
  fi
  if (( result == 0 )) && ! python3 "$repo_root/scripts/analyze-audio-timing.py" \
      "$raw_leader" --reference "$raw_follower" --minimum-duration-ms 10000 \
      "${rendered_timing_args[@]}" \
      > "$output_root/$label.audio-timing.json"; then
    echo "$label: deterministic audio timing thresholds failed" >&2
    result=1
  fi
  if (( result == 0 )) && [[ "$label" == "1.7.10-forge" ]]; then
    local role trace
    for role in leader follower; do
      trace=$(find "$output_root/$label.audio-$role/pcm-trace" -type f -name '*.s16le' \
        -printf '%s\t%p\n' 2>/dev/null | sort -nr | head -n 1 | cut -f 2-)
      if [[ -z "$trace" ]] || ! python3 "$repo_root/scripts/analyze-audio-timing.py" \
          "$trace" --sample-rate 44100 --minimum-duration-ms 10000 \
          > "$output_root/$label.audio-$role-fed-timing.json"; then
        echo "$label: $role PCM supplied to Paulscode failed strict timing thresholds" >&2
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
      sed -n '/"duration_ms"\|"marker_count"\|"max_marker_interval_error_ms"\|"max_silence_ms"\|"inter_client_skew_ms"/p' \
        "$output_root/$label.audio-timing.json"
      if [[ "$label" == "1.7.10-forge" ]]; then
        printf 'Strict pre-backend Paulscode feed timing:\n'
        sed -n '/"duration_ms"\|"marker_count"\|"max_marker_interval_error_ms"\|"max_silence_ms"/p' \
          "$output_root/$label.audio-"{leader,follower}"-fed-timing.json"
      fi
      printf 'Network profile: %s\n' "$network_profile"
      printf 'Fake Plex transcode served; follower joined after leader reached PLAYING.\n'
    } > "$evidence"
  fi
  if (( result == 0 )) && [[ "$audio_scenario_gate" == "true" ]]; then
    if ! run_audio_control_scenarios "$label" "$target_dir" "$java_home" "$client_port" \
        "$sink_leader" "$sink_follower" "$leader_pid" "$follower_pid" \
        "$rcon_port" "$rcon_password" "$fifo_fd"; then
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
  local pid group deadline live result=0
  local -a pids=() groups=()
  mapfile -t pids < <({ printf '%s\n' "$root"; process_tree_pids "$root"; } | sort -un)
  for pid in "${pids[@]}"; do
    group=$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ')
    if [[ -n "$group" ]]; then groups+=("$group"); fi
  done
  mapfile -t groups < <(printf '%s\n' "${groups[@]}" | sed '/^$/d' | sort -unr)

  for group in "${groups[@]}"; do stop_group "$group" TERM; done
  deadline=$((SECONDS + seconds))
  while true; do
    live=0
    for group in "${groups[@]}"; do group_alive "$group" && live=1; done
    if (( live == 0 )); then break; fi
    if (( SECONDS >= deadline )); then result=1; break; fi
    sleep 1
  done
  if (( result != 0 )); then
    for group in "${groups[@]}"; do stop_group "$group" KILL; done
    deadline=$((SECONDS + 10))
    while true; do
      live=0
      for group in "${groups[@]}"; do group_alive "$group" && live=1; done
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

run_invalid_config_check_once() {
  local label=$1
  local target_dir=$2
  local run_dir=$3
  local java_home=$4
  local port=$5
  local level_name=$6
  local console_log="$output_root/$label.invalid-config.console.log"
  local latest_log="$run_dir/logs/latest.log"
  local pid result=0
  local -a cache_args=()
  local -a runtime_args=(-PjammarrServerGameDir="$run_dir")
  [[ "$label" == *-quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
  [[ "$label" == *-fabric && -n "$fabric_loader_version" ]] && runtime_args+=(-PjammarrFabricLoaderVersion="$fabric_loader_version")
  case "$label" in
    1.20.1-forge|1.20.1-neoforge|1.20.2-forge|1.20.2-neoforge)
      cache_args+=(--no-configuration-cache)
      ;;
  esac

  install_invalid_config "$run_dir" "$label" "$level_name"
  (
    cd "$target_dir" || exit 1
    exec setsid env JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAMMARR_PLEX_TOKEN="$fake_plex_token" \
      JAVA_TOOL_OPTIONS="-Djammarr.acceptance.enabled=true -Djammarr.acceptance.audioProbe=true -Djammarr.acceptance.helloTimeoutMs=${hello_timeout_ms}" \
      ./gradlew runServer --no-daemon --max-workers=1 --console=plain "${cache_args[@]}" \
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

  local deadline=$((SECONDS + 600))
  while group_alive "$pid"; do
    if grep -Fq 'Invalid Jammarr configuration value for plexUrl' "$console_log" 2>/dev/null; then
      break
    fi
    if (( SECONDS >= deadline )); then
      echo "$label: invalid-configuration rejection timed out" >&2
      result=1
      break
    fi
    sleep 1
  done

  if ! wait_for_group_exit "$pid" 60; then
    echo "$label: server did not fail closed after rejecting invalid Jammarr configuration" >&2
    stop_group "$pid" TERM
    wait_for_group_exit "$pid" 10 || stop_group "$pid" KILL
    result=1
  fi
  wait "$pid" 2>/dev/null || true
  active_server_pid=""
  if ! grep -Fq 'Invalid Jammarr configuration value for plexUrl' "$latest_log" "$console_log" 2>/dev/null \
      && grep -Eq 'Failed to get asset:|SocketTimeoutException|HttpTimeoutException|Could not download' \
        "$console_log" 2>/dev/null; then
    restore_server_config
    return 75
  fi
  if ! grep -Fq 'Invalid Jammarr configuration value for plexUrl' "$latest_log" "$console_log" 2>/dev/null; then
    echo "$label: invalid configuration failure did not identify the rejected key" >&2
    result=1
  fi
  if grep -Fq 'private-pass' "$latest_log" "$console_log" 2>/dev/null; then
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

backup_server_properties() {
  local properties=$1
  local label=$2
  active_properties="$properties"
  active_properties_backup=$(mktemp "$output_root/$label.server-properties.XXXXXX")
  cp -- "$active_properties" "$active_properties_backup"
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
  local console_log="$output_root/$label.console.log"
  local fifo_dir fifo fifo_fd pid server_pid server_group port rcon_port rcon_password result=0
  local fake_plex_port fake_request_start plex_deadline level_name probe_output
  local -a probe_args=()
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
  port=$(sed -n 's/^server-port=\([0-9][0-9]*\)$/\1/p' "$run_dir/server.properties" | tail -n 1)
  if [[ -z "$port" ]]; then
    echo "$label: unable to resolve server port" >&2
    return 1
  fi
  if ss -ltnH "sport = :$port" | grep -q .; then
    echo "$label: port $port is already in use" >&2
    return 1
  fi
  rcon_port=$((default_port + 1000))
  rcon_password="jammarr-gate-${default_port}"
  if [[ "$label" != "1.7.10-forge" ]] && ss -ltnH "sport = :$rcon_port" | grep -q .; then
    echo "$label: RCON port $rcon_port is already in use" >&2
    return 1
  fi
  active_game_port=$port
  if [[ "$label" != "1.7.10-forge" ]]; then active_rcon_port=$rcon_port; fi
  backup_server_properties "$run_dir/server.properties" "$label"
  # Keep the gate isolated from developer worlds and from damage left by an
  # interrupted prior run. The run directories are ignored build state.
  level_name=jammarr-gate-world
  set_property "$run_dir/server.properties" level-name "$level_name"
  set_property "$run_dir/server.properties" online-mode false
  set_property "$run_dir/server.properties" enforce-secure-profile false
  set_property "$run_dir/server.properties" sync-chunk-writes false
  isolate_gate_world "$run_dir" "$label" "$level_name"
  if [[ "$label" == "1.7.10-forge" ]]; then
    # Vanilla 1.7.10 closes an RCON connection after its authentication packet,
    # so use its working console input instead of weakening clean-shutdown proof.
    set_property "$run_dir/server.properties" enable-rcon false
  else
    set_property "$run_dir/server.properties" enable-rcon true
    set_property "$run_dir/server.properties" rcon.port "$rcon_port"
    set_property "$run_dir/server.properties" rcon.password "$rcon_password"
  fi
  case "$label" in
    1.20.1-forge|1.20.1-neoforge|1.20.2-forge|1.20.2-neoforge)
      cache_args+=(--no-configuration-cache)
      ;;
  esac

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
      ./gradlew runServer --no-daemon --max-workers=1 --console=plain "${cache_args[@]}" \
      "${runtime_args[@]}" \
      < "$fifo" > "$console_log" 2>&1
  ) &
  pid=$!
  active_server_pid=$pid

  local startup_deadline=$((SECONDS + 180))
  while :; do
    if grep -Eq 'Done \([^)]*\)! For help' "$console_log" 2>/dev/null; then
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

  if (( result == 0 )) && [[ "$protocol_client_gate" == "true" ]]; then
    if ! run_wrong_protocol_client "$label" "$target_dir" "$java_home" "$port" "$console_log"; then
      result=1
    fi
  fi

  if (( result == 0 )) && [[ "$delayed_hello_gate" == "true" ]]; then
    if ! run_delayed_hello_client "$label" "$target_dir" "$java_home" "$port"; then
      result=1
    fi
  fi

  if (( result == 0 )) && [[ "$command_client_gate" == "true" ]]; then
    if ! run_command_client "$label" "$target_dir" "$java_home" "$port" "$console_log" \
        "$rcon_port" "$rcon_password" "$fifo_fd"; then
      result=1
    fi
  fi

  if (( result == 0 )) && [[ "$audio_client_gate" == "true" ]]; then
    if ! run_two_client_audio "$label" "$target_dir" "$java_home" "$port" \
        "$rcon_port" "$rcon_password" "$fifo_fd"; then
      result=1
    fi
  fi

  if (( result == 0 )) && [[ "$label" == "1.7.10-forge" ]]; then
    if ! run_missing_hello_client "$label" "$target_dir" "$java_home" "$port" "$console_log"; then
      result=1
    fi
  elif (( result == 0 )); then
    probe_output="$output_root/$label.missing-client.json"
    case "$label" in
      26.1.2-*)
        # Minecraft 26.1 can close legacy protocol -1 status queries before a
        # response; use the protocol declared by these pinned target builds.
        probe_args+=(--protocol 775 --version 26.1.2)
        ;;
      26.2-*)
        probe_args+=(--protocol 776 --version 26.2)
        ;;
    esac
    if ! python3 "$repo_root/scripts/minecraft-login-probe.py" 127.0.0.1 "$port" --timeout 35 \
        "${probe_args[@]}" \
        > "$probe_output" 2>&1; then
      if grep -Fq '"closed": true' "$probe_output" \
          && missing_client_rejection_logged "$latest_log" "$console_log"; then
        printf '%s\n' 'Socket closure paired with the server client-facing required-mod rejection log.' \
          > "$output_root/$label.missing-client.server.txt"
      else
        echo "$label: missing-client probe did not observe a clear required-mod disconnect; see $probe_output" >&2
        result=1
      fi
    fi
    # Let the server finish its disconnect/player-removal tick before the
    # shutdown probe begins; newer versions otherwise may overlap chunk unload
    # with the immediate save-all performed by stop.
    sleep 2
  fi

  if [[ "$label" == "1.7.10-forge" ]]; then
    printf 'stop\n' >&"$fifo_fd"
  elif ! python3 "$repo_root/scripts/minecraft-rcon.py" 127.0.0.1 "$rcon_port" "$rcon_password" stop \
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

  if [[ ! -f "$latest_log" ]] || ! grep -Eq 'Stopping (the )?server' "$latest_log" \
      || ! grep -q 'Saving players' "$latest_log"; then
    echo "$label: log does not prove a clean Minecraft shutdown" >&2
    result=1
  fi
  if [[ "$label" == "1.7.10-forge" ]]; then
    if ! grep -q 'Initializing Jammarr 1.0.2 for Forge 1.7.10 protocol 5' "$run_dir/logs/fml-server-latest.log"; then
      echo "$label: FML log does not prove Jammarr initialized" >&2
      result=1
    fi
  elif ! grep -Eiq 'jammarr' "$latest_log"; then
    echo "$label: server log does not prove Jammarr loaded" >&2
    result=1
  fi
  if grep -Eiq 'Failed to start the minecraft server|ModLoadingException|Preparing crash report|Encountered an unexpected exception' \
      "$latest_log" "$console_log"; then
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
start_fake_plex || exit 1
for target in "${targets[@]}"; do
  IFS='|' read -r label relative_dir java_home port <<< "$target"
  if [[ "$requested" != "all" && "$requested" != "$label"
      && !( "$requested" == "quilt" && "$label" == *-quilt )
      && !( "$requested" == "fabric" && "$label" == *-fabric ) ]]; then
    continue
  fi
  matched=1
  run_target "$label" "$relative_dir" "$java_home" "$port" || failed=1
done

if (( matched == 0 )); then
  echo "Unknown target '$requested'" >&2
  exit 2
fi
if (( failed != 0 )); then
  exit 1
fi
echo "Dedicated-server gate passed for $requested"
