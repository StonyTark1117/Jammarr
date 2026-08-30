# Jammarr

Jammarr provides one server-authoritative Plex music queue for Minecraft. Players browse and queue music in-game, operators control playback, and every listening client follows the same server timeline.

Jammarr may be installed on a dedicated server without requiring every player to install it. Vanilla clients can join and play normally, but only clients with the matching Jammarr build negotiate music browsing, controls, and synchronized audio; an unmodified client cannot decode the Plex stream.

## Requirements

- One supported Minecraft/loader combination from the table below and its required Java version.
- The matching Jammarr Minecraft-version and loader JAR on the dedicated server, plus on each client that should receive Jammarr functionality.
- A Plex Media Server reachable from the Minecraft server. Clients do not need network access to Plex.

| Minecraft | Java | Fabric | Quilt | Forge | NeoForge |
| --- | ---: | :---: | :---: | :---: | :---: |
| 1.7.10 | 8 | unavailable | unavailable | supported | unavailable |
| 1.12.2 | 8 | unavailable | unavailable | supported | unavailable |
| 1.16.5 | 8 | supported | supported | supported | unavailable |
| 1.18.2 | 17 | supported | supported | supported | unavailable |
| 1.19.2 | 17 | supported | supported | supported | unavailable |
| 1.20 | 17 | supported | supported | preview | unavailable |
| 1.20.1 | 17 | supported | supported | supported | supported |
| 1.20.2 | 17 | supported | supported | supported | supported |
| 1.20.3 | 17 | supported | supported | preview | preview |
| 1.20.4 | 17 | supported | supported | supported | supported |
| 1.20.5 | 21 | supported | supported | unavailable | preview |
| 1.20.6 | 21 | supported | supported | supported | supported |
| 1.21 | 21 | supported | supported | preview | supported |
| 1.21.1 | 21 | supported | supported | supported | supported |
| 1.21.2 | 21 | supported | supported | unavailable | preview |
| 1.21.3 | 21 | supported | supported | supported | supported |
| 1.21.4 | 21 | supported | supported | supported | supported |
| 1.21.6 | 21 | supported | supported | preview | preview |
| 1.21.9 | 21 | supported | supported | preview | preview |
| 1.21.11 | 21 | supported | supported | supported | supported |
| 26.1.2 | 25 | supported | supported | supported | supported |
| 26.2 | 25 | supported | supported | supported | supported |

Fabric, Quilt, and NeoForge are unavailable for Minecraft 1.7.10 and 1.12.2. NeoForge is also unavailable before 1.20.1. Quilt is supported on every Fabric target using the matching `-fabric.jar`, Quilt Loader, and upstream Fabric API; no QSL, Quilted Fabric API, or separate `-quilt.jar` is required or published. Fabric-to-Quilt connections and other cross-loader or cross-Minecraft pairings are unsupported. Every 1.1.0 artifact uses protocol 6 and is named `jammarr-<mod-version>+mc<version>-<loader>.jar`; there is no cross-Minecraft or Forge-family universal JAR. Exact pinned dependencies are listed in the family catalogs under `gradle/version-catalogs/` and copied into the generated release manifest.

No external FFmpeg installation is required. Plex prepares an MP3 rendition; if a Plex version returns variable-bitrate data, Jammarr normalizes it in-process to the configured constant bitrate using its bundled pure-Java encoder.

## Server setup

1. Start the server once to generate `world/serverconfig/jammarr-server.toml`.
2. Set `plexUrl` to the Plex base URL and `musicLibrary` to a music-library title or numeric section key. Leaving it blank prefers a music library titled `Music`, then falls back to the first valid music library. Jammarr binds browsing, queues, playlists, radio, fallback metadata, and transcodes to that selected section.
3. Supply the token through `JAMMARR_PLEX_TOKEN` (recommended), or put it in `plexToken` in the server config.
4. If using `plexToken`, restrict the server-config file to the Minecraft server account and keep backups/logs from exposing it. Restart the server after editing its server config. `/jammarr reload` reruns connection and library validation against the currently loaded values.

Important server options and defaults:

