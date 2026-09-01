#!/usr/bin/env python3
"""Stage exact external runtime dependencies on one stopped DiscPanel profile.

Dry-run is the default. Downloads are always checksum-verified, applies are
single-runtime and explicit, replaced dependency files remain disabled as
rollback records, and no server is started.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import sys
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
DEPLOY_SCRIPT = SCRIPT_DIR / "deploy-discopanel-release.py"
SPEC = importlib.util.spec_from_file_location(
    "jammarr_discopanel_dependency_deploy", DEPLOY_SCRIPT
)
assert SPEC and SPEC.loader
deployment = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = deployment
SPEC.loader.exec_module(deployment)
reconciler = deployment.reconciler


@dataclass(frozen=True)
class Dependency:
    dependency_id: str
    filename: str
    url: str
    sha256: str
    owned_prefixes: tuple[str, ...]
    replaces_multiple_active: bool = False


def load_dependencies(path: Path, runtime: str) -> list[Dependency]:
    manifest = json.loads(path.read_text("utf-8"))
    if manifest.get("schemaVersion") != 1:
        raise SystemExit("DiscPanel dependency manifest must use schemaVersion 1")
    raw_runtimes = manifest.get("runtimes")
    if not isinstance(raw_runtimes, dict) or runtime not in raw_runtimes:
        raise SystemExit(f"dependency manifest has no entry for {runtime}")
    dependencies: list[Dependency] = []
    for raw in raw_runtimes[runtime]:
        dependency = Dependency(
            dependency_id=str(raw.get("id", "")),
            filename=str(raw.get("filename", "")),
            url=str(raw.get("url", "")),
            sha256=str(raw.get("sha256", "")),
            owned_prefixes=tuple(str(value).lower() for value in raw.get("ownedPrefixes", [])),
            replaces_multiple_active=raw.get("replacesMultipleActive", False) is True,
        )
        if (
            not dependency.dependency_id
            or not dependency.filename.endswith(".jar")
            or not dependency.url.startswith("https://")
            or len(dependency.sha256) != 64
            or not dependency.owned_prefixes
            or dependency.filename.lower().startswith("jammarr-")
        ):
            raise SystemExit(f"invalid dependency entry for {runtime}: {raw!r}")
        dependencies.append(dependency)
    if len({dependency.dependency_id for dependency in dependencies}) != len(dependencies):
        raise SystemExit(f"dependency manifest has duplicate IDs for {runtime}")
    for candidate in dependencies:
        owners = [
            dependency.dependency_id
            for dependency in dependencies
            if any(
                candidate.filename.lower().startswith(prefix)
                for prefix in dependency.owned_prefixes
            )
        ]
        if owners != [candidate.dependency_id]:
            raise SystemExit(
                f"dependency ownership is ambiguous for {runtime} "
                f"{candidate.filename}: {owners}"
            )
    return dependencies


def owns(dependency: Dependency, mod: dict[str, Any]) -> bool:
    filename = str(mod.get("fileName", "")).lower()
    return any(filename.startswith(prefix) for prefix in dependency.owned_prefixes)


def download_dependency(
    dependency: Dependency, directory: Path, timeout: int
) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / dependency.filename
    if target.is_file() and deployment.file_sha256(target) == dependency.sha256:
        validate_dependency_archive(dependency, target)
        return target
    partial = target.with_suffix(target.suffix + ".partial")
    partial.unlink(missing_ok=True)
    request = urllib.request.Request(
        dependency.url,
        headers={"User-Agent": "Jammarr-DiscPanel-Dependency-Deployer/1"},
    )
    digest = hashlib.sha256()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response, partial.open(
            "wb"
        ) as output:
            while chunk := response.read(1024 * 1024):
                digest.update(chunk)
                output.write(chunk)
    except BaseException:
        partial.unlink(missing_ok=True)
        raise
    actual = digest.hexdigest()
    if actual != dependency.sha256:
        partial.unlink(missing_ok=True)
        raise RuntimeError(
            f"download digest mismatch for {dependency.dependency_id}: "
            f"expected {dependency.sha256}, got {actual}"
        )
    partial.replace(target)
    validate_dependency_archive(dependency, target)
    return target


def validate_dependency_archive(dependency: Dependency, path: Path) -> None:
    """Reject Fabric API's metadata-only Maven coordinate as a server mod."""
    if dependency.dependency_id != "fabric-api":
        return
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
    except zipfile.BadZipFile as error:
        raise RuntimeError(f"{dependency.dependency_id} is not a valid JAR") from error
    has_runtime_code = any(name.endswith(".class") for name in names) or any(
        name.startswith("META-INF/jars/") and name.endswith(".jar")
        for name in names
    )
    if not has_runtime_code:
        raise RuntimeError(
            "fabric-api dependency is metadata-only; use the published mod distribution"
        )


