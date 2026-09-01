#!/usr/bin/env python3
"""Resolve Mojang/Prism client inputs without mutating Prism's shared cache."""

from __future__ import annotations

from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlparse
from urllib.request import urlopen


SHA1 = re.compile(r"^[0-9a-f]{40}$")


def sha1_file(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def maven_path(root: Path, coordinate: str) -> Path:
    coordinate, separator, extension = coordinate.partition("@")
    extension = extension if separator else "jar"
    parts = coordinate.split(":")
    if len(parts) not in {3, 4}:
        raise SystemExit(f"Unsupported Prism library coordinate: {coordinate}")
    group, artifact, version = parts[:3]
    classifier = parts[3] if len(parts) == 4 else ""
    filename = f"{artifact}-{version}{('-' + classifier) if classifier else ''}.{extension}"
    return root.joinpath(*group.split("."), artifact, version, filename)


def verified(path: Path, expected_sha1: str, expected_size: int) -> bool:
    return (
        path.is_file()
        and path.stat().st_size == expected_size
        and sha1_file(path) == expected_sha1
    )


def linux_rule_matches(rule: dict[str, Any]) -> bool:
    if rule.get("features"):
        return False
    os_rule = rule.get("os")
    if not os_rule:
        return True
    return os_rule.get("name") in {None, "linux"}


def library_allowed(library: dict[str, Any]) -> bool:
    rules = library.get("rules", [])
    if not rules:
        return True
    allowed = False
    for rule in rules:
        if linux_rule_matches(rule):
            allowed = rule.get("action") == "allow"
    return allowed


def artifact_descriptor(value: dict[str, Any], label: str) -> tuple[str, str, int]:
    url = value.get("url")
    expected_sha1 = value.get("sha1")
    expected_size = value.get("size")
    if not isinstance(url, str) or urlparse(url).scheme not in {"https", "file"}:
        raise SystemExit(f"{label} does not declare an HTTPS artifact URL")
    if not isinstance(expected_sha1, str) or not SHA1.fullmatch(expected_sha1):
        raise SystemExit(f"{label} does not declare a valid SHA-1")
    if not isinstance(expected_size, int) or expected_size < 0:
        raise SystemExit(f"{label} does not declare a valid size")
    return url, expected_sha1, expected_size


class VerifiedCache:
    """Read verified shared bytes or populate an isolated verified cache."""

    def __init__(
        self,
        shared_root: Path,
        cache_root: Path,
        metadata_root_url: str = "https://meta.prismlauncher.org/v1",
        asset_objects_base_url: str = "https://resources.download.minecraft.net",
    ) -> None:
        self.shared_root = shared_root.resolve()
        self.cache_root = cache_root.resolve()
        self.metadata_root_url = metadata_root_url.rstrip("/")
        self.asset_objects_base_url = asset_objects_base_url.rstrip("/")
        self.sources: Counter[str] = Counter()

    def _download(self, url: str, destination: Path, sha1: str, size: int, label: str) -> Path:
        if verified(destination, sha1, size):
            self.sources["isolated-cache"] += 1
            return destination
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(f".{destination.name}.part-{os.getpid()}")
        try:
            try:
                with urlopen(url, timeout=60) as response, temporary.open("wb") as output:
                    shutil.copyfileobj(response, output, 1024 * 1024)
            except (HTTPError, URLError, TimeoutError, OSError) as exc:
                raise SystemExit(f"Cannot download {label} from {url}: {exc}") from exc
            if not verified(temporary, sha1, size):
                actual_size = temporary.stat().st_size if temporary.exists() else -1
                actual_sha1 = sha1_file(temporary) if temporary.is_file() else "missing"
                raise SystemExit(
                    f"Downloaded {label} failed verification: "
                    f"size={actual_size}/{size} sha1={actual_sha1}/{sha1}"
                )
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)
        self.sources["downloaded"] += 1
        return destination

    def artifact(
        self,
        shared_path: Path,
        isolated_path: Path,
        descriptor: dict[str, Any],
        label: str,
    ) -> Path:
        url, expected_sha1, expected_size = artifact_descriptor(descriptor, label)
        if verified(shared_path, expected_sha1, expected_size):
            self.sources["shared-cache"] += 1
            return shared_path
        return self._download(url, isolated_path, expected_sha1, expected_size, label)

    def library(self, library: dict[str, Any]) -> Path:
        coordinate = library.get("name")
        if not isinstance(coordinate, str):
            raise SystemExit(f"Prism library has no coordinate: {library!r}")
        descriptor = library.get("downloads", {}).get("artifact")
        if not isinstance(descriptor, dict):
            raise SystemExit(f"Prism library has no artifact download: {coordinate}")
        return self.artifact(
            maven_path(self.shared_root / "libraries", coordinate),
            maven_path(self.cache_root / "libraries", coordinate),
            descriptor,
            f"library {coordinate}",
        )

    def native_library(self, library: dict[str, Any]) -> Path | None:
        coordinate = library.get("name")
        natives = library.get("natives")
        if not isinstance(coordinate, str) or not isinstance(natives, dict):
            return None
        classifier = natives.get("linux")
        if not isinstance(classifier, str) or not classifier:
            return None
        classifier = classifier.replace("${arch}", "64")
        descriptor = library.get("downloads", {}).get("classifiers", {}).get(classifier)
        if not isinstance(descriptor, dict):
            raise SystemExit(
                f"Prism library {coordinate} has no Linux native download {classifier}"
            )
        base, separator, extension = coordinate.partition("@")
        native_coordinate = f"{base}:{classifier}"
        if separator:
            native_coordinate += f"@{extension}"
        return self.artifact(
            maven_path(self.shared_root / "libraries", native_coordinate),
            maven_path(self.cache_root / "libraries", native_coordinate),
            descriptor,
            f"native library {native_coordinate}",
        )

    def component_metadata(self, uid: str, version: str) -> dict[str, Any]:
        relative = Path("meta") / uid / f"{version}.json"
        shared = self.shared_root / relative
        isolated = self.cache_root / relative
        for path, source in ((shared, "shared-metadata"), (isolated, "isolated-metadata")):
            try:
                value = json.loads(path.read_text("utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            if value.get("uid") == uid and value.get("version") == version:
                self.sources[source] += 1
                return value
        url = (
            f"{self.metadata_root_url}/"
            f"{quote(uid, safe='')}/{quote(version, safe='')}.json"
        )
        try:
            with urlopen(url, timeout=30) as response:
                value = json.loads(response.read().decode("utf-8"))
        except (HTTPError, URLError, TimeoutError, OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise SystemExit(f"Cannot obtain Prism metadata {uid} {version} from {url}: {exc}") from exc
        if value.get("uid") != uid or value.get("version") != version:
            raise SystemExit(f"Prism metadata identity mismatch for {uid} {version} from {url}")
        isolated.parent.mkdir(parents=True, exist_ok=True)
        isolated.write_text(json.dumps(value, sort_keys=True) + "\n", encoding="utf-8")
        self.sources["downloaded-metadata"] += 1
        return value

    def assets(self, minecraft_metadata: dict[str, Any]) -> tuple[Path, str, int]:
        descriptor = minecraft_metadata.get("assetIndex")
        if not isinstance(descriptor, dict):
            raise SystemExit("Minecraft metadata does not declare an asset index")
        index_id = descriptor.get("id")
        if not isinstance(index_id, str) or not index_id:
            raise SystemExit("Minecraft metadata asset index has no id")
        shared_index = self.shared_root / "assets" / "indexes" / f"{index_id}.json"
        isolated_index = self.cache_root / "assets" / "indexes" / f"{index_id}.json"
        index_path = self.artifact(
            shared_index,
            isolated_index,
            descriptor,
            f"asset index {index_id}",
        )
        try:
            index = json.loads(index_path.read_text("utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise SystemExit(f"Cannot read verified asset index {index_path}: {exc}") from exc
        objects = index.get("objects")
        if not isinstance(objects, dict):
            raise SystemExit(f"Asset index {index_id} has no object map")
        isolated_index.parent.mkdir(parents=True, exist_ok=True)
        if index_path != isolated_index:
            shutil.copyfile(index_path, isolated_index)
        for logical_name, value in objects.items():
            if not isinstance(value, dict):
                raise SystemExit(f"Asset {logical_name!r} has an invalid descriptor")
            digest = value.get("hash")
            size = value.get("size")
            if not isinstance(digest, str) or not SHA1.fullmatch(digest):
                raise SystemExit(f"Asset {logical_name!r} has an invalid SHA-1")
            if not isinstance(size, int) or size < 0:
                raise SystemExit(f"Asset {logical_name!r} has an invalid size")
            relative = Path("assets") / "objects" / digest[:2] / digest
            destination = self.cache_root / relative
            if verified(destination, digest, size):
                self.sources["isolated-cache"] += 1
                continue
            shared_object = self.shared_root / relative
            if verified(shared_object, digest, size):
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.unlink(missing_ok=True)
                destination.symlink_to(shared_object)
                self.sources["shared-cache"] += 1
                continue
            self._download(
                f"{self.asset_objects_base_url}/{digest[:2]}/{digest}",
                destination,
                digest,
                size,
                f"asset object {logical_name}",
            )
        return self.cache_root / "assets", index_id, len(objects)

    def attestation(self) -> dict[str, Any]:
        return {
            "artifactSourceCounts": dict(sorted(self.sources.items())),
            "sharedCacheMutated": False,
            "allArtifactSha1AndSizeVerified": True,
        }
