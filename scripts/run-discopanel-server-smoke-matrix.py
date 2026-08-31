#!/usr/bin/env python3
"""Sequentially certify exact Jammarr artifacts on the DiscPanel server matrix.

The release and target manifests are resolved once. Every runtime remains a
single-server operation with the existing gate's guaranteed teardown, while
matrix-wide stopped/autostart audits run before and after each mutation. Apply
mode is explicit, resumable evidence must match the exact candidate, and no
Minecraft client or GUI is launched by this server-only gate.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
SMOKE_SCRIPT = SCRIPT_DIR / "run-discopanel-server-smoke.py"
SPEC = importlib.util.spec_from_file_location(
    "jammarr_discopanel_server_smoke", SMOKE_SCRIPT
)
assert SPEC and SPEC.loader
smoke = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = smoke
SPEC.loader.exec_module(smoke)
deployment = smoke.deployment
reconciler = smoke.reconciler


def select_targets(targets: list[Any], requested: list[str]) -> list[Any]:
    by_runtime = {target.runtime: target for target in targets}
    if not requested:
        return targets
    if len(set(requested)) != len(requested):
        raise SystemExit("runtime selections must be unique")
    unknown = [runtime for runtime in requested if runtime not in by_runtime]
    if unknown:
        raise SystemExit(f"unknown runtime selections: {', '.join(unknown)}")
    return [by_runtime[runtime] for runtime in requested]


def matrix_state(panel: Any, targets: list[Any]) -> list[str]:
    by_name: dict[str, list[dict[str, Any]]] = {}
    for server in panel.list_servers():
        by_name.setdefault(str(server.get("name", "")), []).append(server)
    errors: list[str] = []
    for target in targets:
        matches = by_name.get(target.server_name, [])
        if len(matches) != 1:
            errors.append(
                f"{target.runtime} maps to {len(matches)} servers; expected one"
            )
            continue
        server = matches[0]
        if server.get("status") != reconciler.STATUS_STOPPED:
            errors.append(f"{target.runtime} status={server.get('status')!r}")
        if server.get("autoStart"):
            errors.append(f"{target.runtime} autostart is enabled")
    return errors


def accepted_evidence(path: Path, version: str, target: Any) -> bool:
    try:
        evidence = json.loads(path.read_text("utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    markers = evidence.get("evidence") or {}
    return (
        evidence.get("schemaVersion") == 1
        and evidence.get("runtime") == target.runtime
        and evidence.get("version") == version
        and evidence.get("artifact") == target.filename
        and evidence.get("sha256") == target.sha256
        and evidence.get("runtimeStatusAtAcceptance") == "SERVER_STATUS_RUNNING"
        and evidence.get("stoppedCleanly") is True
        and not evidence.get("error")
        and all(
            markers.get(marker) is True
            for marker in ("minecraft_ready", "jammarr_initialized", "plex_connected")
        )
    )


def runtime_args(args: argparse.Namespace, target: Any) -> argparse.Namespace:
    return argparse.Namespace(
        runtime=target.runtime,
        manifest=args.manifest,
        release_dir=args.release_dir,
        project_properties=args.project_properties,
        expected_version=args.expected_version,
        url=args.url,
        token_env=args.token_env,
        request_timeout=args.request_timeout,
        start_timeout=args.start_timeout,
        stop_timeout=args.stop_timeout,
        poll_interval=args.poll_interval,
        log_tail=args.log_tail,
        evidence_dir=args.evidence_dir,
        apply=args.apply,
        confirm_runtime=target.runtime if args.apply else None,
    )


def write_summary(path: Path, summary: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", "utf-8")


def run(args: argparse.Namespace) -> int:
    token = os.environ.get(args.token_env)
    if not token:
        raise SystemExit(f"set {args.token_env} in the process environment")
    version = args.expected_version or deployment.project_version(args.project_properties)
    if args.apply and args.confirm_version != version:
        raise SystemExit(f"--apply requires --confirm-version {version}")
    if args.resume and not args.apply:
        raise SystemExit("--resume is only meaningful with --apply")

    artifacts = deployment.verified_release_artifacts(
        args.release_dir, args.manifest, version
    )
    all_targets = deployment.deployment_targets(
        args.manifest, args.release_dir, artifacts
    )
    targets = select_targets(all_targets, args.runtime)
    profiles = {
        profile.runtime: profile
        for profile in reconciler.desired_profiles(args.manifest)
    }
    panel = reconciler.DiscPanel(args.url, token, args.request_timeout)
    initial_errors = matrix_state(panel, all_targets)
    if initial_errors:
        raise RuntimeError(
            "DiscPanel matrix is not safely stopped: " + "; ".join(initial_errors)
        )

    summary: dict[str, Any] = {
        "schemaVersion": 1,
        "version": version,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "headlessServerOnly": True,
        "apply": args.apply,
        "selected": [target.runtime for target in targets],
        "accepted": [],
        "resumed": [],
        "preflighted": [],
        "failures": [],
    }
    failure: BaseException | None = None
    try:
        for index, target in enumerate(targets, start=1):
            errors = matrix_state(panel, all_targets)
            if errors:
                raise RuntimeError(
                    f"matrix unsafe before {target.runtime}: {'; '.join(errors)}"
                )
            evidence_path = args.evidence_dir / f"{target.runtime}.json"
            if args.resume and accepted_evidence(evidence_path, version, target):
                smoke.preflight(panel, version, target, profiles[target.runtime])
                summary["resumed"].append(target.runtime)
                print(
                    f"MATRIX_RESUME {index}/{len(targets)} {target.runtime} "
                    "exact_evidence=true preflight=true"
                )
                continue
            print(f"MATRIX_RUNTIME {index}/{len(targets)} {target.runtime}")
            try:
                smoke.run_target(
                    runtime_args(args, target),
                    panel,
                    version,
                    target,
                    profiles[target.runtime],
                )
                key = "accepted" if args.apply else "preflighted"
                summary[key].append(target.runtime)
            except BaseException as error:
                summary["failures"].append(
                    {"runtime": target.runtime, "errorType": type(error).__name__, "error": str(error)}
                )
                if not args.continue_on_error:
                    raise
            finally:
                errors = matrix_state(panel, all_targets)
                if errors:
                    raise RuntimeError(
                        f"matrix unsafe after {target.runtime}: {'; '.join(errors)}"
                    )
    except BaseException as error:
        failure = error
    finally:
        final_errors = matrix_state(panel, all_targets)
        summary["finishedAt"] = datetime.now(timezone.utc).isoformat()
        summary["allProfilesStopped"] = not final_errors
        summary["finalStateErrors"] = final_errors
        write_summary(args.matrix_evidence, summary)
        print(f"MATRIX_EVIDENCE {args.matrix_evidence}")

    if failure is not None:
        raise failure
    if summary["failures"]:
        return 1
    print(
        f"MATRIX_COMPLETE selected={len(targets)} "
        f"accepted={len(summary['accepted'])} resumed={len(summary['resumed'])} "
        f"preflighted={len(summary['preflighted'])} stopped=true"
    )
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", action="append", default=[])
    parser.add_argument("--manifest", type=Path, default=Path("gradle/targets.json"))
    parser.add_argument("--release-dir", type=Path, default=Path("build/releases"))
    parser.add_argument("--project-properties", type=Path, default=Path("gradle.properties"))
    parser.add_argument("--expected-version")
    parser.add_argument(
        "--url", default=os.environ.get("DISCOPANEL_URL", "http://192.168.1.73:8080")
    )
    parser.add_argument("--token-env", default="DISCOPANEL_TOKEN")
    parser.add_argument("--request-timeout", type=int, default=60)
    parser.add_argument("--start-timeout", type=int, default=600)
    parser.add_argument("--stop-timeout", type=int, default=180)
    parser.add_argument("--poll-interval", type=float, default=3.0)
    parser.add_argument("--log-tail", type=int, default=4000)
    parser.add_argument(
        "--evidence-dir", type=Path, default=Path("build/discopanel-server-smoke")
    )
    parser.add_argument(
        "--matrix-evidence",
        type=Path,
        default=Path("build/discopanel-server-smoke/matrix-summary.json"),
    )
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm-version")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--continue-on-error", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(run(parse_args()))
