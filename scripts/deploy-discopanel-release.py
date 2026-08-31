#!/usr/bin/env python3
"""Stage an exact Jammarr release bundle on stopped DiscPanel test servers.

Dry-run is the default. Applying is deliberately separate from server startup:
it uploads one manifest-mapped artifact at a time, verifies the remote bytes,
then disables the previous active Jammarr record as a retained rollback copy.
It never starts, restarts, or enables autostart on a server.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.util
import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
RECONCILER_SCRIPT = SCRIPT_DIR / "reconcile-discopanel-test-servers.py"
RECONCILER_SPEC = importlib.util.spec_from_file_location(
    "jammarr_discopanel_reconciler", RECONCILER_SCRIPT
)
assert RECONCILER_SPEC and RECONCILER_SPEC.loader
reconciler = importlib.util.module_from_spec(RECONCILER_SPEC)
sys.modules[RECONCILER_SPEC.name] = reconciler
RECONCILER_SPEC.loader.exec_module(reconciler)
target_matrix = reconciler.target_matrix


@dataclass(frozen=True)
class DeploymentTarget:
    runtime: str
    server_name: str
    filename: str
    sha256: str
    source: Path


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def project_version(properties: Path) -> str:
    for line in properties.read_text("utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == "mod_version":
            version = value.strip()
            if version:
                return version
    raise SystemExit(f"{properties} does not define mod_version")


def checksum_entries(path: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    for line in path.read_text("utf-8").splitlines():
        digest, separator, filename = line.partition("  ")
        if not separator or len(digest) != 64 or not filename:
            raise SystemExit(f"invalid checksum line in {path}: {line!r}")
        if filename in entries:
            raise SystemExit(f"duplicate checksum entry for {filename}")
        entries[filename] = digest
    return entries


def verified_release_artifacts(
    release_dir: Path, manifest_path: Path, expected_version: str
) -> dict[str, dict[str, Any]]:
    release_manifest_path = release_dir / "manifest.json"
    checksum_path = release_dir / "SHA256SUMS"
    release = json.loads(release_manifest_path.read_text("utf-8"))
    if release.get("schemaVersion") != 2:
        raise SystemExit("release manifest must use schemaVersion 2")
    if release.get("product") != "Jammarr" or release.get("productVersion") != expected_version:
        raise SystemExit(
            f"release bundle is {release.get('product')!r} {release.get('productVersion')!r}; "
            f"expected Jammarr {expected_version}"
        )
    desired = target_matrix.implemented_artifacts(target_matrix.load_manifest(manifest_path))
    artifacts = release.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != len(desired):
        raise SystemExit(
            f"release manifest has {len(artifacts) if isinstance(artifacts, list) else 'invalid'} "
            f"artifacts; expected {len(desired)}"
        )
    by_filename: dict[str, dict[str, Any]] = {}
    for artifact in artifacts:
        filename = str(artifact.get("filename", ""))
        digest = str(artifact.get("sha256", ""))
        if not filename or filename in by_filename or len(digest) != 64:
            raise SystemExit(f"invalid or duplicate release artifact {filename!r}")
        by_filename[filename] = artifact
    expected_filenames = {Path(item["artifact"]).name for item in desired}
    if set(by_filename) != expected_filenames:
        missing = sorted(expected_filenames - set(by_filename))
        unexpected = sorted(set(by_filename) - expected_filenames)
        raise SystemExit(
            f"release filenames differ from target manifest; missing={missing} unexpected={unexpected}"
        )
    sums = checksum_entries(checksum_path)
    if set(sums) != set(by_filename):
        raise SystemExit("SHA256SUMS does not cover exactly the release manifest")
    for filename, artifact in by_filename.items():
        source = release_dir / filename
        if not source.is_file():
            raise SystemExit(f"release artifact is missing: {source}")
        actual = file_sha256(source)
        expected = str(artifact["sha256"])
        if actual != expected or sums[filename] != expected:
            raise SystemExit(f"release artifact digest mismatch: {filename}")
    return by_filename


def deployment_targets(
    manifest_path: Path,
    release_dir: Path,
    release_artifacts: dict[str, dict[str, Any]],
) -> list[DeploymentTarget]:
    manifest = target_matrix.load_manifest(manifest_path)
    artifacts = target_matrix.implemented_artifacts(manifest)
    profiles = {profile.runtime: profile for profile in reconciler.desired_profiles(manifest_path)}
    targets: list[DeploymentTarget] = []
    for runtime in target_matrix.runtimes(manifest):
        minecraft, runtime_loader = runtime["name"].rsplit("-", 1)
        candidates = [
            artifact
            for artifact in artifacts
            if artifact["runtimeMode"] == "full"
            and artifact["minecraft"] == minecraft
            and (
                artifact["loader"] == runtime_loader
                or (runtime_loader == "quilt" and artifact["quiltCompatible"])
            )
        ]
        if len(candidates) != 1:
            raise SystemExit(
                f"{runtime['name']} maps to {len(candidates)} release artifacts; expected exactly one"
            )
        filename = Path(candidates[0]["artifact"]).name
        release = release_artifacts.get(filename)
        if release is None:
            raise SystemExit(f"{runtime['name']} release artifact is missing: {filename}")
        targets.append(
            DeploymentTarget(
                runtime=runtime["name"],
                server_name=profiles[runtime["name"]].name,
                filename=filename,
                sha256=str(release["sha256"]),
                source=release_dir / filename,
            )
        )
    if len(targets) != len(profiles) or len({target.runtime for target in targets}) != len(targets):
        raise SystemExit("deployment mapping does not cover every DiscPanel runtime exactly once")
    return targets


def is_jammarr_mod(mod: dict[str, Any]) -> bool:
    return str(mod.get("fileName", "")).lower().startswith("jammarr-")


def is_enabled(mod: dict[str, Any]) -> bool:
    return mod.get("enabled") is True


def upload_session(panel: Any, source: Path) -> str:
    chunk_size = 1024 * 1024
    initialized = panel.call(
        "UploadService",
        "InitUpload",
        {"filename": source.name, "totalSize": source.stat().st_size, "chunkSize": chunk_size},
    )
    session_id = str(initialized["sessionId"])
    with source.open("rb") as stream:
        chunk_index = 0
        while chunk := stream.read(chunk_size):
            panel.call(
                "UploadService",
                "UploadChunk",
                {
                    "sessionId": session_id,
                    "chunkIndex": chunk_index,
                    "data": base64.b64encode(chunk).decode("ascii"),
                },
            )
            chunk_index += 1
    status = panel.call("UploadService", "GetUploadStatus", {"sessionId": session_id})
    if not status.get("completed"):
        raise RuntimeError(f"DiscPanel upload did not complete for {source.name}")
    return session_id


def update_mod_enabled(panel: Any, server_id: str, mod: dict[str, Any], enabled: bool) -> None:
    panel.call(
        "ModService",
        "UpdateMod",
        {
            "serverId": server_id,
            "modId": str(mod["id"]),
            "enabled": enabled,
            "displayName": str(mod.get("displayName", mod.get("fileName", "Jammarr"))),
            "description": str(mod.get("description", "")),
        },
    )


def remote_mod_digest(panel: Any, server_id: str, filename: str) -> str:
    return hashlib.sha256(panel.get_file(server_id, f"mods/{filename}")).hexdigest()


def deploy_target(
    panel: Any,
    target: DeploymentTarget,
    server: dict[str, Any],
    previous: list[dict[str, Any]],
    expected_version: str,
) -> None:
    server_id = str(server["id"])
    session_id = upload_session(panel, target.source)
    panel.call(
        "ModService",
        "ImportUploadedMod",
        {
            "serverId": server_id,
            "uploadSessionId": session_id,
            "displayName": target.filename,
            "description": f"Jammarr {expected_version} release candidate; sha256 {target.sha256}",
        },
    )
    if remote_mod_digest(panel, server_id, target.filename) != target.sha256:
        raise RuntimeError(f"{target.runtime} remote release digest does not match")
    for mod in previous:
        update_mod_enabled(panel, server_id, mod, False)
    final_mods = panel.call(
        "ModService", "ListMods", {"serverId": server_id}
    ).get("mods", [])
    final_active = [mod for mod in final_mods if is_jammarr_mod(mod) and is_enabled(mod)]
    if len(final_active) != 1 or final_active[0].get("fileName") != target.filename:
        raise RuntimeError(f"{target.runtime} did not finish with one exact active release")
    if panel.get_server(server_id).get("status") != reconciler.STATUS_STOPPED:
        raise RuntimeError(f"{target.runtime} did not remain stopped after deployment")


def reconcile(args: argparse.Namespace) -> int:
    token = os.environ.get(args.token_env)
    if not token:
        raise SystemExit(f"set {args.token_env} in the process environment")
    expected_version = args.expected_version or project_version(args.project_properties)
    if args.apply and args.confirm_version != expected_version:
        raise SystemExit(f"--apply requires --confirm-version {expected_version}")
    release_artifacts = verified_release_artifacts(
        args.release_dir, args.manifest, expected_version
    )
    targets = deployment_targets(args.manifest, args.release_dir, release_artifacts)
    by_runtime = {target.runtime: target for target in targets}
    if args.runtime:
        unknown = sorted(set(args.runtime) - set(by_runtime))
        if unknown:
            raise SystemExit(f"unknown runtime selection: {', '.join(unknown)}")
        targets = [by_runtime[runtime] for runtime in args.runtime]

    panel = reconciler.DiscPanel(args.url, token, args.request_timeout)
    profiles = {profile.runtime: profile for profile in reconciler.desired_profiles(args.manifest)}
    server_summaries = panel.list_servers()
    by_name: dict[str, dict[str, Any]] = {}
    for server in server_summaries:
        name = str(server.get("name", ""))
        if name in by_name:
            raise RuntimeError(f"DiscPanel contains duplicate server name {name!r}")
        by_name[name] = server

    plan: list[tuple[DeploymentTarget, dict[str, Any], list[dict[str, Any]]]] = []
    already = 0
    for target in targets:
        summary = by_name.get(target.server_name)
        if summary is None:
            raise RuntimeError(f"DiscPanel server is missing for {target.runtime}")
        server = summary
        if profiles[target.runtime].provisioning != "native":
            server = panel.get_server(str(summary["id"]))
        differences = reconciler.drift(profiles[target.runtime], server)
        if differences:
            raise RuntimeError(f"{target.runtime} server drifted: {'; '.join(differences)}")
        server_id = str(summary["id"])
        mods = panel.call("ModService", "ListMods", {"serverId": server_id}).get("mods", [])
        jammarr = [mod for mod in mods if is_jammarr_mod(mod)]
        enabled = [mod for mod in jammarr if is_enabled(mod)]
        if len(enabled) > 1:
            raise RuntimeError(f"{target.runtime} has multiple active Jammarr records")
        expected_active = [mod for mod in enabled if mod.get("fileName") == target.filename]
        expected_disabled = [
            mod for mod in jammarr if not is_enabled(mod) and mod.get("fileName") == target.filename
        ]
        if expected_active:
            actual = remote_mod_digest(panel, server_id, target.filename)
            if actual != target.sha256:
                raise RuntimeError(f"{target.runtime} active release digest does not match")
            already += 1
            continue
        if expected_disabled:
            raise RuntimeError(
                f"{target.runtime} has a disabled {target.filename}; resolve the partial prior deployment"
            )
        plan.append((target, summary, enabled))

    print(
        f"DiscPanel release {expected_version}: selected={len(targets)} "
        f"already_verified={already} deploy_required={len(plan)} apply={str(args.apply).lower()}"
    )
    if not args.apply:
        for target, _, enabled in plan:
            previous = str(enabled[0].get("fileName")) if enabled else "none"
            print(f"WOULD_DEPLOY {target.runtime} {target.filename} rollback={previous}")
        return 0

    for target, server, previous in plan:
        print(f"UPLOAD {target.runtime} {target.filename}", flush=True)
        deploy_target(panel, target, server, previous, expected_version)
        print(f"DEPLOYED_STOPPED {target.runtime} sha256={target.sha256}", flush=True)
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=Path("gradle/targets.json"))
    parser.add_argument("--release-dir", type=Path, default=Path("build/releases"))
    parser.add_argument("--project-properties", type=Path, default=Path("gradle.properties"))
    parser.add_argument("--expected-version")
    parser.add_argument("--runtime", action="append", help="limit deployment to one exact runtime")
    parser.add_argument(
        "--url", default=os.environ.get("DISCOPANEL_URL", "http://192.168.1.73:8080")
    )
    parser.add_argument("--token-env", default="DISCOPANEL_TOKEN")
    parser.add_argument("--request-timeout", type=int, default=60)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm-version")
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(reconcile(parse_args()))
