#!/usr/bin/env python3
"""Minimal offline Minecraft client used to prove required-mod rejection.

The probe intentionally implements no loader protocol and never sends a Jammarr
hello. It advances far enough through vanilla login/configuration for the server
to reject it, then requires a human-readable mod/protocol reason on the wire.
"""

from __future__ import annotations

import argparse
import json
import socket
import struct
import sys
import time
import uuid
import zlib


MAX_PACKET = 8 * 1024 * 1024


def varint(value: int) -> bytes:
    value &= 0xFFFFFFFF
    encoded = bytearray()
    while True:
        current = value & 0x7F
        value >>= 7
        encoded.append(current | (0x80 if value else 0))
        if not value:
            return bytes(encoded)


def decode_varint(data: bytes, offset: int = 0) -> tuple[int, int]:
    value = 0
    for index in range(5):
        if offset + index >= len(data):
            raise ValueError("truncated VarInt")
        current = data[offset + index]
        value |= (current & 0x7F) << (7 * index)
        if not current & 0x80:
            if value & 0x80000000:
                value -= 0x100000000
            return value, offset + index + 1
    raise ValueError("VarInt exceeds five bytes")


def minecraft_string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return varint(len(encoded)) + encoded


def read_exact(connection: socket.socket, length: int) -> bytes:
    result = bytearray()
    while len(result) < length:
        chunk = connection.recv(length - len(result))
        if not chunk:
            raise EOFError("connection closed")
        result.extend(chunk)
    return bytes(result)


def read_varint(connection: socket.socket) -> int:
    encoded = bytearray()
    for _ in range(5):
        current = read_exact(connection, 1)[0]
        encoded.append(current)
        if not current & 0x80:
            return decode_varint(bytes(encoded))[0]
    raise ValueError("VarInt exceeds five bytes")


class ProtocolConnection:
    def __init__(self, connection: socket.socket) -> None:
        self.connection = connection
        self.compression_threshold: int | None = None
        self.transcript = bytearray()

    def send(self, packet_id: int, payload: bytes = b"") -> None:
        body = varint(packet_id) + payload
        if self.compression_threshold is None:
            framed = body
        elif len(body) >= self.compression_threshold:
            framed = varint(len(body)) + zlib.compress(body)
        else:
            framed = varint(0) + body
        self.connection.sendall(varint(len(framed)) + framed)

    def receive(self) -> tuple[int, bytes]:
        frame_length = read_varint(self.connection)
        if frame_length < 0 or frame_length > MAX_PACKET:
            raise ValueError("invalid packet length")
        frame = read_exact(self.connection, frame_length)
        if self.compression_threshold is not None:
            uncompressed_length, offset = decode_varint(frame)
            if uncompressed_length:
                if uncompressed_length > MAX_PACKET:
                    raise ValueError("compressed packet exceeds limit")
                frame = zlib.decompress(frame[offset:])
                if len(frame) != uncompressed_length:
                    raise ValueError("compressed packet length mismatch")
            else:
                frame = frame[offset:]
        packet_id, offset = decode_varint(frame)
        payload = frame[offset:]
        self.transcript.extend(payload)
        return packet_id, payload


def handshake(protocol: int, host: str, port: int, next_state: int) -> bytes:
    return varint(protocol) + minecraft_string(host) + struct.pack(">H", port) + varint(next_state)


def status(host: str, port: int, timeout: float) -> dict:
    with socket.create_connection((host, port), timeout=timeout) as raw:
        raw.settimeout(timeout)
        connection = ProtocolConnection(raw)
        connection.send(0, handshake(-1, host, port, 1))
        connection.send(0)
        packet_id, payload = connection.receive()
        if packet_id != 0:
            raise RuntimeError("unexpected status response packet")
        length, offset = decode_varint(payload)
        response = payload[offset : offset + length]
        if len(response) != length:
            raise RuntimeError("truncated status response")
        return json.loads(response.decode("utf-8"))


def login_payload(protocol: int, name: str) -> bytes:
    payload = bytearray(minecraft_string(name))
    if protocol <= 5:
        return bytes(payload)
    if protocol <= 763:
        payload.append(0)  # Optional UUID is absent through Minecraft 1.20.1.
    else:
        payload.extend(uuid.uuid5(uuid.NAMESPACE_DNS, "jammarr-login-probe").bytes)
    return bytes(payload)


