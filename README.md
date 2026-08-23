# Jammarr

Jammarr provides one server-authoritative Plex music queue for a NeoForge Minecraft server. Players browse and queue music in-game, operators control playback, and every listening client follows the same server timeline.

Jammarr is intentionally a required server-and-client mod. A server-only mod cannot decode arbitrary Plex audio through an unmodified Minecraft client's sound engine.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1, and Java 21.
- The same Jammarr JAR on the dedicated server and every connecting client.
- A Plex Media Server reachable from the Minecraft server.

No external FFmpeg installation is required. Plex prepares an MP3 rendition; if a Plex version returns variable-bitrate data, Jammarr normalizes it in-process to the configured constant bitrate using its bundled pure-Java encoder.

## Server setup

1. Start the server once to generate `world/serverconfig/jammarr-server.toml`.
2. Set `plexUrl` to the Plex base URL and `musicLibrary` to a music-library title or numeric section key. Leaving the library blank selects the first music library.
3. Supply the token through `JAMMARR_PLEX_TOKEN` (recommended), or put it in `plexToken` in the server config.
4. Restart the server after editing its server config. `/jammarr reload` reruns connection and library validation against the currently loaded values.

Important server options and defaults:

| Option | Default | Purpose |
| --- | ---: | --- |
| `restartMode` | `RESTART_TRACK` | `RESTART_TRACK`, `CLEAR`, or `RESUME_POSITION` after restart |
| `pauseWhenNoPlayers` | `true` | Automatically pause an active track while the server is empty |
| `operatorPermissionLevel` | `2` | Permission required for global playback and queue mutations |
| `queueLimit` | `500` | Maximum global queue length; hard-capped at 500 |
| `audioBitrateKbps` | `160` | Exact constant-bitrate MP3 transport target |
| `cacheSizeMiB` | `1024` | LRU audio-cache limit; current and next tracks remain pinned |

Plex metadata responses are capped at 4 MiB, expanded albums/artists/playlists are capped to the remaining queue capacity, individual transcodes are capped at three hours and 256 MiB, and stalled transcode bodies are aborted after three minutes.

Plain HTTP is allowed for trusted private networks and emits a warning. HTTPS uses normal Java certificate validation.

## In-game use

- Press `P` or run `/jammarr` to open the music screen.
- The screen includes Now Playing, Search, Artists, Albums, Playlists, and Queue views with server-side pagination.
- Every player may browse and append tracks, albums, artists, or audio playlists.
- Permission-level 2 operators may pause, resume, skip, clear, remove, and reorder queue entries.
- Each player may independently mute Jammarr and set a persistent local volume. Local opt-out never changes the global queue.
- Master, Music, and Jammarr volume controls all apply. Vanilla background music is suppressed while the Jammarr stream is active and restored afterward.

Commands:

| Command | Access | Effect |
| --- | --- | --- |
| `/jammarr` | Everyone | Open the screen |
| `/jammarr status` | Everyone | Show global playback status |
| `/jammarr pause`, `resume`, `skip`, `clear` | Operator | Control global playback |
| `/jammarr cache` | Operator | Show cache usage |
| `/jammarr reload` | Operator | Revalidate Plex and the selected library |
| `/jammarr diagnostics` | Operator | Show sanitized Plex/cache/transfer state |

## Playback and failure behavior

- The server owns the queue, timeline, Plex credentials, cache, and all media requests. The Plex token is never sent to clients.
- Tracks are validated before atomic cache installation. The next track is prefetched while the current one plays.
- MP3 data is split only on frame boundaries into payloads no larger than 16 KiB. Clients pull one server-authorized window at a time, validate SHA-256 hashes, acknowledge complete windows, and retry only the outstanding window. The server rejects out-of-order, unacknowledged, over-buffered, and excessive requests.
- A new track is scheduled five seconds ahead using a filtered client/server clock estimate. Late joiners begin near the authoritative position. More than 500 ms of drift causes a local rebuffer rather than delaying every listener.
- A preparation failure retries three times with backoff. Missing or permanently invalid items are skipped; authentication, configuration, and outage failures wait for the 30-second Plex recovery check instead of retrying from every server tick. Cached playback continues during a temporary Plex outage.
- The queue and five-second playback checkpoints live in world saved data. A graceful shutdown records the current position for `RESUME_POSITION`.
- Unmodded clients and clients with an incompatible Jammarr network protocol are rejected during payload negotiation.

## Security and privacy

- Prefer `JAMMARR_PLEX_TOKEN` so the token is not written to a configuration file.
- Library requests authenticate by header. Plex's progressive transcode endpoint requires query authentication on some server versions; Jammarr never logs that request URI.
- Errors and operator diagnostics redact plain and URL-encoded token values. Player-facing errors contain no server address, request URI, or credential detail.
- Jammarr does not modify Plex ratings, play history, or playlists.

## Build and verification

```bash
./gradlew cleanTest test
./gradlew runGameTestServer
./gradlew runServer
./gradlew build
```

The opt-in live Plex test reads credentials only from its process environment:

```bash
JAMMARR_LIVE_TEST=true \
JAMMARR_PLEX_URL='https://plex.example.invalid:32400' \
JAMMARR_PLEX_TOKEN='...' \
./gradlew test --tests stonytark.jammarr.server.PlexLiveSmokeTest
```

Credentialed live-test results are deliberately non-cacheable. Test credentials are not build inputs and are not packaged into the mod JAR.

Jammarr is released under CC0-1.0. Its license, the complete LGPL-2.1-or-later text for the embedded MP3 libraries, and `THIRD_PARTY_NOTICES.md` are copied into the built artifact.
