#!/usr/bin/env python3
"""Generate the checksum-pinned DiscPanel runtime dependency manifest.

The versions come from the final release manifest or the exact Ornithe module
set resolved by the isolated target builds. Maven artifacts must already exist
in Gradle's module cache; known metadata-only Fabric API coordinates are
replaced with checksum-pinned public mod distributions. The generated manifest
does not copy dependencies into the Jammarr release bundle.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


REPOSITORIES = {
    "fabric": "https://maven.fabricmc.net",
    "glass": "https://maven.glass-launcher.net/releases",
    "legacy-fabric": "https://maven.legacyfabric.net",
    "ornithe": "https://maven.ornithemc.net/releases",
}

FABRIC_API_DISTRIBUTIONS = {
    # These Maven coordinates contain only fabric.mod.json and a dependency
    # POM. Production servers need the official nested-module distribution.
    "0.42.0+1.16": {
        "url": (
            "https://cdn.modrinth.com/data/P7dR8mSH/versions/"
            "0.42.0%2B1.16/fabric-api-0.42.0%2B1.16.jar"
        ),
        "sha256": "3df8dd503f35aa0ac9fab8ad9f9a369fdfd0b1ab544af19a3d626d948fb4586c",
    },
    "0.77.0+1.18.2": {
        "url": (
            "https://cdn.modrinth.com/data/P7dR8mSH/versions/"
            "qk28POfr/fabric-api-0.77.0%2B1.18.2.jar"
        ),
        "sha256": "6f822fb5aa481b4a6c1cfb8612bbfecc62a58e69d2c792f61a0eafa580e75999",
    },
}

LEGACY_FABRIC_BUNDLE = {
    "filename": "legacy-fabric-api-1.13.2.jar",
    "url": (
        "https://mediafilez.forgecdn.net/files/7133/579/"
        "legacy-fabric-api-1.13.2.jar"
    ),
    "sha256": "397d009ebfee69fbd7d44e7387fe10c758e0d49b6ca8bb316d34ce1a8d37c03a",
}

# The full 1.8.9 bundle includes registry-sync Mixins which target registry
# implementation names that the production dedicated-server remap does not
# inherit from the mapped interface. Jammarr does not use registry sync. Keep
# the descriptor plus the exact transitive closure of only the APIs it imports.
LEGACY_FABRIC_CURATED_MODULES = {
    "1.8.9": (
        "legacy-fabric-api-base",
        "legacy-fabric-command-api-v1",
        "legacy-fabric-keybindings-api-v1",
        "legacy-fabric-lifecycle-events-v1",
        "legacy-fabric-networking-api-v1",
        "legacy-fabric-resource-loader-v1",
    ),
}

ORNITHE_MODULES = {
    "1.6.4-ornithe": {
        "core": "0.10.0-alpha.5+mca1.0.1_01-mc14w26c",
        "entrypoints": "0.6.1+mc13w16a-mc1.14.4",
        "executors": "0.1.0+mc12w18a-mc1.7.5",
        "keybinds": "0.3.0+mcb1.8-pre1-mc1.6.4",
        "lifecycle-events": "0.6.1+mc12w21a-mc1.6.4",
        "networking": "0.10.0-alpha.3+mcb1.0-mc13w39b",
        "networking-impl": "0.2.0-alpha.5+mc12w21a-mc13w39b",
        "resource-loader": "0.8.0-alpha.5+mc13w26a-mc1.8.2-pre4",
        "text-components": "0.1.0-alpha.5+mca1.0.1_01-mc1.6.4",
    },
    "1.8.9-ornithe": {
        "blocks": "0.2.0-alpha.13+mc14w27a-mc15w35a",
        "branding": "0.4.3+mc14w30a-mc16w05a",
        "config": "0.6.2+mc14w27a-mc15w39c",
        "core": "0.10.0-alpha.5+mc14w27a-mc1.14.4",
        "entrypoints": "0.6.1+mc13w16a-mc1.14.4",
        "executors": "0.1.0+mc14w21a-mc1.13.2",
        "items": "0.2.0-alpha.20+mc14w26a-mc17w46a",
        "keybinds": "0.3.0+mc13w36a-mc17w15a",
        "lifecycle-events": "0.6.1+mc13w36a-mc19w07a",
        "localization": "0.1.1-alpha.3+mc13w26a-mc18w01a",
        "networking": "0.10.0-alpha.3+mc13w41a-mc18w30b",
        "networking-impl": "0.2.0-alpha.5+mc14w31a-mc1.13-pre2",
        "registries": "0.1.0-alpha.14+mc14w27a-mc17w46a",
        "resource-loader": "0.8.0-alpha.5+mc1.8.2-pre5-mc1.12.2",
        "text-components": "0.1.0-alpha.5+mc13w36a-mc1.14.4",
    },
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def cached_jar(
    cache: Path, group: str, artifact: str, version: str, filename: str
) -> Path:
    directory = cache / group / artifact / version
    matches = [
        path
        for path in directory.glob(f"*/{filename}")
        if path.is_file() and not path.name.endswith("-sources.jar")
    ]
    if not matches:
        raise SystemExit(f"Gradle cache is missing {group}:{artifact}:{version}")
    digests = {sha256(path) for path in matches}
    if len(digests) != 1:
        raise SystemExit(f"Gradle cache contains differing bytes for {filename}")
    return matches[0]


def cached_pom(cache: Path, group: str, artifact: str, version: str) -> Path:
    directory = cache / group / artifact / version
    filename = f"{artifact}-{version}.pom"
    matches = [path for path in directory.glob(f"*/{filename}") if path.is_file()]
    if not matches:
        raise SystemExit(f"Gradle cache is missing POM for {group}:{artifact}:{version}")
    contents = {path.read_bytes() for path in matches}
    if len(contents) != 1:
        raise SystemExit(f"Gradle cache contains differing POMs for {filename}")
    return matches[0]


def dependency(
    cache: Path,
    *,
    dependency_id: str,
    repository: str,
    group: str,
    artifact: str,
    version: str,
    owned_prefix: str,
) -> dict[str, Any]:
    filename = f"{artifact}-{version}.jar"
    source = cached_jar(cache, group, artifact, version, filename)
    group_path = group.replace(".", "/")
    return {
        "id": dependency_id,
        "filename": filename,
        "url": (
            f"{REPOSITORIES[repository]}/{group_path}/{artifact}/{version}/{filename}"
        ),
        "sha256": sha256(source),
        "ownedPrefixes": [owned_prefix.lower()],
    }


def fabric_api_dependency(cache: Path, version: str) -> dict[str, Any]:
    distribution = FABRIC_API_DISTRIBUTIONS.get(version)
    if distribution:
        return {
            "id": "fabric-api",
            "filename": f"fabric-api-{version}.jar",
            "url": distribution["url"],
            "sha256": distribution["sha256"],
            "ownedPrefixes": ["fabric-api-"],
        }
    return dependency(
        cache,
        dependency_id="fabric-api",
        repository="fabric",
        group="net.fabricmc.fabric-api",
        artifact="fabric-api",
        version=version,
        owned_prefix="fabric-api-",
    )


def legacy_fabric_module_coordinates(
    cache: Path, minecraft: str, version: str
) -> list[tuple[str, str]]:
    group = "net.legacyfabric.legacy-fabric-api"
    aggregate = "legacy-fabric-api"
    pom = cached_pom(cache, group, aggregate, version)
    root = ET.parse(pom).getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    coordinates: list[tuple[str, str]] = []
    for element in root.findall("m:dependencies/m:dependency", namespace):
        dependency_group = element.findtext("m:groupId", namespaces=namespace) or ""
        artifact = element.findtext("m:artifactId", namespaces=namespace) or ""
        dependency_version = element.findtext("m:version", namespaces=namespace) or ""
        scope = element.findtext("m:scope", namespaces=namespace) or "compile"
        if (
            dependency_group != group
            or not artifact.startswith("legacy-fabric-")
            or not dependency_version
            or scope != "compile"
        ):
            raise SystemExit(
                f"unexpected Legacy Fabric API dependency for {minecraft}: "
                f"{dependency_group}:{artifact}:{dependency_version}:{scope}"
            )
        coordinates.append((artifact, dependency_version))
    if not coordinates or len(set(coordinates)) != len(coordinates):
        raise SystemExit(
            f"Legacy Fabric API {version} has missing or duplicate module coordinates"
        )
    return coordinates


def legacy_fabric_bundle(
    cache: Path, minecraft: str, version: str
) -> dict[str, Any]:
    coordinates = legacy_fabric_module_coordinates(cache, minecraft, version)
    owned_filenames = [
        LEGACY_FABRIC_BUNDLE["filename"],
        f"legacy-fabric-api-{version}.jar",
        *(f"{artifact}-{dependency_version}.jar" for artifact, dependency_version in coordinates),
    ]
    return {
        "id": "legacy-fabric-api",
        "filename": LEGACY_FABRIC_BUNDLE["filename"],
        "url": LEGACY_FABRIC_BUNDLE["url"],
        "sha256": LEGACY_FABRIC_BUNDLE["sha256"],
        "ownedPrefixes": [filename.lower() for filename in owned_filenames],
        "replacesMultipleActive": True,
    }


def legacy_fabric_curated_modules(
    cache: Path, minecraft: str, version: str
) -> list[dict[str, Any]]:
    group = "net.legacyfabric.legacy-fabric-api"
    aggregate = "legacy-fabric-api"
    available = dict(legacy_fabric_module_coordinates(cache, minecraft, version))
    roots = LEGACY_FABRIC_CURATED_MODULES.get(minecraft)
    if not roots:
        raise SystemExit(f"no curated Legacy Fabric API roots for {minecraft}")

    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    selected: dict[str, str] = {}

    def include(artifact: str, module_version: str) -> None:
        existing = selected.get(artifact)
        if existing:
            if existing != module_version:
                raise SystemExit(
                    f"Legacy Fabric API selects conflicting {artifact} versions: "
                    f"{existing} and {module_version}"
                )
            return
        if available.get(artifact) != module_version:
            raise SystemExit(
                f"Legacy Fabric API {version} does not pin "
                f"{artifact}:{module_version}"
            )
        selected[artifact] = module_version
        root = ET.parse(cached_pom(cache, group, artifact, module_version)).getroot()
        for element in root.findall("m:dependencies/m:dependency", namespace):
            dependency_group = (
                element.findtext("m:groupId", namespaces=namespace) or ""
            )
            dependency_artifact = (
                element.findtext("m:artifactId", namespaces=namespace) or ""
            )
            dependency_version = (
                element.findtext("m:version", namespaces=namespace) or ""
            )
            scope = element.findtext("m:scope", namespaces=namespace) or "compile"
            if (
                dependency_group != group
                or not dependency_artifact.startswith("legacy-fabric-")
                or not dependency_version
                or scope != "compile"
            ):
                raise SystemExit(
                    f"unexpected curated Legacy Fabric API dependency for "
                    f"{artifact}: {dependency_group}:{dependency_artifact}:"
                    f"{dependency_version}:{scope}"
                )
            include(dependency_artifact, dependency_version)

    for artifact in roots:
        module_version = available.get(artifact)
        if not module_version:
            raise SystemExit(
                f"Legacy Fabric API {version} is missing curated root {artifact}"
            )
        include(artifact, module_version)

    descriptor = dependency(
        cache,
        dependency_id="legacy-fabric-api",
        repository="legacy-fabric",
        group=group,
        artifact=aggregate,
        version=version,
        owned_prefix=f"legacy-fabric-api-{version}.jar",
    )
    descriptor["ownedPrefixes"] = [
        LEGACY_FABRIC_BUNDLE["filename"].lower(),
        descriptor["filename"].lower(),
    ]
    descriptor["replacesMultipleActive"] = True

    modules = []
    for artifact, module_version in sorted(selected.items()):
        module = dependency(
            cache,
            dependency_id=artifact,
            repository="legacy-fabric",
            group=group,
            artifact=artifact,
            version=module_version,
            owned_prefix=f"{artifact}-{module_version}.jar",
        )
        modules.append(module)
    return [descriptor, *modules]


def generate(release_manifest: Path, cache: Path) -> dict[str, Any]:
    release = json.loads(release_manifest.read_text("utf-8"))
    if release.get("schemaVersion") != 2 or release.get("product") != "Jammarr":
        raise SystemExit("release manifest is not a schema-2 Jammarr release")
    runtimes: dict[str, list[dict[str, Any]]] = {}
    for artifact in release.get("artifacts", []):
        if artifact.get("loader") != "fabric" or artifact.get("runtimeMode") != "full":
            continue
        minecraft = str(artifact.get("minecraftVersion"))
        version = str((artifact.get("dependencies") or {}).get("fabric-api", ""))
        if not version:
            continue
        external = fabric_api_dependency(cache, version)
        runtimes[f"{minecraft}-fabric"] = [external]
        if "quilt" in (artifact.get("compatibleLoaders") or []):
            runtimes[f"{minecraft}-quilt"] = [external]

    stationapi = dependency(
        cache,
        dependency_id="stationapi",
        repository="glass",
        group="net.modificationstation",
        artifact="StationAPI",
        version="2.0.0-alpha.6.2",
        owned_prefix="stationapi-",
    )
    runtimes["b1.7.3-babric"] = [stationapi]

    for minecraft in ("1.6.4", "1.8.9"):
        version = f"1.13.2+{minecraft}"
        if minecraft in LEGACY_FABRIC_CURATED_MODULES:
            runtimes[f"{minecraft}-fabric"] = legacy_fabric_curated_modules(
                cache, minecraft, version
            )
        else:
            runtimes[f"{minecraft}-fabric"] = [
                legacy_fabric_bundle(cache, minecraft, version)
            ]

    for runtime, modules in ORNITHE_MODULES.items():
        runtimes[runtime] = [
            dependency(
                cache,
                dependency_id=f"osl-{module}",
                repository="ornithe",
                group="net.ornithemc.osl-gen2",
                artifact=module,
                version=version,
                owned_prefix=f"{module}-{version}",
            )
            for module, version in sorted(modules.items())
        ]
    return {"schemaVersion": 1, "runtimes": dict(sorted(runtimes.items()))}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--release-manifest", type=Path, default=Path("build/releases/manifest.json")
    )
    parser.add_argument(
        "--cache",
        type=Path,
        default=Path.home() / ".gradle/caches/modules-2/files-2.1",
    )
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    rendered = json.dumps(generate(args.release_manifest, args.cache), indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, "utf-8")
    else:
        print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