def client_information(protocol: int) -> bytes:
    payload = minecraft_string("en_us") + bytes((2,)) + varint(0) + bytes((1, 0)) + varint(1) + bytes((0, 0))
    if protocol >= 775:
        payload += varint(0)  # ParticleStatus.ALL, added in Minecraft 26.1.
    return payload


def visible_text(data: bytes) -> str:
    return "".join(chr(value) if 32 <= value < 127 else " " for value in data)


def rejection_is_clear(transcript: bytes) -> bool:
    text = visible_text(transcript).lower()
    markers = (
        "jammarr is required",
        "jammarr protocol",
        "require forge to be installed",
        "server that is running neoforge",
        "network.negotiation.failure",
        "incompatible mod",
        "mod mismatch",
    )
    return any(marker in text for marker in markers)


def probe(
    host: str,
    port: int,
    timeout: float,
    protocol_override: int | None = None,
    version_override: str | None = None,
) -> dict:
    if protocol_override is None:
        status_response = status(host, port, timeout)
        version = status_response.get("version") or {}
        protocol = int(version.get("protocol"))
        version_name = str(version.get("name", "unknown"))
    else:
        protocol = protocol_override
        version_name = version_override or f"protocol-{protocol}"
    started = time.monotonic()
    state = "login"
    packet_counts: dict[str, int] = {}
    closed = False

    with socket.create_connection((host, port), timeout=timeout) as raw:
        raw.settimeout(1.0)
        connection = ProtocolConnection(raw)
        connection.send(0, handshake(protocol, host, port, 2))
        connection.send(0, login_payload(protocol, "JammarrProbe"))

        while time.monotonic() - started < timeout:
            try:
                packet_id, payload = connection.receive()
            except socket.timeout:
                continue
            except (ConnectionResetError, EOFError, BrokenPipeError):
                closed = True
                break
            packet_counts[f"{state}:{packet_id}"] = packet_counts.get(f"{state}:{packet_id}", 0) + 1

            if state == "login":
                if packet_id == 0:  # Login disconnect.
                    closed = True
                    break
                if packet_id == 3:  # Compression threshold.
                    threshold, _ = decode_varint(payload)
                    connection.compression_threshold = threshold
                elif packet_id == 2:  # Login success.
                    if protocol >= 764:
                        connection.send(3)  # Login acknowledged.
                        state = "configuration"
                        connection.send(0, client_information(protocol))
                    else:
                        state = "play"
                elif packet_id == 4:  # Login custom query: explicitly decline it.
                    transaction_id, _ = decode_varint(payload)
                    connection.send(2, varint(transaction_id) + b"\x00")
            elif state == "configuration":
                if protocol == 764:
                    if packet_id == 1:  # Disconnect.
                        closed = True
                        break
                    if packet_id == 2:  # Finish configuration.
                        connection.send(2)
                        state = "play"
                    elif packet_id == 3 and len(payload) >= 8:  # Keepalive.
                        connection.send(3, payload[:8])
                    elif packet_id == 4 and len(payload) >= 4:  # Ping.
                        connection.send(4, payload[:4])
                else:  # 1.20.5 through 1.21.1 packet order.
                    if packet_id == 2:  # Disconnect.
                        closed = True
                        break
                    if packet_id == 3:  # Finish configuration.
                        connection.send(3)
                        state = "play"
                    elif packet_id == 4 and len(payload) >= 8:  # Keepalive.
                        connection.send(4, payload[:8])
                    elif packet_id == 5 and len(payload) >= 4:  # Ping.
                        connection.send(5, payload[:4])
                    elif packet_id == 14:  # Known packs; select none.
                        connection.send(7, varint(0))

    clear = rejection_is_clear(bytes(connection.transcript))
    return {
        "version": version_name,
        "protocol": protocol,
        "closed": closed,
        "clearReason": clear,
        "packets": packet_counts,
        "transcript": visible_text(bytes(connection.transcript))[-1000:],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("host")
    parser.add_argument("port", type=int)
    parser.add_argument("--timeout", type=float, default=25.0)
    parser.add_argument("--protocol", type=int)
    parser.add_argument("--version")
    arguments = parser.parse_args()
    try:
        result = probe(
            arguments.host,
            arguments.port,
            arguments.timeout,
            arguments.protocol,
            arguments.version,
        )
    except Exception as error:
        print(json.dumps({"error": f"{type(error).__name__}: {error}"}), file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0 if result["closed"] and result["clearReason"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
