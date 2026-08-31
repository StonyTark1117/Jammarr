#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
phase=${1:-all}
output_root=${JAMMARR_FORGE1710_OUTPUT_ROOT:-"$repo_root/build/forge1710-regression"}

run_core() {
  JAMMARR_GATE_OUTPUT_ROOT="$output_root/core" \
  JAMMARR_PROTOCOL_CLIENT_GATE=true \
  JAMMARR_COMMAND_CLIENT_GATE=true \
  JAMMARR_DELAYED_HELLO_GATE=true \
  JAMMARR_GATE_HELLO_TIMEOUT_MS=60000 \
  JAMMARR_AUDIO_CLIENT_GATE=true \
  JAMMARR_AUDIO_SCENARIO_GATE=true \
  JAMMARR_CLIENT_COMPANION_GATE=true \
  JAMMARR_LEGACY_PERSISTENCE_GATE=true \
  JAMMARR_LEGACY_BROWSE_STRESS_GATE=true \
  JAMMARR_LEGACY_COLD_STARTS=20 \
  JAMMARR_GATE_AUDIO_DURATION_SECONDS=1800 \
  JAMMARR_GATE_CLIENT_LOG_LIMIT_BLOCKS=262144 \
  JAMMARR_ALSA_PCM_TYPE=pulse \
    "$repo_root/scripts/run-dedicated-server-gate.sh" 1.7.10-forge
}

run_impairments() {
  local profile
  local -a profiles=(direct latency-150ms jitter-20-250ms stall-2s client-stalls-250ms overload-6s)
  for profile in "${profiles[@]}"; do
    echo "1.7.10-forge: running production audio profile $profile"
    JAMMARR_GATE_OUTPUT_ROOT="$output_root/impairments/$profile" \
    JAMMARR_PROTOCOL_CLIENT_GATE=false \
    JAMMARR_COMMAND_CLIENT_GATE=false \
    JAMMARR_AUDIO_CLIENT_GATE=true \
    JAMMARR_AUDIO_SCENARIO_GATE=false \
    JAMMARR_NETWORK_PROFILE="$profile" \
    JAMMARR_GATE_AUDIO_DURATION_SECONDS=600 \
    JAMMARR_ALSA_PCM_TYPE=pulse \
      "$repo_root/scripts/run-dedicated-server-gate.sh" 1.7.10-forge
  done
}

case "$phase" in
  core) run_core ;;
  impairments) run_impairments ;;
  all) run_core; run_impairments ;;
  *)
    echo "Usage: $0 [core|impairments|all]" >&2
    exit 2
    ;;
esac
