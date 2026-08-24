#!/usr/bin/env python3
"""Fail closed when a Jammarr release set is incomplete or incorrectly packaged."""

from __future__ import annotations

import hashlib
import io
import json
import os
import re
import struct
import sys
import zipfile
from pathlib import Path, PurePosixPath


PRODUCT_VERSION = "1.0.0"
PROTOCOL_VERSION = 5
TARGETS = (
    ("1.7.10", "forge", 8, 52),
    ("1.20.1", "fabric", 17, 61),
    ("1.20.1", "forge", 17, 61),
    ("1.20.1", "neoforge", 17, 61),
    ("1.20.2", "fabric", 17, 61),
    ("1.20.2", "forge", 17, 61),
    ("1.20.2", "neoforge", 17, 61),
    ("1.21.1", "fabric", 21, 65),
    ("1.21.1", "forge", 21, 65),
    ("1.21.1", "neoforge", 21, 65),
    ("26.1.2", "fabric", 25, 69),
    ("26.1.2", "forge", 25, 69),
    ("26.1.2", "neoforge", 25, 69),
)
COMMON_ENTRIES = {
    "META-INF/LICENSE-Jammarr-CC0-1.0.txt",
    "META-INF/LICENSE-LGPL-2.1-or-later.txt",
    "META-INF/THIRD_PARTY_NOTICES.md",
    "assets/jammarr/lang/en_us.json",
    "jammarr.png",
    "stonytark/jammarr/Jammarr.class",
}
PRIVATE_ADDRESS = re.compile(rb"(?<![0-9])(?:10\.(?:[0-9]{1,3}\.){2}[0-9]{1,3}|192\.168\.(?:[0-9]{1,3}\.)[0-9]{1,3}|172\.(?:1[6-9]|2[0-9]|3[01])\.(?:[0-9]{1,3}\.)[0-9]{1,3})(?![0-9])")
TEXT_SUFFIXES = (".json", ".toml", ".info", ".lang", ".md", ".txt", ".properties", ".mf")


def fail(message: str) -> None:
    raise AssertionError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def class_major(data: bytes, label: str) -> int:
    if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe":
        fail(f"{label} is not a Java class")
    return struct.unpack(">H", data[6:8])[0]


def safe_entries(archive: zipfile.ZipFile, filename: str) -> set[str]:
    names = [entry.filename for entry in archive.infolist()]
    if len(names) != len(set(names)):
        fail(f"{filename} contains duplicate ZIP entries")
    for name in names:
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or "\\" in name:
            fail(f"{filename} contains unsafe ZIP path {name!r}")
    bad = archive.testzip()
    if bad is not None:
        fail(f"{filename} has a corrupt ZIP entry: {bad}")
    return set(names)


def require_nested_class(archive: zipfile.ZipFile, nested_name: str, class_name: str, filename: str) -> bytes:
    try:
        nested_bytes = archive.read(nested_name)
    except KeyError:
        fail(f"{filename} is missing bundled library {nested_name}")
    try:
        with zipfile.ZipFile(io.BytesIO(nested_bytes)) as nested:
            return nested.read(class_name)
    except (KeyError, zipfile.BadZipFile) as error:
        fail(f"{filename}:{nested_name} is missing {class_name}: {error}")


def verify_png(data: bytes, filename: str) -> None:
    if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        fail(f"{filename} has an invalid icon")
    width, height = struct.unpack(">II", data[16:24])
    colour_type = data[25]
    if width < 16 or height < 16 or colour_type not in (4, 6):
        fail(f"{filename} icon must be at least 16x16 and include an alpha channel")


def verify_no_deployment_secrets(archive: zipfile.ZipFile, filename: str) -> None:
    configured_token = os.environ.get("JAMMARR_PLEX_TOKEN", "").encode()
    for entry in archive.infolist():
        if entry.is_dir() or entry.file_size > 8 * 1024 * 1024:
            continue
        data = archive.read(entry)
        if configured_token and len(configured_token) >= 8 and configured_token in data:
            fail(f"{filename}:{entry.filename} embeds JAMMARR_PLEX_TOKEN")
        if PRIVATE_ADDRESS.search(data):
            fail(f"{filename}:{entry.filename} embeds an RFC1918 deployment address")
        if entry.filename.lower().endswith(TEXT_SUFFIXES):
            lowered = data.lower()
            if b"plex-token=" in lowered or b"plex_token=" in lowered:
                fail(f"{filename}:{entry.filename} appears to embed a Plex credential")


