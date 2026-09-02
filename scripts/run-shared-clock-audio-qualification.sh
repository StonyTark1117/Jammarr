#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
stage=${1:-canary}
target=${2:-1.20.1-fabric}

if [[ ! "$target" =~ ^[a-zA-Z0-9._-]+$ ]]; then
  echo "Target must be a filesystem-safe runtime label" >&2
  exit 2
fi

case "$stage" in
  probe)
    vanilla_gate=false
    churn_cycles=1
    churn_seconds=0
    fixture_seconds=300
    ;;
  canary)
    vanilla_gate=true
    churn_cycles=3
    churn_seconds=0
    fixture_seconds=600
    ;;
  qualification)
    vanilla_gate=true
    churn_cycles=1
    churn_seconds=1800
    fixture_seconds=3600
    ;;
  soak)
    vanilla_gate=true
    churn_cycles=1
    churn_seconds=7200
    fixture_seconds=9000
    ;;
  *)
    echo "Stage must be probe, canary, qualification, or soak" >&2
    exit 2
    ;;
esac

run_id=$(date -u +%Y%m%dT%H%M%SZ)-$$
output_root=${JAMMARR_QUALIFICATION_OUTPUT_ROOT:-$repo_root/build/shared-clock-$stage-$run_id-$target}

exec env \
  JAMMARR_GATE_OUTPUT_ROOT="$output_root" \
  JAMMARR_AUDIO_CLIENT_GATE=true \
  JAMMARR_VANILLA_CLIENT_GATE="$vanilla_gate" \
  JAMMARR_VANILLA_CHURN_CYCLES="$churn_cycles" \
  JAMMARR_VANILLA_CHURN_MIN_SECONDS="$churn_seconds" \
  JAMMARR_GATE_AUDIO_DURATION_SECONDS="$fixture_seconds" \
  JAMMARR_OPENAL_DRIVER="${JAMMARR_OPENAL_DRIVER:-pipewire}" \
  JAMMARR_OPENAL_LOGLEVEL="${JAMMARR_OPENAL_LOGLEVEL:-1}" \
  "$repo_root/scripts/run-dedicated-server-gate.sh" "$target"
