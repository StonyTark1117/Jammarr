#!/usr/bin/env python3
"""Resolve one exact Mojang server JAR into a verified project cache."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import urlopen


DEFAULT_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
SAFE_VERSION = re.compile(r"^[A-Za-z0-9._-]+$")


def sha1_bytes(payload: bytes) -> str:
    return hashlib.sha1(payload).hexdigest()


def sha1_file(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(url: str, description: str) -> bytes:
    try:
        with urlopen(url, timeout=60) as response:
            return response.read()
    except (HTTPError, URLError, TimeoutError, OSError) as exc:
        raise SystemExit(f"Cannot download {description} from {url}: {exc}") from exc


def parse_json(payload: bytes, description: str) -> dict[str, Any]:
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SystemExit(f"{description} is not valid UTF-8 JSON: {exc}") from exc
    if not isinstance(value, dict):
        raise SystemExit(f"{description} must be a JSON object")
    return value


def require_download(value: Any, description: str) -> tuple[str, str, int]:
    if not isinstance(value, dict):
        raise SystemExit(f"{description} is missing")
    url, sha1, size = value.get("url"), value.get("sha1"), value.get("size")
    if not isinstance(url, str) or not url:
        raise SystemExit(f"{description} has no URL")
    if not isinstance(sha1, str) or not re.fullmatch(r"[0-9a-f]{40}", sha1):
        raise SystemExit(f"{description} has no valid SHA-1")
    if not isinstance(size, int) or size <= 0:
        raise SystemExit(f"{description} has no valid size")
    return url, sha1, size


def verified_payload(url: str, sha1: str, description: str) -> bytes:
    payload = fetch(url, description)
    actual = sha1_bytes(payload)
    if actual != sha1:
        raise SystemExit(
            f"{description} SHA-1 mismatch: expected {sha1}, downloaded {actual}"
        )
    return payload


def install_verified(path: Path, payload: bytes, sha1: str, size: int) -> None:
    if len(payload) != size:
        raise SystemExit(
            f"Server JAR size mismatch: expected {size}, downloaded {len(payload)}"
        )
    if sha1_bytes(payload) != sha1:
        raise SystemExit("Server JAR payload changed after verification")
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def resolve_server(
    version: str, cache_root: Path, manifest_url: str = DEFAULT_MANIFEST
) -> Path:
    if not SAFE_VERSION.fullmatch(version):
        raise SystemExit(f"Minecraft version contains unsupported characters: {version!r}")
    manifest = parse_json(fetch(manifest_url, "Mojang version manifest"), "version manifest")
    entry = next(
        (
            candidate
            for candidate in manifest.get("versions", [])
            if isinstance(candidate, dict) and candidate.get("id") == version
        ),
        None,
    )
    if entry is None:
        raise SystemExit(f"Minecraft {version} is absent from the Mojang version manifest")
    metadata_url = entry.get("url")
    metadata_sha1 = entry.get("sha1")
    if not isinstance(metadata_url, str) or not isinstance(metadata_sha1, str):
        raise SystemExit(f"Minecraft {version} manifest entry lacks metadata verification")
    metadata_payload = verified_payload(
        metadata_url, metadata_sha1, f"Minecraft {version} metadata"
    )
    metadata = parse_json(metadata_payload, f"Minecraft {version} metadata")
    if metadata.get("id") != version:
        raise SystemExit(f"Downloaded metadata does not describe Minecraft {version}")
    downloads = metadata.get("downloads")
    server = downloads.get("server") if isinstance(downloads, dict) else None
    server_url, server_sha1, server_size = require_download(
        server, f"Minecraft {version} server download"
    )

    destination = cache_root / version / "server.jar"
    cache_reused = (
        destination.is_file()
        and destination.stat().st_size == server_size
        and sha1_file(destination) == server_sha1
    )
    if not cache_reused:
        install_verified(
            destination,
            verified_payload(server_url, server_sha1, f"Minecraft {version} server JAR"),
            server_sha1,
            server_size,
        )
    if destination.stat().st_size != server_size or sha1_file(destination) != server_sha1:
        raise SystemExit(f"Cached Minecraft {version} server JAR failed final verification")

    attestation = {
        "schemaVersion": 1,
        "minecraftVersion": version,
        "source": "Official Mojang version manifest",
        "manifestUrl": manifest_url,
        "metadataUrl": metadata_url,
        "metadataSha1": metadata_sha1,
        "serverUrl": server_url,
        "serverSha1": server_sha1,
        "serverSize": server_size,
        "serverJar": str(destination.resolve()),
        "cacheReused": cache_reused,
        "jammarrPresent": False,
    }
    attestation_path = destination.with_name("server-attestation.json")
    attestation_path.write_text(json.dumps(attestation, indent=2) + "\n", encoding="utf-8")
    return attestation_path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--cache-root", type=Path, default=Path("build/vanilla-server-cache"))
    parser.add_argument("--manifest-url", default=DEFAULT_MANIFEST)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    print(resolve_server(args.minecraft, args.cache_root, args.manifest_url))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
