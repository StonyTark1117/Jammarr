#!/usr/bin/env python3
"""Run modded-client-to-official-server acceptance across manifest runtimes."""

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
from typing import Any
import zipfile


SCRIPT_DIR = Path(__file__).resolve().parent
TARGET_MATRIX_SCRIPT = SCRIPT_DIR / "target-matrix.py"
SPEC = importlib.util.spec_from_file_location("jammarr_target_matrix", TARGET_MATRIX_SCRIPT)
assert SPEC and SPEC.loader
target_matrix = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(target_matrix)

BETA_SERVER_PROVENANCE = {
    "source": "Pinned Babric/Betacraft archive of the vanilla Beta server",
    "serverUrl": "https://files.betacraft.uk/server-archive/beta/b1.7.3.jar",
    "serverSha1": "2f90dc1cb5ca7e9d71786801b307390a67fcf954",
    "serverSize": 503100,
    "provenanceUrl": "https://babric.github.io/manifest-polyfill/b1.7.3.json",
}


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
        raise SystemExit("unknown full runtime selections: " + ", ".join(unknown))
    return [by_name[name] for name in requested]


def resource_lock_environment(runtime: dict[str, Any], enabled: bool) -> dict[str, str]:
    if not enabled:
        return {}
    key = hashlib.sha256(runtime["minecraft"].encode("utf-8")).hexdigest()[:20]
    return {
        "JAMMARR_GATE_LOCK_SCOPE": "resource",
        "JAMMARR_GATE_LOCK_KEY": key,
    }


def sha1_file(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


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


def server_jar_is_unmodded(path: Path) -> bool:
    if not zipfile.is_zipfile(path):
        return False
    try:
        with zipfile.ZipFile(path) as archive:
            return not any("jammarr" in name.lower() for name in archive.namelist())
    except (OSError, zipfile.BadZipFile):
        return False


def server_joined(server_text: str, username: str = "JammarrNoServer") -> bool:
    return f"{username} joined the game" in server_text or re.search(
        rf"{re.escape(username)} \[/[^]]+\] logged in with entity id", server_text
    ) is not None


def accepted_provenance(value: dict[str, Any], minecraft: str) -> bool:
    if minecraft == "b1.7.3":
        return value.get("officialMojangDownload") is False and all(
            value.get(key) == expected
            for key, expected in BETA_SERVER_PROVENANCE.items()
        )
    server_sha1 = value.get("serverSha1")
    metadata_sha1 = value.get("metadataSha1")
    return (
        value.get("officialMojangDownload") is True
        and value.get("source") == "Official Mojang version manifest"
        and value.get("manifestUrl")
        == "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        and isinstance(metadata_sha1, str)
        and re.fullmatch(r"[0-9a-f]{40}", metadata_sha1) is not None
        and isinstance(server_sha1, str)
        and re.fullmatch(r"[0-9a-f]{40}", server_sha1) is not None
        and value.get("metadataUrl")
        == f"https://piston-meta.mojang.com/v1/packages/{metadata_sha1}/{minecraft}.json"
        and isinstance(value.get("serverUrl"), str)
        and value["serverUrl"]
        == f"https://piston-data.mojang.com/v1/objects/{server_sha1}/server.jar"
    )


def accepted_evidence(
    attempt: Path,
    runtime: dict[str, Any],
    connected_seconds: int = 10,
) -> bool:
    evidence = attempt / "gate.evidence.txt"
    functional_evidence = attempt / "gate.functional.evidence.txt"
    cleanup_evidence = attempt / "gate.cleanup.evidence.txt"
    attestation = attempt / "server-attestation.json"
    server_log = attempt / "server.console.log"
    client_log = attempt / "client.console.log"
    try:
        text = evidence.read_text("utf-8")
        functional_text = functional_evidence.read_text("utf-8")
        cleanup_text = cleanup_evidence.read_text("utf-8")
        value = json.loads(attestation.read_text("utf-8"))
        server_text = server_log.read_text("utf-8", errors="replace")
        client_text = client_log.read_text("utf-8", errors="replace")
        server_jar = Path(value["serverJar"])
        server_size = server_jar.stat().st_size
        server_sha1 = sha1_file(server_jar)
    except (OSError, KeyError, TypeError, json.JSONDecodeError):
        return False
    minecraft = runtime["minecraft"]
    functional_marker = (
        "Modded client remained connected to the attested unmodded server "
        f"for {connected_seconds} seconds after UI verification."
    )
    cleanup_marker = (
        "Attested unmodded server, modded client, private X server, and port cleaned up."
    )
    return (
        value.get("schemaVersion") == 1
        and value.get("minecraftVersion") == minecraft
        and value.get("jammarrPresent") is False
        and value.get("unmoddedVanillaServer") is True
        and server_size == value.get("serverSize")
        and server_sha1 == value.get("serverSha1")
        and server_jar_is_unmodded(server_jar)
        and accepted_provenance(value, minecraft)
        and server_joined(server_text)
        and "Acceptance Jammarr unsupported-server screen remained open" in client_text
        and functional_marker in functional_text
        and functional_marker in text
        and functional_text in text
        and cleanup_marker in cleanup_text
        and cleanup_text in text
        and re.search(
            r"Official server shutdown mode: (?:graceful|forced-after-timeout)\.",
            cleanup_text,
        ) is not None
        and not process_mentions(attempt)
        and not port_listening(int(runtime["port"]))
    )


def accepted_attempt(
    runtime_root: Path,
    runtime: dict[str, Any],
    connected_seconds: int = 10,
) -> Path | None:
    for attempt in sorted(runtime_root.glob("attempt-*"), reverse=True):
        if attempt.is_dir() and accepted_evidence(attempt, runtime, connected_seconds):
            return attempt
    return None


def next_attempt(runtime_root: Path) -> Path:
    used = {
        int(path.name.removeprefix("attempt-"))
        for path in runtime_root.glob("attempt-[0-9][0-9][0-9][0-9]")
        if path.is_dir() and path.name.removeprefix("attempt-").isdigit()
    }
    number = 1
    while number in used:
        number += 1
    return runtime_root / f"attempt-{number:04d}"


def run_gate(command: list[str], *, cwd: Path, env: dict[str, str]) -> int:
    process = subprocess.Popen(command, cwd=cwd, env=env, start_new_session=True)
    try:
        return process.wait()
    except KeyboardInterrupt:
        try:
            process.send_signal(signal.SIGINT)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=180)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError(
                "unmodded-server client gate cleanup did not finish within 180 seconds"
            ) from error
        raise


