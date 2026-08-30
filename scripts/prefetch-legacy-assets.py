#!/usr/bin/env python3
"""Populate a ForgeGradle legacy asset cache over checksum-verified HTTPS."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


ASSET_BASE_URL = "https://resources.download.minecraft.net"


def sha1(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def valid(path: Path, expected_hash: str, expected_size: int | None) -> bool:
    return (
        path.is_file()
        and (expected_size is None or path.stat().st_size == expected_size)
        and sha1(path) == expected_hash
    )


def download(destination: Path, expected_hash: str, expected_size: int | None) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    url = f"{ASSET_BASE_URL}/{expected_hash[:2]}/{expected_hash}"
    last_error: Exception | None = None
    for attempt in range(1, 4):
        temporary: Path | None = None
        try:
            request = Request(url, headers={"User-Agent": "Jammarr legacy asset prefetch/1.1.0"})
            with urlopen(request, timeout=60) as response:
                if response.status != 200:
                    raise RuntimeError(f"HTTP {response.status} for {url}")
                with tempfile.NamedTemporaryFile(
                    dir=destination.parent, prefix=f".{expected_hash}.", delete=False
                ) as output:
                    temporary = Path(output.name)
                    while chunk := response.read(1024 * 1024):
                        output.write(chunk)
            if not valid(temporary, expected_hash, expected_size):
                raise RuntimeError(f"checksum or size mismatch for {url}")
            os.replace(temporary, destination)
            return
        except (HTTPError, URLError, OSError, RuntimeError) as error:
            last_error = error
            if temporary is not None:
                temporary.unlink(missing_ok=True)
            if attempt < 3:
                time.sleep(attempt)
    raise RuntimeError(f"failed to fetch {url}: {last_error}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets-dir", required=True, type=Path)
    parser.add_argument("--index", required=True)
    args = parser.parse_args()

    index_path = args.assets_dir / "indexes" / f"{args.index}.json"
    if not index_path.is_file():
        parser.error(f"asset index does not exist: {index_path}")
    document = json.loads(index_path.read_text(encoding="utf-8"))
    objects = document.get("objects")
    if not isinstance(objects, dict):
        parser.error(f"asset index has no object map: {index_path}")

    unique: dict[str, int | None] = {}
    for name, metadata in objects.items():
        if not isinstance(metadata, dict):
            raise RuntimeError(f"invalid metadata for asset {name}")
        expected_hash = metadata.get("hash")
        expected_size = metadata.get("size")
        if not isinstance(expected_hash, str) or len(expected_hash) != 40:
            raise RuntimeError(f"invalid SHA-1 for asset {name}")
        if expected_size is not None and not isinstance(expected_size, int):
            raise RuntimeError(f"invalid size for asset {name}")
        unique[expected_hash] = expected_size

    downloaded = 0
    objects_dir = args.assets_dir / "objects"
    for position, (expected_hash, expected_size) in enumerate(sorted(unique.items()), start=1):
        destination = objects_dir / expected_hash[:2] / expected_hash
        if valid(destination, expected_hash, expected_size):
            continue
        download(destination, expected_hash, expected_size)
        downloaded += 1
        if downloaded == 1 or downloaded % 50 == 0:
            print(f"Downloaded {downloaded} missing legacy assets ({position}/{len(unique)} checked)")

    print(f"Legacy asset cache verified: {len(unique)} objects, {downloaded} downloaded")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