| Option | Default | Purpose |
| --- | ---: | --- |
| `restartMode` | `RESTART_TRACK` | `RESTART_TRACK`, `CLEAR`, or `RESUME_POSITION` after restart |
| `pauseWhenNoPlayers` | `true` | Automatically pause an active track while the server is empty |
| `operatorPermissionLevel` | `2` | Permission required for global playback and queue mutations |
| `queueLimit` | `500` | Maximum global queue length; hard-capped at 500 |
| `audioBitrateKbps` | `160` | Exact constant-bitrate MP3 transport target |
| `cacheSizeMiB` | `1024` | LRU audio-cache limit; current and next tracks remain pinned |
| `stationMetadataFallbackEnabled` | `false` | Allow lower-quality metadata/random fallback when Plex sonic analysis is unavailable |

Plex metadata responses are capped at 4 MiB, expanded albums/artists/playlists are capped to the remaining queue capacity, individual transcodes are capped at three hours and 256 MiB, and stalled transcode bodies are aborted after three minutes.

Plain HTTP is allowed for trusted private networks and emits a warning. HTTPS uses normal Java certificate validation.
Bitrate is constrained to 64–320 kbps and cache size to 64–16384 MiB; invalid values are rejected during canonical config loading and reported during startup.

Client listening and volume settings are separate from the server file. Forge and NeoForge expose them from the Mods-list Config entry; Fabric exposes them inside Jammarr and through Mod Menu when installed.

## In-game use

- Press `J` by default on modern Minecraft (`P` on 1.7.10), or run `/jammarr` to open the music screen. The key appears as **Open Jammarr** in Controls and can be rebound on every supported version and loader. The modern default avoids vanilla's Social menu binding on `P`.
- The screen includes Now Playing, Search, Artists, Albums, Playlists, Stations, Adventure, and Queue views with server-side pagination.
- Every player may browse and append tracks, albums, artists, or audio playlists.
- Permission-level 2 operators may pause, resume, skip, clear, remove, and reorder queue entries.
- Each player may independently mute Jammarr and set a persistent local volume. Local opt-out never changes the global queue.
- Operators can run one shared endless source: Sonic Autoplay, Library Shuffle, Track Radio, Artist Radio, Album Radio, or a 2–5 seed Sonic Mix. Track, artist, and album browse rows expose radio/mix actions.
- Every main-menu button has concise hover help, including the compact `+`, `R`, `M`, and `A` browse actions, queue arrows, station builders, shared playback controls, paging, tabs, local mute/volume, and audio retry.
- **Adventure is a separate tab.** Operators build an ordered route of 2–5 track waypoints, preview Plex's sonic path, and start it normally or immediately. After the final waypoint, Jammarr continues with Track Radio from that track.
- Manual requests always play before generated station tracks after the current song. The Queue view marks generated preview entries as read-only; they do not consume the manual queue limit.
- The Now Playing screen reports both server playback and local audio state. Decoder or transfer recovery is bounded and can be retried from the screen after a final local audio error.
- Search reports short-query, searching, Plex-unavailable, and empty-result states; queue actions report progress and completion, and long titles expose their full text as tooltips.
- Master, Music, and Jammarr volume controls all apply. Vanilla background music is suppressed while the Jammarr stream is active and restored afterward.

Commands:

| Command | Access | Effect |
| --- | --- | --- |
| `/jammarr` | Everyone | Open the screen |
| `/jammarr status` | Everyone | Show global playback status |
| `/jammarr pause`, `resume`, `skip`, `clear` | Operator | Control global playback |
| `/jammarr cache` | Operator | Show cache usage |
| `/jammarr reload` | Operator | Revalidate Plex and the selected library |
| `/jammarr diagnostics` | Operator | Show sanitized Plex/cache/transfer state, preparation status, and transfer counters |
| `/jammarr station status` | Operator | Show active station, generated lookahead, and sonic capability |
| `/jammarr station stop` | Operator | Stop future station generation after the current track |
| `/jammarr station library-shuffle` | Operator | Start endless library shuffle after pending manual requests |
| `/jammarr autoplay on`, `off` | Operator | Enable or disable sonic continuation from the five most recent tracks |
| `/jammarr adventure status`, `stop` | Operator | Inspect or stop the shared Adventure source |

## Plex Pass and sonic setup

Normal browsing, manual queues and playback, and Library Shuffle do not require Plex Pass. Sonic Adventure always requires an active Plex Pass for the server owner and completed sonic analysis for the selected music library. Enable **Analyze audio tracks for sonic features** in the Plex server Library settings and **Sonic Analysis** in the music library's Advanced settings, then allow the analysis task to finish.

