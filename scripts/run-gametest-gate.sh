#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
log_file="$repo_dir/build/gametest-gate.log"
mkdir -p "$repo_dir/build"
: > "$log_file"

gate_pid=""
cleanup() {
    if [[ -n "$gate_pid" ]] && kill -0 "$gate_pid" 2>/dev/null; then
        kill -TERM -- "-$gate_pid" 2>/dev/null || true
        wait "$gate_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

cd "$repo_dir"
setsid ./gradlew runGameTestServer --no-daemon --max-workers=1 >"$log_file" 2>&1 &
gate_pid=$!

passed=false
for ((second = 0; second < 180; second++)); do
    if grep -Eq 'All [0-9]+ required tests passed' "$log_file"; then
        passed=true
        break
    fi
    if ! kill -0 "$gate_pid" 2>/dev/null; then
        break
    fi
    sleep 1
done

if [[ "$passed" != true ]]; then
    tail -n 160 "$log_file"
    echo "Jammarr GameTest gate did not observe a passing completion marker" >&2
    exit 1
fi

for ((second = 0; second < 15; second++)); do
    if ! kill -0 "$gate_pid" 2>/dev/null; then
        gate_pid=""
        break
    fi
    sleep 1
done

if [[ -n "$gate_pid" ]] && kill -0 "$gate_pid" 2>/dev/null; then
    kill -TERM -- "-$gate_pid" 2>/dev/null || true
    wait "$gate_pid" 2>/dev/null || true
    gate_pid=""
fi

if grep -Eq '[1-9][0-9]* required tests failed|BUILD FAILED' "$log_file"; then
    tail -n 160 "$log_file"
    echo "Jammarr GameTest gate reported a failure" >&2
    exit 1
fi

grep -E 'GAME TESTS COMPLETE|All [0-9]+ required tests passed' "$log_file"
