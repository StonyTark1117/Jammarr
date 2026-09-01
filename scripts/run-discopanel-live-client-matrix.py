#!/usr/bin/env python3
"""Run the two-client live-Plex gate sequentially across DiscPanel runtimes.

The single-runtime shell gate owns all server/client lifecycle and private-X
work. This wrapper adds manifest-derived selection, exact-candidate resume,
matrix-wide stopped/autostart audits, fail-closed interruption handling, and a
sanitized aggregate record. Apply mode requires an exact version confirmation.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import signal
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
SMOKE_SCRIPT = SCRIPT_DIR / "run-discopanel-server-smoke.py"
LIVE_GATE = SCRIPT_DIR / "run-discopanel-live-client-gate.sh"
FORBIDDEN_CLIENT_MARKERS = (
    "Only one OpenAL context",
    "UnsatisfiedLinkError: org.lwjgl.openal",
    "Acceptance audio state: ERROR",
    "Client disconnected with reason:",
    "Couldn't connect to server",
    "Connection refused",
    "Failed to connect to the server",
    "Connection timed out",
)
SPEC = importlib.util.spec_from_file_location("jammarr_live_matrix_smoke", SMOKE_SCRIPT)
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
            errors.append(f"{target.runtime} maps to {len(matches)} servers; expected one")
            continue
        server = matches[0]
        if server.get("status") != reconciler.STATUS_STOPPED:
            errors.append(f"{target.runtime} status={server.get('status')!r}")
        if server.get("autoStart"):
            errors.append(f"{target.runtime} autostart is enabled")
    return errors


def _load_json(path: Path) -> dict[str, Any] | None:
    try:
        value = json.loads(path.read_text("utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def session_has_process(session: Path) -> bool:
    marker = str(session).encode()
    for command_path in Path("/proc").glob("[0-9]*/cmdline"):
        try:
            if marker in command_path.read_bytes():
                return True
        except OSError:
            continue
    return False


def accepted_session(evidence_dir: Path, version: str, target: Any) -> Path | None:
    candidates = sorted(
        evidence_dir.glob(f"{target.runtime}.*"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    for session in candidates:
        if session_has_process(session):
            continue
        audio = _load_json(session / "audio-analysis.json")
        server = _load_json(session / "server-evidence" / f"{target.runtime}.json")
        if not audio or not server:
            continue
        markers = server.get("evidence") or {}
        if not (
            audio.get("passed") is True
            and not audio.get("failures")
            and server.get("schemaVersion") == 1
            and server.get("runtime") == target.runtime
            and server.get("version") == version
            and server.get("artifact") == target.filename
            and server.get("sha256") == target.sha256
            and server.get("runtimeStatusAtAcceptance") == "SERVER_STATUS_RUNNING"
            and server.get("stoppedCleanly") is True
            and server.get("clientHoldCompleted") is True
            and server.get("clientHoldConfigRestored") is True
            and server.get("clientHoldDockerOverridesRestored") is True
            and (
                server.get("clientHoldNonJammarrModsDisabled", 0) == 0
                or server.get("clientHoldNonJammarrModsRestored") is True
            )
            and server.get("clientHoldPropertiesRestored") is True
            and all(
                markers.get(marker) is True
                for marker in ("minecraft_ready", "jammarr_initialized", "plex_connected")
            )
        ):
            continue
        logs: list[str] = []
        try:
            logs = [
                (session / "client-leader.console.log").read_text("utf-8", errors="replace"),
                (session / "client-follower.console.log").read_text("utf-8", errors="replace"),
            ]
        except OSError:
            continue
        if any(marker in log for marker in FORBIDDEN_CLIENT_MARKERS for log in logs):
            continue
        if target.runtime == "b1.7.3-babric" and not (
            session / "operator-file-restored"
        ).is_file():
            continue
        return session
    return None


def write_summary(path: Path, summary: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", "utf-8")


def run_live_gate(
    command: list[str],
    *,
    cwd: Path,
    env: dict[str, str],
    cleanup_timeout: int = 240,
) -> int:
    """Run one gate in its own session and let its trap finish on interruption.

    A terminal Ctrl-C is delivered to the matrix wrapper's foreground process
    group. Starting the gate in a separate session keeps that signal from
    killing the shell and its children simultaneously. We then signal the gate
    shell itself and wait for its EXIT/INT cleanup trap to release the held
    server, restore configuration, and remove private-X client processes.
    """
    process = subprocess.Popen(
        command,
        cwd=cwd,
        env=env,
        start_new_session=True,
    )
    try:
        return process.wait()
    except KeyboardInterrupt:
        try:
            process.send_signal(signal.SIGINT)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=cleanup_timeout)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError(
                f"live gate cleanup did not finish within {cleanup_timeout} seconds"
            ) from error
        raise


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
        "privateXRequired": True,
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
            prior = accepted_session(args.evidence_dir, version, target)
            if args.resume and prior is not None:
                smoke.preflight(
                    panel, version, target, profiles[target.runtime]
                )
                summary["resumed"].append(
                    {"runtime": target.runtime, "evidence": str(prior)}
                )
                print(
                    f"LIVE_MATRIX_RESUME {index}/{len(targets)} {target.runtime} "
                    "exact_evidence=true preflight=true"
                )
                continue
            if not args.apply:
                smoke.preflight(panel, version, target, profiles[target.runtime])
                summary["preflighted"].append(target.runtime)
                print(f"LIVE_MATRIX_WOULD_RUN {index}/{len(targets)} {target.runtime}")
                continue
            print(f"LIVE_MATRIX_RUNTIME {index}/{len(targets)} {target.runtime}")
            environment = os.environ.copy()
            environment["DISCOPANEL_URL"] = args.url
            environment["DISCOPANEL_TOKEN_ENV"] = args.token_env
            environment["JAMMARR_EXPECTED_VERSION"] = version
            returncode = run_live_gate(
                [str(LIVE_GATE), target.runtime],
                cwd=REPO_ROOT,
                env=environment,
            )
            if returncode != 0:
                summary["failures"].append(
                    {"runtime": target.runtime, "exitCode": returncode}
                )
                if not args.continue_on_error:
                    raise RuntimeError(
                        f"live client gate failed for {target.runtime} "
                        f"with exit code {returncode}"
                    )
            else:
                evidence = accepted_session(args.evidence_dir, version, target)
                if evidence is None:
                    summary["failures"].append(
                        {
                            "runtime": target.runtime,
                            "errorType": "EvidenceValidationError",
                            "error": "gate exited successfully without exact accepted evidence",
                        }
                    )
                    if not args.continue_on_error:
                        raise RuntimeError(
                            f"{target.runtime} exited successfully without exact accepted evidence"
                        )
                else:
                    summary["accepted"].append(
                        {"runtime": target.runtime, "evidence": str(evidence)}
                    )
            errors = matrix_state(panel, all_targets)
            if errors:
                raise RuntimeError(
                    f"matrix unsafe after {target.runtime}: {'; '.join(errors)}"
                )
    except BaseException as error:
        failure = error
    finally:
        try:
            final_errors = matrix_state(panel, all_targets)
        except Exception as error:
            final_errors = [f"final state audit failed: {type(error).__name__}: {error}"]
            if failure is None:
                failure = error
        summary["finishedAt"] = datetime.now(timezone.utc).isoformat()
        summary["allProfilesStopped"] = not final_errors
        summary["finalStateErrors"] = final_errors
        write_summary(args.matrix_evidence, summary)
        print(f"LIVE_MATRIX_EVIDENCE {args.matrix_evidence}")
        if failure is None and final_errors:
            failure = RuntimeError(
                "DiscPanel matrix is not safely stopped after the run: "
                + "; ".join(final_errors)
            )

    if failure is not None:
        raise failure
    if summary["failures"]:
        return 1
    print(
        f"LIVE_MATRIX_COMPLETE selected={len(targets)} "
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
        "--url", default=os.environ.get("DISCOPANEL_URL", "http://192.168.1.42:8080")
    )
    parser.add_argument("--token-env", default="DISCOPANEL_TOKEN")
    parser.add_argument("--request-timeout", type=int, default=60)
    parser.add_argument(
        "--evidence-dir",
        type=Path,
        default=Path("build/discopanel-live-client-gate"),
    )
    parser.add_argument(
        "--matrix-evidence",
        type=Path,
        default=Path("build/discopanel-live-client-gate/matrix-summary.json"),
    )
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm-version")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--continue-on-error", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(run(parse_args()))