The Stations and Adventure tabs report whether Plex Pass is unavailable, library/seed analysis is incomplete, the server lacks the operation, validation is still running, or Plex is offline. When `stationMetadataFallbackEnabled=true`, Sonic Autoplay, Track/Artist/Album Radio, and Sonic Mix may continue using genre, style, related-item, and random-library metadata without Plex Pass. Metadata fallback is deliberately off by default and never substitutes for Sonic Adventure.

## Playback and failure behavior

- Every loader delegates server playback to the same Java 8, Minecraft-independent coordinator. Narrow runtime, player/permission, packet-transport, and schema-4 persistence contracts keep loader APIs out of queue, station, Adventure, cache, timing, and transfer policy.
- The server owns the queue, timeline, Plex credentials, cache, and all media requests. The Plex token is never sent to clients.
- Tracks are validated before atomic cache installation. The next track is prefetched while the current one plays.
- MP3 data is split only on frame boundaries into payloads no larger than 16 KiB. Clients pull one server-authorized window at a time, validate SHA-256 hashes, acknowledge complete windows, and retry only the outstanding window. The server rejects out-of-order, unacknowledged, over-buffered, and excessive requests.
- A new track is scheduled five seconds ahead using a filtered client/server clock estimate. Late joiners begin near the authoritative position. More than 500 ms of drift causes a local rebuffer rather than delaying every listener.
- A preparation failure retries three times with backoff. Missing or permanently invalid items are skipped; authentication, configuration, and outage failures wait for the 30-second Plex recovery check instead of retrying from every server tick. Cached playback continues during a temporary Plex outage.
- Client audio recovery retries three times per playback session. A fourth failure stops retrying automatically and exposes a `Retry audio` action instead of leaving a silent stream running indefinitely.
- If the Minecraft log reports `Failed to open OpenAL device` followed by `Turning off sounds & music`, vanilla disabled its entire sound engine before Jammarr playback. Restore the client audio device (or reload resources after it becomes available), then use `Retry audio`; receiving Jammarr chunks cannot produce sound while Minecraft's sound engine is offline.
- The queue and five-second playback checkpoints live in world saved data. A graceful shutdown records the current position for `RESUME_POSITION`.
- The active station, autoplay toggle, seed/waypoint definition, current source, and the last 100 tracks used for repeat suppression also live in world saved data. Generated lookahead is rebuilt after restart. `RESTART_TRACK` and `RESUME_POSITION` retain the source; `CLEAR` removes it.
- Unmodded clients and clients with an incompatible Jammarr network protocol are rejected during payload negotiation. Queue mutations include an expected track key so stale operator screens cannot modify the wrong entry.
- Application-level client hello deadlines allow 60 seconds on Forge 1.7.10, Fabric, and Quilt so slow resource loading does not look like a missing mod. Timeout cleanup is completed before disconnect callbacks run.
- The server diagnostics command reports Plex validation time, cache hit/miss/install/invalid counters, current and next-track cache state, active listener transfer counters, and client-reported recovery/underrun/buffer health.

## Security and privacy

- Prefer `JAMMARR_PLEX_TOKEN` so the token is not written to a configuration file.
- Library requests authenticate by header. Plex's progressive transcode endpoint requires query authentication on some server versions; Jammarr never logs that request URI.
- Errors and operator diagnostics redact plain and URL-encoded token values. Player-facing errors contain no server address, request URI, or credential detail.
- Jammarr does not modify Plex ratings, play history, or playlists.

## Build and verification

```bash
./gradlew releaseMatrixGate --no-daemon --max-workers=1
```

