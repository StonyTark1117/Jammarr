#!/usr/bin/env python3
"""Run compatibility matrices concurrently without sharing runtime workspaces."""

from __future__ import annotations

import argparse
from collections import defaultdict
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
from typing import Any, IO


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
TARGET_MATRIX_SCRIPT = SCRIPT_DIR / "target-matrix.py"
SPEC = importlib.util.spec_from_file_location("jammarr_target_matrix", TARGET_MATRIX_SCRIPT)
assert SPEC and SPEC.loader
target_matrix = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(target_matrix)


KIND_CONFIG = {
    "vanilla": {
        "script": SCRIPT_DIR / "run-vanilla-client-matrix.py",
        "output": Path("build/vanilla-client-matrix"),
    },
    "unmodded": {
        "script": SCRIPT_DIR / "run-unmodded-server-client-matrix.py",
        "output": Path("build/unmodded-server-client-matrix"),
    },
}
OUTPUT_SUFFIX_PATTERN = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}")


def output_suffix(value: str) -> str:
    if OUTPUT_SUFFIX_PATTERN.fullmatch(value) is None:
        raise argparse.ArgumentTypeError(
            "--output-suffix must be 1-64 lowercase letters, digits, dots, "
            "underscores, or hyphens and must start with a letter or digit"
        )
    return value


def matrix_output_root(kind: str, suffix: str | None = None) -> Path:
    base = KIND_CONFIG[kind]["output"]
    if suffix is not None:
        base = base.with_name(f"{base.name}-{suffix}")
    return (REPO_ROOT / base).resolve()


def release_tuple(version: str) -> tuple[int, int, int] | None:
    match = re.fullmatch(r"([0-9]+)\.([0-9]+)(?:\.([0-9]+))?", version)
    if match is None:
        return None
    return tuple(int(value or 0) for value in match.groups())


def selected_runtimes(kind: str, manifest: dict[str, Any]) -> list[dict[str, Any]]:
    runtimes = target_matrix.runtimes(manifest)
    if kind == "vanilla":
        return [
            runtime
            for runtime in runtimes
            if (release_tuple(runtime["minecraft"]) or (0, 0, 0)) >= (1, 12, 2)
        ]
    return runtimes


def partition_runtimes(
    runtimes: list[dict[str, Any]], jobs: int
) -> list[list[dict[str, Any]]]:
    """Balance lanes while keeping one Minecraft/cache family together."""
    by_version: dict[str, list[dict[str, Any]]] = defaultdict(list)
    manifest_index = {runtime["name"]: index for index, runtime in enumerate(runtimes)}
    for runtime in runtimes:
        by_version[runtime["minecraft"]].append(runtime)
    groups = sorted(
        by_version.values(),
        key=lambda group: (-len(group), manifest_index[group[0]["name"]]),
    )
    lanes: list[list[dict[str, Any]]] = [[] for _ in range(min(jobs, len(groups)))]
    for group in groups:
        lane_index = min(range(len(lanes)), key=lambda index: (len(lanes[index]), index))
        lanes[lane_index].extend(group)
    for lane in lanes:
        lane.sort(key=lambda runtime: manifest_index[runtime["name"]])
    return lanes


def matrix_command(
    *,
    kind: str,
    manifest: Path,
    output_root: Path,
    summary: Path,
    connected_seconds: int,
    runtimes: list[dict[str, Any]] | None,
    verify_only: bool = False,
    continue_on_error: bool = False,
) -> list[str]:
    command = [
        sys.executable,
        str(KIND_CONFIG[kind]["script"]),
        "--manifest",
        str(manifest),
        "--output-root",
        str(output_root),
        "--summary",
        str(summary),
        "--connected-seconds",
        str(connected_seconds),
        "--resume",
    ]
    if continue_on_error:
        command.append("--continue-on-error")
    if verify_only:
        command.append("--verify-only")
    else:
        command.append("--resource-locks")
        assert runtimes is not None
        for runtime in runtimes:
            command.extend(("--runtime", runtime["name"]))
    return command


def stop_processes(processes: list[subprocess.Popen[Any]], timeout: int = 300) -> None:
    live = [process for process in processes if process.poll() is None]
    for process in live:
        try:
            os.killpg(process.pid, signal.SIGINT)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + timeout
    while live and time.monotonic() < deadline:
        live = [process for process in live if process.poll() is None]
        if live:
            time.sleep(0.5)
    for escalation, wait_seconds in ((signal.SIGTERM, 30), (signal.SIGKILL, 5)):
        if not live:
            break
        for process in live:
            try:
                os.killpg(process.pid, escalation)
            except ProcessLookupError:
                pass
        deadline = time.monotonic() + wait_seconds
        while live and time.monotonic() < deadline:
            live = [process for process in live if process.poll() is None]
            if live:
                time.sleep(0.5)
    if live:
        raise RuntimeError("one or more compatibility matrix shards did not stop")


