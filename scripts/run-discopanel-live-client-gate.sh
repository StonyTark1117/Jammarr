#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
runtime=${1:-1.7.10-forge}
panel_url=${DISCOPANEL_URL:-http://192.168.1.42:8080}
version=${JAMMARR_EXPECTED_VERSION:-$(sed -n 's/^mod_version=//p' "$repo_root/gradle.properties")}
capture_seconds=${JAMMARR_LIVE_CAPTURE_SECONDS:-31}
client_heap_mb=${JAMMARR_LIVE_CLIENT_HEAP_MB:-1536}
token_env=${DISCOPANEL_TOKEN_ENV:-DISCOPANEL_TOKEN}

if [[ -z ${!token_env:-} ]]; then
  echo "Set $token_env in the process environment" >&2
  exit 2
fi
if [[ ! "$capture_seconds" =~ ^[0-9]+$ ]] || (( capture_seconds < 10 || capture_seconds > 300 )); then
  echo "JAMMARR_LIVE_CAPTURE_SECONDS must be an integer from 10 through 300" >&2
  exit 2
fi
if [[ ! "$client_heap_mb" =~ ^[0-9]+$ ]] || (( client_heap_mb < 1024 || client_heap_mb > 4096 )); then
  echo "JAMMARR_LIVE_CLIENT_HEAP_MB must be an integer from 1024 through 4096" >&2
  exit 2
fi

mkdir -p "$repo_root/build/discopanel-live-client-gate"
session_dir=$(mktemp -d "$repo_root/build/discopanel-live-client-gate/${runtime}.XXXXXX")
ready_file="$session_dir/server-ready.json"
release_file="$session_dir/server-release"
server_console="$session_dir/server-smoke.console.log"
server_evidence="$session_dir/server-evidence"
acceptance_level="jammarr-live-${session_dir##*.}"
recovery_tool="$repo_root/scripts/recover-discopanel-live-client-sessions.py"

exec 9>"$repo_root/build/.dedicated-server-gate.lock"
if ! flock -n 9; then
  echo "Another Jammarr client/server gate owns the shared runtime" >&2
  exit 2
fi

# A shell trap cannot run after SIGKILL or an execution-host teardown. Recover
# any credential-free transaction journal left by such an interruption before
# requiring the complete DiscPanel fleet to be stopped for a new matrix row.
python3 "$recovery_tool" recover-pending \
  --url "$panel_url" --token-env "$token_env" --expected-version "$version" \
  --evidence-root "$repo_root/build/discopanel-live-client-gate"

gate_line=$(python3 "$repo_root/scripts/target-matrix.py" gate-lines "$repo_root/gradle/targets.json" \
  | awk -F '|' -v runtime="$runtime" '$1 == runtime { print; found=1 } END { if (!found) exit 1 }') || {
  echo "Unknown runtime: $runtime" >&2
  exit 2
}
IFS='|' read -r label target_dir build_java _port _control _command audio_profile _log_profile \
  disable_configuration_cache client_task _server_task _stress _optional <<< "$gate_line"
case "$audio_profile" in
  legacy-openal|modern) ;;
  *) echo "$runtime does not expose a supported audible client profile" >&2; exit 2 ;;
esac
case "$build_java" in
  8) java_home=${JAMMARR_JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk} ;;
  17) java_home=${JAMMARR_JAVA17_HOME:-/usr/lib/jvm/java-17-openjdk} ;;
  21) java_home=${JAMMARR_JAVA21_HOME:-/usr/lib/jvm/java-21-openjdk} ;;
  26) java_home=${JAMMARR_JAVA26_HOME:-/usr/lib/jvm/java-26-openjdk} ;;
  *) echo "No gate JDK configured for Java $build_java" >&2; exit 2 ;;
