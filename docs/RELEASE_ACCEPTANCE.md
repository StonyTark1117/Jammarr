# Release acceptance

The automated root gate is:

```bash
./gradlew releaseMatrixGate --no-daemon --max-workers=1
```

It must produce 13 JARs plus `manifest.json` and `SHA256SUMS` under `build/releases/`. Run `sha256sum -c SHA256SUMS` from that directory before publication.

Automation is necessary but does not prove audible playback. Before publishing a Minecraft family, record a real result for every loader in that family:

| Target | Dedicated start/stop | Required-client rejection | Fake Plex | Live Plex | Two-client audible playback | Result |
| --- | --- | --- | --- | --- | --- | --- |
| 1.7.10 Forge | automated gate | pending | automated gate | pending | pending | pending |
| 1.20.1 Fabric | automated gate | automated missing-client probe | automated gate | pending | pending | pending |
| 1.20.1 Forge | automated gate | automated missing-client probe | automated gate | pending | pending | pending |
| 1.20.1 NeoForge | automated gate | automated missing-client probe | automated gate | pending | pending | pending |
| 1.20.2 Fabric | automated gate | automated missing-client probe | automated gate | pending | pending | pending |
| 1.20.2 Forge | automated gate | automated missing-client probe | automated gate | pending | pending | pending |
| 1.20.2 NeoForge | automated gate | automated missing-client probe | automated gate | pending | pending | pending |
| 1.21.1 Fabric | automated gate | automated missing-client probe | automated gate | pending | pending | pending |
| 1.21.1 Forge | automated gate | automated missing-client probe | automated gate | pending | pending | pending |
| 1.21.1 NeoForge | automated gate | automated missing-client probe | automated gate | pending | pending | pending |
| 26.1.2 Fabric | automated gate | automated missing-client probe | automated gate | pending | pending | pending |
| 26.1.2 Forge | automated gate | automated missing-client probe | automated gate | pending | pending | pending |
| 26.1.2 NeoForge | automated gate | automated missing-client probe | automated gate | pending | pending | pending |

For each audible two-client run, exercise late join, reconnect, pause/resume/skip/clear/reorder, local mute and volume, station generation, Sonic Adventure, cache-backed Plex outage playback, sound reload, underrun recovery, drift correction, and the final retry state. Confirm sound through both clients' actual output; connection state, packet receipt, decoder progress, and an allocated OpenAL source are insufficient.

`verifyDedicatedServers` launches a deterministic loopback Plex service and every target. Before its valid startup, each target must reject an intentionally invalid canonical config, fail closed, keep the invalid file unchanged, omit its credential value from diagnostics, and release the game port. Each valid server must then authenticate with the environment token and complete library discovery, server-identity, and sonic-analysis requests. For modern targets, a dependency-free protocol probe connects without a loader or Jammarr and must observe a closed connection plus either the client-visible rejection on the wire or the loader's exact client-facing rejection in the server log. Forge 1.7.10 remains pending because its missing-mod case requires a real matching Forge client to complete the legacy FML handshake. The harness then requests shutdown over authenticated loopback RCON and fails on missing Plex requests, missing rejection evidence, missing save/shutdown markers, a lingering process group, or a listening game port. It uses an isolated gate world and restores each pre-existing `server.properties` and canonical server config byte-for-byte after both runs. Console, connection-probe, and fake-Plex request evidence is written under `build/dedicated-server-gate/`. The centralized artifact inspector validates exact metadata and bytecode targets, the shared coordinator contracts, bundled core/JLayer/Jump3r libraries, mappings-appropriate Mixins (and their intentional absence on pre-Mixin Forge 1.7.10), icon alpha, translations, licenses, notices, checksums, canonical filenames, and absence of an environment token or RFC1918 deployment address.

For each dedicated-server acceptance run, also test wrong protocol, command permissions, and sanitized command/player diagnostics. These interaction checks remain pending until exercised through real clients; server readiness and the modern missing-client probe do not prove them. Invalid-config rejection and sanitization are automated by the gate, but that does not substitute for the remaining client interactions.
