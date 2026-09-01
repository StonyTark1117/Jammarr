#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
requested=${1:-}
if [[ -z "$requested" || "$requested" == "all" ]]; then
  echo "Usage: $0 <runtime-label>" >&2
  exit 2
fi

output_root=${JAMMARR_UNMODDED_GATE_OUTPUT_ROOT:-"$repo_root/build/unmodded-server-client-gate/$requested"}
cache_root=${JAMMARR_VANILLA_SERVER_CACHE_ROOT:-"$repo_root/build/vanilla-server-cache"}
connected_seconds=${JAMMARR_UNMODDED_CONNECTED_SECONDS:-10}
if [[ ! "$connected_seconds" =~ ^[0-9]+$ ]] || (( connected_seconds < 10 || connected_seconds > 300 )); then
  echo "JAMMARR_UNMODDED_CONNECTED_SECONDS must be an integer from 10 through 300" >&2
  exit 2
fi
mkdir -p "$output_root" "$repo_root/build"
output_root=$(cd "$output_root" && pwd)
if find "$output_root" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
  echo "Refusing to reuse non-empty unmodded-server evidence root: $output_root" >&2
  exit 2
fi

# Share the production runtime lock with the dedicated-server gate. Both use
# Gradle client workspaces, game ports, and heavyweight private-X clients.
exec 9>"$repo_root/build/.dedicated-server-gate.lock"
if ! flock -n 9; then
  echo "Another Jammarr runtime gate is using the shared Minecraft workspace" >&2
  exit 2
fi

target_line=""
while IFS= read -r line; do
  [[ ${line%%|*} != "$requested" ]] || target_line=$line
done < <(python3 "$repo_root/scripts/target-matrix.py" unmodded-client-lines \
  "$repo_root/gradle/targets.json")
if [[ -z "$target_line" ]]; then
  echo "Unknown full runtime '$requested'" >&2
  exit 2
fi
IFS='|' read -r label relative_dir build_java runtime_java port client_task \
  disable_configuration_cache runtime_loader <<< "$target_line"
minecraft_version=${label%-*}
target_dir="$repo_root/$relative_dir"

java_home() {
  case "$1" in
    8) printf '%s\n' "${JAMMARR_JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk}" ;;
    17) printf '%s\n' "${JAMMARR_JAVA17_HOME:-/usr/lib/jvm/java-17-openjdk}" ;;
    21) printf '%s\n' "${JAMMARR_JAVA21_HOME:-/usr/lib/jvm/java-21-openjdk}" ;;
    25) printf '%s\n' "${JAMMARR_JAVA25_HOME:-${JAMMARR_JAVA26_HOME:-/usr/lib/jvm/java-26-openjdk}}" ;;
    26) printf '%s\n' "${JAMMARR_JAVA26_HOME:-/usr/lib/jvm/java-26-openjdk}" ;;
    *) echo "No JDK configured for Java $1" >&2; return 1 ;;
  esac
}
build_java_home=$(java_home "$build_java")
runtime_java_home=$(java_home "$runtime_java")
for executable in "$build_java_home/bin/java" "$runtime_java_home/bin/java"; do
  if [[ ! -x "$executable" ]]; then
    echo "Required Java executable is missing: $executable" >&2
    exit 2
  fi
done
for command in xvfb-run ss; do
  if ! command -v "$command" >/dev/null; then
    echo "Unmodded-server client acceptance requires $command" >&2
    exit 2
  fi
done

attestation_path=$(python3 "$repo_root/scripts/prepare-mojang-server.py" \
  --minecraft "$minecraft_version" --cache-root "$cache_root")
server_jar=$(python3 - "$attestation_path" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["serverJar"])
PY
)

server_dir="$output_root/server"
client_dir="$output_root/client"
server_console="$output_root/server.console.log"
client_console="$output_root/client.console.log"
evidence="$output_root/gate.evidence.txt"
server_fifo="$output_root/server.stdin"
username=JammarrNoServer
server_pid=""
client_pid=""
server_fd=""

group_alive() { kill -0 -- "-${1}" 2>/dev/null; }

