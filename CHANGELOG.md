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
- Address the menu-clarity feedback from GitHub issue #2 with concise hover help for every actionable player-menu tab and control across all legacy and modern screen adapters, backed by real private-X hover-render gates for Forge 1.7.10, the 1.18.2 compatibility renderer, and the 1.20.1 native `Tooltip` API.
- Repair the 1.18.2 real-client command acceptance bridge by using that era's `LocalPlayer.chat(String)` fallback, allowing player-visible diagnostics and UI gates to complete instead of timing out before screen validation.
- Add a production Forge 1.7.10 UI regression probe for issue #5 that clicks the real search field and proves typing, backspace, retyping, focus, and cursor state survive repeated screen rebuilds.
- Validate the shared Plex implementation against the live deployment with blank-library preference for `Music`, exclusion of a real item from the separate ASMR library, browse/search/station/Adventure/transcode coverage, and 30 repeat-safe autoplay transitions.
- Add a manifest-driven DiscPanel reconciler with dry-run-by-default planning, exact stopped-state drift checks, sequential no-mod native instance creation, pinned Java images, and fail-closed handling for custom legacy loader distributions.
- Reconcile the live DiscPanel matrix to all 99 manifest-derived runtime profiles, including checksum-pinned Babric and Legacy Fabric launchers plus inspected Ornithe generation-2 bootstrap distributions; leave every profile stopped with a unique port and verify all 78 newly created instances contain no mod records.
- Add a dry-run-by-default DiscPanel release deployer that verifies all 78 indexed artifacts, maps the 75 server artifacts onto all 99 runtime profiles, refuses running, drifted, autostarted, duplicate, or partial deployments, verifies uploaded bytes, and retains the previously active Jammarr file as a disabled rollback without starting a server.
- Diagnose the post-disk-resize 1.18.2 DiscPanel failure as a stale never-started Docker container name conflict, preserve its bind-mounted server data, and clear only the conflicting container so current-candidate runtime validation can resume.
- Stage the exact 1.1.0 release-candidate artifact on all 99 DiscPanel runtime profiles, verify every remote digest, retain 21 stable 1.0.2 rollback files disabled, and finish with no active older Jammarr artifact or deployment-created server start.
- Pin each DiscPanel native runtime to the exact Fabric, Forge, NeoForge, or Quilt loader declared by the release manifest instead of allowing container defaults to drift.
- Add a guarded live-Plex DiscPanel configurator that keeps credentials environment-only, blanks the library setting to exercise the `Music` metadata fallback, preserves existing canonical configs by default, and never starts a server.
- Configure 78 stopped DiscPanel profiles for live-Plex fallback testing while preserving 21 pre-existing configs, then validate the final 1.12.2 Forge artifact through a real Plex connection and clean server stop after recovering from a transient upstream installer timeout.
- Add a dry-run-by-default, single-runtime DiscPanel server smoke gate with exact artifact/hash/loader preflight, post-snapshot log evidence that rejects replayed historical lines, installer-failure classification, and guaranteed stopped-state teardown.
- Make the DiscPanel smoke gate accept both explicit legacy initialization lines and modern Jammarr Plex startup markers after exact remote-artifact preflight, and fail early when a started profile returns to STOPPED before acceptance.
- Add a checksum-pinned, dry-run-by-default DiscPanel runtime-dependency deployer and stage the required StationAPI 2.0.0-alpha.6.2 dependency on the stopped Beta 1.7.3 Babric profile without replacing Jammarr or starting the server.
- Pass fresh final-artifact live-Plex server canaries for Beta 1.7.3 Babric, Forge 1.6.4, Fabric 1.20.1, and NeoForge 26.2 with clean stopped-state teardown; classify the first Babric and Forge attempts as dependency/download bootstrap failures rather than mod failures.
- Generate a checksum-pinned DiscPanel dependency manifest covering 53 Fabric, Quilt, Babric, Legacy Fabric, and Ornithe runtime profiles, including only the version-compatible OSL modules used by each Ornithe generation.
- Deploy and remotely verify the initial 75 pinned runtime-dependency placements across the 53 dependency-bearing DiscPanel profiles, reusing 11 already-exact files, staging the other 64 without starting a server, and finish with all 99 profiles stopped and autostart disabled.
- Add a manifest-derived, resumable DiscPanel server-smoke matrix runner that resolves the release once, audits the complete stopped/autostart state before and after every sequential runtime, accepts resume evidence only for the exact candidate, and writes a sanitized aggregate result; all 99 live profiles pass its no-start preflight.
- Fix production Legacy Fabric dependency resolution after live gates proved that the per-version Maven aggregate is descriptor-only and its raw module artifacts are not the canonical installable distribution. Pin the official 1.13.2 CurseForge bundle containing its nested runtime modules, and explicitly allow its guarded migration to disable the previously staged aggregate/module records as rollback copies.
- Detect terminal Minecraft process failures such as startup Mixin errors and `mc-server-runner` completion immediately instead of waiting for DiscPanel's stale `STARTING` state to reach the ten-minute timeout.
- Anchor DiscPanel smoke evidence to the newly started container segment so a rotated log window cannot replay a completed historical failure—or historical success—as evidence for the current run.

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