esac
target_dir="$repo_root/$target_dir"
client_gradle_args=()
[[ "$runtime" == *-quilt ]] && client_gradle_args+=(-PjammarrRuntimeLoader=quilt)
[[ "$disable_configuration_cache" == true ]] && client_gradle_args+=(--no-configuration-cache)
server_hold_mod_args=(--hold-disable-mod-prefix cinemarr-)
# DiscPanel profiles are shared with Cinemarr testing. Disable only Cinemarr's
# artifact while retaining every loader dependency (Fabric API, StationAPI,
# OSL, and similar), then restore the exact mod record during holder cleanup.

read -r game_host game_port server_id < <(
  cd "$repo_root"
  python3 - "$runtime" "$panel_url" "$token_env" "$acceptance_level" <<'PY'
import importlib.util
import os
import sys
from pathlib import Path
from urllib.parse import urlparse

runtime, panel_url, token_env, acceptance_level = sys.argv[1:]
script = Path("scripts/run-discopanel-server-smoke.py")
spec = importlib.util.spec_from_file_location("live_gate_smoke", script)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
assert spec.loader
spec.loader.exec_module(module)
artifacts = module.deployment.verified_release_artifacts(
    Path("build/releases"), Path("gradle/targets.json"),
    module.deployment.project_version(Path("gradle.properties")),
)
target = next(
    item for item in module.deployment.deployment_targets(
        Path("gradle/targets.json"), Path("build/releases"), artifacts
    ) if item.runtime == runtime
)
panel = module.reconciler.DiscPanel(panel_url, os.environ[token_env], 60)
matches = [server for server in panel.list_servers() if server.get("name") == target.server_name]
if len(matches) != 1:
    raise SystemExit(f"{runtime} maps to {len(matches)} DiscPanel profiles")
server = panel.get_server(str(matches[0]["id"]))
if server.get("status") != module.reconciler.STATUS_STOPPED or server.get("autoStart"):
    raise SystemExit(f"{runtime} is not safely stopped/autostart-off")
root_names = {
    entry.get("name") or str(entry.get("path", "")).rstrip("/").rsplit("/", 1)[-1]
    for entry in panel.list_files(str(server["id"]), "")
}
if acceptance_level in root_names:
    raise SystemExit(f"isolated level already exists: {acceptance_level}")
host = urlparse(panel_url).hostname
port = server.get("port")
if not host or not isinstance(port, int):
    raise SystemExit("DiscPanel did not provide a usable game host and port")
print(host, port, server["id"])
PY
)

server_pid=""
operator_promoted=false
operator_file_prepared=false
acceptance_level_removed=false
client_pids=()
recorder_pids=()
sink_modules=()
leader_username="JmLiveA${session_dir##*.}"
follower_username="JmLiveB${session_dir##*.}"
terminal_client_pattern='Acceptance audio state: ERROR|Failed to open OpenAL device|Only one OpenAL context|UnsatisfiedLinkError: org\.lwjgl\.openal|available update failed: Broken pipe|Client disconnected with reason:|Couldn.t connect to server|Connection refused|Failed to connect to the server|Connection timed out'

remove_stale_gate_sinks() {
  local module
  while read -r module; do
    [[ "$module" =~ ^[0-9]+$ ]] || continue
    pactl unload-module "$module"
  done < <(
    pactl list short modules \
      | awk '$2 == "module-null-sink" && $0 ~ /sink_name=jammarr_live_/ { print $1 }'
  )
  if pactl list short modules | grep -Eq 'module-null-sink.*sink_name=jammarr_live_'; then
    echo "$runtime could not remove stale private live-gate audio sinks" >&2
    return 1
  fi
}

server_command() {
  local command=$1
  (
    cd "$repo_root"
    python3 - "$panel_url" "$token_env" "$server_id" "$command" <<'PY'
import importlib.util
import os
import sys
from pathlib import Path

panel_url, token_env, server_id, command = sys.argv[1:]
script = Path("scripts/reconcile-discopanel-test-servers.py")
spec = importlib.util.spec_from_file_location("live_gate_panel", script)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
assert spec.loader
spec.loader.exec_module(module)
panel = module.DiscPanel(panel_url, os.environ[token_env], 60)
response = panel.call(
    "ServerService", "SendCommand",
    {"id": server_id, "command": command, "silent": True},
)
if response.get("success") is not True:
    raise SystemExit("DiscPanel rejected the silent server command")
PY
  )
}

