#!/usr/bin/env bash
set -uo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output_root="$repo_root/build/dedicated-server-gate"
mkdir -p "$output_root"
fake_plex_token="jammarr-dedicated-gate-token"
fake_plex_port_file="$output_root/fake-plex.port"
fake_plex_request_log="$output_root/fake-plex.requests.tsv"
fake_plex_pid=""
active_config=""
active_config_backup=""
active_config_existed=0
active_properties=""
active_properties_backup=""

targets=(
  "1.7.10-forge|platforms/mc1.7.10/forge|/usr/lib/jvm/java-26-openjdk|25695"
  "1.20.1-fabric|platforms/mc1.20.1/fabric|/usr/lib/jvm/java-21-openjdk|25571"
  "1.20.1-forge|platforms/mc1.20.1/forge|/usr/lib/jvm/java-21-openjdk|25572"
  "1.20.1-neoforge|platforms/mc1.20.1/neoforge|/usr/lib/jvm/java-21-openjdk|25574"
  "1.20.2-fabric|platforms/mc1.20.2/fabric|/usr/lib/jvm/java-21-openjdk|25576"
  "1.20.2-forge|platforms/mc1.20.2/forge|/usr/lib/jvm/java-21-openjdk|25578"
  "1.20.2-neoforge|platforms/mc1.20.2/neoforge|/usr/lib/jvm/java-21-openjdk|25580"
  "1.21.1-fabric|platforms/mc1.21.1/fabric|/usr/lib/jvm/java-21-openjdk|25581"
  "1.21.1-forge|platforms/mc1.21.1/forge|/usr/lib/jvm/java-21-openjdk|25582"
  "1.21.1-neoforge|.|/usr/lib/jvm/java-21-openjdk|25566"
  "26.1.2-fabric|platforms/mc26.1.2/fabric|/usr/lib/jvm/java-26-openjdk|25642"
  "26.1.2-forge|platforms/mc26.1.2/forge|/usr/lib/jvm/java-26-openjdk|25643"
  "26.1.2-neoforge|platforms/mc26.1.2/neoforge|/usr/lib/jvm/java-26-openjdk|25644"
)

requested=${1:-all}
protocol_client_gate=${JAMMARR_PROTOCOL_CLIENT_GATE:-false}

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

cleanup_all() {
  restore_server_config
  restore_server_properties
  if [[ -n "$fake_plex_pid" ]]; then
    kill "$fake_plex_pid" 2>/dev/null || true
    wait "$fake_plex_pid" 2>/dev/null || true
  fi
  rm -f -- "$fake_plex_port_file"
}

trap cleanup_all EXIT
trap 'exit 130' INT TERM

