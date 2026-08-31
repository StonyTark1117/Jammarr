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
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
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
        "environment": {
            "FABRIC_LAUNCHER_URL": "https://meta.babric.glass-launcher.net/v2/versions/loader/b1.7.3/0.16.9/1.0.0-babric.2/server/jar",
        },
    },
    "1.6.4-fabric": {
        "provisioning": "custom-url",
        "environment": {
            "FABRIC_LAUNCHER_URL": "https://meta.legacyfabric.net/v2/versions/loader/1.6.4/0.18.3/1.1.1/server/jar",
        },
    },
    "1.8.9-fabric": {
        "provisioning": "custom-url",
        "environment": {
            "FABRIC_LAUNCHER_URL": "https://meta.legacyfabric.net/v2/versions/loader/1.8.9/0.18.3/1.1.1/server/jar",
        },
    },
    "1.6.4-ornithe": {
        "provisioning": "custom-upload",
        "environment": {
            "FABRIC_LAUNCHER": "1.6.4-ornithe-server-bootstrap/fabric-server-launch.jar"
        },
    },
    "1.8.9-ornithe": {
        "provisioning": "custom-upload",
        "environment": {
            "FABRIC_LAUNCHER": "1.8.9-ornithe-server-bootstrap/fabric-server-launch.jar"
        },
    },
}
ORNITHE_INSTALLER_URL = (
    "https://maven.ornithemc.net/releases/net/ornithemc/ornithe-installer/"
    "0.15.0/ornithe-installer-0.15.0.jar"
)
ORNITHE_INSTALLER_SHA256 = "86b01c48c605e35fba3f4012d2b951ca4ae5f0b7445df8d3152f522b7242389f"
ORNITHE_LOADER_VERSION = "0.19.5"
STATUS_STOPPED = "SERVER_STATUS_STOPPED"
STATUS_ERROR = "SERVER_STATUS_ERROR"


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