`releaseMatrixGate` runs the shared tests, every implemented family build, the cleanup-aware GameTest gate, the isolated Java-8 Forge gates, centralized inspection of every final JAR (including remappable menu-key registration), and fresh dedicated-server checks for every implemented loader/version runtime. Every modern Fabric artifact is exercised under both Fabric and Quilt while remaining one release file. Each runtime must first reject an invalid canonical config without leaking its value, then start successfully and complete authenticated library and sonic-capability calls against a deterministic loopback Plex service. The handshake regression gate models the reported Uranium B285 re-entrant logout, proves a 59-second hello is still accepted and a 60-second hello expires, and can launch real clients with a 12-second acceptance-only delay to cover heavily modded login times. Every runtime launches a real wrong-protocol client and requires either the exact client-received rejection or an exact server marker paired with the loader's generic client disconnect. Modern targets pair a dependency-free missing-client probe with the loader's client-facing rejection; Forge 1.7.10 launches a real matching client with its acceptance-only hello suppressed and requires the explicit missing-hello timeout. A second real client proves public commands are visible before promotion, operator commands appear only after promotion, the player and client-config screens survive rendered frames, and `/jammarr diagnostics` reaches the player without exposing the Plex token or address. Finally, two real clients feed isolated audio sinks so the gate can measure late join, pause/resume, volume, mute, reload, cache-backed outage playback, Library Shuffle, Sonic Adventure, underrun and drift recovery, retry exhaustion/manual retry, reconnect, and clear; state or an allocated OpenAL source alone does not pass. Release staging derives its artifact count from `gradle/targets.json`, places those artifacts in `build/releases/` alongside schema-2 `manifest.json` and `SHA256SUMS` and fails if a tested server leaves a process or game port behind.

Useful narrower gates are `verify1182Family`, `verify1192Family`, `verify1201Family`, `verify1202Family`, `verify121Family`, `verify1211Family`, `verify1212Family`, `verify2612Family`, `verify262Family`, `verifyQuiltRuntimes`, `verifyLegacy1710`, and `verifyGameTests`. Each target's `verifyRelease` checks loader metadata, translations, decoder dependencies, license notices, canonical filename, and other target-specific invariants. The legacy verifier additionally checks all Jammarr classes are Java 8 bytecode.

`scripts/run-audio-impairment-matrix.sh` reruns the representative 1.7.10, 1.20.1, and 26.2 client matrix with direct transport, 150 ms latency, 20–250 ms jitter, a two-second stall, repeated 250 ms client stalls, and a six-second below-bitrate overload. The gate uses a deterministic 997 Hz carrier with pseudo-random 1477/1975 Hz marker identities every 250 ms, so it detects early, late, replayed, reordered, or overlapping PCM as well as post-start silence over 60 ms, marker displacement, late onset, and two-client skew. Its oracle self-test deliberately injects each timing fault before release verification can pass.

Release checklist:

- [ ] Run `./gradlew releaseMatrixGate --no-daemon --max-workers=1` from a clean checkout.
- [ ] Validate the credentialed Plex smoke test once per Minecraft family against the intended deployment server.
- [ ] Confirm the automated dedicated-server gate passed for every runtime derived from `gradle/targets.json`, and retain `build/dedicated-server-gate/` logs with the release evidence.
- [ ] Confirm missing-client and deliberately incompatible-protocol clients receive a clear disconnect.
- [ ] Confirm **Open Jammarr** appears under a localized **Jammarr** Controls category, defaults to `J` on modern versions, and can be rebound without colliding with vanilla Social.
- [ ] Confirm the automated command client keeps both the player and client-config screens alive across rendered frames on every modern runtime.
- [ ] Confirm all modern Fabric JARs start under both Fabric Loader 0.19.2 and the pinned 0.19.3 runtime.
- [ ] Confirm all five Quilt client scenarios pass both without Mod Menu and with the pinned Mod Menu version.
- [ ] Complete the audible two-client matrix in `docs/RELEASE_ACCEPTANCE.md`; a connected client or allocated OpenAL source is not sufficient.
- [ ] On Modrinth, CurseForge, and any other distribution platform, mark each modern `-fabric.jar` as compatible with both Fabric and Quilt; never upload a duplicate `-quilt.jar`.
- [ ] Publish every JAR listed by the final target manifest with `manifest.json` and `SHA256SUMS` from `build/releases/` together.

The opt-in live Plex test reads credentials only from its process environment:

```bash
JAMMARR_LIVE_TEST=true \
JAMMARR_PLEX_URL='https://plex.example.invalid:32400' \
JAMMARR_PLEX_TOKEN='...' \
./gradlew test --tests stonytark.jammarr.server.PlexLiveSmokeTest
```

Credentialed live-test results are deliberately non-cacheable. Test credentials are not build inputs and are not packaged into the mod JAR.

Jammarr is released under CC0-1.0. Its license, the complete LGPL-2.1-or-later text for the embedded MP3 libraries, and `THIRD_PARTY_NOTICES.md` are copied into the built artifact.

See [compatibility](docs/COMPATIBILITY.md), [migration](docs/MIGRATION.md), and the [release acceptance matrix](docs/RELEASE_ACCEPTANCE.md) for target-specific details.
