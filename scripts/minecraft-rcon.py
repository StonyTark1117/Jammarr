#!/usr/bin/env python3
"""Send one authenticated Minecraft RCON command using only the Python stdlib."""

import socket
import struct
import sys


def receive_exact(connection, length):
    result = bytearray()
    while len(result) < length:
        block = connection.recv(length - len(result))
        if not block:
            raise OSError("RCON connection closed")
        result.extend(block)
    return bytes(result)


def send_packet(connection, request_id, packet_type, body):
    payload = struct.pack("<ii", request_id, packet_type) + body.encode("utf-8") + b"\0\0"
    connection.sendall(struct.pack("<i", len(payload)) + payload)


def receive_packet(connection):
    length = struct.unpack("<i", receive_exact(connection, 4))[0]
    if length < 10 or length > 4 * 1024 * 1024:
        raise OSError("invalid RCON packet length")
    payload = receive_exact(connection, length)
    request_id, packet_type = struct.unpack("<ii", payload[:8])
    if payload[-2:] != b"\0\0":
        raise OSError("invalid RCON packet terminator")
    return request_id, packet_type, payload[8:-2].decode("utf-8", "replace")


def main():
    if len(sys.argv) != 5:
        print("usage: minecraft-rcon.py HOST PORT PASSWORD COMMAND", file=sys.stderr)
        return 2
    host, port, password, command = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
    with socket.create_connection((host, port), timeout=5) as connection:
        connection.settimeout(5)
        send_packet(connection, 91, 3, password)
        request_id, _, _ = receive_packet(connection)
        if request_id != 91:
            raise OSError("RCON authentication failed")
        send_packet(connection, 92, 2, command)
        try:
            request_id, _, response = receive_packet(connection)
            if request_id not in (92, -1):
                raise OSError("unexpected RCON response ID")
            if request_id == -1:
                raise OSError("RCON command rejected")
            if response:
                print(response)
        except (ConnectionResetError, BrokenPipeError, socket.timeout):
            if command.lower() != "stop":
                raise
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as error:
        print(f"RCON failed: {error}", file=sys.stderr)
        raise SystemExit(1)
