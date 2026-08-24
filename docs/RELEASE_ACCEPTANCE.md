# Release acceptance

The automated root gate is:

```bash
./gradlew releaseMatrixGate --no-daemon --max-workers=1
```

It must produce 13 JARs plus `manifest.json` and `SHA256SUMS` under `build/releases/`. Run `sha256sum -c SHA256SUMS` from that directory before publication.

Automation is necessary but does not prove audible playback. Before publishing a Minecraft family, record a real result for every loader in that family:

| Target | Dedicated start/stop | Required-client rejection | Fake Plex | Live Plex | Two-client audible playback | Result |
| --- | --- | --- | --- | --- | --- | --- |
| 1.7.10 Forge | pending | pending | pending | pending | pending | pending |
| 1.20.1 Fabric | pending | pending | pending | pending | pending | pending |
| 1.20.1 Forge | pending | pending | pending | pending | pending | pending |
| 1.20.1 NeoForge | pending | pending | pending | pending | pending | pending |
| 1.20.2 Fabric | pending | pending | pending | pending | pending | pending |
| 1.20.2 Forge | pending | pending | pending | pending | pending | pending |
| 1.20.2 NeoForge | pending | pending | pending | pending | pending | pending |
| 1.21.1 Fabric | pending | pending | pending | pending | pending | pending |
| 1.21.1 Forge | pending | pending | pending | pending | pending | pending |
| 1.21.1 NeoForge | pending | pending | pending | pending | pending | pending |
| 26.1.2 Fabric | pending | pending | pending | pending | pending | pending |
| 26.1.2 Forge | pending | pending | pending | pending | pending | pending |
| 26.1.2 NeoForge | pending | pending | pending | pending | pending | pending |

For each audible two-client run, exercise late join, reconnect, pause/resume/skip/clear/reorder, local mute and volume, station generation, Sonic Adventure, cache-backed Plex outage playback, sound reload, underrun recovery, drift correction, and the final retry state. Confirm sound through both clients' actual output; connection state, packet receipt, decoder progress, and an allocated OpenAL source are insufficient.

For each dedicated server run, also test wrong protocol, command permissions, invalid config rejection, sanitized diagnostics, clean shutdown, and absence of a lingering process or listening port. Inspect every final JAR for exact loader/Minecraft metadata, bundled JLayer and Jump3r, mappings-appropriate Mixins, icon, translations, Jammarr and LGPL licenses, third-party notice, canonical filename, and absence of credentials or private addresses.
