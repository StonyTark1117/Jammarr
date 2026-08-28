#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
targets=(
  1.7.10-forge
  1.20.1-fabric 1.20.1-quilt 1.20.1-forge 1.20.1-neoforge
  26.2-fabric 26.2-quilt 26.2-forge 26.2-neoforge
)
profiles=(direct latency-150ms jitter-20-250ms stall-2s client-stalls-250ms overload-6s)

for target in "${targets[@]}"; do
  for profile in "${profiles[@]}"; do
    echo "$target: running audio profile $profile"
    JAMMARR_GATE_OUTPUT_ROOT="$repo_root/build/audio-impairment-gate/$target/$profile" \
    JAMMARR_PROTOCOL_CLIENT_GATE=false \
    JAMMARR_COMMAND_CLIENT_GATE=false \
    JAMMARR_AUDIO_CLIENT_GATE=true \
    JAMMARR_AUDIO_SCENARIO_GATE=false \
    JAMMARR_NETWORK_PROFILE="$profile" \
      "$repo_root/scripts/run-dedicated-server-gate.sh" "$target"
  done
done
