#!/usr/bin/env python3
"""Reconcile stopped DiscPanel test servers from gradle/targets.json.

Dry-run is the default. Applying creates native profiles separately from the
Fabric-derived legacy profiles. It never starts a server or uploads a Jammarr
artifact. Legacy launchers are pinned, inspected, and kept outside the mods
directory.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.util
import io
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
TARGET_MATRIX_SCRIPT = SCRIPT_DIR / "target-matrix.py"
TARGET_MATRIX_SPEC = importlib.util.spec_from_file_location(
    "jammarr_target_matrix", TARGET_MATRIX_SCRIPT
)
assert TARGET_MATRIX_SPEC and TARGET_MATRIX_SPEC.loader
target_matrix = importlib.util.module_from_spec(TARGET_MATRIX_SPEC)
sys.modules[TARGET_MATRIX_SPEC.name] = target_matrix
TARGET_MATRIX_SPEC.loader.exec_module(target_matrix)

LOADER_ENUM = {
    "fabric": "MOD_LOADER_FABRIC",
    "forge": "MOD_LOADER_FORGE",
    "neoforge": "MOD_LOADER_NEOFORGE",
    "quilt": "MOD_LOADER_QUILT",
}
CUSTOM_RUNTIME_CONFIG = {
    "b1.7.3-babric": {
        "provisioning": "custom-url",
        "launcherSha256": "a9374e2da1b0ca336c0f8fa2fb894d761931b7b161d8d264626cc779c13c7278",
        "environment": {
            "FABRIC_LAUNCHER_URL": "https://meta.babric.glass-launcher.net/v2/versions/loader/b1.7.3/0.16.9/1.0.0-babric.2/server/jar",
        },
    },
    "1.6.4-fabric": {
        "provisioning": "custom-url",
        "launcherSha256": "bc3f1a2a1a98dead328d74b834072e90e699b10c551e77214b23f82d4f170012",
        "environment": {
            "FABRIC_LAUNCHER_URL": "https://meta.legacyfabric.net/v2/versions/loader/1.6.4/0.18.3/1.1.1/server/jar",
        },
    },
    "1.8.9-fabric": {
        "provisioning": "custom-url",
        "launcherSha256": "f61b5ea5391cc941e7b7d559c0cd353cabc8e186e7333ab0e1968dff951ea4a0",
        "environment": {
            "FABRIC_LAUNCHER_URL": "https://meta.legacyfabric.net/v2/versions/loader/1.8.9/0.18.3/1.1.1/server/jar",
        },
    },
    "1.6.4-ornithe": {
        "provisioning": "custom-upload",
        "environment": {
            "TYPE": "CUSTOM",
            "CUSTOM_SERVER": (
                "/data/1.6.4-ornithe-server-bootstrap/fabric-server-launch.jar"
            ),
        },
    },
    "1.8.9-ornithe": {
        "provisioning": "custom-upload",
        "environment": {
            "TYPE": "CUSTOM",
            "CUSTOM_SERVER": (
                "/data/1.8.9-ornithe-server-bootstrap/fabric-server-launch.jar"
            ),
        },
    },
}
NATIVE_RUNTIME_ENVIRONMENT = {
    # Quilt Installer 0.8.2 and newer refuse to run on Java 8 even when the
    # selected Minecraft/loader pair remains Java-8 compatible. Keep the
    # production server gate aligned with the tested 1.16.5 loader profile.
    "1.16.5-quilt": {
        "QUILT_INSTALLER_VERSION": "0.7.0",
    },
}
ORNITHE_INSTALLER_URL = (
    "https://maven.ornithemc.net/releases/net/ornithemc/ornithe-installer/"
    "0.15.0/ornithe-installer-0.15.0.jar"
)
ORNITHE_INSTALLER_SHA256 = "86b01c48c605e35fba3f4012d2b951ca4ae5f0b7445df8d3152f522b7242389f"
ORNITHE_LOADER_VERSION = "0.19.5"
ORNITHE_SERVER_JARS = {
    "1.6.4": {
        "url": (
            "https://launcher.mojang.com/v1/objects/"
            "050f93c1f3fe9e2052398f7bd6aca10c63d64a87/server.jar"
        ),
        "sourceSha256": "81841a2fedfe0ce19983156a06fa5294335284beeb95c8ca872d3c1a5fcf5774",
        "runtimeSha256": "605036267dc626a674f9da12ffc18717b01cb28b9cbbb5d09fa005c49919f7e7",
    },
    "1.8.9": {
        "url": (
            "https://launcher.mojang.com/v1/objects/"
            "b58b2ceb36e01bcd8dbf49c8fb66c55a9f0676cd/server.jar"
        ),
        "sourceSha256": "c18e4245073aaff580eb7359902f0251436568b1647a9e443a924cdb73fa8312",
        "runtimeSha256": "539e0cc6da1aabc72b7f7471f7e423fb59b8a9ad9f365179427819f8ea62ae3f",
    },
}
STATUS_STOPPED = "SERVER_STATUS_STOPPED"
STATUS_ERROR = "SERVER_STATUS_ERROR"
MANAGED_DESCRIPTION = (
    "Jammarr test runtime managed from gradle/targets.json; "
    "artifact state is tracked separately by DiscPanel ModService."
)
MANAGED_RUNTIME_ENV_KEYS = {
    "CUSTOM_JAR_EXEC",
    "CUSTOM_SERVER",
    "FABRIC_LAUNCHER",
    "FABRIC_LAUNCHER_URL",
    "TYPE",
    "QUILT_INSTALLER_VERSION",
}
# DiscPanel reserves container TCP 25575 for RCON. Assigning it as a game's
# public port causes Docker's localhost RCON binding to replace the game-port
# binding, leaving an apparently healthy server unreachable from clients.
RESERVED_GAME_PORTS = {25575}


@dataclass(frozen=True)
class Profile:
    runtime: str
    minecraft: str
    loader: str
    name: str
    java: int
    docker_image: str
    panel_loader: str
    provisioning: str
    environment: tuple[tuple[str, str], ...] = ()
    launcher_sha256: str = ""


def desired_profiles(manifest_path: Path) -> list[Profile]:
    manifest = target_matrix.load_manifest(manifest_path)
    profiles: list[Profile] = []
    for runtime in target_matrix.runtimes(manifest):
        minecraft, loader = runtime["name"].rsplit("-", 1)
        custom = CUSTOM_RUNTIME_CONFIG.get(runtime["name"])
        environment = (
            custom["environment"]
            if custom
            else NATIVE_RUNTIME_ENVIRONMENT.get(runtime["name"], {})
        )
        java = int(runtime["runtimeJava"])
        if java not in {8, 17, 21, 25}:
            raise SystemExit(
                f"{runtime['name']} requires Java {java}, for which DiscPanel has no pinned image"
            )
        profiles.append(
            Profile(
                runtime=runtime["name"],
                minecraft=minecraft,
                loader=loader,
                name=f"Jammarr {minecraft} {display_loader(loader)} Test",
                java=java,
                docker_image=f"java{java}",
                panel_loader="MOD_LOADER_FABRIC" if custom else LOADER_ENUM.get(loader, ""),
                provisioning=str(custom["provisioning"]) if custom else "native",
                environment=tuple(sorted(environment.items())),
                launcher_sha256=str(custom.get("launcherSha256", "")) if custom else "",
            )
        )
    unsupported = [profile.runtime for profile in profiles if not profile.panel_loader]
    if unsupported:
        raise SystemExit(f"DiscPanel loader mapping missing for: {', '.join(unsupported)}")
    return profiles


def display_loader(loader: str) -> str:
    return {
        "babric": "Babric",
        "fabric": "Fabric",
        "forge": "Forge",
        "neoforge": "NeoForge",
        "ornithe": "Ornithe",
        "quilt": "Quilt",
    }[loader]


def verify_custom_url_launcher(profile: Profile, timeout: int = 120) -> str:
    """Download and verify a pinned Fabric-derived server launcher."""
    if profile.provisioning != "custom-url":
        raise ValueError(f"{profile.runtime} is not a custom URL launcher profile")
    if len(profile.launcher_sha256) != 64:
        raise RuntimeError(f"{profile.runtime} has no valid pinned launcher digest")
    environment = dict(profile.environment)
    url = environment.get("FABRIC_LAUNCHER_URL")
    if not url:
        raise RuntimeError(f"{profile.runtime} has no FABRIC_LAUNCHER_URL")
    request = urllib.request.Request(url, headers={"User-Agent": "Jammarr-DiscPanel-Reconciler/1"})
    digest = hashlib.sha256()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
    actual = digest.hexdigest()
    if actual != profile.launcher_sha256:
        raise RuntimeError(
            f"custom launcher digest mismatch for {profile.runtime}: "
            f"expected {profile.launcher_sha256}, got {actual}"
        )
    return actual


class DiscPanel:
    def __init__(self, base_url: str, token: str, timeout: int = 30) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    def call(self, service: str, method: str, payload: dict[str, Any]) -> dict[str, Any]:
        request = urllib.request.Request(
            f"{self.base_url}/discopanel.v1.{service}/{method}",
            data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", "replace")[:500]
            raise RuntimeError(f"DiscPanel {method} returned HTTP {error.code}: {detail}") from error

    def list_servers(self) -> list[dict[str, Any]]:
        return list(self.call("ServerService", "ListServers", {}).get("servers", []))

    def next_port_state(self) -> tuple[int, set[int]]:
        response = self.call("ServerService", "GetNextAvailablePort", {})
        return int(response["port"]), {
            int(entry["port"]) for entry in response.get("usedPorts", []) if entry.get("inUse")
        }

    def create_server(self, profile: Profile, port: int) -> dict[str, Any]:
        if not profile.panel_loader:
            raise RuntimeError(f"refusing creation without a panel loader for {profile.runtime}")
        payload: dict[str, Any] = {
            "name": profile.name,
            "description": MANAGED_DESCRIPTION,
            "modLoader": profile.panel_loader,
            "mcVersion": profile.minecraft,
            "port": port,
            "maxPlayers": 5,
            "memory": 4096,
            "dockerImage": profile.docker_image,
            "autoStart": False,
            "detached": True,
            "startImmediately": False,
        }
        if profile.environment:
            payload["dockerOverrides"] = {
                "environment": dict(profile.environment),
            }
        return self.call(
            "ServerService",
            "CreateServer",
            payload,
        )

    def get_server(self, server_id: str) -> dict[str, Any]:
        return self.call("ServerService", "GetServer", {"id": server_id})["server"]

    def update_server(
        self,
        profile: Profile,
        server: dict[str, Any],
        port: int | None = None,
    ) -> dict[str, Any]:
        overrides = json.loads(json.dumps(server.get("dockerOverrides") or {}))
        environment = dict(overrides.get("environment") or {})
        for key in MANAGED_RUNTIME_ENV_KEYS:
            environment.pop(key, None)
        environment.update(dict(profile.environment))
        overrides["environment"] = environment
        return self.call(
            "ServerService",
            "UpdateServer",
            {
                "id": server["id"],
                "name": server["name"],
                "description": server.get("description", ""),
                "port": int(server["port"] if port is None else port),
                "maxPlayers": int(server.get("maxPlayers", 5)),
                "memory": int(server.get("memory", 4096)),
                "modLoader": profile.panel_loader.removeprefix("MOD_LOADER_").lower(),
                "mcVersion": profile.minecraft,
                "dockerImage": profile.docker_image,
                "autoStart": False,
                "detached": True,
                "dockerOverrides": overrides,
            },
        )

    def update_server_description(
        self, server: dict[str, Any], description: str
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "id": server["id"],
            "name": server["name"],
            "description": description,
            "port": int(server["port"]),
            "maxPlayers": int(server.get("maxPlayers", 5)),
            "memory": int(server.get("memory", 4096)),
            "modLoader": str(server["modLoader"]).removeprefix("MOD_LOADER_").lower(),
            "mcVersion": server["mcVersion"],
            "dockerImage": server["dockerImage"],
            "autoStart": bool(server.get("autoStart", False)),
            "detached": bool(server.get("detached", True)),
        }
        if server.get("dockerOverrides") is not None:
            payload["dockerOverrides"] = server["dockerOverrides"]
        return self.call("ServerService", "UpdateServer", payload)

    def update_server_environment(
        self, server: dict[str, Any], additions: dict[str, str]
    ) -> dict[str, Any]:
        overrides = json.loads(json.dumps(server.get("dockerOverrides") or {}))
        environment = dict(overrides.get("environment") or {})
        environment.update(additions)
        overrides["environment"] = environment
        payload: dict[str, Any] = {
            "id": server["id"],
            "name": server["name"],
            "description": server.get("description", ""),
            "port": int(server["port"]),
            "maxPlayers": int(server.get("maxPlayers", 5)),
            "memory": int(server.get("memory", 4096)),
            "modLoader": str(server["modLoader"]).removeprefix("MOD_LOADER_").lower(),
            "mcVersion": server["mcVersion"],
            "dockerImage": server["dockerImage"],
            "autoStart": bool(server.get("autoStart", False)),
            "detached": bool(server.get("detached", True)),
            "dockerOverrides": overrides,
        }
        return self.call("ServerService", "UpdateServer", payload)

    def create_folder(self, server_id: str, path: str) -> None:
        self.call("FileService", "CreateFolder", {"serverId": server_id, "path": path})

    def update_file(self, server_id: str, path: str, content: bytes) -> None:
        self.call(
            "FileService",
            "UpdateFile",
            {
                "serverId": server_id,
                "path": path,
                "content": base64.b64encode(content).decode("ascii"),
            },
        )

    def list_files(self, server_id: str, path: str = "") -> list[dict[str, Any]]:
        return list(
            self.call(
                "FileService",
                "ListFiles",
                {"serverId": server_id, "path": path, "tree": False},
            ).get("files", [])
        )

    def get_file(self, server_id: str, path: str) -> bytes:
        response = self.call(
            "FileService", "GetFile", {"serverId": server_id, "path": path}
        )
        content = response.get("content", "")
        if not isinstance(content, str):
            raise RuntimeError(f"DiscPanel GetFile returned invalid content for {path}")
        return base64.b64decode(content)

    def delete_file(self, server_id: str, path: str) -> None:
        self.call(
            "FileService",
            "DeleteFile",
            {"serverId": server_id, "path": path},
        )

    def create_upload_session(self, source: Path) -> str:
        # DiscPanel's upload staging area can retain completed or failed names.
        # Keep each transient session filename unique.
        upload_filename = f"jammarr-{uuid.uuid4().hex}-{source.name}"
        initialized = self.call(
            "UploadService",
            "InitUpload",
            {
                "filename": upload_filename,
                "totalSize": source.stat().st_size,
                "chunkSize": 0,
            },
        )
        session_id = str(initialized["sessionId"])
        try:
            for attempt in range(3):
                status = self.call(
                    "UploadService", "GetUploadStatus", {"sessionId": session_id}
                )
                if status.get("completed"):
                    break
                offset = int(status.get("bytesReceived", 0))
                if offset < 0 or offset > source.stat().st_size:
                    raise RuntimeError(
                        f"DiscPanel returned an invalid upload offset for {source.name}"
                    )
                with source.open("rb") as stream:
                    stream.seek(offset)
                    data = stream.read()
                headers = {
                    "Authorization": f"Bearer {self.token}",
                    "Content-Type": "application/octet-stream",
                }
                if offset:
                    headers["X-Upload-Offset"] = str(offset)
                request = urllib.request.Request(
                    f"{self.base_url}/api/v1/upload/"
                    f"{urllib.parse.quote(session_id, safe='')}",
                    data=data,
                    headers=headers,
                    method="PUT",
                )
                try:
                    with urllib.request.urlopen(request, timeout=self.timeout) as response:
                        response.read()
                except (OSError, urllib.error.URLError):
                    if attempt == 2:
                        raise
                    time.sleep(0.5 * (attempt + 1))
            status = self.call(
                "UploadService", "GetUploadStatus", {"sessionId": session_id}
            )
            if not status.get("completed"):
                raise RuntimeError(f"DiscPanel upload did not complete for {source.name}")
            return session_id
        except BaseException:
            try:
                self.call(
                    "UploadService", "CancelUpload", {"sessionId": session_id}
                )
            except BaseException:
                pass
            raise

    def upload_file(self, server_id: str, source: Path, destination: str) -> None:
        session_id = self.create_upload_session(source)
        try:
            self.call(
                "FileService",
                "SaveUploadedFile",
                {
                    "serverId": server_id,
                    "uploadSessionId": session_id,
                    "destinationPath": destination,
                    "filename": source.name,
                },
            )
        except BaseException:
            try:
                self.call(
                    "UploadService", "CancelUpload", {"sessionId": session_id}
                )
            except BaseException:
                pass
            raise

    def extract_archive(
        self, server_id: str, archive_path: str, timeout_seconds: int
    ) -> None:
        response = self.call(
            "FileService",
            "ExtractArchive",
            {"serverId": server_id, "path": archive_path},
        )
        operation_id = str(response["operationId"])
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            status = self.call(
                "FileService",
                "GetExtractionStatus",
                {"operationId": operation_id},
            )
            state = str(status.get("state", "")).upper()
            if state in {"COMPLETED", "COMPLETE", "SUCCESS", "SUCCEEDED"}:
                return
            if state in {"ERROR", "FAILED", "CANCELLED"}:
                raise RuntimeError(
                    f"DiscPanel extraction failed for {archive_path}: {status.get('error', state)}"
                )
            time.sleep(1)
        raise RuntimeError(f"DiscPanel extraction timed out for {archive_path}")


def is_stopped(server: dict[str, Any]) -> bool:
    return server.get("status") == STATUS_STOPPED


def drift(profile: Profile, server: dict[str, Any]) -> list[str]:
    expected = {
        "mcVersion": profile.minecraft,
        "modLoader": profile.panel_loader,
        "dockerImage": profile.docker_image,
        "memory": 4096,
    }
    differences = [
        f"{key}={server.get(key)!r} expected {value!r}"
        for key, value in expected.items()
        if server.get(key) != value
    ]
    if not is_stopped(server):
        differences.append(f"status={server.get('status')!r} expected {STATUS_STOPPED!r}")
    if bool(server.get("autoStart", False)):
        differences.append("autoStart=True expected False")
    if server.get("port") in RESERVED_GAME_PORTS:
        differences.append(
            f"port={server.get('port')} is reserved for DiscPanel container RCON"
        )
    environment = (server.get("dockerOverrides") or {}).get("environment") or {}
    expected_environment = dict(profile.environment)
    for key, value in profile.environment:
        if environment.get(key) != value:
            differences.append(f"docker environment {key} is not the pinned value")
    for key in MANAGED_RUNTIME_ENV_KEYS - expected_environment.keys():
        if key in environment:
            differences.append(f"docker environment {key} is unexpectedly managed")
    return differences


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_pinned(url: str, destination: Path, expected_sha256: str) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and sha256(destination) == expected_sha256:
        return
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected_sha256:
        raise RuntimeError(
            f"pinned download digest mismatch for {destination.name}: {actual}"
        )
    destination.write_bytes(data)


def validate_ornithe_launcher(launcher: bytes | Path, minecraft: str) -> None:
    source: Any = io.BytesIO(launcher) if isinstance(launcher, bytes) else launcher
    with zipfile.ZipFile(source) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
        properties = archive.read("fabric-server-launch.properties").decode(
            "utf-8", "replace"
        )
    manifest = manifest.replace("\r\n ", "").replace("\n ", "")
    if "net.fabricmc.loader.impl.launch.server.FabricServerLauncher" not in manifest:
        raise RuntimeError(f"Ornithe launcher has the wrong main class for {minecraft}")
    if "launch.mainClass=net.fabricmc.loader.impl.launch.knot.KnotServer" not in properties:
        raise RuntimeError(f"Ornithe launcher has the wrong Knot entrypoint for {minecraft}")
    if f"calamus-intermediary-gen2/{minecraft}/" not in manifest:
        raise RuntimeError(f"Ornithe launcher has the wrong game mappings for {minecraft}")
    if f"fabric-loader/{ORNITHE_LOADER_VERSION}/" not in manifest:
        raise RuntimeError(f"Ornithe launcher has the wrong loader version for {minecraft}")
    for required_library in (
        "log4j-api/2.19.0/",
        "log4j-core/2.19.0/",
        "sponge-mixin/0.17.4+mixin.0.8.7/",
    ):
        if required_library not in manifest:
            raise RuntimeError(
                f"Ornithe launcher is missing {required_library} for {minecraft}"
            )


def validate_ornithe_install(install_dir: Path, minecraft: str) -> Path:
    launcher = install_dir / "fabric-server-launch.jar"
    if not launcher.is_file():
        raise RuntimeError(f"Ornithe installer omitted the server launcher for {minecraft}")
    if any("jammarr" in path.name.lower() for path in install_dir.rglob("*")):
        raise RuntimeError("Ornithe bootstrap unexpectedly contains a Jammarr artifact")
    validate_ornithe_launcher(launcher, minecraft)
    libraries = list((install_dir / "libraries").rglob("*.jar"))
    if len(libraries) < 8:
        raise RuntimeError(f"Ornithe installer produced only {len(libraries)} libraries")
    return launcher


def prepare_ornithe_archive(profile: Profile, output_dir: Path, java: str) -> Path:
    if profile.provisioning != "custom-upload" or profile.loader != "ornithe":
        raise RuntimeError(f"refusing Ornithe preparation for {profile.runtime}")
    output_dir.mkdir(parents=True, exist_ok=True)
    installer = output_dir / "ornithe-installer-0.15.0.jar"
    download_pinned(ORNITHE_INSTALLER_URL, installer, ORNITHE_INSTALLER_SHA256)
    archive_path = output_dir / f"{profile.runtime}-server-bootstrap.zip"
    with tempfile.TemporaryDirectory(prefix=f"jammarr-{profile.runtime}-") as temporary:
        install_dir = Path(temporary)
        subprocess.run(
            [
                java,
                "-jar",
                str(installer.resolve()),
                "install",
                "server",
                profile.minecraft,
                "Fabric",
                ORNITHE_LOADER_VERSION,
                "--intermediary-generation=2",
                f"--install-dir={install_dir}",
            ],
            check=True,
        )
        validate_ornithe_install(install_dir, profile.minecraft)
        temporary_archive = install_dir.parent / f"{profile.runtime}-bootstrap.zip"
        with zipfile.ZipFile(
            temporary_archive, "w", compression=zipfile.ZIP_STORED
        ) as archive:
            for path in sorted(install_dir.rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(install_dir).as_posix())
        with zipfile.ZipFile(temporary_archive) as archive:
            names = set(archive.namelist())
            if "fabric-server-launch.jar" not in names:
                raise RuntimeError("Ornithe bootstrap archive omitted its launcher")
            if not any(name.startswith("libraries/") for name in names):
                raise RuntimeError("Ornithe bootstrap archive omitted its libraries")
        shutil.copy2(temporary_archive, archive_path)
    return archive_path


def _zip_entries_without_paths(source: bytes, removed_paths: set[str]) -> bytes:
    """Remove ZIP members while preserving every retained compressed byte.

    Current Ornithe launchers strip libraries shaded into legacy Mojang server
    JARs before handing the game JAR to Fabric. The pinned Java installer used
    by this automation predates that launcher behavior, so reproduce it here.
    Rebuilding entries through ZipFile would make the derived digest depend on
    the host zlib; this small central-directory rewrite keeps it reproducible.
    """
    eocd_signature = b"PK\x05\x06"
    central_signature = b"PK\x01\x02"
    eocd_offset = source.rfind(eocd_signature, max(0, len(source) - 65_557))
    if eocd_offset < 0 or eocd_offset + 22 > len(source):
        raise RuntimeError("server JAR has no valid ZIP end record")
    disk, central_disk, disk_entries, total_entries, central_size, central_offset, comment_size = (
        struct.unpack_from("<HHHHIIH", source, eocd_offset + 4)
    )
    if disk or central_disk or disk_entries != total_entries:
        raise RuntimeError("multi-disk server JARs are unsupported")
    if total_entries == 0xFFFF or central_offset == 0xFFFFFFFF:
        raise RuntimeError("ZIP64 server JARs are unsupported")
    if eocd_offset + 22 + comment_size != len(source):
        raise RuntimeError("server JAR has unexpected data after its ZIP end record")
    if central_offset + central_size != eocd_offset:
        raise RuntimeError("server JAR has an unsupported central-directory trailer")

    entries: list[dict[str, Any]] = []
    cursor = central_offset
    for _ in range(total_entries):
        if source[cursor : cursor + 4] != central_signature:
            raise RuntimeError("server JAR central directory is malformed")
        flags = struct.unpack_from("<H", source, cursor + 8)[0]
        filename_size, extra_size, entry_comment_size = struct.unpack_from(
            "<HHH", source, cursor + 28
        )
        record_size = 46 + filename_size + extra_size + entry_comment_size
        record = bytearray(source[cursor : cursor + record_size])
        filename_bytes = bytes(record[46 : 46 + filename_size])
        encoding = "utf-8" if flags & 0x800 else "cp437"
        filename = filename_bytes.decode(encoding)
        local_offset = struct.unpack_from("<I", record, 42)[0]
        if local_offset == 0xFFFFFFFF:
            raise RuntimeError("ZIP64 server JAR entries are unsupported")
        entries.append(
            {
                "filename": filename,
                "localOffset": local_offset,
                "record": record,
            }
        )
        cursor += record_size
    if cursor != central_offset + central_size:
        raise RuntimeError("server JAR central-directory size does not match")

    by_local_offset = sorted(entries, key=lambda entry: int(entry["localOffset"]))
    if len({entry["localOffset"] for entry in by_local_offset}) != len(entries):
        raise RuntimeError("server JAR contains duplicate local entry offsets")
    first_local_offset = int(by_local_offset[0]["localOffset"]) if entries else central_offset
    output = bytearray(source[:first_local_offset])
    retained_offsets: dict[int, int] = {}
    for index, entry in enumerate(by_local_offset):
        old_offset = int(entry["localOffset"])
        next_offset = (
            int(by_local_offset[index + 1]["localOffset"])
            if index + 1 < len(by_local_offset)
            else central_offset
        )
        if old_offset < first_local_offset or next_offset < old_offset:
            raise RuntimeError("server JAR local entry offsets are malformed")
        if str(entry["filename"]) in removed_paths:
            continue
        retained_offsets[old_offset] = len(output)
        output.extend(source[old_offset:next_offset])

    new_central_offset = len(output)
    retained_count = 0
    for entry in entries:
        old_offset = int(entry["localOffset"])
        if old_offset not in retained_offsets:
            continue
        record = bytearray(entry["record"])
        struct.pack_into("<I", record, 42, retained_offsets[old_offset])
        output.extend(record)
        retained_count += 1
    if retained_count > 0xFFFF:
        raise RuntimeError("derived server JAR has too many entries")
    new_central_size = len(output) - new_central_offset
    end_record = bytearray(source[eocd_offset:])
    struct.pack_into("<HHII", end_record, 8, retained_count, retained_count, new_central_size, new_central_offset)
    output.extend(end_record)
    return bytes(output)


def prepare_ornithe_server_jar(
    profile: Profile, bootstrap_archive: Path, output_dir: Path
) -> Path:
    spec = ORNITHE_SERVER_JARS.get(profile.minecraft)
    if not spec:
        raise RuntimeError(f"no pinned Minecraft server JAR for {profile.runtime}")
    runtime_dir = output_dir / profile.runtime
    source = runtime_dir / "server-source.jar"
    download_pinned(spec["url"], source, spec["sourceSha256"])

    shaded_paths: set[str] = set()
    with zipfile.ZipFile(bootstrap_archive) as bootstrap:
        library_names = sorted(
            name
            for name in bootstrap.namelist()
            if name.startswith("libraries/") and name.endswith(".jar")
        )
        if not library_names:
            raise RuntimeError(f"Ornithe bootstrap has no libraries for {profile.runtime}")
        for library_name in library_names:
            with zipfile.ZipFile(io.BytesIO(bootstrap.read(library_name))) as library:
                shaded_paths.update(
                    entry.filename for entry in library.infolist() if not entry.is_dir()
                )

    destination = runtime_dir / "server.jar"
    transformed = _zip_entries_without_paths(source.read_bytes(), shaded_paths)
    destination.write_bytes(transformed)
    actual = hashlib.sha256(transformed).hexdigest()
    if actual != spec["runtimeSha256"]:
        raise RuntimeError(
            f"derived Ornithe server digest mismatch for {profile.runtime}: {actual}"
        )
    with zipfile.ZipFile(destination) as runtime_jar:
        remaining = set(runtime_jar.namelist())
    leaked = shaded_paths & remaining
    if leaked:
        raise RuntimeError(
            f"derived Ornithe server retained {len(leaked)} shaded library entries"
        )
    return destination


def ornithe_bootstrap_dir(profile: Profile) -> str:
    return f"{profile.runtime}-server-bootstrap"


def has_ornithe_launcher_files(
    panel: DiscPanel, server_id: str, profile: Profile
) -> bool:
    bootstrap_dir = ornithe_bootstrap_dir(profile)
    names = {str(entry.get("name", "")) for entry in panel.list_files(server_id)}
    if bootstrap_dir not in names:
        return False
    nested_names = {
        str(entry.get("name", ""))
        for entry in panel.list_files(server_id, bootstrap_dir)
    }
    return "fabric-server-launch.jar" in nested_names and "libraries" in nested_names


def has_ornithe_bootstrap(
    panel: DiscPanel, server_id: str, profile: Profile
) -> bool:
    names = {str(entry.get("name", "")) for entry in panel.list_files(server_id)}
    if "server.jar" not in names or not has_ornithe_launcher_files(
        panel, server_id, profile
    ):
        return False
    expected = ORNITHE_SERVER_JARS.get(profile.minecraft, {}).get("runtimeSha256")
    if not expected:
        raise RuntimeError(f"no pinned Minecraft server JAR for {profile.runtime}")
    return hashlib.sha256(panel.get_file(server_id, "server.jar")).hexdigest() == expected


def clear_ornithe_remap_cache(
    panel: DiscPanel, server_id: str, profile: Profile
) -> bool:
    cache_parent = ".fabric/remappedJars"
    cache_name = f"minecraft-{profile.minecraft}-{ORNITHE_LOADER_VERSION}"
    try:
        names = {
            str(entry.get("name", ""))
            for entry in panel.list_files(server_id, cache_parent)
        }
    except RuntimeError:
        return False
    if cache_name not in names:
        return False
    panel.delete_file(server_id, f"{cache_parent}/{cache_name}")
    return True


def install_ornithe_bootstrap(
    panel: DiscPanel,
    server_id: str,
    profile: Profile,
    output_dir: Path,
    java: str,
    timeout_seconds: int,
) -> None:
    archive = prepare_ornithe_archive(profile, output_dir, java)
    server_jar = prepare_ornithe_server_jar(profile, archive, output_dir)
    server_spec = ORNITHE_SERVER_JARS.get(profile.minecraft)
    if not server_spec:
        raise RuntimeError(f"no pinned Minecraft server JAR for {profile.runtime}")
    launcher_path = f"{ornithe_bootstrap_dir(profile)}/fabric-server-launch.jar"
    remote_launcher = (
        panel.get_file(server_id, launcher_path)
        if has_ornithe_launcher_files(panel, server_id, profile)
        else b""
    )
    try:
        validate_ornithe_launcher(remote_launcher, profile.minecraft)
        remote_launcher_valid = True
    except (KeyError, OSError, RuntimeError, zipfile.BadZipFile):
        remote_launcher_valid = False
    if not remote_launcher_valid:
        panel.upload_file(server_id, archive, "")
        panel.extract_archive(server_id, archive.name, timeout_seconds)
        remote_launcher = panel.get_file(server_id, launcher_path)
    validate_ornithe_launcher(remote_launcher, profile.minecraft)
    # This is a bounded, checksum-pinned replacement of an existing stopped
    # server file. Use FileService directly so stale upload staging sessions do
    # not prevent legacy server-JAR repair.
    panel.update_file(server_id, "server.jar", server_jar.read_bytes())
    clear_ornithe_remap_cache(panel, server_id, profile)
    remote_server = panel.get_file(server_id, "server.jar")
    if hashlib.sha256(remote_server).hexdigest() != server_spec["runtimeSha256"]:
        raise RuntimeError(f"DiscPanel Minecraft server digest mismatch for {profile.runtime}")
    if not has_ornithe_bootstrap(panel, server_id, profile):
        raise RuntimeError(f"DiscPanel omitted extracted Ornithe bootstrap for {profile.runtime}")


def allocate_ports(start: int, used: set[int], count: int) -> list[int]:
    ports: list[int] = []
    candidate = start
    while len(ports) < count:
        if candidate > 65535:
            raise SystemExit("DiscPanel has insufficient available TCP ports")
        if candidate not in used and candidate not in RESERVED_GAME_PORTS:
            ports.append(candidate)
            used.add(candidate)
        candidate += 1
    return ports


def wait_until_stopped(
    panel: DiscPanel, server_id: str, runtime: str, timeout_seconds: int
) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        server = panel.get_server(server_id)
        status = server.get("status")
        if status == STATUS_STOPPED:
            return
        if status == STATUS_ERROR:
            raise RuntimeError(f"DiscPanel creation failed for {runtime}: server entered ERROR")
        time.sleep(2)
    raise RuntimeError(f"DiscPanel creation timed out for {runtime}")


def server_id_from_create(response: dict[str, Any]) -> str:
    server = response.get("server")
    if isinstance(server, dict) and server.get("id"):
        return str(server["id"])
    if response.get("id"):
        return str(response["id"])
    raise RuntimeError("DiscPanel CreateServer response omitted the server id")


def reconcile(args: argparse.Namespace) -> int:
    token = os.environ.get(args.token_env)
    if not token:
        raise SystemExit(f"set {args.token_env} in the process environment")
    profiles = desired_profiles(args.manifest)
    panel = DiscPanel(args.url, token, args.request_timeout)
    servers = panel.list_servers()
    by_name: dict[str, dict[str, Any]] = {}
    for server in servers:
        name = str(server.get("name", ""))
        if name in by_name:
            raise RuntimeError(f"DiscPanel contains duplicate server name {name!r}")
        by_name[name] = server

    present: list[Profile] = []
    missing_native: list[Profile] = []
    missing_custom: list[Profile] = []
    incomplete_custom: list[tuple[Profile, str]] = []
    repairable_native: list[tuple[Profile, dict[str, Any]]] = []
    repairable_custom: list[tuple[Profile, dict[str, Any]]] = []
    drifted: list[tuple[Profile, list[str]]] = []
    for profile in profiles:
        server = by_name.get(profile.name)
        if server is None:
            (missing_native if profile.provisioning == "native" else missing_custom).append(profile)
            continue
        if profile.provisioning != "native":
            server = panel.get_server(str(server["id"]))
        differences = drift(profile, server)
        if differences:
            environment_only_drift = is_stopped(server) and all(
                item.startswith("docker environment ") or item.startswith("port=")
                for item in differences
            )
            repairable_differences = all(
                item.startswith("docker environment ")
                or item.startswith("port=")
                or item.startswith("modLoader='MOD_LOADER_VANILLA' expected 'MOD_LOADER_FABRIC'")
                for item in differences
            )
            if profile.provisioning == "native" and environment_only_drift:
                repairable_native.append((profile, server))
            elif (
                profile.provisioning != "native"
                and is_stopped(server)
                and repairable_differences
            ):
                repairable_custom.append((profile, server))
            else:
                drifted.append((profile, differences))
        elif profile.provisioning == "custom-upload" and not has_ornithe_bootstrap(
            panel, str(server["id"]), profile
        ):
            incomplete_custom.append((profile, str(server["id"])))
        else:
            present.append(profile)

    print(
        f"DiscPanel Jammarr matrix: desired={len(profiles)} present={len(present)} "
        f"missing_native={len(missing_native)} missing_custom={len(missing_custom)} "
        f"incomplete_custom={len(incomplete_custom)} "
        f"repairable_native={len(repairable_native)} "
        f"repairable_custom={len(repairable_custom)} drifted={len(drifted)}"
    )
    for profile, differences in drifted:
        print(f"DRIFT {profile.runtime}: {'; '.join(differences)}")
    for profile in missing_custom:
        print(f"MISSING custom Fabric-derived runtime: {profile.runtime}")
    for profile, _ in incomplete_custom:
        print(f"INCOMPLETE custom bootstrap required: {profile.runtime}")
    for profile, _ in repairable_custom:
        print(f"REPAIRABLE custom definition: {profile.runtime}")
    for profile, _ in repairable_native:
        print(f"REPAIRABLE native definition: {profile.runtime}")

    if args.json:
        print(json.dumps({
            "desired": [asdict(profile) for profile in profiles],
            "present": [profile.runtime for profile in present],
            "missingNative": [profile.runtime for profile in missing_native],
            "missingCustom": [profile.runtime for profile in missing_custom],
            "incompleteCustom": [profile.runtime for profile, _ in incomplete_custom],
            "repairableNative": [profile.runtime for profile, _ in repairable_native],
            "repairableCustom": [profile.runtime for profile, _ in repairable_custom],
            "drifted": {profile.runtime: differences for profile, differences in drifted},
        }, indent=2, sort_keys=True))

    if drifted:
        print("Refusing changes while existing server definitions drift.", file=sys.stderr)
        return 2

    launchers_to_verify: list[Profile] = []
    if args.verify_custom_launchers:
        launchers_to_verify.extend(
            profile for profile in profiles if profile.provisioning == "custom-url"
        )
    elif args.apply_custom:
        launchers_to_verify.extend(
            profile
            for profile in missing_custom
            if profile.provisioning == "custom-url"
        )
        launchers_to_verify.extend(
            profile
            for profile, _ in repairable_custom
            if profile.provisioning == "custom-url"
        )
    for profile in dict.fromkeys(launchers_to_verify):
        digest = verify_custom_url_launcher(profile, args.request_timeout)
        print(f"VERIFIED_CUSTOM_LAUNCHER {profile.runtime} sha256={digest}")

    if not args.apply_native and not args.apply_custom:
        for profile in missing_native:
            print(f"WOULD_CREATE {profile.runtime} {profile.docker_image}")
        for profile in missing_custom:
            print(
                f"WOULD_CREATE_CUSTOM {profile.runtime} {profile.docker_image} "
                f"provisioning={profile.provisioning}"
            )
        for profile, _ in incomplete_custom:
            print(f"WOULD_UPLOAD_BOOTSTRAP {profile.runtime}")
        for profile, _ in repairable_custom:
            print(f"WOULD_REPAIR_CUSTOM {profile.runtime}")
        for profile, _ in repairable_native:
            print(f"WOULD_REPAIR_NATIVE {profile.runtime}")
        return 0

    if args.apply_native:
        for profile, server in repairable_native:
            print(f"REPAIR_NATIVE {profile.runtime}", flush=True)
            replacement_port = None
            if server.get("port") in RESERVED_GAME_PORTS:
                start_port, live_used_ports = panel.next_port_state()
                live_used_ports.update(
                    int(item["port"])
                    for item in panel.list_servers()
                    if item.get("port")
                )
                replacement_port = allocate_ports(start_port, live_used_ports, 1)[0]
            panel.update_server(profile, server, replacement_port)
            updated = panel.get_server(str(server["id"]))
            differences = drift(profile, updated)
            if differences:
                raise RuntimeError(
                    f"DiscPanel native repair drifted for {profile.runtime}: "
                    f"{'; '.join(differences)}"
                )
            print(f"REPAIRED_NATIVE {profile.runtime}", flush=True)

    if args.apply_custom:
        for profile, server in repairable_custom:
            print(f"REPAIR_CUSTOM {profile.runtime}", flush=True)
            replacement_port = None
            if server.get("port") in RESERVED_GAME_PORTS:
                start_port, live_used_ports = panel.next_port_state()
                live_used_ports.update(
                    int(item["port"])
                    for item in panel.list_servers()
                    if item.get("port")
                )
                replacement_port = allocate_ports(start_port, live_used_ports, 1)[0]
            panel.update_server(profile, server, replacement_port)
            updated = panel.get_server(str(server["id"]))
            differences = drift(profile, updated)
            if differences:
                raise RuntimeError(
                    f"DiscPanel custom repair drifted for {profile.runtime}: "
                    f"{'; '.join(differences)}"
                )
            if profile.provisioning == "custom-upload" and not has_ornithe_bootstrap(
                panel, str(server["id"]), profile
            ):
                install_ornithe_bootstrap(
                    panel,
                    str(server["id"]),
                    profile,
                    args.custom_output_dir,
                    args.java,
                    args.extract_timeout,
                )
            print(f"REPAIRED_CUSTOM {profile.runtime}", flush=True)

    selected_missing = []
    if args.apply_native:
        selected_missing.extend(missing_native)
    if args.apply_custom:
        selected_missing.extend(missing_custom)
    start_port, used_ports = panel.next_port_state()
    used_ports.update(int(server["port"]) for server in servers if server.get("port"))
    ports = allocate_ports(start_port, used_ports, len(selected_missing))
    for profile, port in zip(selected_missing, ports, strict=True):
        print(f"CREATE {profile.runtime} port={port} image={profile.docker_image}", flush=True)
        response = panel.create_server(profile, port)
        server_id = server_id_from_create(response)
        wait_until_stopped(panel, server_id, profile.runtime, args.create_timeout)
        server = panel.get_server(server_id)
        differences = drift(profile, server)
        if differences:
            raise RuntimeError(
                f"new DiscPanel server {profile.runtime} drifted: {'; '.join(differences)}"
            )
        if profile.provisioning == "custom-upload":
            print(f"UPLOAD_BOOTSTRAP {profile.runtime}", flush=True)
            install_ornithe_bootstrap(
                panel,
                server_id,
                profile,
                args.custom_output_dir,
                args.java,
                args.extract_timeout,
            )
        print(f"CREATED_STOPPED {profile.runtime} port={port}", flush=True)
    if args.apply_custom:
        for profile, server_id in incomplete_custom:
            print(f"UPLOAD_BOOTSTRAP {profile.runtime}", flush=True)
            install_ornithe_bootstrap(
                panel,
                server_id,
                profile,
                args.custom_output_dir,
                args.java,
                args.extract_timeout,
            )
            print(f"BOOTSTRAP_READY {profile.runtime}", flush=True)
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest", type=Path, default=Path("gradle/targets.json")
    )
    parser.add_argument(
        "--url", default=os.environ.get("DISCOPANEL_URL", "http://192.168.1.42:8080")
    )
    parser.add_argument("--token-env", default="DISCOPANEL_TOKEN")
    parser.add_argument("--request-timeout", type=int, default=30)
    parser.add_argument("--create-timeout", type=int, default=900)
    parser.add_argument("--extract-timeout", type=int, default=300)
    parser.add_argument("--java", default="java")
    parser.add_argument(
        "--custom-output-dir",
        type=Path,
        default=Path("build/discopanel-legacy-distributions"),
    )
    parser.add_argument(
        "--apply-native",
        action="store_true",
        help=(
            "create missing native-loader servers and repair stopped managed "
            "runtime environment pins, without uploading mods"
        ),
    )
    parser.add_argument(
        "--apply-custom",
        action="store_true",
        help="create pinned Fabric-derived legacy servers and upload only required bootstrap files",
    )
    parser.add_argument(
        "--verify-custom-launchers",
        action="store_true",
        help="download and verify every pinned custom URL launcher without changing servers",
    )
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(reconcile(parse_args()))
