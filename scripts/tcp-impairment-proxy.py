#!/usr/bin/env python3
"""Small deterministic TCP proxy for Jammarr release impairment tests."""

import argparse
import asyncio
import json
import random
import time
from pathlib import Path


class Profile:
    def __init__(self, name: str, log_path: Path):
        self.name = name
        self.log_path = log_path

    async def delay(self, direction: str, size: int, started: float, state: dict, rng: random.Random) -> None:
        elapsed = time.monotonic() - started
        delay = 0.0
        if self.name == "latency-150ms":
            delay = 0.075
        elif self.name == "jitter-20-250ms":
            delay = rng.uniform(0.020, 0.250)
        elif self.name == "stall-2s" and direction == "server-to-client" and not state.get("stalled") and elapsed >= 3.0:
            state["stalled"] = True
            delay = 2.0
        elif self.name == "client-stalls-250ms" and direction == "server-to-client":
            slot = int(elapsed // 2.0)
            if slot > state.get("stall_slot", -1):
                state["stall_slot"] = slot
                delay = 0.250
        elif self.name == "overload-6s" and direction == "server-to-client" and elapsed < 6.0:
            delay = size / 12_000.0
        if delay > 0:
            with self.log_path.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps({"elapsed_ms": round(elapsed * 1000), "direction": direction,
                                         "bytes": size, "delay_ms": round(delay * 1000)}) + "\n")
            await asyncio.sleep(delay)


async def pump(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, direction: str,
               profile: Profile, seed: int) -> None:
    started = time.monotonic()
    state: dict = {}
    rng = random.Random(seed)
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            await profile.delay(direction, len(data), started, state, rng)
            writer.write(data)
            await writer.drain()
    except (ConnectionError, OSError, asyncio.IncompleteReadError):
        # Clients and test servers are deliberately terminated at scenario
        # boundaries; a half-close/reset is normal proxy teardown.
        pass
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except (ConnectionError, OSError):
            pass


async def main_async(args: argparse.Namespace) -> None:
    profile = Profile(args.profile, args.event_log)
    connection = 0

    async def connected(client_reader: asyncio.StreamReader, client_writer: asyncio.StreamWriter) -> None:
        nonlocal connection
        connection += 1
        identity = connection
        try:
            server_reader, server_writer = await asyncio.open_connection(args.target_host, args.target_port)
        except OSError:
            client_writer.close()
            return
        await asyncio.gather(
            pump(client_reader, server_writer, "client-to-server", profile, identity * 2),
            pump(server_reader, client_writer, "server-to-client", profile, identity * 2 + 1),
        )

    server = await asyncio.start_server(connected, args.listen_host, 0)
    port = server.sockets[0].getsockname()[1]
    temporary = args.port_file.with_suffix(args.port_file.suffix + ".tmp")
    temporary.write_text(str(port), encoding="ascii")
    temporary.replace(args.port_file)
    async with server:
        await server.serve_forever()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-host", default="127.0.0.1")
    parser.add_argument("--target-host", default="127.0.0.1")
    parser.add_argument("--target-port", required=True, type=int)
    parser.add_argument("--profile", required=True, choices=(
        "direct", "latency-150ms", "jitter-20-250ms", "stall-2s",
        "client-stalls-250ms", "overload-6s"))
    parser.add_argument("--port-file", required=True, type=Path)
    parser.add_argument("--event-log", required=True, type=Path)
    args = parser.parse_args()
    args.port_file.parent.mkdir(parents=True, exist_ok=True)
    args.event_log.parent.mkdir(parents=True, exist_ok=True)
    args.event_log.write_text("", encoding="utf-8")
    try:
        asyncio.run(main_async(args))
    finally:
        args.port_file.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