def run_kind(
    *,
    kind: str,
    runtimes: list[dict[str, Any]],
    jobs: int,
    manifest: Path,
    output_root: Path,
    shard_root: Path,
    connected_seconds: int,
) -> int:
    lanes = partition_runtimes(runtimes, jobs)
    kind_shard_root = shard_root / kind
    kind_shard_root.mkdir(parents=True, exist_ok=True)
    processes: list[subprocess.Popen[Any]] = []
    logs: list[IO[bytes]] = []
    try:
        for index, lane in enumerate(lanes, start=1):
            summary = kind_shard_root / f"shard-{index:02d}.json"
            log_path = kind_shard_root / f"shard-{index:02d}.log"
            log = log_path.open("wb")
            logs.append(log)
            command = matrix_command(
                kind=kind,
                manifest=manifest,
                output_root=output_root,
                summary=summary,
                connected_seconds=connected_seconds,
                runtimes=lane,
                continue_on_error=True,
            )
            process = subprocess.Popen(
                command,
                cwd=REPO_ROOT,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            processes.append(process)
            print(
                f"COMPATIBILITY_SHARD_START kind={kind} lane={index}/{len(lanes)} "
                f"runtimes={len(lane)} pid={process.pid} log={log_path}",
                flush=True,
            )

        remaining = set(range(len(processes)))
        failure = False
        while remaining:
            for index in list(remaining):
                status = processes[index].poll()
                if status is None:
                    continue
                remaining.remove(index)
                print(
                    f"COMPATIBILITY_SHARD_END kind={kind} lane={index + 1} "
                    f"exit={status}",
                    flush=True,
                )
                if status != 0:
                    failure = True
            if remaining:
                time.sleep(1)
    except BaseException:
        stop_processes(processes)
        raise
    finally:
        for log in logs:
            log.close()

    final_summary = output_root / "matrix-summary.json"
    verifier = matrix_command(
        kind=kind,
        manifest=manifest,
        output_root=output_root,
        summary=final_summary,
        connected_seconds=connected_seconds,
        runtimes=None,
        verify_only=True,
        continue_on_error=True,
    )
    result = subprocess.run(verifier, cwd=REPO_ROOT, check=False)
    if result.returncode != 0:
        print(
            f"COMPATIBILITY_MATRIX_VERIFY_FAILED kind={kind} summary={final_summary}",
            file=sys.stderr,
            flush=True,
        )
        return result.returncode
    if failure:
        print(
            f"COMPATIBILITY_MATRIX_SHARD_FAILED kind={kind} summary={final_summary}",
            file=sys.stderr,
            flush=True,
        )
        return 1
    print(
        f"COMPATIBILITY_MATRIX_COMPLETE kind={kind} runtimes={len(runtimes)} "
        f"summary={final_summary}",
        flush=True,
    )
    return 0


def plan_value(
    kind: str, lanes: list[list[dict[str, Any]]], output_root: Path
) -> dict[str, Any]:
    return {
        "kind": kind,
        "outputRoot": str(output_root),
        "selected": sum(len(lane) for lane in lanes),
        "lanes": [
            {
                "index": index,
                "count": len(lane),
                "runtimes": [runtime["name"] for runtime in lane],
                "paths": sorted({runtime["path"] for runtime in lane}),
            }
            for index, lane in enumerate(lanes, start=1)
        ],
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=("vanilla", "unmodded", "both"), default="both")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--manifest", type=Path, default=Path("gradle/targets.json"))
    parser.add_argument("--connected-seconds", type=int, default=10)
    parser.add_argument(
        "--shard-root", type=Path, default=Path("build/compatibility-matrix-shards")
    )
    parser.add_argument(
        "--output-suffix",
        type=output_suffix,
        help=(
            "append a safe candidate identifier to both canonical output roots; "
            "use this to isolate one qualification generation"
        ),
    )
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.jobs <= 8:
        parser.error("--jobs must be from 1 through 8")
    if not 10 <= args.connected_seconds <= 300:
        parser.error("--connected-seconds must be from 10 through 300")
    return args


def main() -> int:
    args = parse_args()
    manifest_path = args.manifest.resolve()
    manifest = target_matrix.load_manifest(manifest_path)
    kinds = ("vanilla", "unmodded") if args.kind == "both" else (args.kind,)
    plans = []
    for kind in kinds:
        runtimes = selected_runtimes(kind, manifest)
        lanes = partition_runtimes(runtimes, args.jobs)
        output_root = matrix_output_root(kind, args.output_suffix)
        plans.append(plan_value(kind, lanes, output_root))
        if args.dry_run:
            continue
        status = run_kind(
            kind=kind,
            runtimes=runtimes,
            jobs=args.jobs,
            manifest=manifest_path,
            output_root=output_root,
            shard_root=args.shard_root.resolve(),
            connected_seconds=args.connected_seconds,
        )
        if status != 0:
            return status
    if args.dry_run:
        print(json.dumps({"schemaVersion": 1, "plans": plans}, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
