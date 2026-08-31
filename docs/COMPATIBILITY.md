# Compatibility

Jammarr 1.1.0 currently produces 76 artifacts covering 99 dedicated-server loader/version runtimes across 29 Minecraft versions. Jammarr is optional on clients: an unmodified client can remain on a Jammarr server but receives no browsing, controls, or audio. A participating client must use the same Minecraft version and a protocol-compatible Jammarr build. Supported Quilt installations use the matching modern Fabric artifact. The additional LiteLoader artifact is a client companion paired with Forge 1.8.9 and does not add a server runtime.

| Minecraft | Java | Babric | Fabric / Quilt | Forge | NeoForge | Ornithe | LiteLoader |
| --- | ---: | :---: | :---: | :---: | :---: | :---: | :---: |
| Beta 1.7.3 | 17 | preview (StationAPI) | unavailable | unavailable | unavailable | unavailable | unavailable |
| 1.6.4 | 8 | unavailable | preview (Legacy Fabric) / unavailable | preview | unavailable | preview | unavailable |
| 1.7.10 | 8 | unavailable | unavailable | supported | unavailable | unavailable | unavailable |
| 1.8.9 | 8 | unavailable | preview (Legacy Fabric) / unavailable | preview | unavailable | preview | preview (client companion) |
| 1.12.2 | 8 | unavailable | unavailable | supported | unavailable | unavailable | unavailable |
| 1.16.5 | 8 | unavailable | supported | supported | unavailable | unavailable | unavailable |
| 1.18.2 | 17 | unavailable | supported | supported | unavailable | unavailable | unavailable |
| 1.19.2 | 17 | unavailable | supported | supported | unavailable | unavailable | unavailable |
| 1.20 | 17 | unavailable | supported | preview | unavailable | unavailable | unavailable |
| 1.20.1 | 17 | unavailable | supported | supported | supported | unavailable | unavailable |
| 1.20.2 | 17 | unavailable | supported | supported | supported | unavailable | unavailable |
| 1.20.3 | 17 | unavailable | supported | preview | preview | unavailable | unavailable |
| 1.20.4 | 17 | unavailable | supported | supported | supported | unavailable | unavailable |
| 1.20.5 | 21 | unavailable | supported | unavailable | preview | unavailable | unavailable |
| 1.20.6 | 21 | unavailable | supported | supported | supported | unavailable | unavailable |
| 1.21 | 21 | unavailable | supported | preview | supported | unavailable | unavailable |
| 1.21.1 | 21 | unavailable | supported | supported | supported | unavailable | unavailable |
| 1.21.2 | 21 | unavailable | supported | unavailable | preview | unavailable | unavailable |
| 1.21.3 | 21 | unavailable | supported | supported | supported | unavailable | unavailable |
| 1.21.4 | 21 | unavailable | supported | supported | supported | unavailable | unavailable |
| 1.21.5 | 21 | unavailable | supported | supported | supported | unavailable | unavailable |
| 1.21.6 | 21 | unavailable | supported | preview | preview | unavailable | unavailable |
| 1.21.7 | 21 | unavailable | supported | preview | preview | unavailable | unavailable |
| 1.21.8 | 21 | unavailable | supported | supported | supported | unavailable | unavailable |
| 1.21.9 | 21 | unavailable | supported | preview | preview | unavailable | unavailable |
| 1.21.10 | 21 | unavailable | supported | supported | supported | unavailable | unavailable |
| 1.21.11 | 21 | unavailable | supported | supported | supported | unavailable | unavailable |
| 26.1.2 | 25 | unavailable | supported | supported | supported | unavailable | unavailable |
| 26.2 | 25 | unavailable | supported | supported | supported | unavailable | unavailable |

The exact loader, API, mapping, build-plugin, and wrapper versions are authoritative in `gradle/version-catalogs/mc-<version>.toml` and copied to each record in `build/releases/manifest.json`. Preview denotes an available upstream loader without the same promoted/stable confidence as the supported entries; it still must pass the full release contract before publication.

Beta 1.7.3 has a distinct Babric/StationAPI preview built with Biny `b1.7.3+e1fe071`, Fabric Loader 0.16.9, StationAPI 2.0.0-alpha.6.2, Loom/Babric 1.9.2, and Java 17. It uses StationAPI lifecycle, keybinding, and message events plus a bounded custom-payload envelope; it is not interchangeable with modern Fabric or Legacy Fabric. Modern Fabric is unavailable for Minecraft 1.7.10 and 1.12.2. Minecraft 1.6.4 and 1.8.9 have separate Legacy Fabric previews built with Yarn build 604, Fabric Loader 0.18.3, the matching Legacy Fabric API 1.13.2 release, and Java 8; those artifacts are not compatible with Quilt. The 1.6.4 adapter uses the native bounded custom-payload packet because Legacy Fabric's networking API is not available for 1.6.x. Both versions also have distinct Java 8 Ornithe generation-2 previews using Feather build 1, Fabric Loader 0.19.5, OSL 0.21.0-alpha.36, Loom 1.17.20, and Ploceus 1.17.7. The 1.6.4 Ornithe target selects only the OSL modules Jammarr uses because unrelated aggregate modules contain Mixins for later snapshots. Ornithe is not interchangeable with Legacy Fabric and is not Quilt-compatible. The LiteLoader 1.8.9 preview pins snapshot `1.8.9-SNAPSHOT-r08134E7-b8-2016-04-14_16-33-58`, produces Java 8 bytecode, and is released only as a production-reobfuscated client `.litemod`. Standalone LiteLoader server bootstrap was not viable; this companion is supported only with the matching Forge 1.8.9 Jammarr server, whose optional-client policy accepts the LiteLoader connection before protocol-6 capability negotiation. Quilt and NeoForge remain unavailable across these legacy versions. Every modern Fabric artifact declares `fabricloader >=0.19.2`, which Quilt Loader 0.30.0 satisfies where the target explicitly declares Quilt compatibility; ordinary Fabric builds remain pinned to Fabric Loader 0.19.3. The non-obfuscated 26.x targets include guarded bootstrap and payload-codec compatibility hooks for Quilt Loader 0.30.0; they are inert under Fabric and idempotent if Quilt supplies the corresponding initialization itself. QSL and Quilted Fabric API are not required. Fabric-to-Quilt clients, other cross-loader clients, cross-Minecraft clients, and worlds downgraded through unsupported vanilla world formats are not supported.

Forge 1.6.4 is compiled reproducibly against the newest ForgeGradle-compatible 9.11.1.964 userdev and runtime-certified against the official recommended Forge 9.11.1.1345 installer. It requires Java 8. Because Forge 1.6.4 embeds ASM 4, the release task validates Java 8 compiler output and then changes only Jammarr class-file headers from major 52 to ASM-4-readable major 51; the centralized inspector rejects any other class format. The official Minecraft client and Forge installer are checksum-pinned before runtime acceptance.

All 1.1.0 targets use logical protocol 6, canonical saved-data schema 4, `world/serverconfig/jammarr-server.toml`, `config/jammarr-client.toml`, and the `JAMMARR_PLEX_TOKEN` environment variable. Protocol negotiation advertises feature flags and bounded audio-window limits before the server includes a client in playback fan-out. Plex is contacted only by the Minecraft server. Compressed, bounded, hash-validated audio windows travel through the mod's Minecraft network channel to each participating client.