prepare_babric_operator_file() {
  (
    cd "$repo_root"
    python3 - "$panel_url" "$token_env" "$server_id" "$leader_username" \
      "$session_dir/operator-file-original" "$session_dir/operator-file-state" <<'PY'
import importlib.util
import os
import sys
from pathlib import Path

panel_url, token_env, server_id, username, original_path, state_path = sys.argv[1:]
script = Path("scripts/reconcile-discopanel-test-servers.py")
spec = importlib.util.spec_from_file_location("live_gate_operator_prepare", script)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
assert spec.loader
spec.loader.exec_module(module)
panel = module.DiscPanel(panel_url, os.environ[token_env], 60)
server = panel.get_server(server_id)
if server.get("status") != module.STATUS_STOPPED or server.get("autoStart"):
    raise SystemExit("refusing operator-file preparation while the server is not safely stopped")
names = {
    entry.get("name") or str(entry.get("path", "")).rstrip("/").rsplit("/", 1)[-1]
    for entry in panel.list_files(server_id, "")
}
exists = "ops.txt" in names
original = panel.get_file(server_id, "ops.txt") if exists else b""
Path(original_path).write_bytes(original)
Path(state_path).write_text("present\n" if exists else "absent\n", "ascii")
lines = original.decode("utf-8", "replace").splitlines()
if username.lower() not in {line.strip().lower() for line in lines}:
    lines.append(username)
updated = ("\n".join(lines) + "\n").encode("utf-8")
panel.update_file(server_id, "ops.txt", updated)
if panel.get_file(server_id, "ops.txt") != updated:
    raise SystemExit("DiscPanel did not preserve the temporary Babric operator file")
PY
  )
}

restore_babric_operator_file() {
  [[ -f "$session_dir/operator-file-state" ]] || return 0
  (
    cd "$repo_root"
    python3 - "$panel_url" "$token_env" "$server_id" \
      "$session_dir/operator-file-original" "$session_dir/operator-file-state" <<'PY'
import importlib.util
import os
import sys
from pathlib import Path

panel_url, token_env, server_id, original_path, state_path = sys.argv[1:]
script = Path("scripts/reconcile-discopanel-test-servers.py")
spec = importlib.util.spec_from_file_location("live_gate_operator_restore", script)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
assert spec.loader
spec.loader.exec_module(module)
panel = module.DiscPanel(panel_url, os.environ[token_env], 60)
server = panel.get_server(server_id)
if server.get("status") != module.STATUS_STOPPED or server.get("autoStart"):
    raise SystemExit("refusing operator-file restore while the server is not safely stopped")
state = Path(state_path).read_text("ascii").strip()
original = Path(original_path).read_bytes()
names = {
    entry.get("name") or str(entry.get("path", "")).rstrip("/").rsplit("/", 1)[-1]
    for entry in panel.list_files(server_id, "")
}
if state == "present":
    panel.update_file(server_id, "ops.txt", original)
    if panel.get_file(server_id, "ops.txt") != original:
        raise SystemExit("DiscPanel did not restore the original Babric operator file")
elif state == "absent":
    if "ops.txt" in names:
        panel.delete_file(server_id, "ops.txt")
    remaining = {
        entry.get("name") or str(entry.get("path", "")).rstrip("/").rsplit("/", 1)[-1]
        for entry in panel.list_files(server_id, "")
    }
    if "ops.txt" in remaining:
        raise SystemExit("DiscPanel retained the temporary Babric operator file")
else:
    raise SystemExit("invalid Babric operator-file state")
PY
  )
  touch "$session_dir/operator-file-restored"
}