def desired_profiles(manifest_path: Path) -> list[Profile]:
    manifest = target_matrix.load_manifest(manifest_path)
    profiles: list[Profile] = []
    for runtime in target_matrix.runtimes(manifest):
        minecraft, loader = runtime["name"].rsplit("-", 1)
        custom = CUSTOM_RUNTIME_CONFIG.get(runtime["name"])
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
                environment=tuple(sorted(custom["environment"].items())) if custom else (),
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
            "description": (
                "Jammarr 1.1.0 release-candidate test runtime; managed from "
                "gradle/targets.json. No 1.1.0 mod artifact installed yet."
            ),
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

    def update_server(self, profile: Profile, server: dict[str, Any]) -> dict[str, Any]:
        return self.call(
            "ServerService",
            "UpdateServer",
            {
                "id": server["id"],
                "name": server["name"],
                "description": server.get("description", ""),
                "port": int(server["port"]),
                "maxPlayers": int(server.get("maxPlayers", 5)),
                "memory": int(server.get("memory", 4096)),
                "modLoader": profile.panel_loader,
                "mcVersion": profile.minecraft,
                "dockerImage": profile.docker_image,
                "autoStart": False,
                "detached": True,
                "dockerOverrides": {"environment": dict(profile.environment)},
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

    def upload_file(self, server_id: str, source: Path, destination: str) -> None:
        chunk_size = 1024 * 1024
        initialized = self.call(
            "UploadService",
            "InitUpload",
            {
                "filename": source.name,
                "totalSize": source.stat().st_size,
                "chunkSize": chunk_size,
            },
        )
        session_id = str(initialized["sessionId"])
        with source.open("rb") as stream:
            chunk_index = 0
            while chunk := stream.read(chunk_size):
                self.call(
                    "UploadService",
                    "UploadChunk",
                    {
                        "sessionId": session_id,
                        "chunkIndex": chunk_index,
                        "data": base64.b64encode(chunk).decode("ascii"),
                    },
                )
                chunk_index += 1
        status = self.call(
            "UploadService", "GetUploadStatus", {"sessionId": session_id}
        )
        if not status.get("completed"):
            raise RuntimeError(f"DiscPanel upload did not complete for {source.name}")
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
    environment = (server.get("dockerOverrides") or {}).get("environment") or {}
    for key, value in profile.environment:
        if environment.get(key) != value:
            differences.append(f"docker environment {key} is not the pinned value")
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


def validate_ornithe_install(install_dir: Path, minecraft: str) -> Path:
    launcher = install_dir / "fabric-server-launch.jar"
    if not launcher.is_file():
        raise RuntimeError(f"Ornithe installer omitted the server launcher for {minecraft}")
    if any("jammarr" in path.name.lower() for path in install_dir.rglob("*")):
        raise RuntimeError("Ornithe bootstrap unexpectedly contains a Jammarr artifact")
    with zipfile.ZipFile(launcher) as archive:
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


def ornithe_bootstrap_dir(profile: Profile) -> str:
    return f"{profile.runtime}-server-bootstrap"


def has_ornithe_bootstrap(
    panel: DiscPanel, server_id: str, profile: Profile
) -> bool:
    names = {str(entry.get("name", "")) for entry in panel.list_files(server_id)}
    bootstrap_dir = ornithe_bootstrap_dir(profile)
    if bootstrap_dir not in names:
        return False
    nested_names = {
        str(entry.get("name", ""))
        for entry in panel.list_files(server_id, bootstrap_dir)
    }
    return "fabric-server-launch.jar" in nested_names and "libraries" in nested_names


def install_ornithe_bootstrap(
    panel: DiscPanel,
    server_id: str,
    profile: Profile,
    output_dir: Path,
    java: str,
    timeout_seconds: int,
) -> None:
    archive = prepare_ornithe_archive(profile, output_dir, java)
    panel.upload_file(server_id, archive, "")
    panel.extract_archive(server_id, archive.name, timeout_seconds)
    if not has_ornithe_bootstrap(panel, server_id, profile):
        raise RuntimeError(f"DiscPanel omitted extracted Ornithe bootstrap for {profile.runtime}")
    local_launcher: bytes
    with zipfile.ZipFile(archive) as source:
        local_launcher = source.read("fabric-server-launch.jar")
    remote_launcher = panel.get_file(
        server_id, f"{ornithe_bootstrap_dir(profile)}/fabric-server-launch.jar"
    )
    if hashlib.sha256(remote_launcher).digest() != hashlib.sha256(local_launcher).digest():
        raise RuntimeError(f"DiscPanel launcher digest mismatch for {profile.runtime}")


def allocate_ports(start: int, used: set[int], count: int) -> list[int]:
    ports: list[int] = []
    candidate = start
    while len(ports) < count:
        if candidate > 65535:
            raise SystemExit("DiscPanel has insufficient available TCP ports")
        if candidate not in used:
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
            if (
                profile.provisioning != "native"
                and is_stopped(server)
                and all(item.startswith("docker environment ") for item in differences)
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
        f"repairable_custom={len(repairable_custom)} drifted={len(drifted)}"
    )
    for profile, differences in drifted:
        print(f"DRIFT {profile.runtime}: {'; '.join(differences)}")
    for profile in missing_custom:
        print(f"MISSING custom Fabric-derived runtime: {profile.runtime}")
    for profile, _ in incomplete_custom:
        print(f"INCOMPLETE custom bootstrap required: {profile.runtime}")
    for profile, _ in repairable_custom:
        print(f"REPAIRABLE custom environment: {profile.runtime}")

    if args.json:
        print(json.dumps({
            "desired": [asdict(profile) for profile in profiles],
            "present": [profile.runtime for profile in present],
            "missingNative": [profile.runtime for profile in missing_native],
            "missingCustom": [profile.runtime for profile in missing_custom],
            "incompleteCustom": [profile.runtime for profile, _ in incomplete_custom],
            "repairableCustom": [profile.runtime for profile, _ in repairable_custom],
            "drifted": {profile.runtime: differences for profile, differences in drifted},
        }, indent=2, sort_keys=True))

    if drifted:
        print("Refusing changes while existing server definitions drift.", file=sys.stderr)
        return 2
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
        return 0

    if args.apply_custom:
        for profile, server in repairable_custom:
            print(f"REPAIR_CUSTOM {profile.runtime}", flush=True)
            panel.update_server(profile, server)
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
        "--url", default=os.environ.get("DISCOPANEL_URL", "http://192.168.1.73:8080")
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
        help="create missing native-loader servers, sequentially, stopped, and without mods",
    )
    parser.add_argument(
        "--apply-custom",
        action="store_true",
        help="create pinned Fabric-derived legacy servers and upload only required bootstrap files",
    )
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(reconcile(parse_args()))
