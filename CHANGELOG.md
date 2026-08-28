# Changelog

## 1.0.2 - 2026-08-28

- Fix the Forge 1.7.10 handshake-timeout crash reported on Uranium by removing pending state before disconnect callbacks can fire, and extend the production hello deadline from five to sixty seconds.
- Apply the sixty-second hello policy to every Fabric and Quilt runtime while retaining an acceptance-only short timeout for deterministic missing-client tests.
- Prefer the Plex music library titled `Music` when `musicLibrary` is blank, and fail closed when unscoped metadata, playlists, native radio, or sonic results do not belong to the selected library.
- Clarify which playback and station modes work without Plex Pass, including the optional metadata fallback and Adventure's Sonic-only requirement.
- Start decoded audio with enough of the server's scheduling lead remaining, wait for measured clock synchronization, skip PCM elapsed during asynchronous channel creation, publish backend-ready channel state atomically across threads to prevent duplicate orphan playback, and give newly aligned channels a short correction grace instead of accepting startup skew or same-tick recovery churn.
- Bootstrap time synchronization with ten rapid samples, retain the lowest-round-trip measurement without blending in asymmetric server-thread delay, and ignore unmeasured playback-state timestamps so a slow first response cannot misalign late joiners.
- Tolerate transient chunk-delivery delays without prematurely rebuffering audio that the sound backend still has queued, while retaining explicit forced-underrun recovery coverage.
- Coalesce sound-thread volume updates so stale per-tick commands cannot delay a local volume change under load.
- Bound temporary decoder waits on the sound executor so a withheld compressed window cannot stall volume, stop, reload, or recovery commands.
- Reserve one complete low-bitrate chunk window in server playback-lead flow control, and avoid backend state queries while a streaming read is waiting for that bounded delivery window.
- Add descriptive legacy action help plus deterministic marked-audio, two-client timing, pre-backend legacy PCM classification, and isolated network-impairment acceptance tooling.
- Start synchronized PCM capture only after both clients' latest audio states remain PLAYING, including when a slow late join overlaps leader recovery.
- Isolate shared-core build outputs per Gradle root so incremental cross-version release verification cannot reuse incompatible binary test metadata.
- Retain protocol 5, saved-data schema 4, and the complete 16-artifact / 21-runtime compatibility matrix.

## 1.0.1 - 2026-08-27

- Fix Forge 1.7.10 clients crashing in LWJGL2/OpenAL after Forge starts overlapping sound-engine reloads, including the `nalGetSourcei` and `nalSourcef` native-link failures seen on Linux.
- Let the initial 1.7.10 sound loader settle during normal startup, recover safely from an unusable or reloaded sound engine, and restore vanilla music scheduling when Jammarr no longer owns playback.
- Replace fragile modern SoundManager/SoundEngine accessor Mixins with runtime type-based field lookup, fixing Forge and NeoForge audio startup across mapped and production runtimes.
- Retain protocol 5, the existing server configuration format, and the complete 16-artifact / 21-runtime compatibility matrix.

## 1.0.0 - 2026-08-25

- Initial Jammarr release.