def write_summary(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def run(args: argparse.Namespace) -> int:
    manifest = target_matrix.load_manifest(args.manifest)
    runtimes = select_runtimes(target_matrix.runtimes(manifest), args.runtime)
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
        "unmoddedVanillaServer": True,
        "officialMojangServerWhereAvailable": True,
        "legacyArchiveExceptions": ["b1.7.3"],
        "privateXRequired": True,
    }
    interrupted: BaseException | None = None
    try:
        for index, runtime in enumerate(runtimes, start=1):
            label = runtime["name"]
            runtime_root = args.output_root / label
            prior = (
                accepted_attempt(runtime_root, runtime, args.connected_seconds)
                if args.resume
                else None
            )
            if prior is not None:
                summary["resumed"].append({"runtime": label, "evidenceRoot": str(prior)})
                print(f"UNMODDED_MATRIX_RESUME {index}/{len(runtimes)} {label}", flush=True)
                continue
            if args.verify_only:
                summary["failures"].append(
                    {
                        "runtime": label,
                        "exitCode": None,
                        "acceptedEvidence": False,
                        "reason": "current resumable evidence is missing",
                    }
                )
                if not args.continue_on_error:
                    break
                continue
            attempt = next_attempt(runtime_root)
            attempt.mkdir(parents=True, exist_ok=False)
            environment = dict(os.environ)
            environment["JAMMARR_UNMODDED_GATE_OUTPUT_ROOT"] = str(attempt.resolve())
            environment["JAMMARR_UNMODDED_CONNECTED_SECONDS"] = str(
                args.connected_seconds
            )
            environment.update(resource_lock_environment(runtime, args.resource_locks))
            print(f"UNMODDED_MATRIX_RUNTIME {index}/{len(runtimes)} {label}", flush=True)
            exit_code = run_gate(
                [str(args.gate_script.resolve()), label],
                cwd=args.gate_script.resolve().parent.parent,
                env=environment,
            )
            accepted = accepted_evidence(attempt, runtime, args.connected_seconds)
            if exit_code == 0 and accepted:
                summary["accepted"].append(
                    {"runtime": label, "evidenceRoot": str(attempt)}
                )
                continue
            summary["failures"].append(
                {
                    "runtime": label,
                    "exitCode": exit_code,
                    "acceptedEvidence": accepted,
                    "evidenceRoot": str(attempt),
                }
            )
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
        print(f"UNMODDED_MATRIX_EVIDENCE {args.summary}", flush=True)
    if interrupted is not None:
        raise interrupted
    return 0 if summary["complete"] else 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", action="append", default=[])
    parser.add_argument("--manifest", type=Path, default=Path("gradle/targets.json"))
    parser.add_argument(
        "--gate-script", type=Path, default=Path("scripts/run-unmodded-server-client-gate.sh")
    )
    parser.add_argument(
        "--output-root", type=Path, default=Path("build/unmodded-server-client-matrix")
    )
    parser.add_argument(
        "--summary",
        type=Path,
        default=Path("build/unmodded-server-client-matrix/matrix-summary.json"),
    )
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--resource-locks", action="store_true")
    parser.add_argument("--continue-on-error", action="store_true")
    parser.add_argument("--connected-seconds", type=int, default=10)
    args = parser.parse_args()
    if not 10 <= args.connected_seconds <= 300:
        parser.error("--connected-seconds must be from 10 through 300")
    if args.verify_only and not args.resume:
        parser.error("--verify-only requires --resume")
    return args


if __name__ == "__main__":
    raise SystemExit(run(parse_args()))
