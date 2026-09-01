#!/usr/bin/env python3
"""Reconstruct the newest chronological tail from a bounded PCM trace."""

from __future__ import annotations

import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import time


SEGMENT = re.compile(r"^(?P<session>.+)-(?P<sequence>[0-9]{5})\.s16le$")


def newest_session(directory: Path) -> list[Path]:
    sessions: dict[str, list[tuple[int, Path]]] = defaultdict(list)
    legacy: list[Path] = []
    for path in directory.glob("*.s16le"):
        if not path.is_file():
            continue
        match = SEGMENT.fullmatch(path.name)
        if match:
            sessions[match.group("session")].append((int(match.group("sequence")), path))
        else:
            legacy.append(path)
    candidates: list[tuple[int, list[Path]]] = []
    for values in sessions.values():
        ordered = [path for _, path in sorted(values)]
        candidates.append((max(path.stat().st_mtime_ns for path in ordered), ordered))
    for path in legacy:
        candidates.append((path.stat().st_mtime_ns, [path]))
    if not candidates:
        raise SystemExit(f"No PCM trace files exist in {directory}")
    return max(candidates, key=lambda value: value[0])[1]


def extract(paths: list[Path], output: Path, requested: int) -> int:
    available = sum(path.stat().st_size for path in paths)
    remaining = min(requested, available)
    start_index = len(paths) - 1
    start_offset = paths[start_index].stat().st_size
    while start_index >= 0 and remaining > 0:
        size = paths[start_index].stat().st_size
        take = min(remaining, size)
        start_offset = size - take
        remaining -= take
        if remaining > 0:
            start_index -= 1
    output.parent.mkdir(parents=True, exist_ok=True)
    written = 0
    with output.open("wb") as destination:
        for index in range(max(0, start_index), len(paths)):
            with paths[index].open("rb") as source:
                if index == start_index:
                    source.seek(start_offset)
                while True:
                    block = source.read(1024 * 1024)
                    if not block:
                        break
                    destination.write(block)
                    written += len(block)
    return written


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--bytes", type=int, required=True)
    parser.add_argument("--maximum-age-seconds", type=float, default=15.0)
    args = parser.parse_args()
    if args.bytes < 1:
        parser.error("--bytes must be positive")
    if args.maximum_age_seconds <= 0:
        parser.error("--maximum-age-seconds must be positive")
    paths = newest_session(args.directory)
    newest_mtime_ns = max(path.stat().st_mtime_ns for path in paths)
    age_ms = max(0, (time.time_ns() - newest_mtime_ns) // 1_000_000)
    if age_ms > args.maximum_age_seconds * 1000:
        raise SystemExit(
            f"Newest PCM trace in {args.directory} is stale by {age_ms} ms"
        )
    written = extract(paths, args.output, args.bytes)
    print(json.dumps({
        "ageMs": age_ms,
        "bytes": written,
        "newestMtimeNs": newest_mtime_ns,
        "output": str(args.output),
        "segments": [str(path) for path in paths],
    }, sort_keys=True))


if __name__ == "__main__":
    main()
