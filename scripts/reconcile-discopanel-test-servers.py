#!/usr/bin/env python3
"""Reconcile stopped DiscPanel test servers from gradle/targets.json.

Dry-run is the default. Applying creates only runtime profiles that DiscPanel can
install natively. It never starts a server or uploads a Jammarr artifact. Legacy
Fabric, Ornithe, and Babric profiles remain blocked until their custom server
distributions are defined and inspected.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys
import time
import urllib.error
import urllib.request
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
CUSTOM_RUNTIME_NAMES = {
    "b1.7.3-babric",
    "1.6.4-fabric",
    "1.6.4-ornithe",
    "1.8.9-fabric",
    "1.8.9-ornithe",
}
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
    panel_loader: str | None
    provisioning: str


def desired_profiles(manifest_path: Path) -> list[Profile]:
    manifest = target_matrix.load_manifest(manifest_path)
    profiles: list[Profile] = []
    for runtime in target_matrix.runtimes(manifest):
        minecraft, loader = runtime["name"].rsplit("-", 1)
        custom = runtime["name"] in CUSTOM_RUNTIME_NAMES
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
                panel_loader=None if custom else LOADER_ENUM.get(loader),
                provisioning="custom" if custom else "native",
            )
        )
    unsupported = [profile.runtime for profile in profiles if not profile.panel_loader and profile.provisioning == "native"]
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
        if not profile.panel_loader or profile.provisioning != "native":
            raise RuntimeError(f"refusing native creation for {profile.runtime}")
        return self.call(
            "ServerService",
            "CreateServer",
            {
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
            },
        )

    def get_server(self, server_id: str) -> dict[str, Any]:
        return self.call("ServerService", "GetServer", {"id": server_id})["server"]


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
    return differences


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
    blocked_custom: list[Profile] = []
    drifted: list[tuple[Profile, list[str]]] = []
    for profile in profiles:
        server = by_name.get(profile.name)
        if server is None:
            (blocked_custom if profile.provisioning == "custom" else missing_native).append(profile)
            continue
        if profile.provisioning == "custom":
            blocked_custom.append(profile)
            continue
        differences = drift(profile, server)
        if differences:
            drifted.append((profile, differences))
        else:
            present.append(profile)

    print(
        f"DiscPanel Jammarr matrix: desired={len(profiles)} present={len(present)} "
        f"missing_native={len(missing_native)} blocked_custom={len(blocked_custom)} "
        f"drifted={len(drifted)}"
    )
    for profile, differences in drifted:
        print(f"DRIFT {profile.runtime}: {'; '.join(differences)}")
    for profile in blocked_custom:
        print(f"BLOCKED custom distribution required: {profile.runtime}")

    if args.json:
        print(json.dumps({
            "desired": [asdict(profile) for profile in profiles],
            "present": [profile.runtime for profile in present],
            "missingNative": [profile.runtime for profile in missing_native],
            "blockedCustom": [profile.runtime for profile in blocked_custom],
            "drifted": {profile.runtime: differences for profile, differences in drifted},
        }, indent=2, sort_keys=True))

    if drifted:
        print("Refusing changes while existing server definitions drift.", file=sys.stderr)
        return 2
    if not args.apply_native:
        for profile in missing_native:
            print(f"WOULD_CREATE {profile.runtime} {profile.docker_image}")
        return 0

    start_port, used_ports = panel.next_port_state()
    used_ports.update(int(server["port"]) for server in servers if server.get("port"))
    ports = allocate_ports(start_port, used_ports, len(missing_native))
    for profile, port in zip(missing_native, ports, strict=True):
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
        print(f"CREATED_STOPPED {profile.runtime} port={port}", flush=True)
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
    parser.add_argument(
        "--apply-native",
        action="store_true",
        help="create missing native-loader servers, sequentially, stopped, and without mods",
    )
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(reconcile(parse_args()))
