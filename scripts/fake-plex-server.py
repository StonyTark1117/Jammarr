#!/usr/bin/env python3
"""Deterministic loopback Plex subset used by the all-target runtime gate."""

import argparse
import json
import os
import signal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port-file", required=True)
    parser.add_argument("--request-log", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--audio-file")
    parser.add_argument("--state-file")
    args = parser.parse_args()

    audio_file = Path(args.audio_file) if args.audio_file else None
    if audio_file is not None and not audio_file.is_file():
        parser.error(f"audio file does not exist: {audio_file}")
    state_file = Path(args.state_file) if args.state_file else None
    tracks = [
        {
            "type": "track", "ratingKey": str(key),
            "title": f"Gate Track {key}",
            "grandparentTitle": f"Gate Artist {key % 3 + 1}",
            "parentTitle": f"Gate Album {key % 2 + 1}",
            "duration": 120000, "musicAnalysisVersion": 1,
        }
        for key in range(42, 50)
    ]
    tracks_by_key = {track["ratingKey"]: track for track in tracks}

    port_file = Path(args.port_file)
    request_log = Path(args.request_log)
    request_log.parent.mkdir(parents=True, exist_ok=True)
    request_log.write_text("", encoding="utf-8")

    class Handler(BaseHTTPRequestHandler):
        server_version = "JammarrFakePlex/1"

        def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            request = urlsplit(self.path)
            token = self.headers.get("X-Plex-Token", "")
            if not token:
                token = parse_qs(request.query).get("X-Plex-Token", [""])[0]
            state = "offline" if state_file is not None and state_file.exists() \
                and state_file.read_text(encoding="utf-8").strip() == "offline" else "online"
            with request_log.open("a", encoding="utf-8") as stream:
                stream.write(f"GET\t{request.path}\t{token}\t{state}\n")
            if token != args.token:
                self.respond(401, {})
                return
            if state == "offline":
                self.respond(503, {})
                return
            path = request.path
            if path == "/library/sections":
                body = {"MediaContainer": {"Directory": [
                    {"type": "artist", "key": "1", "title": "Music"}
                ]}}
            elif path == "/":
                body = {"MediaContainer": {
                    "version": "1.41.0", "machineIdentifier": "jammarr-fake-plex",
                    "myPlexSubscription": True
                }}
            elif path == "/library/sections/1/all":
                body = {"MediaContainer": {"Metadata": tracks}}
            elif path.startswith("/library/metadata/") and path.endswith("/nearest"):
                key = path.removeprefix("/library/metadata/").removesuffix("/nearest")
                if key not in tracks_by_key:
                    self.respond(404, {})
                    return
                nearest = []
                for index, candidate in enumerate(tracks):
                    if candidate["ratingKey"] == key:
                        continue
                    value = dict(candidate)
                    value["distance"] = round(0.05 + index * 0.02, 3)
                    nearest.append(value)
                body = {"MediaContainer": {"Metadata": nearest}}
            elif path == "/library/sections/1/computePath":
                query = parse_qs(request.query)
                start = query.get("startID", ["42"])[0]
                end = query.get("endID", ["43"])[0]
                if start not in tracks_by_key or end not in tracks_by_key:
                    self.respond(404, {})
                    return
                middle = next(track for track in tracks if track["ratingKey"] not in (start, end))
                body = {"MediaContainer": {"Metadata": [tracks_by_key[start], middle, tracks_by_key[end]]}}
            elif path.startswith("/library/metadata/"):
                key = path.removeprefix("/library/metadata/")
                track = tracks_by_key.get(key)
                if track is None:
                    self.respond(404, {})
                    return
                body = {"MediaContainer": {"Metadata": [track]}}
            elif path == "/music/:/transcode/universal/start.mp3" and audio_file is not None:
                self.respond_bytes(200, audio_file.read_bytes(), "audio/mpeg")
                return
            else:
                self.respond(404, {})
                return
            self.respond(200, body)

        def respond(self, status: int, body: dict) -> None:
            payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def respond_bytes(self, status: int, payload: bytes, content_type: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *_args: object) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port_file.parent.mkdir(parents=True, exist_ok=True)
    temporary = port_file.with_suffix(port_file.suffix + ".tmp")
    temporary.write_text(str(server.server_address[1]), encoding="ascii")
    os.replace(temporary, port_file)

    def stop(_signal: int, _frame: object) -> None:
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        server.serve_forever(poll_interval=0.1)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        port_file.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
