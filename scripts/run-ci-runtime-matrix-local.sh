#!/usr/bin/env bash
# Run the runtime portion of .github/workflows/ci.yml locally without omitting
# any acceptance scenario.  Each row keeps its own output root and resource
# lock, while a small number of whole runtime rows run concurrently.
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

parallelism=${JAMMARR_CI_RUNTIME_PARALLELISM:-4}
if ! [[ "$parallelism" =~ ^[1-9][0-9]*$ ]]; then
  echo "JAMMARR_CI_RUNTIME_PARALLELISM must be a positive integer" >&2
  exit 2
fi

output_root=${JAMMARR_CI_RUNTIME_OUTPUT_ROOT:-"$repo_root/build/ci-runtime-local"}
if [[ -e "$output_root" ]]; then
  echo "Refusing to reuse output root: $output_root" >&2
  exit 2
fi
mkdir -p "$output_root"
summary="$output_root/summary.tsv"
lock_file="$output_root/summary.lock"
: > "$summary"

run_gate() {
  local label=$1 suffix=$2
  shift 2
  local gate_root="$output_root/$label/$suffix"
  mkdir -p "$gate_root"
  env \
    JAMMARR_GATE_LOCK_SCOPE=resource \
    JAMMARR_GATE_LOCK_KEY="ci-local-$label-$suffix" \
    JAMMARR_GATE_OUTPUT_ROOT="$gate_root" \
    JAMMARR_ALSA_PCM_TYPE=pulse \
    "$@" \
    bash scripts/run-dedicated-server-gate.sh "$label" >"$gate_root/harness.log" 2>&1
}

run_row() {
  local row=$1 label quilt_mod_menu minimum_fabric_loader started result elapsed
  IFS='|' read -r label quilt_mod_menu minimum_fabric_loader <<< "$row"
  started=$(date +%s)
  result=PASS

  run_gate "$label" base \
    JAMMARR_PROTOCOL_CLIENT_GATE=true \
    JAMMARR_COMMAND_CLIENT_GATE=true \
    JAMMARR_AUDIO_CLIENT_GATE=true \
    JAMMARR_AUDIO_SCENARIO_GATE=true || result=FAIL

  if [[ "$result" == PASS && "$quilt_mod_menu" == true ]]; then
    run_gate "$label" quilt-modmenu \
      JAMMARR_QUILT_MODMENU_GATE=true \
      JAMMARR_PROTOCOL_CLIENT_GATE=true \
      JAMMARR_COMMAND_CLIENT_GATE=true \
      JAMMARR_AUDIO_CLIENT_GATE=true \
      JAMMARR_AUDIO_SCENARIO_GATE=true || result=FAIL
  fi

  if [[ "$result" == PASS && "$minimum_fabric_loader" == true ]]; then
    run_gate "$label" minimum-fabric-loader \
      JAMMARR_FABRIC_LOADER_VERSION=0.19.2 || result=FAIL
  fi

  elapsed=$(( $(date +%s) - started ))
  flock "$lock_file" printf '%s\t%s\t%s\n' "$label" "$result" "$elapsed" >> "$summary"
  printf '%s %s elapsed=%ss\n' "$result" "$label" "$elapsed"
  [[ "$result" == PASS ]]
}

export output_root summary lock_file
export -f run_gate run_row

python3 - <<'PY' | xargs -r -d '\n' -P "$parallelism" -I '{}' bash -c 'run_row "$1"' _ '{}'
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("target_matrix", "scripts/target-matrix.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
manifest = module.load_manifest(Path("gradle/targets.json"))
for runtime in module.runtimes(manifest):
    print(f"{runtime['name']}|{str(runtime['quiltModMenu']).lower()}|{str(runtime['minimumFabricLoader']).lower()}")
PY

awk -F '\t' '{states[$2]++} END {for (state in states) print state, states[state]}' "$summary" | sort
test "$(wc -l < "$summary")" -eq 99
test "$(awk -F '\t' '$2 != "PASS" {count++} END {print count + 0}' "$summary")" -eq 0
echo "CI_RUNTIME_LOCAL_COMPLETE runtimes=99 output=$output_root"
