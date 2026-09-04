#!/usr/bin/env bash
# Fail fast on the shared headless-client contract before expanding to the
# generated runtime matrix.  Each invocation uses the production gate; these
# three targets cover legacy OpenAL, legacy Loom launcher injection, and the
# modern command/UI client respectively.
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
python3 "$repo_root/scripts/test-runtime-process-lifecycle.py"

run_gate() {
  local runtime=$1
  shift
  env \
    JAMMARR_GATE_OUTPUT_ROOT="$repo_root/build/runtime-preflight/$runtime" \
    JAMMARR_GATE_LOCK_SCOPE=exclusive \
    JAMMARR_ALSA_PCM_TYPE=pulse \
    "$@" \
    bash "$repo_root/scripts/run-dedicated-server-gate.sh" "$runtime"
}

# The legacy client proves the WirePlumber/Pulse/OpenAL bootstrap itself.
run_gate 1.7.10-forge JAMMARR_PROTOCOL_CLIENT_GATE=true JAMMARR_COMMAND_CLIENT_GATE=true
# This is the oldest Loom client: wrong-protocol launch proves injector is on
# RunGameTask's actual execution classpath.
run_gate 1.16.5-fabric JAMMARR_PROTOCOL_CLIENT_GATE=true
# This client exercises command UI/render/audio initialization on the same
# production private-client environment.
run_gate 1.18.2-fabric JAMMARR_COMMAND_CLIENT_GATE=true
