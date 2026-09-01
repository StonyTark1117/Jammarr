#!/usr/bin/env python3
"""Snapshot and recover interrupted DiscPanel live-client transactions.

The live-client gate temporarily changes only a small, reversible subset of a
stopped profile.  Shell traps cover ordinary failures and interrupts, but they
cannot run after SIGKILL or an execution-host teardown.  This helper persists a
credential-free journal before the first mutation and can restore an orphaned
profile while the repository-wide runtime lock is held.
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
from types import SimpleNamespace
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
SMOKE_SCRIPT = SCRIPT_DIR / "run-discopanel-server-smoke.py"
SPEC = importlib.util.spec_from_file_location(
    "jammarr_live_recovery_smoke", SMOKE_SCRIPT
)
assert SPEC and SPEC.loader
smoke = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = smoke
SPEC.loader.exec_module(smoke)
deployment = smoke.deployment
reconciler = smoke.reconciler

SCHEMA_VERSION = 1
PENDING_NAME = "recovery-pending.json"
COMPLETE_NAME = "recovery-complete.json"
PROPERTY_KEYS = ("level-name", "online-mode", "enforce-secure-profile")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def property_values(data: bytes) -> dict[str, list[str]]:
    text = data.decode("utf-8")
    values = {key: [] for key in PROPERTY_KEYS}
    for line in text.splitlines():
        for key in PROPERTY_KEYS:
            prefix = f"{key}="
            if line.startswith(prefix):
                values[key].append(line[len(prefix) :])
                break
    return values


def restore_property_values(data: bytes, originals: dict[str, list[str]]) -> bytes:
    text = data.decode("utf-8")
    lines = text.splitlines(keepends=True)
    newline = "\r\n" if "\r\n" in text else "\n"
    for key in PROPERTY_KEYS:
        wanted = list(originals.get(key, []))
        indexes = [
            index
            for index, line in enumerate(lines)
            if line.rstrip("\r\n").startswith(f"{key}=")
        ]
        for occurrence, index in enumerate(indexes):
            if occurrence >= len(wanted):
                lines[index] = ""
                continue
            body = lines[index].rstrip("\r\n")
            ending = lines[index][len(body) :]
            lines[index] = f"{key}={wanted[occurrence]}{ending}"
        if len(wanted) > len(indexes):
            if lines and not lines[-1].endswith(("\n", "\r")):
                lines[-1] += newline
            lines.extend(
                f"{key}={value}{newline}" for value in wanted[len(indexes) :]
            )
    return "".join(lines).encode("utf-8")


def atomic_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", "utf-8")
    temporary.replace(path)


def exact_target(args: argparse.Namespace, runtime: str) -> tuple[str, Any]:
    version, target, _profile = smoke.exact_target(
        SimpleNamespace(
            expected_version=args.expected_version,
            release_dir=args.release_dir,
            manifest=args.manifest,
            project_properties=args.project_properties,
            runtime=runtime,
        )
    )
    return version, target


def panel_client(args: argparse.Namespace) -> Any:
    token = os.environ.get(args.token_env)
    if not token:
        raise SystemExit(f"set {args.token_env} in the process environment")
    return reconciler.DiscPanel(args.url, token, args.request_timeout)


def root_names(panel: Any, server_id: str) -> set[str]:
    return {
        entry.get("name")
        or str(entry.get("path", "")).rstrip("/").rsplit("/", 1)[-1]
        for entry in panel.list_files(server_id, "")
    }


def matching_mod_states(panel: Any, server_id: str) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    mods = panel.call("ModService", "ListMods", {"serverId": server_id}).get(
        "mods", []
    )
    for mod in mods:
        filename = str(mod.get("fileName", ""))
        if not filename.lower().startswith("cinemarr-"):
            continue
        result.append(
            {
                "id": str(mod.get("id")),
                "fileName": filename,
                "enabled": deployment.is_enabled(mod),
            }
        )
    return result


def resolve_server(
    args: argparse.Namespace, panel: Any, runtime: str, expected_id: str | None = None
) -> tuple[str, Any, dict[str, Any]]:
    version, target = exact_target(args, runtime)
    matches = [
        server
        for server in panel.list_servers()
        if server.get("name") == target.server_name
    ]
    if len(matches) != 1:
        raise RuntimeError(f"{runtime} maps to {len(matches)} profiles; expected one")
    server = panel.get_server(str(matches[0]["id"]))
    if expected_id is not None and str(server.get("id")) != expected_id:
        raise RuntimeError(f"{runtime} server id changed after the recovery snapshot")
    return version, target, server


def snapshot(args: argparse.Namespace) -> int:
    if not args.level or not args.session_dir:
        raise SystemExit("snapshot requires --level and --session-dir")
    if not re.fullmatch(r"jammarr-live-[A-Za-z0-9]{6}", args.level):
        raise SystemExit("snapshot level is not a live-gate isolated world")
    panel = panel_client(args)
    version, target, server = resolve_server(args, panel, args.runtime)
    server_id = str(server["id"])
    if args.server_id and args.server_id != server_id:
        raise RuntimeError("snapshot server id does not match the runtime profile")
    if server.get("status") != reconciler.STATUS_STOPPED or server.get("autoStart"):
        raise RuntimeError("refusing to snapshot a profile that is not stopped/autostart-off")
    if args.level in root_names(panel, server_id):
        raise RuntimeError("refusing to snapshot an existing isolated world")
    properties = panel.get_file(server_id, "server.properties")
    overrides = server.get("dockerOverrides") or {}
    environment = overrides.get("environment") or {}
    online_present = "ONLINE_MODE" in environment
    online_value = environment.get("ONLINE_MODE") if online_present else None
    if online_value is not None and not isinstance(online_value, str):
        raise RuntimeError("ONLINE_MODE override is not a string")
    journal = {
        "schemaVersion": SCHEMA_VERSION,
        "state": "pending",
        "createdAt": utc_now(),
        "runtime": args.runtime,
        "version": version,
        "artifact": target.filename,
        "sha256": target.sha256,
        "serverId": server_id,
        "serverName": target.server_name,
        "level": args.level,
        "originalProperties": property_values(properties),
        "originalOnlineModeOverride": {
            "present": online_present,
            "value": online_value,
        },
        "originalMatchingMods": matching_mod_states(panel, server_id),
    }
    atomic_json(args.session_dir / PENDING_NAME, journal)
    print(f"LIVE_RECOVERY_SNAPSHOT runtime={args.runtime} pending=true")
    return 0


def load_journal(session_dir: Path) -> dict[str, Any]:
    path = session_dir / PENDING_NAME
    try:
        journal = json.loads(path.read_text("utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"invalid recovery journal in {session_dir}") from error
    if not isinstance(journal, dict) or journal.get("schemaVersion") != SCHEMA_VERSION:
        raise RuntimeError(f"unsupported recovery journal in {session_dir}")
    if journal.get("state") != "pending":
        raise RuntimeError(f"recovery journal is not pending in {session_dir}")
    return journal


def session_has_process(session_dir: Path) -> bool:
    marker = str(session_dir.resolve()).encode()
    ignored = {os.getpid(), os.getppid()}
    for command_path in Path("/proc").glob("[0-9]*/cmdline"):
        try:
            pid = int(command_path.parent.name)
            if pid not in ignored and marker in command_path.read_bytes():
                return True
        except (OSError, ValueError):
            continue
    return False


def validate_journal(journal: dict[str, Any]) -> None:
    if not isinstance(journal.get("runtime"), str):
        raise RuntimeError("recovery journal runtime is invalid")
    level = journal.get("level")
    if not isinstance(level, str) or not re.fullmatch(
        r"jammarr-live-[A-Za-z0-9]{6}", level
    ):
        raise RuntimeError("recovery journal level is invalid")
    originals = journal.get("originalProperties")
    if not isinstance(originals, dict) or set(originals) != set(PROPERTY_KEYS):
        raise RuntimeError("recovery journal properties are invalid")
    for key in PROPERTY_KEYS:
        if not isinstance(originals[key], list) or not all(
            isinstance(value, str) for value in originals[key]
        ):
            raise RuntimeError(f"recovery journal property {key} is invalid")
    online = journal.get("originalOnlineModeOverride")
    if not isinstance(online, dict) or not isinstance(online.get("present"), bool):
        raise RuntimeError("recovery journal ONLINE_MODE state is invalid")
    if online["present"] and not isinstance(online.get("value"), str):
        raise RuntimeError("recovery journal ONLINE_MODE value is invalid")
    mods = journal.get("originalMatchingMods")
    if not isinstance(mods, list):
        raise RuntimeError("recovery journal mod state is invalid")
    for mod in mods:
        if not (
            isinstance(mod, dict)
            and isinstance(mod.get("id"), str)
            and isinstance(mod.get("fileName"), str)
            and isinstance(mod.get("enabled"), bool)
            and mod["fileName"].lower().startswith("cinemarr-")
        ):
            raise RuntimeError("recovery journal contains an invalid mod state")


def recover_session(
    args: argparse.Namespace,
    panel: Any,
    session_dir: Path,
    require_no_process: bool,
) -> None:
    journal = load_journal(session_dir)
    validate_journal(journal)
    if require_no_process and session_has_process(session_dir):
        raise RuntimeError(f"pending recovery session still has a process: {session_dir}")
    runtime = str(journal["runtime"])
    _version, target, server = resolve_server(
        args, panel, runtime, str(journal["serverId"])
    )
    if target.server_name != journal.get("serverName"):
        raise RuntimeError(f"{runtime} server name changed after the recovery snapshot")
    if server.get("autoStart"):
        raise RuntimeError(f"{runtime} autostart is enabled during recovery")
    server_id = str(server["id"])
    level = str(journal["level"])
    current_properties = panel.get_file(server_id, "server.properties")
    current_values = property_values(current_properties)
    status = str(server.get("status", "unknown"))
    if status != reconciler.STATUS_STOPPED:
        if current_values.get("level-name") != [level]:
            raise RuntimeError(
                f"{runtime} is active without the journaled isolated world; refusing stop"
            )
        panel.call("ServerService", "StopServer", {"id": server_id})
        smoke.wait_for_status(
            panel,
            server_id,
            reconciler.STATUS_STOPPED,
            args.stop_timeout,
            args.poll_interval,
        )
    restored_properties = restore_property_values(
        panel.get_file(server_id, "server.properties"),
        journal["originalProperties"],
    )
    panel.update_file(server_id, "server.properties", restored_properties)
    if panel.get_file(server_id, "server.properties") != restored_properties:
        raise RuntimeError(f"{runtime} property recovery verification failed")

    current_server = panel.get_server(server_id)
    restored_server = dict(current_server)
    overrides = json.loads(json.dumps(current_server.get("dockerOverrides") or {}))
    environment = dict(overrides.get("environment") or {})
    online = journal["originalOnlineModeOverride"]
    if online["present"]:
        environment["ONLINE_MODE"] = online["value"]
    else:
        environment.pop("ONLINE_MODE", None)
    overrides["environment"] = environment
    restored_server["dockerOverrides"] = overrides
    panel.update_server_description(
        restored_server, str(current_server.get("description", ""))
    )

    current_mods = {
        str(mod.get("id")): mod
        for mod in panel.call("ModService", "ListMods", {"serverId": server_id}).get(
            "mods", []
        )
    }
    for original in journal["originalMatchingMods"]:
        mod = current_mods.get(original["id"])
        if mod is None or str(mod.get("fileName")) != original["fileName"]:
            raise RuntimeError(f"{runtime} matching mod changed during recovery")
        if deployment.is_enabled(mod) != original["enabled"]:
            deployment.update_mod_enabled(
                panel, server_id, mod, bool(original["enabled"])
            )

    if level in root_names(panel, server_id):
        panel.delete_file(server_id, level)

    final_server = panel.get_server(server_id)
    if (
        final_server.get("status") != reconciler.STATUS_STOPPED
        or final_server.get("autoStart")
    ):
        raise RuntimeError(f"{runtime} is not stopped/autostart-off after recovery")
    if property_values(panel.get_file(server_id, "server.properties")) != journal[
        "originalProperties"
    ]:
        raise RuntimeError(f"{runtime} original property values were not restored")
    final_environment = (
        (final_server.get("dockerOverrides") or {}).get("environment") or {}
    )
    if online["present"]:
        if final_environment.get("ONLINE_MODE") != online["value"]:
            raise RuntimeError(f"{runtime} ONLINE_MODE value was not restored")
    elif "ONLINE_MODE" in final_environment:
        raise RuntimeError(f"{runtime} temporary ONLINE_MODE value remains")
    final_mods = {state["id"]: state for state in matching_mod_states(panel, server_id)}
    for original in journal["originalMatchingMods"]:
        if final_mods.get(original["id"]) != original:
            raise RuntimeError(f"{runtime} matching mod state was not restored")
    if level in root_names(panel, server_id):
        raise RuntimeError(f"{runtime} isolated world remains after recovery")

    completion = {
        "schemaVersion": SCHEMA_VERSION,
        "state": "complete",
        "runtime": runtime,
        "serverName": journal["serverName"],
        "level": level,
        "snapshotCreatedAt": journal["createdAt"],
        "recoveredAt": utc_now(),
        "stopped": True,
        "propertiesRestored": True,
        "dockerOverrideRestored": True,
        "matchingModsRestored": True,
        "isolatedLevelRemoved": True,
    }
    atomic_json(session_dir / COMPLETE_NAME, completion)
    (session_dir / PENDING_NAME).unlink()
    print(f"LIVE_RECOVERY_COMPLETE runtime={runtime} stopped=true")


def recover(args: argparse.Namespace) -> int:
    panel = panel_client(args)
    if args.command == "recover-owned":
        if not args.session_dir:
            raise SystemExit("recover-owned requires --session-dir")
        sessions = [args.session_dir]
        require_no_process = False
    else:
        if not args.evidence_root:
            raise SystemExit("recover-pending requires --evidence-root")
        sessions = sorted(
            path.parent for path in args.evidence_root.glob(f"*/{PENDING_NAME}")
        )
        require_no_process = True
    for session_dir in sessions:
        recover_session(args, panel, session_dir, require_no_process)
    print(f"LIVE_RECOVERY_SCAN_COMPLETE recovered={len(sessions)}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("snapshot", "recover-pending", "recover-owned"))
    parser.add_argument("--runtime")
    parser.add_argument("--server-id")
    parser.add_argument("--level")
    parser.add_argument("--session-dir", type=Path)
    parser.add_argument("--evidence-root", type=Path)
    parser.add_argument("--manifest", type=Path, default=Path("gradle/targets.json"))
    parser.add_argument("--release-dir", type=Path, default=Path("build/releases"))
    parser.add_argument("--project-properties", type=Path, default=Path("gradle.properties"))
    parser.add_argument("--expected-version", default="1.1.0")
    parser.add_argument(
        "--url", default=os.environ.get("DISCOPANEL_URL", "http://192.168.1.42:8080")
    )
    parser.add_argument("--token-env", default="DISCOPANEL_TOKEN")
    parser.add_argument("--request-timeout", type=int, default=60)
    parser.add_argument("--stop-timeout", type=int, default=180)
    parser.add_argument("--poll-interval", type=float, default=1.0)
    args = parser.parse_args()
    if args.command == "snapshot" and not args.runtime:
        parser.error("snapshot requires --runtime")
    return args


if __name__ == "__main__":
    arguments = parse_args()
    if arguments.command == "snapshot":
        raise SystemExit(snapshot(arguments))
    raise SystemExit(recover(arguments))
