#!/usr/bin/env python3
"""Generate build and runtime matrices from Jammarr's target manifest."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


REQUIRED_CAPABILITIES = {
    "control",
    "commandMarkers",
    "audioProfile",
    "logProfile",
    "disableConfigurationCache",
    "clientTask",
    "serverTask",
    "stressProfile",
    "optionalClientProfile",
}


def load_manifest(path: Path) -> dict[str, Any]:
    manifest = json.loads(path.read_text("utf-8"))
    if manifest.get("schemaVersion") != 1:
        raise SystemExit("unsupported target-manifest schema")
    gate = manifest.get("runtimeGate")
    if not isinstance(gate, dict) or not isinstance(gate.get("defaults"), dict):
        raise SystemExit("target manifest is missing runtimeGate.defaults")
    base_port = gate.get("basePort")
    if not isinstance(base_port, int) or not 1024 <= base_port <= 64535:
        raise SystemExit("runtimeGate.basePort must be an unprivileged port with room for RCON")
    return manifest


def verification_task(minecraft: str, loader: dict[str, Any]) -> str:
    if loader.get("verificationTask"):
        return str(loader["verificationTask"])
    loader_name = "NeoForge" if loader["id"] == "neoforge" else str(loader["id"]).capitalize()
    return f"verify{loader_name}{minecraft.replace('.', '')}"


def implemented_artifacts(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    artifacts: list[dict[str, Any]] = []
    targets = manifest.get("targets")
    if not isinstance(targets, list):
        raise SystemExit("target manifest is missing targets")
    identities: set[str] = set()
    filenames: set[str] = set()
    for target in targets:
        for loader in target["loaders"]:
            if not loader.get("implemented", False):
                continue
            identity = f"{target['minecraft']}-{loader['id']}"
            if identity in identities:
                raise SystemExit(f"duplicate implemented target {identity}")
            identities.add(identity)
            if loader["artifact"] in filenames:
                raise SystemExit(f"duplicate implemented artifact {loader['artifact']}")
            filenames.add(loader["artifact"])
            for value in (identity, loader["path"], loader["artifact"]):
                if "|" in value or "\n" in value:
                    raise SystemExit(f"target field contains a gate-line delimiter: {value!r}")
            artifacts.append(
                {
                    "name": identity,
                    "minecraft": target["minecraft"],
                    "loader": loader["id"],
                    "task": verification_task(target["minecraft"], loader),
                    "artifact": f"{loader['path']}/build/libs/{loader['artifact']}",
                    "path": loader["path"],
                    "buildJava": loader.get("buildJava", target["java"]["build"]),
                    "runtimeJava": loader.get("runtimeJava", target["java"].get("runtime", target["java"]["build"])),
                    "clientTask": loader.get("clientTask", "runClient"),
                    "quiltCompatible": bool(loader.get("quiltCompatible", False)),
                    "gameTests": bool(loader.get("gameTests", False)),
                    "runtimeMode": loader.get("runtimeMode", "full"),
                    "pairedServerLoader": loader.get("pairedServerLoader"),
                    "runtimeCapabilities": loader.get("runtimeCapabilities", {}),
                }
            )
    if not artifacts:
        raise SystemExit("target manifest contains no implemented artifacts")
    return artifacts


def runtime_capabilities(
    manifest: dict[str, Any], artifact: dict[str, Any], runtime_loader: str
) -> dict[str, Any]:
    defaults = manifest["runtimeGate"]["defaults"].get(runtime_loader)
    if not isinstance(defaults, dict):
        raise SystemExit(f"runtimeGate.defaults is missing {runtime_loader}")
    capabilities = dict(defaults)
    capabilities.update(artifact["runtimeCapabilities"])
    missing = REQUIRED_CAPABILITIES - capabilities.keys()
    if missing:
        raise SystemExit(
            f"{artifact['name']} is missing runtime capabilities: {sorted(missing)}"
        )
    if capabilities["control"] not in {"rcon", "console"}:
        raise SystemExit(f"{artifact['name']} has an invalid control capability")
    if not isinstance(capabilities["disableConfigurationCache"], bool):
        raise SystemExit(
            f"{artifact['name']} disableConfigurationCache must be boolean"
        )
    for task_key in ("clientTask", "serverTask"):
        if not isinstance(capabilities[task_key], str) or not capabilities[task_key]:
            raise SystemExit(f"{artifact['name']} {task_key} must be a non-empty string")
    if capabilities["stressProfile"] not in {"none", "forge-1.7.10"}:
        raise SystemExit(f"{artifact['name']} has an invalid stressProfile capability")
    if capabilities["optionalClientProfile"] not in {
        "mod-suppressed", "loader-no-jammarr-mod", "loader-only"
    }:
        raise SystemExit(f"{artifact['name']} has an invalid optionalClientProfile capability")
    return capabilities


def runtimes(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    port = manifest["runtimeGate"]["basePort"]
    for artifact in implemented_artifacts(manifest):
        if artifact["runtimeMode"] == "client-companion":
            if not artifact["pairedServerLoader"]:
                raise SystemExit(
                    f"{artifact['name']} client companion is missing pairedServerLoader"
                )
            continue
        if artifact["runtimeMode"] != "full":
            raise SystemExit(f"{artifact['name']} has an invalid runtimeMode")
        runtime_loaders = [artifact["loader"]]
        if artifact["quiltCompatible"]:
            runtime_loaders.append("quilt")
        for runtime_loader in runtime_loaders:
            capabilities = runtime_capabilities(manifest, artifact, runtime_loader)
            label = f"{artifact['minecraft']}-{runtime_loader}"
            result.append(
                {
                    "name": label,
                    "runtime": label,
                    "minecraft": artifact["minecraft"],
                    "loader": runtime_loader,
                    "path": artifact["path"],
                    "buildJava": artifact["buildJava"],
                    "runtimeJava": artifact["runtimeJava"],
                    "port": port,
                    "quiltModMenu": runtime_loader == "quilt",
                    "minimumFabricLoader": runtime_loader == "fabric",
                    **capabilities,
                }
            )
            port += 1
    labels = [entry["name"] for entry in result]
    if len(labels) != len(set(labels)):
        raise SystemExit("target manifest generates duplicate runtime labels")
    if port + 1000 > 65535:
        raise SystemExit("generated runtime/RCON port range exceeds 65535")
    return result


def client_companions(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    artifacts = implemented_artifacts(manifest)
    full_targets = {
        (artifact["minecraft"], artifact["loader"]): artifact
        for artifact in artifacts
        if artifact["runtimeMode"] == "full"
    }
    companions: list[dict[str, Any]] = []
    pairs: set[tuple[str, str]] = set()
    for artifact in artifacts:
        if artifact["runtimeMode"] != "client-companion":
            continue
        paired_loader = artifact["pairedServerLoader"]
        pair = (artifact["minecraft"], paired_loader)
        if pair not in full_targets:
            raise SystemExit(
                f"{artifact['name']} pairs with missing full server target "
                f"{artifact['minecraft']}-{paired_loader}"
            )
        if pair in pairs:
            raise SystemExit(
                f"multiple client companions pair with {artifact['minecraft']}-{paired_loader}"
            )
        pairs.add(pair)
        companions.append(
            {
                "name": artifact["name"],
                "pairedRuntime": f"{artifact['minecraft']}-{paired_loader}",
                "path": artifact["path"],
                "buildJava": artifact["buildJava"],
                "runtimeJava": artifact["runtimeJava"],
                "clientTask": artifact["clientTask"],
            }
        )
    return companions


def compact_matrix(entries: list[dict[str, Any]]) -> str:
    return json.dumps({"include": entries}, separators=(",", ":"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "mode",
        choices=(
            "artifact-matrix",
            "runtime-matrix",
            "gate-lines",
            "unmodded-client-lines",
            "companion-lines",
            "summary",
        ),
    )
    parser.add_argument(
        "manifest", nargs="?", type=Path, default=Path("gradle/targets.json")
    )
    args = parser.parse_args()
    manifest = load_manifest(args.manifest)
    artifacts = implemented_artifacts(manifest)
    runtime_entries = runtimes(manifest)
    companion_entries = client_companions(manifest)

    if args.mode == "artifact-matrix":
        print(
            compact_matrix(
                [
                    {
                        "name": entry["name"],
                        "task": entry["task"],
                        "artifact": entry["artifact"],
                        "game_tests": entry["gameTests"],
                    }
                    for entry in artifacts
                ]
            )
        )
    elif args.mode == "runtime-matrix":
        print(
            compact_matrix(
                [
                    {
                        "name": entry["name"],
                        "runtime": entry["runtime"],
                        "quilt_mod_menu": entry["quiltModMenu"],
                        "minimum_fabric_loader": entry["minimumFabricLoader"],
                    }
                    for entry in runtime_entries
                ]
            )
        )
    elif args.mode == "gate-lines":
        for entry in runtime_entries:
            values = (
                entry["name"],
                entry["path"],
                entry["buildJava"],
                entry["port"],
                entry["control"],
                entry["commandMarkers"],
                entry["audioProfile"],
                entry["logProfile"],
                str(entry["disableConfigurationCache"]).lower(),
                entry["clientTask"],
                entry["serverTask"],
                entry["stressProfile"],
                entry["optionalClientProfile"],
            )
            print("|".join(map(str, values)))
    elif args.mode == "unmodded-client-lines":
        for entry in runtime_entries:
            values = (
                entry["name"],
                entry["minecraft"],
                entry["path"],
                entry["buildJava"],
                entry["runtimeJava"],
                entry["port"],
                entry["clientTask"],
                str(entry["disableConfigurationCache"]).lower(),
                entry["loader"],
            )
            print("|".join(map(str, values)))
    elif args.mode == "companion-lines":
        for entry in companion_entries:
            values = (
                entry["name"],
                entry["pairedRuntime"],
                entry["path"],
                entry["buildJava"],
                entry["runtimeJava"],
                entry["clientTask"],
            )
            print("|".join(map(str, values)))
    else:
        print(
            json.dumps(
                {
                    "minecraftVersions": len(manifest["targets"]),
                    "artifacts": len(artifacts),
                    "runtimes": len(runtime_entries),
                    "quiltRuntimes": sum(
                        entry["runtime"].endswith("-quilt") for entry in runtime_entries
                    ),
                },
                sort_keys=True,
            )
        )


if __name__ == "__main__":
    main()
