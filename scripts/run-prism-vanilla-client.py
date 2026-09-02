#!/usr/bin/env python3
"""Launch an exact, artifact-free Minecraft client from verified Prism caches.

The dedicated-server gate uses this helper to prove that a client whose
classpath contains only Mojang Minecraft and its declared libraries can join a
Jammarr server. Prism's metadata, assets, libraries, and JRE caches are reused,
but its launcher UI and account store are never opened or inspected.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager, nullcontext
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import shlex
import subprocess
import sys
import threading
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import urlopen
import zipfile


SAFE_VALUE = re.compile(r"^[A-Za-z0-9._-]+$")
SAFE_CHAT_MESSAGE = re.compile(r"^[A-Za-z0-9 ._-]+$")


class OfflinePrivilegesHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path == "/privileges":
            payload = {
                "privileges": {
                    "onlineChat": {"enabled": True},
                    "multiplayerServer": {"enabled": True},
                    "multiplayerRealms": {"enabled": False},
                }
            }
            status = 200
        elif self.path == "/privacy/blocklist":
            payload = {"blockedProfiles": []}
            status = 200
        else:
            payload = {"error": "not available in offline acceptance"}
            status = 404
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format: str, *_args: object) -> None:
        return


@contextmanager
def offline_privileges_service():
    service = ThreadingHTTPServer(("127.0.0.1", 0), OfflinePrivilegesHandler)
    thread = threading.Thread(target=service.serve_forever, daemon=True)
    thread.start()
    try:
        host, port = service.server_address
        yield f"http://{host}:{port}"
    finally:
        service.shutdown()
        service.server_close()
        thread.join(timeout=5)


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
    parser.add_argument(
        "--fallback-cache-root",
        type=Path,
        default=Path("build/vanilla-client-cache"),
    )
    parser.add_argument(
        "--metadata-root-url",
        default="https://meta.prismlauncher.org/v1",
    )
    parser.add_argument(
        "--asset-objects-base-url",
        default="https://resources.download.minecraft.net",
    )
    parser.add_argument("--chat-trigger-file", type=Path)
    parser.add_argument("--chat-message")
    parser.add_argument("--shutdown-trigger-file", type=Path)
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    if (args.chat_trigger_file is None) != (args.chat_message is None):
        parser.error("--chat-trigger-file and --chat-message must be supplied together")
    if args.chat_message is not None and (
        not 1 <= len(args.chat_message) <= 128
        or not SAFE_CHAT_MESSAGE.fullmatch(args.chat_message)
    ):
        parser.error("--chat-message contains unsupported characters")
    return args


def send_chat_when_triggered(
    trigger_file: Path, message: str, process: subprocess.Popen[bytes]
) -> None:
    """Inject one player-originated chat message into the private X client.

    The gate creates the trigger only after its server log proves that the
    exact client joined. This keeps GUI automation synchronized without
    exposing or controlling the user's active desktop.
    """
    while process.poll() is None and not trigger_file.is_file():
        time.sleep(0.1)
    if process.poll() is not None:
        return
    try:
        search = subprocess.run(
            ["xdotool", "search", "--onlyvisible", "--name", "Minecraft"],
            check=True,
            capture_output=True,
            text=True,
        )
        windows = [line for line in search.stdout.splitlines() if line.isdigit()]
        if not windows:
            raise RuntimeError("no visible Minecraft window was found")
        window = windows[-1]
        subprocess.run(
            ["xdotool", "windowfocus", "--sync", window],
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["xdotool", "key", "--window", window, "--clearmodifiers", "t"],
            check=True,
            capture_output=True,
        )
        time.sleep(0.2)
        subprocess.run(
            [
                "xdotool",
                "type",
                "--window",
                window,
                "--clearmodifiers",
                "--delay",
                "5",
                message,
            ],
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["xdotool", "key", "--window", window, "--clearmodifiers", "Return"],
            check=True,
            capture_output=True,
        )
        print(f"VANILLA_CHAT_SENT message={message}", flush=True)
    except (OSError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"VANILLA_CHAT_FAILED error={exc}", file=sys.stderr, flush=True)


def close_when_triggered(trigger_file: Path, process: subprocess.Popen[bytes]) -> None:
    """Ask Minecraft to close normally before its private X group is torn down."""
    while process.poll() is None and not trigger_file.is_file():
        time.sleep(0.1)
    if process.poll() is not None:
        return
    try:
        search = subprocess.run(
            ["xdotool", "search", "--onlyvisible", "--name", "Minecraft"],
            check=True,
            capture_output=True,
            text=True,
        )
        windows = [line for line in search.stdout.splitlines() if line.isdigit()]
        if not windows:
            raise RuntimeError("no visible Minecraft window was found for shutdown")
        subprocess.run(
            ["xdotool", "windowclose", windows[-1]],
            check=True,
            capture_output=True,
            text=True,
        )
        print("VANILLA_SHUTDOWN_REQUESTED", flush=True)
    except (OSError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"VANILLA_SHUTDOWN_FAILED error={exc}", file=sys.stderr, flush=True)


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
    from prism_vanilla_runtime import direct_launch_command as verified_launch_command

    return verified_launch_command(args, game_dir, sys.modules[__name__])


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
    needs_privileges_stub = (1, 16, 0) <= release_version_tuple(args.minecraft) < (1, 20, 0)
    runtime_details["offlinePrivilegesStub"] = needs_privileges_stub
    with offline_privileges_service() if needs_privileges_stub else nullcontext(None) as services_host:
        if services_host is not None:
            command[1:1] = [
                f"-Dminecraft.api.auth.host={services_host}",
                f"-Dminecraft.api.account.host={services_host}",
                f"-Dminecraft.api.session.host={services_host}",
                f"-Dminecraft.api.services.host={services_host}",
            ]
        write_attestation(
            instance_dir, game_dir, args.minecraft, components, args.username, runtime_details
        )
        process = subprocess.Popen(command, cwd=game_dir)
        chat_thread: threading.Thread | None = None
        shutdown_thread: threading.Thread | None = None
        if args.chat_trigger_file is not None and args.chat_message is not None:
            args.chat_trigger_file.unlink(missing_ok=True)
            chat_thread = threading.Thread(
                target=send_chat_when_triggered,
                args=(args.chat_trigger_file, args.chat_message, process),
                daemon=True,
            )
            chat_thread.start()
        if args.shutdown_trigger_file is not None:
            args.shutdown_trigger_file.unlink(missing_ok=True)
            shutdown_thread = threading.Thread(
                target=close_when_triggered,
                args=(args.shutdown_trigger_file, process),
                daemon=True,
            )
            shutdown_thread.start()
        try:
            return process.wait()
        finally:
            if chat_thread is not None:
                chat_thread.join(timeout=2)
            if shutdown_thread is not None:
                shutdown_thread.join(timeout=2)
            write_attestation(
                instance_dir, game_dir, args.minecraft, components, args.username, runtime_details
            )


if __name__ == "__main__":
    sys.exit(main())
