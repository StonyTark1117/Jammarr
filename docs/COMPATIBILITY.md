# Compatibility

Jammarr 1.0.1 produces 16 required client-and-server artifacts covering 21 supported loader/version runtimes. The server and every client must use the same Minecraft version, loader, and Jammarr version. Supported Quilt installations use the matching Fabric artifact.

| Minecraft | Java | Loader | Pinned loader/API |
| --- | ---: | --- | --- |
| 1.7.10 | 8 | Forge | Forge 10.13.4.1614 |
| 1.20.1 | 17 | Fabric | Loader 0.19.3, Fabric API 0.92.11 |
| 1.20.1 | 17 | Quilt | Quilt Loader 0.30.0, Fabric API 0.92.11, `-fabric.jar` |
| 1.20.1 | 17 | Forge | Forge 47.4.23 |
| 1.20.1 | 17 | NeoForge | Transitional NeoForge/Forge 47.1.106 |
| 1.20.2 | 17 | Fabric | Loader 0.19.3, Fabric API 0.91.6 |
| 1.20.2 | 17 | Quilt | Quilt Loader 0.30.0, Fabric API 0.91.6, `-fabric.jar` |
| 1.20.2 | 17 | Forge | Forge 48.1.0 |
| 1.20.2 | 17 | NeoForge | NeoForge 20.2.93 |
| 1.21.1 | 21 | Fabric | Loader 0.19.3, Fabric API 0.116.15 |
| 1.21.1 | 21 | Quilt | Quilt Loader 0.30.0, Fabric API 0.116.15, `-fabric.jar` |
| 1.21.1 | 21 | Forge | Forge 52.1.16 |
| 1.21.1 | 21 | NeoForge | NeoForge 21.1.248 |
| 26.1.2 | 25 | Fabric | Loader 0.19.3, Fabric API 0.155.2 |
| 26.1.2 | 25 | Quilt | Quilt Loader 0.30.0, Fabric API 0.155.2, `-fabric.jar` |
| 26.1.2 | 25 | Forge | Forge 64.1.2 |
| 26.1.2 | 25 | NeoForge | NeoForge 26.1.2.97 |
| 26.2 | 25 | Fabric | Loader 0.19.3, Fabric API 0.158.0 |
| 26.2 | 25 | Quilt | Quilt Loader 0.30.0, Fabric API 0.158.0, `-fabric.jar` |
| 26.2 | 25 | Forge | Forge 65.1.2 |
| 26.2 | 25 | NeoForge | NeoForge 26.2.0.67 |

The complete versions, including build plugins, are authoritative in `gradle/version-catalogs/mc-<version>.toml` and copied to each record in `build/releases/manifest.json`.

Fabric, Quilt, and NeoForge are unavailable for Minecraft 1.7.10. Every modern Fabric artifact declares `fabricloader >=0.19.2`, which Quilt Loader 0.30.0 satisfies; ordinary Fabric builds remain pinned to Fabric Loader 0.19.3. The non-obfuscated 26.x targets include guarded bootstrap and payload-codec compatibility hooks for Quilt Loader 0.30.0; they are inert under Fabric and idempotent if Quilt supplies the corresponding initialization itself. QSL and Quilted Fabric API are not required. Unmodded clients, Fabric-to-Quilt clients, other cross-loader clients, cross-Minecraft clients, and worlds downgraded through unsupported vanilla world formats are not supported.

All targets use logical protocol 5, canonical saved-data schema 4, `world/serverconfig/jammarr-server.toml`, `config/jammarr-client.toml`, and the `JAMMARR_PLEX_TOKEN` environment variable. Plex is contacted only by the Minecraft server. Compressed, bounded, hash-validated audio windows travel through the mod's Minecraft network channel to each client.