def stage_dependency(
    panel: Any,
    server: dict[str, Any],
    dependency: Dependency,
    source: Path,
    owned_active: list[dict[str, Any]],
) -> None:
    server_id = str(server["id"])
    fresh = panel.get_server(server_id)
    if fresh.get("status") != reconciler.STATUS_STOPPED:
        raise RuntimeError(f"server became active before staging {dependency.dependency_id}")
    session_id = deployment.upload_session(panel, source)
    panel.call(
        "ModService",
        "ImportUploadedMod",
        {
            "serverId": server_id,
            "uploadSessionId": session_id,
            "displayName": dependency.filename,
            "description": (
                f"Jammarr runtime dependency {dependency.dependency_id}; "
                f"sha256 {dependency.sha256}"
            ),
        },
    )
    if deployment.remote_mod_digest(panel, server_id, dependency.filename) != dependency.sha256:
        raise RuntimeError(f"remote digest mismatch for {dependency.dependency_id}")
    for mod in owned_active:
        deployment.update_mod_enabled(panel, server_id, mod, False)
    final_mods = panel.call("ModService", "ListMods", {"serverId": server_id}).get(
        "mods", []
    )
    final_active = [
        mod
        for mod in final_mods
        if owns(dependency, mod) and deployment.is_enabled(mod)
    ]
    if len(final_active) != 1 or final_active[0].get("fileName") != dependency.filename:
        raise RuntimeError(
            f"{dependency.dependency_id} did not finish with one exact active dependency"
        )
    if panel.get_server(server_id).get("status") != reconciler.STATUS_STOPPED:
        raise RuntimeError(f"server did not remain stopped after {dependency.dependency_id}")


def enable_existing_dependency(
    panel: Any,
    server: dict[str, Any],
    dependency: Dependency,
    exact_disabled: dict[str, Any],
    owned_active: list[dict[str, Any]],
) -> None:
    server_id = str(server["id"])
    fresh = panel.get_server(server_id)
    if fresh.get("status") != reconciler.STATUS_STOPPED:
        raise RuntimeError(
            f"server became active before enabling {dependency.dependency_id}"
        )
    deployment.update_mod_enabled(panel, server_id, exact_disabled, True)
    try:
        if (
            deployment.remote_mod_digest(panel, server_id, dependency.filename)
            != dependency.sha256
        ):
            raise RuntimeError(
                f"re-enabled remote digest mismatch for {dependency.dependency_id}"
            )
    except BaseException:
        deployment.update_mod_enabled(panel, server_id, exact_disabled, False)
        raise
    for mod in owned_active:
        deployment.update_mod_enabled(panel, server_id, mod, False)
    final_mods = panel.call("ModService", "ListMods", {"serverId": server_id}).get(
        "mods", []
    )
    final_active = [
        mod
        for mod in final_mods
        if owns(dependency, mod) and deployment.is_enabled(mod)
    ]
    if len(final_active) != 1 or final_active[0].get("fileName") != dependency.filename:
        raise RuntimeError(
            f"{dependency.dependency_id} did not finish with one exact active dependency"
        )
    if panel.get_server(server_id).get("status") != reconciler.STATUS_STOPPED:
        raise RuntimeError(f"server did not remain stopped after {dependency.dependency_id}")


def refresh_existing_dependency(
    panel: Any,
    server: dict[str, Any],
    dependency: Dependency,
    source: Path,
) -> None:
    """Replace stopped-server bytes while preserving the exact ModService record."""
    server_id = str(server["id"])
    fresh = panel.get_server(server_id)
    if fresh.get("status") != reconciler.STATUS_STOPPED:
        raise RuntimeError(
            f"server became active before refreshing {dependency.dependency_id}"
        )
    panel.update_file(server_id, f"mods/{dependency.filename}", source.read_bytes())
    if deployment.remote_mod_digest(panel, server_id, dependency.filename) != dependency.sha256:
        raise RuntimeError(f"refreshed remote digest mismatch for {dependency.dependency_id}")
    final_mods = panel.call("ModService", "ListMods", {"serverId": server_id}).get(
        "mods", []
    )
    final_active = [
        mod
        for mod in final_mods
        if owns(dependency, mod) and deployment.is_enabled(mod)
    ]
    if len(final_active) != 1 or final_active[0].get("fileName") != dependency.filename:
        raise RuntimeError(
            f"{dependency.dependency_id} refresh changed the active dependency record"
        )
    if panel.get_server(server_id).get("status") != reconciler.STATUS_STOPPED:
        raise RuntimeError(f"server did not remain stopped after {dependency.dependency_id}")


