#!/usr/bin/env python3
"""Run genuine artifact-free client acceptance across modern Jammarr runtimes."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
TARGET_MATRIX_SCRIPT = SCRIPT_DIR / "target-matrix.py"
SPEC = importlib.util.spec_from_file_location("jammarr_target_matrix", TARGET_MATRIX_SCRIPT)
assert SPEC and SPEC.loader
target_matrix = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(target_matrix)


def release_tuple(version: str) -> tuple[int, int, int] | None:
    match = re.fullmatch(r"([0-9]+)\.([0-9]+)(?:\.([0-9]+))?", version)
    if not match:
        return None
    return tuple(int(value or 0) for value in match.groups())


def artifact_free_runtimes(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        runtime
        for runtime in target_matrix.runtimes(manifest)
        if (release_tuple(runtime["minecraft"]) or (0, 0, 0))
        >= (1, 12, 2)
    ]


def select_runtimes(
    runtimes: list[dict[str, Any]], requested: list[str]
) -> list[dict[str, Any]]:
    by_name = {runtime["name"]: runtime for runtime in runtimes}
    if not requested:
        return runtimes
    if len(requested) != len(set(requested)):
        raise SystemExit("runtime selections must be unique")
    unknown = [name for name in requested if name not in by_name]
    if unknown:
        raise SystemExit(
            "runtime selections are not artifact-free-client targets: " + ", ".join(unknown)
        )
    return [by_name[name] for name in requested]


def port_listening(port: int) -> bool:
    with socket.socket() as probe:
        probe.settimeout(0.2)
        return probe.connect_ex(("127.0.0.1", port)) == 0


def process_mentions(path: Path) -> bool:
    needle = os.fsencode(str(path.resolve()))
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit() or int(entry.name) == os.getpid():
            continue
        try:
            if needle in (entry / "cmdline").read_bytes():
                return True
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            continue
    return False


def accepted_evidence(
    output: Path,
    runtime: dict[str, Any],
    connected_seconds: int = 10,
) -> bool:
    label = runtime["name"]
    minecraft = runtime["minecraft"]
    evidence = output / f"{label}.vanilla-client.evidence.txt"
    attestation = (
        output
        / f"{label}.vanilla-client.prism/instances"
        / f"jammarr-vanilla-{minecraft}/vanilla-attestation.json"
    )
    try:
        text = evidence.read_text("utf-8")
        value = json.loads(attestation.read_text("utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    if not isinstance(value, dict):
        return False
    details = value.get("runtime", {})
    if not isinstance(details, dict):
        return False
    counts = details.get("artifactSourceCounts")
    version = release_tuple(minecraft)
    if version is None:
        return False
    expected_stub = (1, 16, 0) <= version < (1, 20, 0)
    expected_mode = "quick-play-multiplayer" if version >= (1, 20, 0) else "legacy-server-port"
    expected_instance = (
        output
        / f"{label}.vanilla-client.prism/instances"
        / f"jammarr-vanilla-{minecraft}"
    ).resolve()
    component_uids = value.get("componentUids")
    client_sha1 = details.get("clientJarSha1")
    instance_directory = value.get("instanceDirectory")
    game_directory = value.get("gameDirectory")
    return (
        value.get("schemaVersion") == 1
        and value.get("launcher") == "Direct Mojang client from verified Prism caches"
        and value.get("minecraftVersion") == minecraft
        and component_uids in (["org.lwjgl", "net.minecraft"], ["org.lwjgl3", "net.minecraft"])
        and value.get("jammarrComponentPresent") is False
        and value.get("mods") == []
        and value.get("accountMode") == "direct-offline"
        and value.get("offlineUsername") == "PureVanilla"
        and isinstance(instance_directory, str)
        and Path(instance_directory).resolve() == expected_instance
        and isinstance(game_directory, str)
        and Path(game_directory).resolve() == expected_instance / "minecraft"
        and details.get("allArtifactSha1Verified") is True
        and details.get("allArtifactSha1AndSizeVerified") is True
        and details.get("sharedCacheMutated") is False
        and details.get("connectionTarget") == f"127.0.0.1:{runtime['port']}"
        and details.get("connectionMode") == expected_mode
        and details.get("offlinePrivilegesStub") is expected_stub
        and isinstance(client_sha1, str)
        and re.fullmatch(r"[0-9a-f]{40}", client_sha1) is not None
        and isinstance(counts, dict)
        and counts
        and all(type(count) is int and count >= 0 for count in counts.values())
        and sum(counts.values()) > 0
        and "capableListeners=0, vanillaListeners=1, listenerStats=0" in text
        and f"Artifact-free vanilla client remained connected for {connected_seconds} seconds." in text
        and "Artifact-free vanilla client sent player-originated chat" in text
        and "Artifact-free vanilla client reconnected and completed a second clean lifecycle" in text
        and "Plex request count remained unchanged" in text
        and not process_mentions(output)
        and not port_listening(int(runtime["port"]))
    )


def write_summary(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def run_gate(
    command: list[str],
    *,
    cwd: Path,
    env: dict[str, str],
    cleanup_timeout: int = 240,
) -> int:
    """Run a gate in an isolated session and preserve its interrupt cleanup.

    A terminal interrupt reaches the matrix wrapper but not the separately
    sessioned gate. The wrapper can therefore signal the gate shell itself and
    wait for its EXIT trap to stop the dedicated server, private X server, and
    client descendants before propagating the interrupt.
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
                f"vanilla client gate cleanup did not finish within "
                f"{cleanup_timeout} seconds"
            ) from error
        raise


