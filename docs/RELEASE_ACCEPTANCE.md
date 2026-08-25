# Release acceptance

The automated root gate is:

```bash
./gradlew releaseMatrixGate --no-daemon --max-workers=1
```

It must produce 16 JARs plus schema-2 `manifest.json` and `SHA256SUMS` under `build/releases/`, then exercise 21 loader/version runtimes. Run `sha256sum -c SHA256SUMS` from that directory before publication.

The release gate observes each real client's decoded output through an isolated audio sink; PLAYING state or an allocated OpenAL source alone is not accepted. It also starts every modern Fabric artifact under Loader 0.19.2 as well as the pinned 0.19.3, and repeats all five Quilt client/audio scenarios with the pinned Mod Menu installed. Retain a result for every loader before publishing a Minecraft family:

| Target | Dedicated start/stop | Required-client rejection | Wrong protocol | Commands/diagnostics | Fake Plex | Live Plex | Two-client audible playback | Result |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1.7.10 Forge | automated gate | real no-hello client | real client gate | real client gate | automated gate | family smoke 2026-08-24 | observable audio gate | passed 2026-08-24 |
| 1.20.1 Fabric | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-24 | observable audio gate | passed 2026-08-24 |
| 1.20.1 Quilt | automated gate | missing-client probe | real client gate | real client gate | automated gate | covered by family smoke | observable audio gate | server gate passed; client/audio pending |
| 1.20.1 Forge | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-24 | observable audio gate | passed 2026-08-24 |
| 1.20.1 NeoForge | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-24 | observable audio gate | passed 2026-08-24 |
| 1.20.2 Fabric | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-24 | observable audio gate | passed 2026-08-24 |
| 1.20.2 Quilt | automated gate | missing-client probe | real client gate | real client gate | automated gate | covered by family smoke | observable audio gate | server gate passed; client/audio pending |
| 1.20.2 Forge | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-24 | observable audio gate | passed 2026-08-24 |
| 1.20.2 NeoForge | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-24 | observable audio gate | passed 2026-08-24 |
| 1.21.1 Fabric | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-24 | observable audio gate | passed 2026-08-24 |
| 1.21.1 Quilt | automated gate | missing-client probe | real client gate | real client gate | automated gate | covered by family smoke | observable audio gate | server gate passed; client/audio pending |
| 1.21.1 Forge | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-24 | observable audio gate | passed 2026-08-24 |
| 1.21.1 NeoForge | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-24 | observable audio gate | passed 2026-08-24 |
| 26.1.2 Fabric | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-24 | observable audio gate | passed 2026-08-24 |
| 26.1.2 Quilt | automated gate | missing-client probe | real client gate | real client gate | automated gate | covered by family smoke | observable audio gate | server gate passed; client/audio pending |
| 26.1.2 Forge | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-24 | observable audio gate | passed 2026-08-24 |
| 26.1.2 NeoForge | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-24 | observable audio gate | passed 2026-08-24 |
| 26.2 Fabric | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-25 | observable audio gate | passed 2026-08-25 |
| 26.2 Quilt | automated gate | missing-client probe | real client gate | real client gate | automated gate | covered by family smoke | observable audio gate | server gate passed; client/audio pending |
| 26.2 Forge | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-25 | observable audio gate | passed 2026-08-25 |
| 26.2 NeoForge | automated gate | missing-client probe | real client gate | real client gate | automated gate | family smoke 2026-08-25 | observable audio gate | passed 2026-08-25 |

For each audible two-client run, exercise late join, reconnect, pause/resume/skip/clear/reorder, local mute and volume, station generation, Sonic Adventure, cache-backed Plex outage playback, sound reload, underrun recovery, drift correction, and the final retry state. Confirm sound through both clients' actual output; connection state, packet receipt, decoder progress, and an allocated OpenAL source are insufficient.

The live Plex family smoke is the Java 8 shared-core test used by every target. It validates live library discovery and search, all station modes, 30 continuous autoplay transitions without a recent-track repeat, Sonic Adventure, and a constant-bitrate stereo transcode. Credentials remain process-environment-only and the retained logs must not contain the server address or token.

`verifyDedicatedServers` launches a deterministic loopback Plex service and every runtime, including every modern Fabric artifact under pinned Fabric and Quilt loaders. Before its valid startup, each runtime must reject an intentionally invalid canonical config, fail closed, keep the invalid file unchanged, omit its credential value from diagnostics, and release the game port. Each valid server must then authenticate with the environment token and complete library discovery, server-identity, and sonic-analysis requests. Every runtime launches its matching real client with protocol 4 and requires the exact protocol-5 rejection in both client and server logs. Modern targets also run a dependency-free missing-client probe and pair the closed connection with the loader's exact client-facing required-mod rejection. Forge 1.7.10 instead launches a real matching Forge client with only its acceptance-gated Jammarr hello suppressed, traverses the FML handshake, and requires the explicit missing-hello timeout on both sides.

The harness then launches another real client as a non-operator. Modern clients must receive a command tree containing public `status` but not operator-only `diagnostics`; after promotion, the server must resend a tree containing both. The promoted client executes `/jammarr diagnostics` and must visibly receive a `Plex=` health summary. The legacy client executes public status and operator diagnostics directly, must receive the denial before promotion, and must receive the diagnostics summary afterward. Both the real player response and authenticated administration response are rejected if they contain the token, an HTTP URL, loopback address, localhost, or a Plex token header. Forge 1.7.10 overrides vanilla's all-or-nothing non-op command gate so its public root/status behavior matches modern targets while retaining Jammarr's subcommand checks.

Finally, the harness requests shutdown over authenticated loopback RCON (or the working legacy console), and fails on missing Plex requests, missing client evidence, missing save/shutdown markers, a lingering process group, or a listening game/RCON port. It uses an isolated gate world, removes the temporary operator, and restores each pre-existing `server.properties` and canonical server config byte-for-byte after both runs. Console, client, connection-probe, command, and fake-Plex request evidence is written under `build/dedicated-server-gate/`. The centralized artifact inspector validates exact metadata and bytecode targets, the shared coordinator contracts, loader Controls registration and configured-key consumption, bundled core/JLayer/Jump3r libraries, mappings-appropriate Mixins (and their intentional absence on pre-Mixin Forge 1.7.10), icon alpha, translations, licenses, notices, checksums, canonical filenames, and absence of an environment token or RFC1918 deployment address.

Keep the generated `*.wrong-protocol-client.server.txt` and `*.command-client.evidence.txt` files with release evidence. These prove the client/server rejection pair, non-op/operator visibility transition, real player response, and diagnostic redaction for the exact built target; readiness alone is not a substitute.
