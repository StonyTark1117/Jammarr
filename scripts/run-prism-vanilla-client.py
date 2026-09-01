#!/usr/bin/env python3
"""Launch an exact, artifact-free Minecraft client from verified Prism caches.

The dedicated-server gate uses this helper to prove that a client whose
classpath contains only Mojang Minecraft and its declared libraries can join a
Jammarr server. Prism's metadata, assets, libraries, and JRE caches are reused,
but its launcher UI and account store are never opened or inspected.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import shlex
import subprocess
import sys
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import urlopen
import zipfile


SAFE_VALUE = re.compile(r"^[A-Za-z0-9._-]+$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--server", required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--shared-root", type=Path, required=True)
    parser.add_argument(
        "--metadata-base-url",
        default="https://meta.prismlauncher.org/v1/net.minecraft",
    )
    parser.add_argument("--prepare-only", action="store_true")
    return parser.parse_args()


def checked_value(name: str, value: str) -> str:
    if not SAFE_VALUE.fullmatch(value):
        raise SystemExit(f"{name} contains unsupported characters: {value!r}")
    return value


def release_version_tuple(version: str) -> tuple[int, int, int]:
    match = re.fullmatch(r"([ab]?)([0-9]+)\.([0-9]+)(?:\.([0-9]+))?", version)
    if not match:
        raise SystemExit(f"Unsupported Minecraft release version: {version!r}")
    era, major, minor, patch = match.groups()
    values = (int(major), int(minor), int(patch or 0))
    if era:
        return (0, *values[:2])
    return values


def server_connection_arguments(version: str, server: str) -> tuple[list[str], str]:
    host, separator, port_text = server.rpartition(":")
    if not separator or not host:
        raise SystemExit("--server must be in host:port form")
    if not port_text.isdigit() or not 1 <= int(port_text) <= 65535:
        raise SystemExit("--server port is invalid")
    if release_version_tuple(version) >= (1, 20, 0):
        return ["--quickPlayMultiplayer", server], "quick-play-multiplayer"
    return ["--server", host, "--port", port_text], "legacy-server-port"


def load_minecraft_metadata(
    shared_root: Path, version: str, metadata_base_url: str
) -> dict[str, Any]:
    metadata = shared_root / "meta" / "net.minecraft" / f"{version}.json"
    if metadata.is_file():
        try:
            value = json.loads(metadata.read_text("utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise SystemExit(f"Cannot read Prism Minecraft metadata {metadata}: {exc}") from exc
    else:
        url = f"{metadata_base_url.rstrip('/')}/{quote(version, safe='')}.json"
        try:
            with urlopen(url, timeout=30) as response:
                value = json.loads(response.read().decode("utf-8"))
        except (HTTPError, URLError, TimeoutError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise SystemExit(
                f"Cannot obtain exact Prism metadata for Minecraft {version} from {url}: {exc}"
            ) from exc
    if value.get("uid") != "net.minecraft" or value.get("version") != version:
        raise SystemExit(f"Prism metadata does not describe net.minecraft {version}: {metadata}")
    return value


def minecraft_components(metadata: dict[str, Any], version: str) -> list[dict[str, Any]]:
    components: list[dict[str, Any]] = []
    for requirement in metadata.get("requires", []):
        uid = requirement.get("uid")
        dependency_version = requirement.get("equals") or requirement.get("suggests")
        if uid not in {"org.lwjgl", "org.lwjgl3"} or not dependency_version:
            raise SystemExit(f"Unsupported Minecraft component requirement: {requirement!r}")
        components.append(
            {
                "cachedName": "LWJGL 2" if uid == "org.lwjgl" else "LWJGL 3",
                "cachedVersion": dependency_version,
                "cachedVolatile": True,
                "dependencyOnly": True,
                "uid": uid,
                "version": dependency_version,
            }
        )
    components.append(
        {
            "cachedName": "Minecraft",
            "cachedRequires": metadata.get("requires", []),
            "cachedVersion": version,
            "important": True,
            "uid": "net.minecraft",
            "version": version,
        }
    )
    return components


def link_cache(workspace: Path, shared_root: Path, name: str) -> None:
    source = shared_root / name
    destination = workspace / name
    if not source.exists():
        raise SystemExit(f"Shared Prism {name} cache is missing: {source}")
    if destination.is_symlink():
        if destination.resolve() != source.resolve():
            raise SystemExit(f"Isolated Prism cache points at the wrong {name} directory")
        return
    if destination.exists():
        raise SystemExit(f"Refusing to replace existing isolated Prism path: {destination}")
    destination.symlink_to(source, target_is_directory=True)


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8")


def offline_uuid(username: str) -> str:
    digest = bytearray(hashlib.md5(f"OfflinePlayer:{username}".encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return digest.hex()


def component_metadata(shared_root: Path, uid: str, version: str) -> dict[str, Any]:
    path = shared_root / "meta" / uid / f"{version}.json"
    try:
        value = json.loads(path.read_text("utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"Cannot read Prism component metadata {path}: {exc}") from exc
    if value.get("uid") != uid or value.get("version") != version:
        raise SystemExit(f"Prism component metadata identity mismatch: {path}")
    return value


def linux_rule_matches(rule: dict[str, Any]) -> bool:
    if rule.get("features"):
        return False
    os_rule = rule.get("os")
    if not os_rule:
        return True
    name = os_rule.get("name")
    return name in {None, "linux"}


def library_allowed(library: dict[str, Any]) -> bool:
    rules = library.get("rules", [])
    if not rules:
        return True
    allowed = False
    for rule in rules:
        if linux_rule_matches(rule):
            allowed = rule.get("action") == "allow"
    return allowed


def maven_library_path(libraries: Path, coordinate: str) -> Path:
    coordinate, separator, extension = coordinate.partition("@")
    extension = extension if separator else "jar"
    parts = coordinate.split(":")
    if len(parts) not in {3, 4}:
        raise SystemExit(f"Unsupported Prism library coordinate: {coordinate}")
    group, artifact, version = parts[:3]
    classifier = parts[3] if len(parts) == 4 else ""
    filename = f"{artifact}-{version}{('-' + classifier) if classifier else ''}.{extension}"
    return libraries.joinpath(*group.split("."), artifact, version, filename)


def sha1_file(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verified_artifact(shared_root: Path, library: dict[str, Any]) -> Path:
    path = maven_library_path(shared_root / "libraries", library["name"])
    artifact = library.get("downloads", {}).get("artifact", {})
    expected_sha1 = artifact.get("sha1")
    if not path.is_file():
        raise SystemExit(f"Required verified Prism library is not cached: {path}")
    actual_sha1 = sha1_file(path)
    if not expected_sha1 or actual_sha1 != expected_sha1:
        raise SystemExit(f"Cached Prism library failed SHA-1 verification: {path}")
    return path


def extract_native_bundles(native_jars: list[Path], native_dir: Path) -> None:
    if native_dir.exists():
        if native_dir.is_symlink() or not native_dir.is_dir():
            raise SystemExit(f"Refusing to replace unexpected native path: {native_dir}")
        shutil.rmtree(native_dir)
    native_dir.mkdir(parents=True)
    suffixes = (".so", ".dll", ".dylib", ".jnilib")
    for archive in native_jars:
        with zipfile.ZipFile(archive) as source:
            for member in source.infolist():
                name = Path(member.filename).name
                if member.is_dir() or not name.lower().endswith(suffixes):
                    continue
                destination = native_dir / name
                destination.write_bytes(source.read(member))


def java_major(java: Path) -> int | None:
    try:
        result = subprocess.run(
            [str(java), "-version"], capture_output=True, text=True, timeout=10, check=False
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    match = re.search(r'version "([0-9]+)(?:\.([0-9]+))?', result.stderr + result.stdout)
    if not match:
        return None
    first = int(match.group(1))
    return int(match.group(2)) if first == 1 and match.group(2) else first


def select_java(shared_root: Path, metadata: dict[str, Any]) -> tuple[Path, int]:
    compatible = {int(value) for value in metadata.get("compatibleJavaMajors", [8])}
    candidates: list[Path] = []
    for major in sorted(compatible, reverse=True):
        candidates.extend(
            [
                Path(f"/usr/lib/jvm/java-{major}-openjdk/bin/java"),
                Path(f"/usr/lib/jvm/java-{major}-openjdk-amd64/bin/java"),
            ]
        )
    candidates.extend(sorted((shared_root / "java").glob("*/bin/java")))
    current = shutil.which("java")
    if current:
        candidates.append(Path(current))
    seen: set[Path] = set()
    for candidate in candidates:
        candidate = candidate.resolve()
        if candidate in seen or not candidate.is_file():
            continue
        seen.add(candidate)
        major = java_major(candidate)
        if major in compatible:
            return candidate, major
    raise SystemExit(f"No cached Java runtime matches Minecraft majors {sorted(compatible)}")


def replace_arguments(template: str, values: dict[str, str]) -> list[str]:
    arguments: list[str] = []
    for argument in shlex.split(template):
        for name, value in values.items():
            argument = argument.replace("${" + name + "}", value)
        if "${" in argument:
            raise SystemExit(f"Unsupported unresolved Minecraft argument: {argument}")
        arguments.append(argument)
    return arguments


def direct_launch_command(
    args: argparse.Namespace, game_dir: Path
) -> tuple[list[str], dict[str, Any]]:
    shared_root = args.shared_root.resolve()
    metadata = load_minecraft_metadata(shared_root, args.minecraft, args.metadata_base_url)
    component_values = [metadata]
    for requirement in metadata.get("requires", []):
        uid = requirement.get("uid")
        version = requirement.get("equals") or requirement.get("suggests")
        if uid not in {"org.lwjgl", "org.lwjgl3"} or not version:
            raise SystemExit(f"Unsupported Minecraft component requirement: {requirement!r}")
        component_values.append(component_metadata(shared_root, uid, version))

    classpath: list[Path] = []
    natives: list[Path] = []
    for component in component_values:
        for library in component.get("libraries", []):
            if not library_allowed(library):
                continue
            artifact = verified_artifact(shared_root, library)
            if "jammarr" in artifact.name.lower():
                raise SystemExit(f"Jammarr artifact appeared in vanilla classpath: {artifact}")
            classpath.append(artifact)
            if "-natives-" in library["name"]:
                natives.append(artifact)

    main_library = metadata.get("mainJar")
    if not isinstance(main_library, dict) or "name" not in main_library:
        raise SystemExit("Minecraft metadata does not declare a main JAR")
    client_jar = verified_artifact(shared_root, main_library)
    classpath.append(client_jar)
    native_dir = game_dir.parent / "natives"
    extract_native_bundles(natives, native_dir)

    asset_index = metadata.get("assetIndex", {}).get("id")
    if not asset_index or not (shared_root / "assets" / "indexes" / f"{asset_index}.json").is_file():
        raise SystemExit(f"Minecraft asset index is not cached: {asset_index!r}")
    connection_arguments, connection_mode = server_connection_arguments(
        args.minecraft, args.server
    )
    java, major = select_java(shared_root, metadata)
    values = {
        "auth_player_name": args.username,
        "version_name": args.minecraft,
        "game_directory": str(game_dir),
        "assets_root": str(shared_root / "assets"),
        "assets_index_name": asset_index,
        "auth_uuid": offline_uuid(args.username),
        "auth_access_token": "0",
        "auth_session": "0",
        "user_properties": "{}",
        "user_type": "legacy",
        "version_type": str(metadata.get("type", "release")),
        "clientid": "",
        "auth_xuid": "",
    }
    game_arguments = replace_arguments(metadata.get("minecraftArguments", ""), values)
    game_arguments.extend(connection_arguments)
    command = [
        str(java),
        "-Xms512m",
        "-Xmx2048m",
        f"-Djava.library.path={native_dir}",
        "-Dminecraft.launcher.brand=JammarrAcceptance",
        "-Dminecraft.launcher.version=1",
        "-cp",
        os.pathsep.join(str(path) for path in classpath),
        metadata["mainClass"],
        *game_arguments,
    ]
    details = {
        "javaMajor": major,
        "mainClass": metadata["mainClass"],
        "classpathEntryCount": len(classpath),
        "nativeBundleCount": len(natives),
        "clientJarSha1": sha1_file(client_jar),
        "allArtifactSha1Verified": True,
        "connectionMode": connection_mode,
        "connectionTarget": args.server,
    }
    return command, details


def prepare_instance(args: argparse.Namespace) -> tuple[Path, Path, list[dict[str, Any]]]:
    version = checked_value("Minecraft version", args.minecraft)
    username = checked_value("username", args.username)
    if not 1 <= len(username) <= 16:
        raise SystemExit("username must contain 1 through 16 characters")
    server_connection_arguments(version, args.server)

    workspace = args.workspace.resolve()
    shared_root = args.shared_root.resolve()
    workspace.mkdir(parents=True, exist_ok=True)
    for cache_name in ("assets", "libraries", "meta", "java"):
        link_cache(workspace, shared_root, cache_name)

    metadata = load_minecraft_metadata(shared_root, version, args.metadata_base_url)
    components = minecraft_components(metadata, version)
    instance_id = f"jammarr-vanilla-{version}"
    instance_dir = workspace / "instances" / instance_id
    game_dir = instance_dir / "minecraft"
    game_dir.mkdir(parents=True, exist_ok=True)

    pack = {"components": components, "formatVersion": 1}
    write_text(instance_dir / "mmc-pack.json", json.dumps(pack, indent=2) + "\n")
    write_text(
        instance_dir / "instance.cfg",
        "\n".join(
            [
                "[General]",
                "AutoCloseConsole=true",
                "AutomaticJava=true",
                "CloseAfterLaunch=true",
                "ConfigVersion=1.3",
                "InstanceType=OneSix",
                "MaxMemAlloc=2048",
                "MinMemAlloc=512",
                "OverrideMemory=true",
                "QuitAfterGameStop=true",
                "ShowConsole=false",
                "UseAccountForInstance=false",
                f"name=Jammarr vanilla acceptance {version}",
                "",
            ]
        ),
    )
    write_text(
        workspace / "prismlauncher.cfg",
        "\n".join(
            [
                "[General]",
                "ApplicationTheme=system",
                "AutomaticJavaDownload=true",
                "AutomaticJavaSwitch=true",
                "CloseAfterLaunch=true",
                "ConfigVersion=1.3",
                "IconTheme=pe_colored",
                "InstanceDir=instances",
                "JavaDir=java",
                "Language=en_US",
                "MaxMemAlloc=2048",
                "MinMemAlloc=512",
                "PastebinURL=",
                "QuitAfterGameStop=true",
                "ShowConsole=false",
                "",
            ]
        ),
    )
    # Keep a deterministic options file so first-run accessibility and warning
    # screens cannot prevent the direct client launch from reaching the server.
    write_text(
        game_dir / "options.txt",
        "onboardAccessibility:false\nskipMultiplayerWarning:true\njoinedFirstServer:true\nnarrator:0\n",
    )
    return instance_dir, game_dir, components


def write_attestation(
    instance_dir: Path,
    game_dir: Path,
    version: str,
    components: list[dict[str, Any]],
    username: str,
    runtime_details: dict[str, Any] | None = None,
) -> None:
    mods_dir = game_dir / "mods"
    mods = sorted(path.name for path in mods_dir.iterdir()) if mods_dir.is_dir() else []
    attestation = {
        "schemaVersion": 1,
        "launcher": "Direct Mojang client from verified Prism caches",
        "minecraftVersion": version,
        "componentUids": [component["uid"] for component in components],
        "jammarrComponentPresent": any(
            "jammarr" in str(component.get("uid", "")).lower() for component in components
        ),
        "mods": mods,
        "accountMode": "direct-offline",
        "offlineUsername": username,
        "instanceDirectory": str(instance_dir),
        "gameDirectory": str(game_dir),
    }
    if runtime_details is not None:
        attestation["runtime"] = runtime_details
    write_text(instance_dir / "vanilla-attestation.json", json.dumps(attestation, indent=2) + "\n")


def main() -> int:
    args = parse_args()
    instance_dir, game_dir, components = prepare_instance(args)
    write_attestation(instance_dir, game_dir, args.minecraft, components, args.username)
    if args.prepare_only:
        print(instance_dir / "vanilla-attestation.json")
        return 0

    if not os.environ.get("DISPLAY"):
        raise SystemExit("A private X display is required to launch the vanilla acceptance client")

    command, runtime_details = direct_launch_command(args, game_dir)
    write_attestation(
        instance_dir, game_dir, args.minecraft, components, args.username, runtime_details
    )
    try:
        return subprocess.run(command, cwd=game_dir, check=False).returncode
    finally:
        write_attestation(
            instance_dir, game_dir, args.minecraft, components, args.username, runtime_details
        )


if __name__ == "__main__":
    sys.exit(main())
