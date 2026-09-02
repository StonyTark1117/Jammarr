#!/usr/bin/env python3
"""Hold one loopback Minecraft connection until a release file appears."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import select
import socket
import threading
import time


class DeferredConnectionRelay:
    def __init__(self, target: str) -> None:
        host, separator, port_text = target.rpartition(":")
        if not separator or host not in {"127.0.0.1", "localhost"} or not port_text.isdigit():
            raise ValueError("relay target must be a loopback host:port")
        port = int(port_text)
        if not 1 <= port <= 65535:
            raise ValueError("relay target port is invalid")
        self.target = (host, port)
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.listener.settimeout(0.2)
        self.stop_event = threading.Event()
        self.connections: list[socket.socket] = []

    @property
    def endpoint(self) -> str:
        return f"127.0.0.1:{self.listener.getsockname()[1]}"

    def stop(self) -> None:
        self.stop_event.set()
        for connection in self.connections:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        self.listener.close()

    def run(self, release_file: Path) -> None:
        client: socket.socket | None = None
        while not self.stop_event.is_set() and client is None:
            try:
                client, _ = self.listener.accept()
            except socket.timeout:
                continue
        if client is None:
            return
        self.connections.append(client)
        while not self.stop_event.is_set() and not release_file.is_file():
            time.sleep(0.05)
        if self.stop_event.is_set():
            return
        upstream = socket.create_connection(self.target, timeout=10)
        upstream.settimeout(None)
        self.connections.append(upstream)
        while not self.stop_event.is_set():
            readable, _, _ = select.select((client, upstream), (), (), 0.2)
            for source in readable:
                destination = upstream if source is client else client
                payload = source.recv(65536)
                if not payload:
                    return
                destination.sendall(payload)


def write_endpoint(path: Path, endpoint: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    temporary.write_text(endpoint + "\n", encoding="utf-8")
    temporary.replace(path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True)
    parser.add_argument("--endpoint-file", type=Path, required=True)
    parser.add_argument("--release-file", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        relay = DeferredConnectionRelay(args.target)
    except ValueError as error:
        raise SystemExit(str(error)) from error

    write_endpoint(args.endpoint_file, relay.endpoint)
    print(f"DEFERRED_RELAY_READY endpoint={relay.endpoint}", flush=True)
    try:
        relay.run(args.release_file)
    finally:
        relay.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
