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
| 1.20.1 Fabric | automated gate | pending | automated gate | pending | pending | pending |
| 1.20.1 Forge | automated gate | pending | automated gate | pending | pending | pending |
| 1.20.1 NeoForge | automated gate | pending | automated gate | pending | pending | pending |
| 1.20.2 Fabric | automated gate | pending | automated gate | pending | pending | pending |
| 1.20.2 Forge | automated gate | pending | automated gate | pending | pending | pending |
| 1.20.2 NeoForge | automated gate | pending | automated gate | pending | pending | pending |
| 1.21.1 Fabric | automated gate | pending | automated gate | pending | pending | pending |
| 1.21.1 Forge | automated gate | pending | automated gate | pending | pending | pending |
| 1.21.1 NeoForge | automated gate | pending | automated gate | pending | pending | pending |
| 26.1.2 Fabric | automated gate | pending | automated gate | pending | pending | pending |
| 26.1.2 Forge | automated gate | pending | automated gate | pending | pending | pending |
| 26.1.2 NeoForge | automated gate | pending | automated gate | pending | pending | pending |

For each audible two-client run, exercise late join, reconnect, pause/resume/skip/clear/reorder, local mute and volume, station generation, Sonic Adventure, cache-backed Plex outage playback, sound reload, underrun recovery, drift correction, and the final retry state. Confirm sound through both clients' actual output; connection state, packet receipt, decoder progress, and an allocated OpenAL source are insufficient.

`verifyDedicatedServers` launches a deterministic loopback Plex service and every target. Each server must authenticate with the environment token and complete library discovery, server-identity, and sonic-analysis requests before the harness requests shutdown over authenticated loopback RCON. The harness fails on missing Plex requests, missing save/shutdown markers, a lingering process group, or a listening game port; it restores each pre-existing canonical server config after the run. Console and fake-Plex request evidence is written under `build/dedicated-server-gate/`. The centralized artifact inspector validates exact metadata and bytecode targets, the shared coordinator contracts, bundled core/JLayer/Jump3r libraries, mappings-appropriate Mixins (and their intentional absence on pre-Mixin Forge 1.7.10), icon alpha, translations, licenses, notices, checksums, canonical filenames, and absence of an environment token or RFC1918 deployment address.

For each dedicated-server acceptance run, also test wrong protocol, command permissions, invalid config rejection, and sanitized diagnostics. These interaction checks remain pending until exercised through real clients; server readiness alone does not prove them.
