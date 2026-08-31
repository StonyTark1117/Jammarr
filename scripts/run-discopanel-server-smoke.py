#!/usr/bin/env python3
"""Start one exact DiscPanel release runtime, prove server health, then stop it.

This is a headless server-only gate. It does not launch a Minecraft client and
therefore cannot establish UI, optional-client, library-isolation, or audible
playback acceptance.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
DEPLOY_SCRIPT = SCRIPT_DIR / "deploy-discopanel-release.py"
SPEC = importlib.util.spec_from_file_location("jammarr_discopanel_deploy", DEPLOY_SCRIPT)
assert SPEC and SPEC.loader
deployment = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = deployment
SPEC.loader.exec_module(deployment)
reconciler = deployment.reconciler

READY_PATTERN = re.compile(
    r"(?:Done \([^\r\n]+\)!|Dedicated server started|Server started)", re.IGNORECASE
)
INSTALLER_FAILURE_PATTERN = re.compile(
    r"(?:Failed to install|Failed to download version manifest|"
    r"SocketTimeoutException|Could not resolve all files)",
    re.IGNORECASE,
)
SERVER_FAILURE_PATTERN = re.compile(
    r"(?:Failed to start the minecraft server|Minecraft server failed|"
    r"MixinApplyError|Exception in server tick loop|mc-server-runner\s+Done|"
    r"install-fabric-loader[^\r\n]*command failed|"
    r"Failed to locate install\.properties|"
    r"Failed to use provided Fabric launcher)",
    re.IGNORECASE,
)
RUN_START_PATTERN = re.compile(r"\[init\] Running as uid=", re.IGNORECASE)


def log_messages(response: dict[str, Any]) -> list[str]:
    return [
        entry.get("message", "")
        for entry in response.get("logs") or []
        if isinstance(entry, dict) and isinstance(entry.get("message"), str)
    ]


def message_overlap_length(baseline: list[str], current: list[str]) -> int:
    if not baseline:
        return 0
    if current[: len(baseline)] == baseline:
        return len(baseline)
    overlap_limit = min(len(baseline), len(current))
    for overlap in range(overlap_limit, 0, -1):
        if baseline[-overlap:] == current[:overlap]:
            return overlap
    return 0


def appended_messages(baseline: list[str], current: list[str]) -> list[str]:
    """Return messages appended after a snapshot, tolerating tail-window rotation."""
    if not baseline:
        return current
    overlap = message_overlap_length(baseline, current)
    return current[overlap:] if overlap else current


def latest_run_segment(messages: list[str]) -> list[str]:
    for index in range(len(messages) - 1, -1, -1):
        if RUN_START_PATTERN.search(messages[index]):
            return messages[index:]
    return []


def active_run_messages(
    baseline: list[str], current: list[str], anchor_seen: bool
) -> tuple[list[str], bool]:
    """Select only the newly started container segment despite replay/rotation."""
    overlap = message_overlap_length(baseline, current)
    delta = appended_messages(baseline, current)
    delta_segment = latest_run_segment(delta)
    if delta_segment and (not baseline or overlap):
        return delta_segment, True
    current_segment = latest_run_segment(current)
    if anchor_seen:
        return (current_segment or delta), True
    if current_segment and not SERVER_FAILURE_PATTERN.search("\n".join(current_segment)):
        # The window may have rotated away every baseline line. A latest segment
        # without a terminal marker is the active post-StartServer invocation;
        # a completed segment is historical until a new anchor appears.
        return current_segment, True
    return [], False


def startup_evidence(messages: list[str], version: str) -> dict[str, bool]:
    joined = "\n".join(messages)
    plex_connected = "Jammarr connected to Plex" in joined
    return {
        "minecraft_ready": bool(READY_PATTERN.search(joined)),
        "jammarr_initialized": (
            f"Initializing Jammarr {version}" in joined and "protocol 6" in joined
        ) or plex_connected,
        "plex_connected": plex_connected,
        "installer_failure": bool(INSTALLER_FAILURE_PATTERN.search(joined)),
        "server_failure": bool(SERVER_FAILURE_PATTERN.search(joined)),
    }


def wait_for_status(
    panel: Any, server_id: str, expected: str, timeout: int, poll_interval: float
) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        last = panel.get_server(server_id)
        if last.get("status") == expected:
            return last
        time.sleep(poll_interval)
    raise RuntimeError(
        f"server did not reach {expected} within {timeout}s; "
        f"last_status={last.get('status', 'unknown')}"
    )


def exact_target(args: argparse.Namespace) -> tuple[Any, Any, dict[str, Any]]:
    version = args.expected_version or deployment.project_version(args.project_properties)
    artifacts = deployment.verified_release_artifacts(
        args.release_dir, args.manifest, version
    )
    targets = {
        target.runtime: target
        for target in deployment.deployment_targets(
            args.manifest, args.release_dir, artifacts
        )
    }
    target = targets.get(args.runtime)
    if target is None:
        raise SystemExit(f"unknown runtime selection: {args.runtime}")
    profiles = {
        profile.runtime: profile
        for profile in reconciler.desired_profiles(args.manifest)
    }
    return version, target, profiles[args.runtime]


def preflight(
    panel: Any, version: str, target: Any, profile: Any
) -> dict[str, Any]:
    matches = [
        server for server in panel.list_servers() if server.get("name") == target.server_name
    ]
    if len(matches) != 1:
        raise RuntimeError(
            f"{target.runtime} maps to {len(matches)} DiscPanel servers; expected one"
        )
    server = panel.get_server(str(matches[0]["id"]))
    differences = reconciler.drift(profile, server)
    if differences:
        raise RuntimeError(f"{target.runtime} server drifted: {'; '.join(differences)}")
    if server.get("status") != reconciler.STATUS_STOPPED:
        raise RuntimeError(f"{target.runtime} must be stopped before a smoke run")
    if server.get("autoStart"):
        raise RuntimeError(f"{target.runtime} has autostart enabled")
    environment = ((server.get("dockerOverrides") or {}).get("environment") or {})
    if any(
        environment.get(key) != value for key, value in target.loader_environment
    ):
        raise RuntimeError(f"{target.runtime} does not have its exact loader pin")
    server_id = str(server["id"])
    mods = panel.call("ModService", "ListMods", {"serverId": server_id}).get(
        "mods", []
    )
    active = [
        mod
        for mod in mods
        if deployment.is_jammarr_mod(mod) and deployment.is_enabled(mod)
    ]
    if len(active) != 1 or active[0].get("fileName") != target.filename:
        raise RuntimeError(f"{target.runtime} does not have one exact active {version} artifact")
    if deployment.remote_mod_digest(panel, server_id, target.filename) != target.sha256:
        raise RuntimeError(f"{target.runtime} remote artifact digest does not match")
    return server


def run_target(
    args: argparse.Namespace,
    panel: Any,
    version: str,
    target: Any,
    profile: Any,
) -> int:
    """Run one already-resolved target without re-reading the release bundle."""
    if args.apply and args.confirm_runtime != args.runtime:
        raise SystemExit(f"--apply requires --confirm-runtime {args.runtime}")
    server = preflight(panel, version, target, profile)
    server_id = str(server["id"])
    print(
        f"DiscPanel server smoke: runtime={args.runtime} artifact={target.filename} "
        f"sha256={target.sha256} apply={str(args.apply).lower()}"
    )
    if not args.apply:
        print(f"WOULD_START_AND_STOP {args.runtime}")
        return 0

    started_at = datetime.now(timezone.utc)
    baseline_response = panel.call(
        "ServerService", "GetServerLogs", {"id": server_id, "tail": args.log_tail}
    )
    baseline_messages = log_messages(baseline_response)
    start_requested = False
    result: dict[str, Any] = {
        "schemaVersion": 1,
        "runtime": args.runtime,
        "version": version,
        "artifact": target.filename,
        "sha256": target.sha256,
        "startedAt": started_at.isoformat(),
        "headlessServerOnly": True,
    }
    run_error: BaseException | None = None
    try:
        start_requested = True
        panel.call("ServerService", "StartServer", {"id": server_id})
        deadline = time.monotonic() + args.start_timeout
        observed: dict[str, bool] = {}
        last_status = "unknown"
        active_seen = False
        run_anchor_seen = False
        while time.monotonic() < deadline:
            last_status = str(panel.get_server(server_id).get("status", "unknown"))
            if last_status != reconciler.STATUS_STOPPED:
                active_seen = True
            response = panel.call(
                "ServerService",
                "GetServerLogs",
                {"id": server_id, "tail": args.log_tail},
            )
            current_messages, run_anchor_seen = active_run_messages(
                baseline_messages,
                log_messages(response),
                run_anchor_seen,
            )
            observed = startup_evidence(current_messages, version)
            if observed["installer_failure"]:
                raise RuntimeError(f"{args.runtime} failed during server bootstrap")
            if observed["server_failure"]:
                raise RuntimeError(f"{args.runtime} server process failed during startup")
            if last_status == "SERVER_STATUS_RUNNING" and all(
                observed[key]
                for key in (
                    "minecraft_ready",
                    "jammarr_initialized",
                    "plex_connected",
                )
            ):
                break
            if last_status == reconciler.STATUS_ERROR:
                raise RuntimeError(f"{args.runtime} entered DiscPanel error state")
            if active_seen and last_status == reconciler.STATUS_STOPPED:
                raise RuntimeError(
                    f"{args.runtime} stopped before acceptance; evidence={observed}"
                )
            time.sleep(args.poll_interval)
        else:
            raise RuntimeError(
                f"{args.runtime} did not append fresh readiness, Jammarr initialization, "
                f"and Plex connection evidence within {args.start_timeout}s; "
                f"status={last_status} evidence={observed}"
            )
        result["evidence"] = observed
        result["freshRunAnchor"] = run_anchor_seen
        result["runtimeStatusAtAcceptance"] = last_status
        print(
            f"SERVER_SMOKE_ACCEPTED {args.runtime} running=true fresh_logs=true "
            "initialized=true plex_connected=true"
        )
    except BaseException as error:
        run_error = error
        result["errorType"] = type(error).__name__
        result["error"] = str(error)
    finally:
        if start_requested:
            try:
                current = panel.get_server(server_id)
                if current.get("status") != reconciler.STATUS_STOPPED:
                    panel.call("ServerService", "StopServer", {"id": server_id})
                wait_for_status(
                    panel,
                    server_id,
                    reconciler.STATUS_STOPPED,
                    args.stop_timeout,
                    args.poll_interval,
                )
                result["stoppedCleanly"] = True
                print(f"SERVER_SMOKE_STOPPED {args.runtime}")
            except BaseException as stop_error:
                result["stoppedCleanly"] = False
                result["stopErrorType"] = type(stop_error).__name__
                result["stopError"] = str(stop_error)
                if run_error is None:
                    run_error = stop_error
        args.evidence_dir.mkdir(parents=True, exist_ok=True)
        evidence_path = args.evidence_dir / f"{args.runtime}.json"
        evidence_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", "utf-8")
        print(f"EVIDENCE {evidence_path}")
    if run_error is not None:
        raise run_error
    return 0


def run(args: argparse.Namespace) -> int:
    token = os.environ.get(args.token_env)
    if not token:
        raise SystemExit(f"set {args.token_env} in the process environment")
    version, target, profile = exact_target(args)
    panel = reconciler.DiscPanel(args.url, token, args.request_timeout)
    return run_target(args, panel, version, target, profile)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", required=True)
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
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm-runtime")
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(run(parse_args()))
