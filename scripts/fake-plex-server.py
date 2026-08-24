#!/usr/bin/env python3
"""Deterministic loopback Plex subset used by the all-target runtime gate."""

import argparse
import json
import os
import signal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port-file", required=True)
    parser.add_argument("--request-log", required=True)
    parser.add_argument("--token", required=True)
    args = parser.parse_args()

    port_file = Path(args.port_file)
    request_log = Path(args.request_log)
    request_log.parent.mkdir(parents=True, exist_ok=True)
    request_log.write_text("", encoding="utf-8")

    class Handler(BaseHTTPRequestHandler):
        server_version = "JammarrFakePlex/1"

        def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            token = self.headers.get("X-Plex-Token", "")
            with request_log.open("a", encoding="utf-8") as stream:
                stream.write(f"GET\t{self.path.split('?', 1)[0]}\t{token}\n")
            if token != args.token:
                self.respond(401, {})
                return
            path = self.path.split("?", 1)[0]
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
                body = {"MediaContainer": {"Metadata": [{
                    "type": "track", "ratingKey": "42", "title": "Gate Track",
                    "grandparentTitle": "Gate Artist", "parentTitle": "Gate Album",
                    "duration": 120000, "musicAnalysisVersion": 1
                }]}}
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
