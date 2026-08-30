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


TARGET_MANIFEST = json.loads(
    (Path(__file__).resolve().parents[1] / "gradle" / "targets.json").read_text("utf-8"))
PRODUCT_VERSION = TARGET_MANIFEST["productVersion"]
PROTOCOL_VERSION = TARGET_MANIFEST["protocolVersion"]
CLASS_MAJOR = {7: 51, 8: 52, 17: 61, 21: 65, 25: 69}
TARGETS = tuple(
    (target["minecraft"], loader["id"], target["java"]["runtime"],
     CLASS_MAJOR[target["java"]["bytecode"]], loader.get("artifactProfile", "modern"))
    for target in TARGET_MANIFEST["targets"]
    for loader in target["loaders"]
    if loader["implemented"]
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


def class_contract(data: bytes, label: str) -> tuple[set[tuple[str, str]], tuple[str, ...]]:
    """Returns declared method signatures and directly implemented interfaces."""
    class_major(data, label)
    offset = 8

    def take(size: int) -> bytes:
        nonlocal offset
        end = offset + size
        if end > len(data):
            fail(f"{label} has a truncated class structure")
        value = data[offset:end]
        offset = end
        return value

    def u1() -> int:
        return take(1)[0]

    def u2() -> int:
        return struct.unpack(">H", take(2))[0]

    def u4() -> int:
        return struct.unpack(">I", take(4))[0]

    constant_count = u2()
    constants: list[object | None] = [None] * constant_count
    index = 1
    while index < constant_count:
        tag = u1()
        if tag == 1:
            length = u2()
            constants[index] = take(length).decode("utf-8", "strict")
        elif tag in (3, 4):
            take(4)
        elif tag in (5, 6):
            take(8)
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            constants[index] = (tag, u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            take(4)
        elif tag == 15:
            take(3)
        else:
            fail(f"{label} has unknown constant-pool tag {tag}")
        index += 1

    def utf8(constant_index: int) -> str:
        value = constants[constant_index] if 0 < constant_index < len(constants) else None
        if not isinstance(value, str):
            fail(f"{label} has an invalid UTF-8 constant reference")
        return value

    def class_name(constant_index: int) -> str:
        value = constants[constant_index] if 0 < constant_index < len(constants) else None
        if not isinstance(value, tuple) or value[0] != 7:
            fail(f"{label} has an invalid class constant reference")
        return utf8(value[1])

    take(2)  # access_flags
    take(2)  # this_class
    take(2)  # super_class
    interfaces = tuple(class_name(u2()) for _ in range(u2()))

    def skip_attributes() -> None:
        for _ in range(u2()):
            take(2)
            take(u4())

    for _ in range(u2()):
        take(2)  # access_flags
        take(2)  # name_index
        take(2)  # descriptor_index
        skip_attributes()

    methods: set[tuple[str, str]] = set()
    for _ in range(u2()):
        take(2)  # access_flags
        name = utf8(u2())
        descriptor = utf8(u2())
        methods.add((name, descriptor))
        skip_attributes()
    return methods, interfaces


def verify_direct_interface(archive: zipfile.ZipFile, interface_name: str,
                            implementation_name: str, filename: str) -> None:
    """Ensures a reobfuscated adapter still declares every shared-interface method."""
    implementation_entry = implementation_name + ".class"
    try:
        implementation_data = archive.read(implementation_entry)
    except KeyError:
        fail(f"{filename} is missing mapped adapter {implementation_entry}")
    implementation_methods, _ = class_contract(
        implementation_data, f"{filename}:{implementation_entry}")

    interface_methods: set[tuple[str, str]] = set()
    pending = [interface_name]
    visited: set[str] = set()
    while pending:
        current = pending.pop()
        if current in visited:
            continue
        visited.add(current)
        entry = current + ".class"
        try:
            interface_data = archive.read(entry)
        except KeyError:
            fail(f"{filename} is missing shared interface {entry}")
        methods, parents = class_contract(interface_data, f"{filename}:{entry}")
        interface_methods.update(methods)
        pending.extend(parents)
    required = {signature for signature in interface_methods if not signature[0].startswith("<")}
    missing = required - implementation_methods
    if missing:
        rendered = ", ".join(name + descriptor for name, descriptor in sorted(missing))
        fail(f"{filename}:{implementation_entry} loses shared-interface methods after reobfuscation: {rendered}")


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


def verify_remappable_keybinding(archive: zipfile.ZipFile, minecraft: str,
                                 loader: str, artifact_profile: str, filename: str) -> None:
    translations = json.loads(archive.read("assets/jammarr/lang/en_us.json"))
    required_translation_keys = ["key.jammarr.open", "key.categories.jammarr"]
    # 26.x registers a namespaced custom category, whose translation key is
    # derived from the category identifier rather than the legacy string.
    if minecraft.startswith("26."):
        required_translation_keys.append("key.category.jammarr.controls")
    required_translation_keys.extend((
        "jammarr.configuration", "jammarr.configuration.plexUrl",
        "jammarr.configuration.plexToken", "jammarr.configuration.musicLibrary",
        "jammarr.configuration.restartMode",
        "jammarr.configuration.restartMode.RESTART_TRACK",
        "jammarr.configuration.restartMode.CLEAR",
        "jammarr.configuration.restartMode.RESUME_POSITION",
        "jammarr.configuration.pauseWhenNoPlayers",
        "jammarr.configuration.operatorPermissionLevel", "jammarr.configuration.queueLimit",
        "jammarr.configuration.audioBitrateKbps", "jammarr.configuration.cacheSizeMiB",
        "jammarr.configuration.stationMetadataFallbackEnabled",
        "jammarr.configuration.enabled", "jammarr.configuration.volume"))
    referenced_ui_keys: set[str] = set()
    for entry in archive.infolist():
        if not entry.filename.startswith("stonytark/jammarr/client/") \
                or not entry.filename.endswith(".class"):
            continue
        referenced_ui_keys.update(match.decode("ascii") for match in re.findall(
            rb"jammarr\.(?:screen|status|config)\.[A-Za-z0-9_.]+",
            archive.read(entry)))
    required_translation_keys.extend(sorted(referenced_ui_keys))
    for key in required_translation_keys:
        if not isinstance(translations.get(key), str) or not translations[key].strip():
            fail(f"{filename} is missing the Controls translation {key}")

    legacy = artifact_profile.startswith("legacy-")
    class_name = ("stonytark/jammarr/client/LegacyClient.class" if legacy
                  else "stonytark/jammarr/client/JammarrClient.class")
    try:
        client = archive.read(class_name)
    except KeyError:
        fail(f"{filename} is missing keybinding owner {class_name}")
    if b"key.jammarr.open" not in client:
        fail(f"{filename} does not construct the translated Jammarr menu binding")
    if minecraft.startswith("26.") and b"controls" not in client:
        fail(f"{filename} does not register the namespaced Jammarr Controls category")

    if legacy:
        if artifact_profile == "legacy-java8-asm4":
            required = (b"net/minecraft/client/settings/KeyBinding", b"KeyBindingRegistry",
                        b"registerKeyBinding")
        else:
            required = (b"net/minecraft/client/settings/KeyBinding", b"registerKeyBinding",
                        b"InputEvent$KeyInputEvent")
        consumes_binding = (b"isPressed" in client or b"func_151468_f" in client
                            or (artifact_profile == "legacy-java8-asm4" and b"func_74509_c" in client))
        if any(marker not in client for marker in required) or not consumes_binding:
            fail(f"{filename} does not register and consume a remappable legacy KeyBinding")
        legacy_lang = archive.read("assets/jammarr/lang/en_US.lang")
        if b"key.jammarr.open=" not in legacy_lang or b"key.categories.jammarr=" not in legacy_lang:
            fail(f"{filename} is missing legacy Controls translations")
        return

    # Fabric remaps released pre-26.x classes to stable intermediary names;
    # class_304 and method_1436 are KeyMapping and consumeClick respectively.
    if b"net/minecraft/client/KeyMapping" not in client and b"net/minecraft/class_304" not in client:
        fail(f"{filename} does not construct a remappable KeyMapping")
    if loader == "fabric":
        if not ((b"KeyBindingHelper" in client and b"registerKeyBinding" in client)
                or (b"KeyMappingHelper" in client and b"registerKeyMapping" in client)):
            fail(f"{filename} does not register the menu binding with Fabric")
        if b"consumeClick" not in client and b"method_1436" not in client:
            fail(f"{filename} does not consume the configured Fabric menu binding")
    elif b"RegisterKeyMappingsEvent" not in client:
        fail(f"{filename} does not register the menu binding with its loader Controls event")
    elif b"consumeClick" not in client and b"getKey" not in client and b"m_90859_" not in client:
        fail(f"{filename} does not consume the configured menu binding")


def verify_config_translations(archive: zipfile.ZipFile, minecraft: str,
                               loader: str, artifact_profile: str, filename: str) -> None:
    if artifact_profile.startswith("legacy-") or loader == "fabric":
        return
    config = archive.read("stonytark/jammarr/config/JammarrConfig.class")
    keys = (
        "plexUrl", "plexToken", "musicLibrary", "restartMode",
        "pauseWhenNoPlayers", "operatorPermissionLevel", "queueLimit",
        "audioBitrateKbps", "cacheSizeMiB", "stationMetadataFallbackEnabled",
        "enabled", "volume")
    for key in keys:
        translation = f"jammarr.configuration.{key}".encode("ascii")
        if translation not in config:
            fail(f"{filename} does not attach config translation {translation.decode()}")


def verify_metadata(archive: zipfile.ZipFile, names: set[str], minecraft: str, loader: str,
                    java: int, artifact_profile: str, filename: str) -> None:
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
        expected_fabric_loader = ">=0.19.2"
        if metadata.get("depends", {}).get("fabricloader") != expected_fabric_loader:
            fail(f"{filename} has incorrect Fabric Loader compatibility metadata")
        if "quilt.mod.json" in names or any("qsl" in name.lower() or "quilted_fabric_api" in name.lower()
                                            for name in names):
            fail(f"{filename} must not embed Quilt metadata, QSL, or Quilted Fabric API")
        if metadata.get("mixins") != ["jammarr.mixins.json"]:
            fail(f"{filename} does not declare the Jammarr Mixin config")
        if minecraft in ("26.1.2", "26.2"):
            required_quilt_hooks = {
                "stonytark/jammarr/mixin/QuiltServerBootstrapMixin.class",
                "stonytark/jammarr/mixin/client/QuiltClientBootstrapMixin.class",
                "stonytark/jammarr/quilt/QuiltNetworkingCodecRepair.class",
            }
            if not required_quilt_hooks.issubset(names):
                fail(f"{filename} is missing its guarded Quilt 26.x compatibility hooks")
        expected_jars = {
            "META-INF/jars/core-1.1.0.jar",
            "META-INF/jars/jlayer-1.0.1.jar",
            "META-INF/jars/jump3r-1.0.5.jar",
        }
        declared_jars = {value.get("file") for value in metadata.get("jars", [])}
        if declared_jars != expected_jars:
            fail(f"{filename} has incorrect nested-library metadata: {sorted(declared_jars)}")
        return

    if artifact_profile.startswith("legacy-"):
        metadata = json.loads(archive.read("mcmod.info"))
        if len(metadata) != 1 or metadata[0].get("modid") != "jammarr":
            fail(f"{filename} has incorrect legacy mod identity")
        if metadata[0].get("version") != PRODUCT_VERSION or metadata[0].get("mcversion") != minecraft:
            fail(f"{filename} has incorrect legacy version metadata")
        if "jammarr.mixins.json" in names:
            fail(f"{filename} must not advertise unsupported modern Mixins on legacy Forge")
        legacy_class = archive.read("stonytark/jammarr/Jammarr.class")
        if artifact_profile == "legacy-java8-asm4":
            if b"NetworkMod" not in legacy_class or b"clientSideRequired" not in legacy_class \
                    or b"serverSideRequired" not in legacy_class:
                fail(f"{filename} does not contain optional-client FML 6 negotiation")
        elif b"NetworkCheckHandler" not in legacy_class or b"acceptableRemoteVersions" not in legacy_class:
            fail(f"{filename} does not contain optional-client FML negotiation")
        return

    metadata_name = "META-INF/neoforge.mods.toml" if loader == "neoforge" and minecraft in ("1.21.1", "26.1.2", "26.2") else "META-INF/mods.toml"
    text = archive.read(metadata_name).decode("utf-8")
    compact = re.sub(r"\s+", "", text)
    if 'modId="jammarr"' not in compact or f'version="{PRODUCT_VERSION}"' not in compact:
        fail(f"{filename} has incorrect loader metadata identity/version")
    if minecraft not in text or 'side="BOTH"' not in compact:
        fail(f"{filename} is not constrained to Minecraft {minecraft} on both sides")
    if loader == "neoforge" and minecraft in ("1.21.1", "26.1.2", "26.2"):
        if 'type="required"' not in compact:
            fail(f"{filename} does not declare required NeoForge dependencies")
    elif 'displayTest="IGNORE_SERVER_VERSION"' not in compact:
        fail(f"{filename} does not permit an optional remote mod")


def verify_jar(path: Path, minecraft: str, loader: str, java: int,
               expected_major: int, artifact_profile: str) -> None:
    filename = path.name
    with zipfile.ZipFile(path) as archive:
        names = safe_entries(archive, filename)
        missing = COMMON_ENTRIES - names
        if missing:
            fail(f"{filename} is missing required entries: {sorted(missing)}")
        if loader == "fabric" and "fabric.mod.json" not in names:
            fail(f"{filename} is missing Fabric metadata")
        if artifact_profile.startswith("legacy-") and "mcmod.info" not in names:
            fail(f"{filename} is missing legacy Forge metadata")

        verify_metadata(archive, names, minecraft, loader, java, artifact_profile, filename)
        json.loads(archive.read("assets/jammarr/lang/en_us.json"))
        verify_remappable_keybinding(archive, minecraft, loader, artifact_profile, filename)
        verify_config_translations(archive, minecraft, loader, artifact_profile, filename)
        verify_png(archive.read("jammarr.png"), filename)
        for notice in ("META-INF/LICENSE-Jammarr-CC0-1.0.txt", "META-INF/LICENSE-LGPL-2.1-or-later.txt",
                       "META-INF/THIRD_PARTY_NOTICES.md"):
            if len(archive.read(notice).strip()) < 32:
                fail(f"{filename}:{notice} is unexpectedly empty")

        main_major = class_major(archive.read("stonytark/jammarr/Jammarr.class"), f"{filename}:Jammarr.class")
        if main_major != expected_major:
            fail(f"{filename} targets class major {main_major}, expected {expected_major} for Java {java}")

        if artifact_profile.startswith("legacy-"):
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
                    if major != expected_major:
                        fail(f"{filename}:{name} is class major {major}, expected major {expected_major}")
            # The shared core is compiled against stable names while legacy Forge
            # reobfuscates Minecraft methods in adapters. Require each adapter to
            # declare its full shared contract so an inherited MCP-named method
            # cannot disappear from the production linkage (as markDirty once did).
            for interface_name, implementation_name in (
                ("stonytark/jammarr/core/server/PlaybackStore",
                 "stonytark/jammarr/server/LegacySavedData"),
                ("stonytark/jammarr/core/server/CoordinatorRuntime",
                 "stonytark/jammarr/server/LegacyGlobalPlayer$1"),
                ("stonytark/jammarr/core/platform/CoreLogger",
                 "stonytark/jammarr/server/LegacyGlobalPlayer$1$1"),
                ("stonytark/jammarr/core/server/PlexGateway",
                 "stonytark/jammarr/core/server/PlexService"),
                ("stonytark/jammarr/core/network/HttpTransport",
                 "stonytark/jammarr/core/network/UrlConnectionHttpTransport"),
            ):
                verify_direct_interface(archive, interface_name, implementation_name, filename)
        else:
            if "jammarr.mixins.json" not in names:
                fail(f"{filename} is missing Mixin metadata")
            mixin = json.loads(archive.read("jammarr.mixins.json"))
            # Forge 26.1.2 still embeds Mixin 0.8.7, whose highest declared
            # compatibility constant is JAVA_21. The classes themselves are
            # independently required to be Java 25 bytecode above.
            mixin_java = 21 if minecraft in ("26.1.2", "26.2") and loader == "forge" else java
            if mixin.get("compatibilityLevel") != f"JAVA_{mixin_java}":
                fail(f"{filename} has incorrect Mixin Java compatibility")
            nested_prefix = "META-INF/jars" if loader == "fabric" else "META-INF/jarjar"
            core_candidates = sorted(name for name in names if name.startswith(f"{nested_prefix}/")
                                     and name.endswith("core-1.1.0.jar"))
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
    if manifest.get("schemaVersion") != 2 or manifest.get("modId") != "jammarr":
        fail("release manifest has incorrect schema or mod ID")
    if manifest.get("productVersion") != PRODUCT_VERSION or manifest.get("protocolVersion") != PROTOCOL_VERSION:
        fail("release manifest has incorrect product or protocol version")

    expected_names = {f"jammarr-{PRODUCT_VERSION}+mc{mc}-{loader}.jar" for mc, loader, _, _, _ in TARGETS}
    actual_names = {path.name for path in release_dir.glob("*.jar")}
    if actual_names != expected_names:
        fail(f"release JAR set mismatch; missing={sorted(expected_names - actual_names)}, extra={sorted(actual_names - expected_names)}")

    entries = manifest.get("artifacts", [])
    if len(entries) != len(TARGETS) or {entry.get("filename") for entry in entries} != expected_names:
        fail(f"release manifest does not map exactly the {len(TARGETS)} canonical artifacts")
    manifest_by_name = {entry["filename"]: entry for entry in entries}

    sums = {}
    for line in sums_path.read_text("utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (jammarr-[^/]+\.jar)", line)
        if not match or match.group(2) in sums:
            fail(f"invalid SHA256SUMS line: {line!r}")
        sums[match.group(2)] = match.group(1)
    if set(sums) != expected_names:
        fail(f"SHA256SUMS does not cover exactly the {len(TARGETS)} canonical artifacts")

    for minecraft, loader, java, major, artifact_profile in TARGETS:
        filename = f"jammarr-{PRODUCT_VERSION}+mc{minecraft}-{loader}.jar"
        path = release_dir / filename
        digest = sha256(path)
        entry = manifest_by_name[filename]
        expected_identity = (minecraft, loader, java)
        actual_identity = (entry.get("minecraftVersion"), entry.get("loader"), entry.get("javaVersion"))
        if actual_identity != expected_identity:
            fail(f"{filename} manifest identity is {actual_identity}, expected {expected_identity}")
        expected_loaders = ["fabric", "quilt"] if loader == "fabric" else [loader]
        if entry.get("compatibleLoaders") != expected_loaders:
            fail(f"{filename} has incorrect compatible loader declaration")
        if entry.get("productVersion") != PRODUCT_VERSION or entry.get("sha256") != digest or sums[filename] != digest:
            fail(f"{filename} manifest/checksum does not match artifact bytes")
        if not isinstance(entry.get("dependencies"), dict) or not entry["dependencies"]:
            fail(f"{filename} has no locked dependency versions in the manifest")
        if loader == "fabric" \
                and entry["dependencies"].get("quilt-loader") != "0.30.0":
            fail(f"{filename} does not record the certified Quilt Loader version")
        verify_jar(path, minecraft, loader, java, major, artifact_profile)

    print(f"Inspected {len(TARGETS)} Jammarr release artifacts in {release_dir}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        print(f"release artifact inspection failed: {error}", file=sys.stderr)
        raise SystemExit(1)