remove_acceptance_level() {
  (
    cd "$repo_root"
    python3 - "$panel_url" "$token_env" "$server_id" "$acceptance_level" <<'PY'
import importlib.util
import os
import re
import sys
from pathlib import Path

panel_url, token_env, server_id, level = sys.argv[1:]
if not re.fullmatch(r"jammarr-live-[A-Za-z0-9]{6}", level):
    raise SystemExit("refusing to remove an invalid live-gate level name")
script = Path("scripts/reconcile-discopanel-test-servers.py")
spec = importlib.util.spec_from_file_location("live_gate_cleanup", script)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
assert spec.loader
spec.loader.exec_module(module)
panel = module.DiscPanel(panel_url, os.environ[token_env], 60)
server = panel.get_server(server_id)
if server.get("status") != module.STATUS_STOPPED or server.get("autoStart"):
    raise SystemExit("refusing isolated-level removal while the server is not safely stopped")

def root_names():
    return {
        entry.get("name") or str(entry.get("path", "")).rstrip("/").rsplit("/", 1)[-1]
        for entry in panel.list_files(server_id, "")
    }

if level in root_names():
    panel.delete_file(server_id, level)
if level in root_names():
    raise SystemExit("DiscPanel retained the isolated live-gate level after deletion")
PY
  )
}

process_tree_pids() {
  local parent=$1 child
  for child in $(pgrep -P "$parent" 2>/dev/null || true); do
    printf '%s\n' "$child"
    process_tree_pids "$child"
  done
}

