# Changelog

## 1.1.0 - Unreleased

- Add a separately pinned Minecraft 1.8.9 Legacy Fabric preview with Java 8 bytecode, protocol-6 optional-client negotiation, commands, player/config screens, direct legacy OpenAL playback, and canonical server configuration/state behavior.
- Add a separately pinned Minecraft 1.6.4 Legacy Fabric preview with Java 8 bytecode and a bounded native custom-payload bridge for protocol-6 negotiation where the Legacy Fabric networking API is unavailable.
- Certify the 1.8.9 Legacy Fabric target with invalid-config rejection, a real incompatible-protocol client, an actual client without Jammarr, two-client audible timing, the full recovery/station scenario matrix, and clean process/port teardown; live-Plex acceptance remains pending for the final release matrix.
- Certify the 1.6.4 Legacy Fabric target with fail-closed configuration, protocol mismatch, command/UI, actual no-Jammarr client, synchronized two-client audio, recovery/station scenarios, and clean teardown gates.
- Add and certify a separately pinned Minecraft 1.8.9 Ornithe generation-2 preview with Feather mappings, OSL networking and lifecycle integration, Java 8 bytecode/runtime, optional-client negotiation, command/UI coverage, synchronized two-client audio, recovery scenarios, and clean teardown.
- Add and certify a separately pinned Minecraft 1.6.4 Ornithe generation-2 preview with Feather mappings, only the version-compatible OSL modules Jammarr uses, Java 8 bytecode/runtime, optional-client negotiation, command/UI coverage, synchronized two-client audio, recovery scenarios, and clean teardown.
- Add and runtime-certify a production-reobfuscated Minecraft 1.8.9 LiteLoader client companion, explicitly paired with a Forge server, with protocol-6 negotiation, audio playback, player/config UI coverage, and pinned upstream loader bytes. LiteLoader is not advertised as a dedicated-server runtime.
- Add and runtime-certify a fully obfuscated Minecraft 1.7.10 LiteLoader client companion using checksum-pinned production Forge and LiteLoader distributions. The supported client requires Forge+LiteLoader coexistence and pairs only with the matching Forge server; standalone LiteLoader cannot complete Forge 1.7.10's FML handshake.
- Add and runtime-certify a fully obfuscated Minecraft 1.6.4 LiteLoader client companion using official LiteLoader 1.6.4_01 and recommended Forge 9.11.1.1345. Its Java-7 class headers remain Java-8 runtime code compatible with Forge's ASM 4, and the production pair passes protocol, player/config UI, playable-audio, sustained-health, and teardown gates.
- Expand the candidate matrix to 78 artifacts and 99 dedicated-server loader/version runtimes while keeping Babric, Legacy Fabric, LiteLoader, Ornithe, and Quilt compatibility boundaries explicit.
- Make the root release matrix enable every manifest-declared LiteLoader paired-client gate instead of leaving those runtime checks opt-in.
- Make global clear operations remove persisted repeat-suppression history on every saved-data implementation, matching the shared store contract and preventing cleared or restart-reset servers from retaining old playback history.
- Deep-certify the final Forge 1.7.10 artifact with twenty cold-client cycles, forty audible sound reloads, all six deterministic impairment profiles, the 30-minute playback fixture, LiteLoader and no-mod client coverage, and a second production boot that reloads schema-4 state; final live-Plex acceptance remains pending.
- Order resource processing before Java compilation in every Forge-family module that intentionally shares its classes/resources output, preventing clean or non-incremental builds from erasing compiled classes before tests and packaging.
- Add a production Forge 1.7.10 UI regression probe for issue #5 that clicks the real search field and proves typing, backspace, retyping, focus, and cursor state survive repeated screen rebuilds.
- Validate the shared Plex implementation against the live deployment with blank-library preference for `Music`, exclusion of a real item from the separate ASMR library, browse/search/station/Adventure/transcode coverage, and 30 repeat-safe autoplay transitions.
- Add a manifest-driven DiscPanel reconciler with dry-run-by-default planning, exact stopped-state drift checks, sequential no-mod native instance creation, pinned Java images, and fail-closed handling for custom legacy loader distributions.
- Reconcile the live DiscPanel matrix to 94 stopped native-loader profiles with unique ports and no creation errors; verify all 73 newly created instances contain no mod records while five custom legacy distributions remain pending.

## 1.0.2 - 2026-08-28

- Give Forge 1.7.10 PCM a single direct OpenAL queue owner instead of routing it through Paulscode's competing stream-maintenance and raw-feed paths, prevent stopped queues from replaying stale PCM, keep a larger backend reserve across ordinary client hitches, and extend rendered legacy audio verification beyond the former ten-second window.
- Prevent every client backend from starting before its authoritative chunk boundary or without enough decoded PCM to discard startup delay, trim late starts at PCM-sample alignment instead of whole MP3 frames, reject incomplete catch-up, and rebuild on every authoritative timeline change so old and replacement audio cannot overlap.
- Make the rendered-audio oracle reject deliberately injected early, late, replayed, reordered, and overlapping PCM instead of relying only on cadence and silence.
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
- Add concise hover descriptions to every main-menu button across modern and Forge 1.7.10 clients, and require two seeds before the legacy Sonic Mix action becomes available.
- Add deterministic marked-audio, two-client timing, pre-backend legacy PCM classification, and isolated network-impairment acceptance tooling.
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