terminate_group() {
  local pid=$1 timeout=${2:-20} deadline
  [[ -n "$pid" ]] || return 0
  if group_alive "$pid"; then kill -TERM -- "-$pid" 2>/dev/null || true; fi
  deadline=$((SECONDS + timeout))
  while group_alive "$pid" && (( SECONDS < deadline )); do sleep .2; done
  if group_alive "$pid"; then
    kill -KILL -- "-$pid" 2>/dev/null || true
    sleep 1
  fi
  ! group_alive "$pid"
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  terminate_group "$client_pid" 20 || true
  if [[ -n "$server_pid" ]] && group_alive "$server_pid"; then
    if [[ -n "$server_fd" ]]; then printf 'stop\n' >&"$server_fd" 2>/dev/null || true; fi
    local deadline=$((SECONDS + 60))
    while group_alive "$server_pid" && (( SECONDS < deadline )); do sleep .5; done
    terminate_group "$server_pid" 10 || true
  fi
  if [[ -n "$server_fd" ]]; then exec {server_fd}>&-; fi
  if ss -ltnH "sport = :$port" | grep -q .; then
    echo "$label: port $port remained open after unmodded-server cleanup" >&2
    status=1
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

mkdir -p "$server_dir" "$client_dir"
printf 'eula=true\n' > "$server_dir/eula.txt"
{
  printf 'online-mode=false\n'
  printf 'server-port=%s\n' "$port"
  printf 'server-ip=127.0.0.1\n'
  printf 'motd=Jammarr unmodded-server acceptance\n'
  printf 'view-distance=3\n'
  printf 'simulation-distance=3\n'
  printf 'max-players=4\n'
  printf 'spawn-protection=0\n'
} > "$server_dir/server.properties"
mkfifo "$server_fifo"
exec {server_fd}<>"$server_fifo"
(
  cd "$server_dir"
  exec setsid "$runtime_java_home/bin/java" -Xms512m -Xmx2048m -jar "$server_jar" nogui \
    <&"$server_fd" > "$server_console" 2>&1
) &
server_pid=$!

deadline=$((SECONDS + 300))
while ! grep -Eq 'Done \(|Done \[' "$server_console" 2>/dev/null; do
  if ! group_alive "$server_pid" || (( SECONDS >= deadline )); then
    echo "$label: official Mojang server did not become ready; see $server_console" >&2
    exit 1
  fi
  sleep 1
done
if ! ss -ltnH "sport = :$port" | grep -q .; then
  echo "$label: official Mojang server reported ready without listening on $port" >&2
  exit 1
fi

printf '%s\n' 'onboardAccessibility:false' 'skipMultiplayerWarning:true' \
  'joinedFirstServer:true' 'narrator:0' > "$client_dir/options.txt"
runtime_args=()
cache_args=()
[[ "$runtime_loader" == quilt ]] && runtime_args+=(-PjammarrRuntimeLoader=quilt)
[[ "$disable_configuration_cache" == true ]] && cache_args+=(--no-configuration-cache)
(
  cd "$target_dir"
  exec setsid env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 ALSOFT_DRIVERS=null \
    xvfb-run -a -s '-screen 0 1280x720x24 +extension GLX +render -noreset' env \
    JAVA_HOME="$build_java_home" PATH="$build_java_home/bin:$PATH" \
    JAVA_TOOL_OPTIONS='-Djammarr.acceptance.enabled=true -Djammarr.acceptance.unmoddedServerProbe=true -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true' \
    LIBGL_ALWAYS_SOFTWARE=1 \
    ./gradlew "$client_task" --no-daemon --max-workers=2 --console=plain \
      "${cache_args[@]}" "${runtime_args[@]}" \
      -PjammarrAcceptanceUsername="$username" \
      -PjammarrAcceptanceServer="127.0.0.1:$port" \
      -PjammarrAcceptanceGameDir="$client_dir" \
      > "$client_console" 2>&1
) &
client_pid=$!

deadline=$((SECONDS + 600))
while ! grep -Fq "$username joined the game" "$server_console" 2>/dev/null \
    && ! grep -Eq "$username \[/[^]]+\] logged in with entity id" "$server_console" 2>/dev/null; do
  if ! group_alive "$client_pid" || (( SECONDS >= deadline )); then
    echo "$label: modded client did not join the official unmodded server; see $client_console" >&2
    exit 1
  fi
  sleep 1
done

ui_marker='Acceptance Jammarr unsupported-server screen remained open'
deadline=$((SECONDS + 90))
while ! grep -Fq "$ui_marker" "$client_console" 2>/dev/null; do
  if ! group_alive "$client_pid" || (( SECONDS >= deadline )); then
    echo "$label: unsupported-server UI did not remain rendered; see $client_console" >&2
    exit 1
  fi
  sleep 1
done
sleep "$connected_seconds"
if ! group_alive "$client_pid" \
    || grep -Eiq 'lost connection|disconnected from server|Connection Lost|mismatched mod|incompatible' "$client_console"; then
  echo "$label: modded client did not remain connected to the unmodded server" >&2
  exit 1
fi

{
  cat "$attestation_path"
  grep -F "$username joined the game" "$server_console" | tail -n 1 || \
    grep -E "$username \[/[^]]+\] logged in with entity id" "$server_console" | tail -n 1
  grep -F "$ui_marker" "$client_console" | tail -n 1
  printf 'Modded client remained connected to the official unmodded server for %s seconds after UI verification.\n' \
    "$connected_seconds"
  printf 'Client and server ran on a private X display and verified null audio output.\n'
} > "$evidence"

terminate_group "$client_pid" 20
client_pid=""
printf 'stop\n' >&"$server_fd"
deadline=$((SECONDS + 60))
while group_alive "$server_pid" && (( SECONDS < deadline )); do sleep .5; done
if group_alive "$server_pid"; then
  echo "$label: official unmodded server did not stop cleanly" >&2
  exit 1
fi
server_pid=""
if ss -ltnH "sport = :$port" | grep -q .; then
  echo "$label: game port remained open after official server shutdown" >&2
  exit 1
fi
printf 'Official unmodded server, modded client, private X server, and port cleaned up.\n' >> "$evidence"
echo "$label: modded-client-to-unmodded-server gate passed"