def verify_metadata(archive: zipfile.ZipFile, names: set[str], minecraft: str, loader: str,
                    java: int, filename: str) -> None:
    if loader == "fabric":
        metadata = json.loads(archive.read("fabric.mod.json"))
        if metadata.get("id") != "jammarr" or metadata.get("version") != PRODUCT_VERSION:
            fail(f"{filename} has incorrect Fabric identity/version")
        if metadata.get("environment") != "*" or not metadata.get("entrypoints", {}).get("client"):
            fail(f"{filename} is not declared for both client and server")
        if metadata.get("depends", {}).get("minecraft") != f"~{minecraft}":
            fail(f"{filename} has incorrect Minecraft dependency")
        if metadata.get("depends", {}).get("java") != f">={java}":
            fail(f"{filename} has incorrect Java dependency")
        if metadata.get("mixins") != ["jammarr.mixins.json"]:
            fail(f"{filename} does not declare the Jammarr Mixin config")
        expected_jars = {
            "META-INF/jars/core-1.0.0.jar",
            "META-INF/jars/jlayer-1.0.1.jar",
            "META-INF/jars/jump3r-1.0.5.jar",
        }
        declared_jars = {value.get("file") for value in metadata.get("jars", [])}
        if declared_jars != expected_jars:
            fail(f"{filename} has incorrect nested-library metadata: {sorted(declared_jars)}")
        return

    if minecraft == "1.7.10":
        metadata = json.loads(archive.read("mcmod.info"))
        if len(metadata) != 1 or metadata[0].get("modid") != "jammarr":
            fail(f"{filename} has incorrect legacy mod identity")
        if metadata[0].get("version") != PRODUCT_VERSION or metadata[0].get("mcversion") != minecraft:
            fail(f"{filename} has incorrect legacy version metadata")
        if "jammarr.mixins.json" in names:
            fail(f"{filename} must not advertise unsupported modern Mixins on Forge 1.7.10")
        legacy_class = archive.read("stonytark/jammarr/Jammarr.class")
        if b"NetworkCheckHandler" not in legacy_class or b"acceptableRemoteVersions" not in legacy_class:
            fail(f"{filename} does not contain required-client FML negotiation")
        return

    metadata_name = "META-INF/neoforge.mods.toml" if loader == "neoforge" and minecraft in ("1.21.1", "26.1.2") else "META-INF/mods.toml"
    text = archive.read(metadata_name).decode("utf-8")
    compact = re.sub(r"\s+", "", text)
    if 'modId="jammarr"' not in compact or f'version="{PRODUCT_VERSION}"' not in compact:
        fail(f"{filename} has incorrect loader metadata identity/version")
    if minecraft not in text or 'side="BOTH"' not in compact:
        fail(f"{filename} is not constrained to Minecraft {minecraft} on both sides")
    if loader == "neoforge" and minecraft in ("1.21.1", "26.1.2"):
        if 'type="required"' not in compact:
            fail(f"{filename} does not declare required NeoForge dependencies")
    elif 'displayTest="MATCH_VERSION"' not in compact:
        fail(f"{filename} does not require a matching remote mod version")


def verify_jar(path: Path, minecraft: str, loader: str, java: int, expected_major: int) -> None:
    filename = path.name
    with zipfile.ZipFile(path) as archive:
        names = safe_entries(archive, filename)
        missing = COMMON_ENTRIES - names
        if missing:
            fail(f"{filename} is missing required entries: {sorted(missing)}")
        if loader == "fabric" and "fabric.mod.json" not in names:
            fail(f"{filename} is missing Fabric metadata")
        if minecraft == "1.7.10" and "mcmod.info" not in names:
            fail(f"{filename} is missing legacy Forge metadata")

        verify_metadata(archive, names, minecraft, loader, java, filename)
        json.loads(archive.read("assets/jammarr/lang/en_us.json"))
        verify_png(archive.read("jammarr.png"), filename)
        for notice in ("META-INF/LICENSE-Jammarr-CC0-1.0.txt", "META-INF/LICENSE-LGPL-2.1-or-later.txt",
                       "META-INF/THIRD_PARTY_NOTICES.md"):
            if len(archive.read(notice).strip()) < 32:
                fail(f"{filename}:{notice} is unexpectedly empty")

        main_major = class_major(archive.read("stonytark/jammarr/Jammarr.class"), f"{filename}:Jammarr.class")
        if main_major != expected_major:
            fail(f"{filename} targets class major {main_major}, expected {expected_major} for Java {java}")

        if minecraft == "1.7.10":
            required_legacy = {
                "assets/jammarr/lang/en_US.lang",
                "stonytark/jammarr/core/server/ChunkTransferPolicy.class",
                "stonytark/jammarr/core/server/CoordinatorRuntime.class",
                "stonytark/jammarr/core/server/GlobalPlaybackCoordinator.class",
                "stonytark/jammarr/core/server/PlaybackStore.class",
                "stonytark/jammarr/network/LegacyNetwork.class",
                "stonytark/jammarr/server/LegacyGlobalPlayer.class",
                "stonytark/jammarr/client/LegacyAudioPlayer.class",
                "stonytark/jammarr/client/LegacyScreen.class",
                "stonytark/jammarr/server/LegacySavedData.class",
                "javazoom/jl/decoder/Decoder.class",
                "de/sciss/jump3r/mp3/Lame.class",
            }
            if required_legacy - names:
                fail(f"{filename} is missing legacy runtime entries: {sorted(required_legacy - names)}")
            for name in names:
                if name.startswith("stonytark/jammarr/") and name.endswith(".class"):
                    major = class_major(archive.read(name), f"{filename}:{name}")
                    if major != 52:
                        fail(f"{filename}:{name} is class major {major}, expected Java 8 major 52")
        else:
            if "jammarr.mixins.json" not in names:
                fail(f"{filename} is missing Mixin metadata")
            mixin = json.loads(archive.read("jammarr.mixins.json"))
            # Forge 26.1.2 still embeds Mixin 0.8.7, whose highest declared
            # compatibility constant is JAVA_21. The classes themselves are
            # independently required to be Java 25 bytecode above.
            mixin_java = 21 if minecraft == "26.1.2" and loader == "forge" else java
            if mixin.get("compatibilityLevel") != f"JAVA_{mixin_java}":
                fail(f"{filename} has incorrect Mixin Java compatibility")
            nested_prefix = "META-INF/jars" if loader == "fabric" else "META-INF/jarjar"
            core_candidates = sorted(name for name in names if name.startswith(f"{nested_prefix}/")
                                     and name.endswith("core-1.0.0.jar"))
            if len(core_candidates) != 1:
                fail(f"{filename} must bundle exactly one shared core JAR, found {core_candidates}")
            for core_entry in (
                "stonytark/jammarr/core/server/ChunkTransferPolicy.class",
                "stonytark/jammarr/core/server/CoordinatorRuntime.class",
                "stonytark/jammarr/core/server/GlobalPlaybackCoordinator.class",
                "stonytark/jammarr/core/server/PlaybackStore.class",
            ):
                core_class = require_nested_class(archive, core_candidates[0], core_entry, filename)
                if class_major(core_class, f"{filename}:{core_entry}") != 52:
                    fail(f"{filename} shared core is not Java 8 bytecode")
            require_nested_class(archive, f"{nested_prefix}/jlayer-1.0.1.jar",
                                 "javazoom/jl/decoder/Decoder.class", filename)
            require_nested_class(archive, f"{nested_prefix}/jump3r-1.0.5.jar",
                                 "de/sciss/jump3r/mp3/Lame.class", filename)

        verify_no_deployment_secrets(archive, filename)