start_fake_plex() {
  rm -f -- "$fake_plex_port_file"
  python3 "$repo_root/scripts/fake-plex-server.py" \
    --port-file "$fake_plex_port_file" --request-log "$fake_plex_request_log" \
    --token "$fake_plex_token" &
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

run_wrong_protocol_client() {
  local label=$1
  local target_dir=$2
  local java_home=$3
  local port=$4
  local server_console=$5
  local client_dir="$output_root/$label.wrong-protocol-client"
  local client_console="$output_root/$label.wrong-protocol-client.console.log"
  local evidence="$output_root/$label.wrong-protocol-client.server.txt"
  local pid deadline result=0

  mkdir -p "$client_dir"
  : > "$client_console"
  printf '%s\n' \
    'onboardAccessibility:false' \
    'skipMultiplayerWarning:true' \
    'joinedFirstServer:true' \
    'narrator:0' > "$client_dir/options.txt"
  (
    cd "$target_dir" || exit 1
    exec setsid xvfb-run -a env \
      JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAVA_TOOL_OPTIONS='-Djammarr.acceptance.enabled=true -Djammarr.acceptance.clientProtocol=4 -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true' \
      LIBGL_ALWAYS_SOFTWARE=1 \
      ./gradlew runClient --no-daemon --max-workers=1 --console=plain \
      -PjammarrAcceptanceUsername=JammarrMismatch \
      -PjammarrAcceptanceServer="127.0.0.1:${port}" \
      -PjammarrAcceptanceGameDir="$client_dir" \
      > "$client_console" 2>&1
  ) &
  pid=$!

  deadline=$((SECONDS + 600))
  while ! grep -Fq 'Jammarr protocol mismatch: server requires' "$server_console" 2>/dev/null \
      || ! grep -Fq 'Client disconnected with reason: Jammarr protocol mismatch: server requires' "$client_console" 2>/dev/null; do
    if ! group_alive "$pid"; then
      echo "$label: wrong-protocol client exited before the server rejected its hello; see $client_console" >&2
      result=1
      break
    fi
    if (( SECONDS >= deadline )); then
      echo "$label: wrong-protocol client was not rejected within 600 seconds; see $client_console" >&2
      result=1
      break
    fi
    sleep 1
  done

  if (( result == 0 )); then
    {
      grep -F 'Jammarr protocol mismatch: server requires' "$server_console" | tail -n 1
      grep -F 'Client disconnected with reason: Jammarr protocol mismatch: server requires' "$client_console" | tail -n 1
    } > "$evidence"
  fi
  stop_process_tree "$pid" TERM
  if ! wait_for_process_tree_exit "$pid" 20; then
    stop_process_tree "$pid" KILL
    wait_for_process_tree_exit "$pid" 10 || result=1
  fi
  wait "$pid" 2>/dev/null || true
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

run_invalid_config_check() {
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
      ./gradlew runServer --no-daemon --max-workers=1 --console=plain "${cache_args[@]}" \
      < /dev/null > "$console_log" 2>&1
  ) &
  pid=$!

  local deadline=$((SECONDS + 180))
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
  local latest_log="$run_dir/logs/latest.log"
  local console_log="$output_root/$label.console.log"
  local fifo_dir fifo fifo_fd pid server_pid port rcon_port rcon_password result=0
  local fake_plex_port fake_request_start plex_deadline level_name probe_output
  local -a probe_args=()
  local -a cache_args=()

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
  backup_server_properties "$run_dir/server.properties" "$label"
  # Keep the gate isolated from developer worlds and from damage left by an
  # interrupted prior run. The run directories are ignored build state.
  level_name=jammarr-gate-world
  set_property "$run_dir/server.properties" level-name "$level_name"
  set_property "$run_dir/server.properties" online-mode false
  set_property "$run_dir/server.properties" enforce-secure-profile false
  set_property "$run_dir/server.properties" sync-chunk-writes false
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
    return 1
  fi

  fake_plex_port=$(<"$fake_plex_port_file")
  fake_request_start=$(wc -l < "$fake_plex_request_log")
  install_fake_plex_config "$run_dir" "$label" "$level_name" "$fake_plex_port"

  fifo_dir=$(mktemp -d "$output_root/$label.fifo.XXXXXX")
  fifo="$fifo_dir/stdin"
  mkfifo "$fifo"
  exec {fifo_fd}<>"$fifo"
  echo "$label: starting on port $port"
  (
    cd "$target_dir" || exit 1
    exec setsid env JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAMMARR_PLEX_TOKEN="$fake_plex_token" \
      ./gradlew runServer --no-daemon --max-workers=1 --console=plain "${cache_args[@]}" \
      < "$fifo" > "$console_log" 2>&1
  ) &
  pid=$!

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

  if (( result == 0 )) && [[ "$protocol_client_gate" == "true" ]]; then
    if ! run_wrong_protocol_client "$label" "$target_dir" "$java_home" "$port" "$console_log"; then
      result=1
    fi
  fi

  if (( result == 0 )) && [[ "$label" != "1.7.10-forge" ]]; then
    probe_output="$output_root/$label.missing-client.json"
    if [[ "$label" == 26.1.2-* ]]; then
      # Minecraft 26.1 can close legacy protocol -1 status queries before a
      # response; use the protocol declared by these pinned target builds.
      probe_args+=(--protocol 775 --version 26.1.2)
    fi
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
  if ! wait_for_group_exit "$pid" 60; then
    server_pid=$(ss -ltnp "sport = :$port or sport = :$rcon_port" \
      | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -n 1)
    if [[ -n "$server_pid" ]]; then
      kill -TERM "$server_pid" 2>/dev/null || true
    else
      echo "$label: console stop timed out and the listening server process could not be identified" >&2
      stop_group "$pid" TERM
      result=1
    fi
    if ! wait_for_group_exit "$pid" 30; then
      echo "$label: graceful shutdown timed out" >&2
      stop_group "$pid" KILL
      wait_for_group_exit "$pid" 10 || true
      result=1
    fi
  fi
  wait "$pid" 2>/dev/null || true
  exec {fifo_fd}>&-
  rm -f -- "$fifo"
  rmdir -- "$fifo_dir"

  if [[ ! -f "$latest_log" ]] || ! grep -Eq 'Stopping (the )?server' "$latest_log" \
      || ! grep -q 'Saving players' "$latest_log"; then
    echo "$label: log does not prove a clean Minecraft shutdown" >&2
    result=1
  fi
  if [[ "$label" == "1.7.10-forge" ]]; then
    if ! grep -q 'Initializing Jammarr 1.0.0 for Forge 1.7.10 protocol 5' "$run_dir/logs/fml-server-latest.log"; then
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
  if group_alive "$pid"; then
    echo "$label: process group $pid remains alive after shutdown" >&2
    result=1
  fi

  restore_server_config
  restore_server_properties

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
  if [[ "$requested" != "all" && "$requested" != "$label" ]]; then continue; fi
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