def run(args: argparse.Namespace) -> int:
    token = os.environ.get(args.token_env)
    if not token:
        raise SystemExit(f"set {args.token_env} in the process environment")
    if args.apply and args.confirm_runtime != args.runtime:
        raise SystemExit(f"--apply requires --confirm-runtime {args.runtime}")
    dependencies = load_dependencies(args.dependencies, args.runtime)
    profiles = {
        profile.runtime: profile
        for profile in reconciler.desired_profiles(args.manifest)
    }
    profile = profiles.get(args.runtime)
    if profile is None:
        raise SystemExit(f"unknown runtime selection: {args.runtime}")
    panel = reconciler.DiscPanel(args.url, token, args.request_timeout)
    matches = [server for server in panel.list_servers() if server.get("name") == profile.name]
    if len(matches) != 1:
        raise RuntimeError(f"{args.runtime} maps to {len(matches)} servers; expected one")
    server = panel.get_server(str(matches[0]["id"]))
    differences = reconciler.drift(profile, server)
    if differences:
        raise RuntimeError(f"{args.runtime} server drifted: {'; '.join(differences)}")
    if server.get("status") != reconciler.STATUS_STOPPED or server.get("autoStart"):
        raise RuntimeError(f"{args.runtime} must be stopped with autostart disabled")

    downloaded = {
        dependency.dependency_id: download_dependency(
            dependency, args.download_cache, args.download_timeout
        )
        for dependency in dependencies
    }
    try:
        mods = panel.call(
            "ModService", "ListMods", {"serverId": str(server["id"])}
        ).get("mods", [])
        pending: list[
            tuple[Dependency, list[dict[str, Any]], dict[str, Any] | None]
        ] = []
        refreshes: list[Dependency] = []
        already = 0
        for dependency in dependencies:
            owned = [mod for mod in mods if owns(dependency, mod)]
            active = [mod for mod in owned if deployment.is_enabled(mod)]
            exact = [mod for mod in active if mod.get("fileName") == dependency.filename]
            exact_disabled = [
                mod
                for mod in owned
                if not deployment.is_enabled(mod)
                and mod.get("fileName") == dependency.filename
            ]
            if len(active) > 1 and not dependency.replaces_multiple_active:
                raise RuntimeError(
                    f"{args.runtime} has multiple active {dependency.dependency_id} records"
                )
            if len(exact) > 1 or len(exact_disabled) > 1:
                raise RuntimeError(
                    f"{args.runtime} has duplicate exact {dependency.dependency_id} records"
                )
            if exact:
                if (
                    deployment.remote_mod_digest(
                        panel, str(server["id"]), dependency.filename
                    )
                    != dependency.sha256
                ):
                    refreshes.append(dependency)
                else:
                    already += 1
            else:
                pending.append(
                    (
                        dependency,
                        active,
                        exact_disabled[0] if exact_disabled else None,
                    )
                )
        print(
            f"DiscPanel runtime dependencies: runtime={args.runtime} "
            f"selected={len(dependencies)} already_verified={already} "
            f"deploy_required={len(pending) + len(refreshes)} "
            f"apply={str(args.apply).lower()}"
        )
        if not args.apply:
            for dependency in refreshes:
                print(
                    f"WOULD_REFRESH_DEPENDENCY {dependency.dependency_id} "
                    f"{dependency.filename} record=preserved"
                )
            for dependency, active, exact_disabled in pending:
                rollback = str(active[0].get("fileName")) if active else "none"
                action = "ENABLE" if exact_disabled else "DEPLOY"
                print(
                    f"WOULD_{action}_DEPENDENCY {dependency.dependency_id} "
                    f"{dependency.filename} rollback={rollback}"
                )
            return 0
        for dependency in refreshes:
            refresh_existing_dependency(
                panel,
                server,
                dependency,
                downloaded[dependency.dependency_id],
            )
            print(
                f"REFRESHED_DEPENDENCY_STOPPED {dependency.dependency_id} "
                f"sha256={dependency.sha256}",
                flush=True,
            )
        for dependency, active, exact_disabled in pending:
            if exact_disabled:
                enable_existing_dependency(
                    panel, server, dependency, exact_disabled, active
                )
                action = "ENABLED"
            else:
                stage_dependency(
                    panel,
                    server,
                    dependency,
                    downloaded[dependency.dependency_id],
                    active,
                )
                action = "DEPLOYED"
            print(
                f"{action}_DEPENDENCY_STOPPED {dependency.dependency_id} "
                f"sha256={dependency.sha256}",
                flush=True,
            )
    finally:
        for path in args.download_cache.glob("*.partial"):
            path.unlink(missing_ok=True)
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--manifest", type=Path, default=Path("gradle/targets.json"))
    parser.add_argument(
        "--dependencies",
        type=Path,
        default=Path("gradle/discopanel-runtime-dependencies.json"),
    )
    parser.add_argument(
        "--url", default=os.environ.get("DISCOPANEL_URL", "http://192.168.1.42:8080")
    )
    parser.add_argument("--token-env", default="DISCOPANEL_TOKEN")
    parser.add_argument("--request-timeout", type=int, default=60)
    parser.add_argument("--download-timeout", type=int, default=180)
    parser.add_argument(
        "--download-cache",
        type=Path,
        default=Path("build/discopanel-runtime-dependencies"),
    )
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm-runtime")
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(run(parse_args()))