def main() -> int:
    release_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "build/releases").resolve()
    manifest_path = release_dir / "manifest.json"
    sums_path = release_dir / "SHA256SUMS"
    if not manifest_path.is_file() or not sums_path.is_file():
        fail(f"{release_dir} is missing manifest.json or SHA256SUMS")

    manifest = json.loads(manifest_path.read_text("utf-8"))
    if manifest.get("schemaVersion") != 1 or manifest.get("modId") != "jammarr":
        fail("release manifest has incorrect schema or mod ID")
    if manifest.get("productVersion") != PRODUCT_VERSION or manifest.get("protocolVersion") != PROTOCOL_VERSION:
        fail("release manifest has incorrect product or protocol version")

    expected_names = {f"jammarr-{PRODUCT_VERSION}+mc{mc}-{loader}.jar" for mc, loader, _, _ in TARGETS}
    actual_names = {path.name for path in release_dir.glob("*.jar")}
    if actual_names != expected_names:
        fail(f"release JAR set mismatch; missing={sorted(expected_names - actual_names)}, extra={sorted(actual_names - expected_names)}")

    entries = manifest.get("artifacts", [])
    if len(entries) != len(TARGETS) or {entry.get("filename") for entry in entries} != expected_names:
        fail("release manifest does not map exactly the 13 canonical artifacts")
    manifest_by_name = {entry["filename"]: entry for entry in entries}

    sums = {}
    for line in sums_path.read_text("utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (jammarr-[^/]+\.jar)", line)
        if not match or match.group(2) in sums:
            fail(f"invalid SHA256SUMS line: {line!r}")
        sums[match.group(2)] = match.group(1)
    if set(sums) != expected_names:
        fail("SHA256SUMS does not cover exactly the 13 canonical artifacts")

    for minecraft, loader, java, major in TARGETS:
        filename = f"jammarr-{PRODUCT_VERSION}+mc{minecraft}-{loader}.jar"
        path = release_dir / filename
        digest = sha256(path)
        entry = manifest_by_name[filename]
        expected_identity = (minecraft, loader, java)
        actual_identity = (entry.get("minecraftVersion"), entry.get("loader"), entry.get("javaVersion"))
        if actual_identity != expected_identity:
            fail(f"{filename} manifest identity is {actual_identity}, expected {expected_identity}")
        if entry.get("productVersion") != PRODUCT_VERSION or entry.get("sha256") != digest or sums[filename] != digest:
            fail(f"{filename} manifest/checksum does not match artifact bytes")
        if not isinstance(entry.get("dependencies"), dict) or not entry["dependencies"]:
            fail(f"{filename} has no locked dependency versions in the manifest")
        verify_jar(path, minecraft, loader, java, major)

    print(f"Inspected 13 Jammarr release artifacts in {release_dir}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        print(f"release artifact inspection failed: {error}", file=sys.stderr)
        raise SystemExit(1)
