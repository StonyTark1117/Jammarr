#!/usr/bin/env python3
"""Configure stopped DiscPanel runtimes for environment-only live Plex testing.

The source server's canonical config is read in memory. Its URL is retained,
its token is moved to JAMMARR_PLEX_TOKEN, and musicLibrary is blanked so the
candidate must select the library named Music while excluding other libraries.
No credential value is printed or written to the local workspace.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import importlib.util
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
RECONCILER_SCRIPT = SCRIPT_DIR / "reconcile-discopanel-test-servers.py"
SPEC = importlib.util.spec_from_file_location(
    "jammarr_live_plex_reconciler", RECONCILER_SCRIPT
)
assert SPEC and SPEC.loader
reconciler = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = reconciler
SPEC.loader.exec_module(reconciler)

CANONICAL_CONFIG = "world/serverconfig/jammarr-server.toml"
SOURCE_SERVER_NAME = "Jammarr 1.7.10 Forge Test"


@dataclass(frozen=True)
class PlexSource:
    url: str
    token: str
    config: bytes


def quoted_value(text: str, key: str) -> str | None:
    match = re.search(rf'(?m)^\s*{re.escape(key)}\s*=\s*"([^"]*)"\s*$', text)
    return match.group(1) if match else None


def replace_quoted_value(text: str, key: str, value: str) -> str:
    pattern = re.compile(rf'(?m)^(\s*{re.escape(key)}\s*=\s*)"[^"]*"\s*$')
    updated, count = pattern.subn(rf'\1"{value}"', text)
    if count != 1:
        raise RuntimeError(f"source config must define {key} exactly once")
    return updated


def plex_source(config: bytes) -> PlexSource:
    text = config.decode("utf-8")
    url = quoted_value(text, "plexUrl") or ""
    token = quoted_value(text, "plexToken") or ""
    library = quoted_value(text, "musicLibrary")
    if not url or not token:
        raise RuntimeError("source config does not contain a Plex URL and token")
    if library != "Music":
        raise RuntimeError("source config must explicitly select the Music library")
    sanitized = replace_quoted_value(text, "plexToken", "")
    sanitized = replace_quoted_value(sanitized, "musicLibrary", "")
    encoded = sanitized.encode("utf-8")
    if token.encode("utf-8") in encoded:
        raise RuntimeError("sanitized config still contains the Plex token")
    return PlexSource(url=url, token=token, config=encoded)


def ensure_folder(panel: Any, server_id: str, path: str) -> None:
    parent, _, name = path.rpartition("/")
    existing = {
        str(entry.get("name", "")) for entry in panel.list_files(server_id, parent)
    }
    if name in existing:
        return
    panel.create_folder(server_id, path)
    verified = {
        str(entry.get("name", "")) for entry in panel.list_files(server_id, parent)
    }
    if name not in verified:
        raise RuntimeError(f"DiscPanel did not create {path}")


def config_state(panel: Any, server_id: str) -> bytes | None:
    try:
        return panel.get_file(server_id, CANONICAL_CONFIG)
    except RuntimeError as error:
        if "HTTP 404" in str(error) or "not found" in str(error).lower():
            return None
        raise


def target_action(
    current: bytes | None,
    expected: bytes,
    token_matches: bool,
    *,
    replace_existing: bool,
    missing_only: bool,
) -> str:
    """Classify a target without exposing any credential value."""
    if current == expected and token_matches:
        return "already"
    if current is not None and current != expected:
        if missing_only:
            return "skip-existing"
        if not replace_existing:
            return "conflict"
    return "configure"


def configure_target(
    panel: Any, profile: Any, server: dict[str, Any], source: PlexSource
) -> None:
    server_id = str(server["id"])
    fresh = panel.get_server(server_id)
    differences = reconciler.drift(profile, fresh)
    if differences:
        raise RuntimeError(f"{profile.runtime} server drifted: {'; '.join(differences)}")
    ensure_folder(panel, server_id, "world")
    ensure_folder(panel, server_id, "world/serverconfig")
    panel.update_file(server_id, CANONICAL_CONFIG, source.config)
    if panel.get_file(server_id, CANONICAL_CONFIG) != source.config:
        raise RuntimeError(f"{profile.runtime} live Plex config did not persist")
    panel.update_server_environment(fresh, {"JAMMARR_PLEX_TOKEN": source.token})
    updated = panel.get_server(server_id)
    environment = ((updated.get("dockerOverrides") or {}).get("environment") or {})
    if environment.get("JAMMARR_PLEX_TOKEN") != source.token:
        raise RuntimeError(f"{profile.runtime} Plex token environment did not persist")
    if updated.get("status") != reconciler.STATUS_STOPPED:
        raise RuntimeError(f"{profile.runtime} did not remain stopped during Plex setup")


def reconcile(args: argparse.Namespace) -> int:
    panel_token = os.environ.get(args.token_env)
    if not panel_token:
        raise SystemExit(f"set {args.token_env} in the process environment")
    if args.apply and args.confirm_library != "Music":
        raise SystemExit("--apply requires --confirm-library Music")
    if args.replace_existing and args.missing_only:
        raise SystemExit("--replace-existing and --missing-only are mutually exclusive")
    if not 1 <= args.read_workers <= 16:
        raise SystemExit("--read-workers must be between 1 and 16")
    panel = reconciler.DiscPanel(args.url, panel_token, args.request_timeout)
    servers = panel.list_servers()
    by_name = {str(server.get("name", "")): server for server in servers}
    if len(by_name) != len(servers):
        raise RuntimeError("DiscPanel contains duplicate server names")
    source_server = by_name.get(args.source_server)
    if source_server is None:
        raise RuntimeError(f"source server is missing: {args.source_server}")
    source = plex_source(
        panel.get_file(str(source_server["id"]), args.source_config)
    )
    profiles = reconciler.desired_profiles(args.manifest)
    by_runtime = {profile.runtime: profile for profile in profiles}
    if not args.runtime:
        raise SystemExit("select at least one --runtime explicitly")
    unknown = sorted(set(args.runtime) - set(by_runtime))
    if unknown:
        raise SystemExit(f"unknown runtime selection: {', '.join(unknown)}")

    pending: list[tuple[Any, dict[str, Any]]] = []
    already = 0
    skipped_existing = 0
    conflicts: list[str] = []

    def inspect(runtime: str) -> tuple[str, bytes | None, dict[str, Any] | None]:
        profile = by_runtime[runtime]
        summary = by_name.get(profile.name)
        if summary is None:
            raise RuntimeError(f"DiscPanel server is missing for {runtime}")
        server_id = str(summary["id"])
        current = config_state(panel, server_id)
        if args.missing_only and current is not None and current != source.config:
            return runtime, current, None
        return runtime, current, panel.get_server(server_id)

    with concurrent.futures.ThreadPoolExecutor(
        max_workers=args.read_workers
    ) as executor:
        inspections = list(executor.map(inspect, args.runtime))

    for runtime, current, full in inspections:
        profile = by_runtime[runtime]
        summary = by_name[profile.name]
        if full is None:
            skipped_existing += 1
            continue
        environment = ((full.get("dockerOverrides") or {}).get("environment") or {})
        token_matches = environment.get("JAMMARR_PLEX_TOKEN") == source.token
        action = target_action(
            current,
            source.config,
            token_matches,
            replace_existing=args.replace_existing,
            missing_only=args.missing_only,
        )
        if action == "already":
            already += 1
            continue
        if action == "skip-existing":
            skipped_existing += 1
            continue
        differences = reconciler.drift(profile, full)
        if differences:
            raise RuntimeError(f"{runtime} server drifted: {'; '.join(differences)}")
        if action == "conflict":
            conflicts.append(runtime)
        else:
            pending.append((profile, summary))

    print(
        f"DiscPanel live Plex: selected={len(args.runtime)} already={already} "
        f"configure_required={len(pending)} conflicts={len(conflicts)} "
        f"skipped_existing={skipped_existing} "
        f"apply={str(args.apply).lower()} library_fallback=Music"
    )
    for runtime in conflicts:
        print(f"CONFLICT existing canonical config: {runtime}")
    if conflicts:
        return 2
    if not args.apply:
        for profile, _ in pending:
            print(f"WOULD_CONFIGURE_LIVE_PLEX {profile.runtime}")
        return 0
    for profile, server in pending:
        print(f"CONFIGURE_LIVE_PLEX {profile.runtime}", flush=True)
        configure_target(panel, profile, server, source)
        print(f"CONFIGURED_LIVE_PLEX_STOPPED {profile.runtime}", flush=True)
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=Path("gradle/targets.json"))
    parser.add_argument("--runtime", action="append")
    parser.add_argument("--source-server", default=SOURCE_SERVER_NAME)
    parser.add_argument("--source-config", default=CANONICAL_CONFIG)
    parser.add_argument(
        "--url", default=os.environ.get("DISCOPANEL_URL", "http://192.168.1.42:8080")
    )
    parser.add_argument("--token-env", default="DISCOPANEL_TOKEN")
    parser.add_argument("--request-timeout", type=int, default=60)
    parser.add_argument(
        "--read-workers",
        type=int,
        default=6,
        help="parallel read-only classification requests (1-16; writes stay sequential)",
    )
    parser.add_argument("--replace-existing", action="store_true")
    parser.add_argument(
        "--missing-only",
        action="store_true",
        help="preserve and skip any existing differing canonical config",
    )
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm-library")
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(reconcile(parse_args()))