def run(args: argparse.Namespace) -> int:
    manifest = target_matrix.load_manifest(args.manifest)
    all_runtimes = artifact_free_runtimes(manifest)
    runtimes = select_runtimes(all_runtimes, args.runtime)
    if not args.gate_script.is_file():
        raise SystemExit(f"dedicated-server gate is missing: {args.gate_script}")
    summary: dict[str, Any] = {
        "schemaVersion": 1,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "manifest": str(args.manifest),
        "manifestSha256": hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
        "connectedSeconds": args.connected_seconds,
        "selected": [runtime["name"] for runtime in runtimes],
        "accepted": [],
        "resumed": [],
        "failures": [],
        "privateXRequiredByGate": True,
        "artifactFree": True,
    }
    interrupted: BaseException | None = None
    try:
        for index, runtime in enumerate(runtimes, start=1):
            label = runtime["name"]
            output = args.output_root / label
            if args.resume and accepted_evidence(output, runtime, args.connected_seconds):
                summary["resumed"].append(label)
                print(f"VANILLA_MATRIX_RESUME {index}/{len(runtimes)} {label}", flush=True)
                continue
            print(f"VANILLA_MATRIX_RUNTIME {index}/{len(runtimes)} {label}", flush=True)
            environment = dict(os.environ)
            environment.update(
                {
                    "JAMMARR_GATE_OUTPUT_ROOT": str(output.resolve()),
                    "JAMMARR_PROTOCOL_CLIENT_GATE": "false",
                    "JAMMARR_COMMAND_CLIENT_GATE": "false",
                    "JAMMARR_AUDIO_CLIENT_GATE": "false",
                    "JAMMARR_AUDIO_SCENARIO_GATE": "false",
                    "JAMMARR_VANILLA_CLIENT_GATE": "true",
                    "JAMMARR_VANILLA_CONNECTED_SECONDS": str(args.connected_seconds),
                }
            )
            exit_code = run_gate(
                [str(args.gate_script.resolve()), label],
                cwd=args.gate_script.resolve().parent.parent,
                env=environment,
            )
            accepted = accepted_evidence(output, runtime, args.connected_seconds)
            if exit_code == 0 and accepted:
                summary["accepted"].append(label)
                continue
            failure = {
                "runtime": label,
                "exitCode": exit_code,
                "acceptedEvidence": accepted,
            }
            summary["failures"].append(failure)
            if not args.continue_on_error:
                break
    except BaseException as error:
        interrupted = error
    finally:
        summary["finishedAt"] = datetime.now(timezone.utc).isoformat()
        summary["complete"] = (
            not summary["failures"]
            and len(summary["accepted"]) + len(summary["resumed"]) == len(runtimes)
        )
        write_summary(args.summary, summary)
        print(f"VANILLA_MATRIX_EVIDENCE {args.summary}", flush=True)
    if interrupted is not None:
        raise interrupted
    if not summary["complete"]:
        return 1
    print(
        f"VANILLA_MATRIX_COMPLETE selected={len(runtimes)} "
        f"accepted={len(summary['accepted'])} resumed={len(summary['resumed'])}",
        flush=True,
    )
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", action="append", default=[])
    parser.add_argument("--manifest", type=Path, default=Path("gradle/targets.json"))
    parser.add_argument(
        "--gate-script", type=Path, default=Path("scripts/run-dedicated-server-gate.sh")
    )
    parser.add_argument(
        "--output-root", type=Path, default=Path("build/vanilla-client-matrix")
    )
    parser.add_argument(
        "--summary",
        type=Path,
        default=Path("build/vanilla-client-matrix/matrix-summary.json"),
    )
    parser.add_argument("--connected-seconds", type=int, default=10)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--continue-on-error", action="store_true")
    args = parser.parse_args()
    if not 10 <= args.connected_seconds <= 300:
        parser.error("--connected-seconds must be from 10 through 300")
    return args


if __name__ == "__main__":
    sys.exit(run(parse_args()))
