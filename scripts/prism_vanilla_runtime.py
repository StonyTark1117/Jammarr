#!/usr/bin/env python3
"""Build a direct vanilla launch from shared or isolated verified inputs."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from prism_verified_cache import VerifiedCache, library_allowed, sha1_file


def direct_launch_command(
    args: Any, game_dir: Path, helper: Any
) -> tuple[list[str], dict[str, Any]]:
    shared_root = args.shared_root.resolve()
    cache = VerifiedCache(
        shared_root,
        args.fallback_cache_root.resolve(),
        metadata_root_url=args.metadata_root_url,
        asset_objects_base_url=args.asset_objects_base_url,
    )
    metadata = cache.component_metadata("net.minecraft", args.minecraft)
    component_values = [metadata]
    for requirement in metadata.get("requires", []):
        uid = requirement.get("uid")
        version = requirement.get("equals") or requirement.get("suggests")
        if uid not in {"org.lwjgl", "org.lwjgl3"} or not version:
            raise SystemExit(f"Unsupported Minecraft component requirement: {requirement!r}")
        component_values.append(cache.component_metadata(uid, version))

    classpath: list[Path] = []
    natives: list[Path] = []
    for component in component_values:
        for library in component.get("libraries", []):
            if not library_allowed(library):
                continue
            artifact = cache.library(library)
            if "jammarr" in artifact.name.lower():
                raise SystemExit(f"Jammarr artifact appeared in vanilla classpath: {artifact}")
            classpath.append(artifact)
            if "-natives-" in library["name"]:
                natives.append(artifact)

    main_library = metadata.get("mainJar")
    if not isinstance(main_library, dict) or "name" not in main_library:
        raise SystemExit("Minecraft metadata does not declare a main JAR")
    client_jar = cache.library(main_library)
    classpath.append(client_jar)
    native_dir = game_dir.parent / "natives"
    helper.extract_native_bundles(natives, native_dir)
    assets_root, asset_index, asset_object_count = cache.assets(metadata)

    connection_arguments, connection_mode = helper.server_connection_arguments(
        args.minecraft, args.server
    )
    java, major = helper.select_java(shared_root, metadata)
    values = {
        "auth_player_name": args.username,
        "version_name": args.minecraft,
        "game_directory": str(game_dir),
        "assets_root": str(assets_root),
        "assets_index_name": asset_index,
        "auth_uuid": helper.offline_uuid(args.username),
        "auth_access_token": "0",
        "auth_session": "0",
        "user_properties": "{}",
        "user_type": "legacy",
        "version_type": str(metadata.get("type", "release")),
        "clientid": "",
        "auth_xuid": "",
    }
    game_arguments = helper.replace_arguments(metadata.get("minecraftArguments", ""), values)
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
        "assetIndex": asset_index,
        "assetObjectCount": asset_object_count,
        "assetsRoot": str(assets_root),
        **cache.attestation(),
    }
    return command, details
