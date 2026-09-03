#!/usr/bin/env python3
"""Write a minimal, deterministic Minecraft servers.dat for Prism profiles."""
from __future__ import annotations

import struct
import sys
from pathlib import Path


def string(value: str) -> bytes:
    raw = value.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def tag_string(name: str, value: str) -> bytes:
    return b"\x08" + string(name) + string(value)


def tag_byte(name: str, value: int) -> bytes:
    return b"\x01" + string(name) + bytes((value & 0xFF,))


def servers_dat(name: str, address: str) -> bytes:
    entry = tag_byte("hidden", 0) + tag_string("ip", address) + tag_string("name", name) + b"\x00"
    payload = b"\x09" + string("servers") + b"\x0a" + struct.pack(">i", 1) + entry
    return b"\x0a" + string("") + payload + b"\x00"


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: configure-prism-server-list.py SERVERS.DAT NAME ADDRESS", file=sys.stderr)
        return 2
    # Keep argument handling explicit so paths containing spaces remain safe.
    target = Path(sys.argv[1])
    name = sys.argv[2]
    address = sys.argv[3]
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(servers_dat(name, address))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