terminate_tree() {
  local root=$1 signal=${2:-TERM}
  local -a pids=()
  mapfile -t pids < <({ process_tree_pids "$root"; printf '%s\n' "$root"; } | sort -unr)
  if (( ${#pids[@]} )); then kill "-$signal" -- "${pids[@]}" 2>/dev/null || true; fi
}

session_process_pids() {
  local process cmdline
  for process in /proc/[0-9]*; do
    [[ -r "$process/cmdline" ]] || continue
    cmdline=$(tr '\0' ' ' < "$process/cmdline" 2>/dev/null) || continue
    if [[ "$cmdline" == *"$session_dir"* ]]; then
      printf '%s\n' "${process##*/}"
    fi
  done
}

terminate_session_processes() {
  local signal=$1 pid
  while read -r pid; do
    [[ -n "$pid" ]] || continue
    kill "-$signal" "$pid" 2>/dev/null || true
  done < <(session_process_pids)
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  for pid in "${recorder_pids[@]}"; do
    kill -TERM "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  done
  recorder_pids=()
  if [[ "$operator_promoted" == true && -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    if server_command "deop $leader_username"; then
      operator_promoted=false
    else
      echo "$runtime could not remove the temporary test operator" >&2
      status=1
    fi
  fi
  for pid in "${client_pids[@]}"; do terminate_tree "$pid" TERM; done
  sleep 1
  for pid in "${client_pids[@]}"; do terminate_tree "$pid" KILL; wait "$pid" 2>/dev/null || true; done
  if [[ -n "$server_pid" ]]; then
    touch "$release_file"
    local deadline=$((SECONDS + 190))
    while kill -0 "$server_pid" 2>/dev/null && (( SECONDS < deadline )); do sleep 1; done
    if kill -0 "$server_pid" 2>/dev/null; then
      kill -TERM "$server_pid" 2>/dev/null || true
      wait "$server_pid" 2>/dev/null || true
      status=1
    else
      wait "$server_pid" || status=1
    fi
  fi
  if [[ -f "$session_dir/recovery-pending.json" ]]; then
    if python3 "$recovery_tool" recover-owned \
      --url "$panel_url" --token-env "$token_env" --expected-version "$version" \
      --session-dir "$session_dir"; then
      acceptance_level_removed=true
    else
      echo "$runtime could not recover its durable DiscPanel transaction" >&2
      status=1
    fi
  fi
  # Gradle may detach a production client after xvfb-run exits, so the
  # launcher's original PID is not a sufficient cleanup boundary. Every gate
  # path is unique; terminate only processes whose command line still names
  # this exact session, then fail if even SIGKILL leaves one behind.
  terminate_session_processes TERM
  sleep 1
  terminate_session_processes KILL
  sleep 1
  if [[ -n $(session_process_pids) ]]; then
    echo "$runtime retained a process for $session_dir after cleanup" >&2
    status=1
  fi
  if [[ "$operator_file_prepared" == true ]]; then
    if ! restore_babric_operator_file; then
      echo "$runtime could not restore the Babric operator file" >&2
      status=1
    fi
  fi
  if [[ "$acceptance_level_removed" == false ]]; then
    if remove_acceptance_level; then
      acceptance_level_removed=true
    else
      echo "$runtime could not remove the isolated live-gate level" >&2
      status=1
    fi
  fi
  for module in "${sink_modules[@]}"; do pactl unload-module "$module" >/dev/null 2>&1 || true; done
  printf 'LIVE_CLIENT_GATE_EVIDENCE %s\n' "$session_dir"
  exit "$status"
}
trap cleanup EXIT INT TERM

# The repository-wide gate lock proves no other supported gate is active. Any
# remaining sink with this private prefix is therefore orphaned evidence from
# an interrupted older run and can make PipeWire/OpenAL fail or stall later
# clients. Remove only that exact test-owned prefix before allocating new ones.
remove_stale_gate_sinks

# Snapshot only the non-secret values this gate changes. The recovery helper
# can therefore reverse an abruptly orphaned transaction without persisting the
# Plex token, unrelated Docker overrides, or complete configuration files.
python3 "$recovery_tool" snapshot \
  --runtime "$runtime" --server-id "$server_id" --level "$acceptance_level" \
  --session-dir "$session_dir" --url "$panel_url" --token-env "$token_env" \
  --expected-version "$version"

sink_prefix="jammarr_live_${BASHPID}_${runtime//[^a-zA-Z0-9]/_}"
sink_leader="${sink_prefix}_leader"
sink_follower="${sink_prefix}_follower"
sink_modules+=("$(pactl load-module module-null-sink sink_name="$sink_leader" rate=48000 channels=2)")
sink_modules+=("$(pactl load-module module-null-sink sink_name="$sink_follower" rate=48000 channels=2)")

if [[ "$runtime" == b1.7.3-babric ]]; then
  operator_file_prepared=true
  prepare_babric_operator_file
fi

(
  cd "$repo_root"
  exec python3 -u scripts/run-discopanel-server-smoke.py \
    --runtime "$runtime" --url "$panel_url" --expected-version "$version" \
    --apply --confirm-runtime "$runtime" --token-env "$token_env" \
    --evidence-dir "$server_evidence" \
    --hold-ready-file "$ready_file" --hold-release-file "$release_file" --hold-timeout 1800 \
    --hold-level-name "$acceptance_level" --hold-config-source-world world \
    --hold-bootstrap-level --hold-offline-mode "${server_hold_mod_args[@]}"
) > "$server_console" 2>&1 &
server_pid=$!

deadline=$((SECONDS + 900))
while [[ ! -s "$ready_file" ]]; do
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "$runtime server holder exited before client readiness; see $server_console" >&2
    exit 1
  fi
  if (( SECONDS >= deadline )); then
    echo "$runtime server holder did not become ready within 900 seconds" >&2
    exit 1
  fi
  sleep 1
done

start_client() {
  local role=$1 username=$2 sink=$3
  local game_dir="$session_dir/client-$role"
  local console="$session_dir/client-$role.console.log"
  local control="$session_dir/client-$role.control"
  local alsoft_drivers=alsa pulse_sink=$sink
  local gradle_jvmargs="-Xmx${client_heap_mb}m -XX:MaxMetaspaceSize=512m"
  if [[ "$audio_profile" == legacy-openal ]]; then
    alsoft_drivers=pulse
  fi
  mkdir -p "$game_dir/config" "$game_dir/pcm-trace"
  : > "$control"
  printf '%s\n' \
    'onboardAccessibility:false' \
    'skipMultiplayerWarning:true' \
    'joinedFirstServer:true' \
    'narrator:0' \
    'soundCategory_master:1.0' \
    'soundCategory_music:1.0' > "$game_dir/options.txt"
  printf '%s\n' '# Generated by the DiscPanel live-client gate.' \
    'enabled = true' 'volume = 1.0' > "$game_dir/config/jammarr-client.toml"
  printf '%s\n' '[general]' 'frequency = 48000' 'period_size = 1024' 'periods = 8' \
    > "$game_dir/alsoft.conf"
  # Modern OpenAL stays on its ALSA backend, but ALSA reaches the private
  # Pulse null sink through the pulse plugin. Addressing a Pulse-created sink
  # as a native PipeWire playback_node can initialize successfully and then
  # lose the stream with `available update failed: Broken pipe` before music
  # starts. Legacy OpenAL continues to use its proven native Pulse backend.
  printf '%s\n' \
    'pcm.!default {' \
    '  type pulse' \
    '}' \
    'ctl.!default {' \
    '  type pulse' \
    '}' > "$game_dir/alsa.conf"
  (
    cd "$target_dir"
    exec setsid env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 \
      xvfb-run -a -s '-screen 0 1280x720x24 +extension GLX +render -noreset' env \
      JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      JAVA_TOOL_OPTIONS="-Xmx${client_heap_mb}m -XX:MaxMetaspaceSize=512m -Djammarr.acceptance.enabled=true -Djammarr.acceptance.audioProbe=true -Djammarr.acceptance.audioLeader=false -Djammarr.acceptance.audioControlFile=$control -Djammarr.acceptance.pcmTraceDir=$game_dir/pcm-trace -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true" \
      ALSA_CONFIG_PATH="$game_dir/alsa.conf" ALSOFT_CONF="$game_dir/alsoft.conf" \
      ALSOFT_DRIVERS="$alsoft_drivers" PULSE_SINK="$pulse_sink" LIBGL_ALWAYS_SOFTWARE=1 \
      ./gradlew "$client_task" --no-daemon --max-workers=2 --console=plain \
      "-Dorg.gradle.jvmargs=$gradle_jvmargs" \
      "${client_gradle_args[@]}" \
      -PjammarrAcceptanceUsername="$username" \
      -PjammarrAcceptanceServer="$game_host:$game_port" \
      -PjammarrAcceptanceGameDir="$game_dir" \
      > "$console" 2>&1
  ) &
  client_pids+=("$!")
}

wait_for_client_state() {
  local role=$1 pid=$2 state=$3 timeout=$4
  local console="$session_dir/client-$role.console.log"
  local deadline=$((SECONDS + timeout))
  while ! grep -Fq "Acceptance audio state: $state" "$console" 2>/dev/null; do
    if grep -Eq "$terminal_client_pattern" "$console" 2>/dev/null; then
      echo "$runtime $role client entered a terminal audio/connection state; see $console" >&2
      return 1
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "$runtime $role client exited before reaching $state; see $console" >&2
      return 1
    fi
    if (( SECONDS >= deadline )); then
      echo "$runtime $role client did not reach $state within ${timeout}s; see $console" >&2
      return 1
    fi
    sleep 1
  done
}

wait_for_client_marker() {
  local role=$1 pid=$2 marker=$3 timeout=$4
  local console="$session_dir/client-$role.console.log"
  local deadline=$((SECONDS + timeout))
  while ! grep -Fq "$marker" "$console" 2>/dev/null; do
    if grep -Eq "$terminal_client_pattern" "$console" 2>/dev/null; then
      echo "$runtime $role client entered a terminal audio/connection state; see $console" >&2
      return 1
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "$runtime $role client exited before producing $marker; see $console" >&2
      return 1
    fi
    if (( SECONDS >= deadline )); then
      echo "$runtime $role client did not produce $marker within ${timeout}s; see $console" >&2
      return 1
    fi
    sleep 1
  done
}

start_client leader "$leader_username" "$sink_leader"
wait_for_client_state leader "${client_pids[0]}" NO_STREAM 600
wait_for_client_marker leader "${client_pids[0]}" 'Acceptance playback state:' 600
start_client follower "$follower_username" "$sink_follower"
wait_for_client_state follower "${client_pids[1]}" NO_STREAM 600
wait_for_client_marker follower "${client_pids[1]}" 'Acceptance playback state:' 600
if [[ "$operator_file_prepared" == false ]]; then
  server_command "op $leader_username"
  operator_promoted=true
fi
printf '%s\n' '1|station:library-shuffle' > "$session_dir/client-leader.control"
wait_for_client_state leader "${client_pids[0]}" PLAYING 600
wait_for_client_state follower "${client_pids[1]}" PLAYING 600

leader_raw="$session_dir/leader.s16le"
follower_raw="$session_dir/follower.s16le"
parec --raw --latency-msec=50 --device="${sink_leader}.monitor" --format=s16le --rate=48000 --channels=2 \
  > "$leader_raw" & recorder_pids+=("$!")
parec --raw --latency-msec=50 --device="${sink_follower}.monitor" --format=s16le --rate=48000 --channels=2 \
  > "$follower_raw" & recorder_pids+=("$!")
sleep "$capture_seconds"
for pid in "${recorder_pids[@]}"; do kill -TERM "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; done
recorder_pids=()

for index in 0 1; do
  if ! kill -0 "${client_pids[$index]}" 2>/dev/null; then
    echo "$runtime client $index exited during live capture" >&2
    exit 1
  fi
done
if ! kill -0 "$server_pid" 2>/dev/null; then
  echo "$runtime server holder exited during live capture" >&2
  exit 1
fi

analyzer_args=(--minimum-duration-ms $((capture_seconds * 1000 - 1500))
  --timing-left-log "$session_dir/client-leader.console.log"
  --timing-right-log "$session_dir/client-follower.console.log")
if [[ "$audio_profile" == legacy-openal ]]; then
  leader_trace=$(find "$session_dir/client-leader/pcm-trace" -type f -name '*.s16le' -printf '%s\t%p\n' \
    | sort -nr | head -n 1 | cut -f 2-)
  follower_trace=$(find "$session_dir/client-follower/pcm-trace" -type f -name '*.s16le' -printf '%s\t%p\n' \
    | sort -nr | head -n 1 | cut -f 2-)
  if [[ -z "$leader_trace" || -z "$follower_trace" ]]; then
    echo "$runtime did not produce both legacy pre-backend PCM traces" >&2
    exit 1
  fi
  analyzer_args+=(--trace-left "$leader_trace" --trace-right "$follower_trace")
fi
python3 "$repo_root/scripts/analyze-live-audio.py" "$leader_raw" "$follower_raw" \
  "${analyzer_args[@]}" > "$session_dir/audio-analysis.json"

if grep -Eq "$terminal_client_pattern" \
    "$session_dir/client-leader.console.log" "$session_dir/client-follower.console.log"; then
  echo "$runtime logged an OpenAL linkage/context, terminal audio, or disconnect failure" >&2
  exit 1
fi

if [[ "$operator_promoted" == true ]]; then
  server_command "deop $leader_username"
  operator_promoted=false
fi
touch "$release_file"
wait "$server_pid"
server_pid=""
jq -e '.passed == true' "$session_dir/audio-analysis.json" >/dev/null
printf 'LIVE_CLIENT_GATE_ACCEPTED runtime=%s clients=2 capture_seconds=%s private_x=true\n' \
  "$runtime" "$capture_seconds"
