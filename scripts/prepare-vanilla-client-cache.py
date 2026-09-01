#!/usr/bin/env python3
"""Populate a shared, isolated, verified vanilla-client acceptance cache."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re
import sys
from typing import Any

from prism_verified_cache import VerifiedCache, library_allowed, sha1_file


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


def modern_versions(manifest: dict[str, Any]) -> list[str]:
    versions: list[str] = []
    for target in manifest["targets"]:
        version = target["minecraft"]
        if (release_tuple(version) or (0, 0, 0)) < (1, 12, 2):
            continue
        if any(loader.get("implemented") and loader.get("runtimeMode", "full") == "full"
               for loader in target["loaders"]):
            versions.append(version)
    return versions


def select_versions(available: list[str], requested: list[str]) -> list[str]:
    if not requested:
        return available
    if len(requested) != len(set(requested)):
        raise SystemExit("version selections must be unique")
    unknown = [version for version in requested if version not in available]
    if unknown:
        raise SystemExit("unsupported vanilla cache versions: " + ", ".join(unknown))
    return [version for version in available if version in requested]


def prepare_version(cache: VerifiedCache, version: str) -> dict[str, Any]:
    metadata = cache.component_metadata("net.minecraft", version)
    components = [metadata]
    component_uids = ["net.minecraft"]
    for requirement in metadata.get("requires", []):
        uid = requirement.get("uid")
        component_version = requirement.get("equals") or requirement.get("suggests")
        if uid not in {"org.lwjgl", "org.lwjgl3"} or not component_version:
            raise SystemExit(f"Unsupported Minecraft component requirement: {requirement!r}")
        components.append(cache.component_metadata(uid, component_version))
        component_uids.insert(0, uid)

    libraries: list[Path] = []
    for component in components:
        for library in component.get("libraries", []):
            if not library_allowed(library):
                continue
            path = cache.library(library)
            if "jammarr" in path.name.lower():
                raise SystemExit(f"Jammarr appeared in vanilla classpath: {path}")
            libraries.append(path)
    main_jar = metadata.get("mainJar")
    if not isinstance(main_jar, dict):
        raise SystemExit(f"Minecraft {version} metadata does not declare a main JAR")
    client = cache.library(main_jar)
    assets, asset_index, asset_count = cache.assets(metadata)
    return {
        "minecraftVersion": version,
        "componentUids": component_uids,
        "classpathEntryCount": len(libraries) + 1,
        "clientJarSha1": sha1_file(client),
        "assetIndex": asset_index,
        "assetObjectCount": asset_count,
        "assetsRoot": str(assets),
        "allArtifactSha1AndSizeVerified": True,
        "sharedCacheMutated": False,
    }


def write_summary(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def run(args: argparse.Namespace) -> int:
    manifest = target_matrix.load_manifest(args.manifest)
    versions = select_versions(modern_versions(manifest), args.version)
    summary: dict[str, Any] = {
        "schemaVersion": 1,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "versions": versions,
        "prepared": [],
        "failures": [],
        "sharedCacheMutated": False,
    }
    cache = VerifiedCache(
        args.shared_root,
        args.cache_root,
        metadata_root_url=args.metadata_root_url,
        asset_objects_base_url=args.asset_objects_base_url,
    )
    interrupted: BaseException | None = None
    try:
        for index, version in enumerate(versions, start=1):
            print(f"VANILLA_CACHE_VERSION {index}/{len(versions)} {version}", flush=True)
            try:
                summary["prepared"].append(prepare_version(cache, version))
            except Exception as error:
                summary["failures"].append(
                    {"version": version, "errorType": type(error).__name__, "error": str(error)}
                )
                if not args.continue_on_error:
                    break
    except BaseException as error:
        interrupted = error
    finally:
        summary["finishedAt"] = datetime.now(timezone.utc).isoformat()
        summary["artifactSourceCounts"] = cache.attestation()["artifactSourceCounts"]
        summary["complete"] = (
            not summary["failures"] and len(summary["prepared"]) == len(versions)
        )
        write_summary(args.summary, summary)
        print(f"VANILLA_CACHE_EVIDENCE {args.summary}", flush=True)
    if interrupted is not None:
        raise interrupted
    return 0 if summary["complete"] else 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", action="append", default=[])
    parser.add_argument("--manifest", type=Path, default=Path("gradle/targets.json"))
    parser.add_argument(
        "--shared-root",
        type=Path,
        default=Path.home() / ".local/share/PrismLauncher",
    )
    parser.add_argument(
        "--cache-root", type=Path, default=Path("build/vanilla-client-cache")
    )
    parser.add_argument(
        "--metadata-root-url", default="https://meta.prismlauncher.org/v1"
    )
    parser.add_argument(
        "--asset-objects-base-url", default="https://resources.download.minecraft.net"
    )
    parser.add_argument(
        "--summary",
        type=Path,
        default=Path("build/vanilla-client-cache/preparation-summary.json"),
    )
    parser.add_argument("--continue-on-error", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    sys.exit(run(parse_args()))
