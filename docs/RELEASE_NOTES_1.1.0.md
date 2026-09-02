# Jammarr 1.1.0

Jammarr 1.1.0 greatly expands Minecraft and loader support while making the server safer for mixed modded and unmodded communities.

## Highlights

- Clients without Jammarr may join a Jammarr server normally. They do not receive the music interface or audio stream and are excluded from Jammarr listener accounting. Clients that install Jammarr still need the matching Minecraft/loader build; an older incompatible Jammarr protocol receives a clear disconnect instead of undefined behavior.
- The supported matrix now covers 29 Minecraft versions, from Beta 1.7.3 through 26.2, with 78 release artifacts and 99 certified server runtime profiles. This includes the requested 1.12.2, 1.16.5, 1.18.2, and 1.19.2 additions plus complete 1.20.x and 1.21.x version coverage wherever each loader actually exists.
- Modern Fabric artifacts are also the Quilt artifacts for their Minecraft version. Do not install a duplicate Quilt file. NeoForge and Forge remain separate builds.
- Preview support is available for Babric/StationAPI Beta 1.7.3, Legacy Fabric and Ornithe on 1.6.4 and 1.8.9, and Forge-paired LiteLoader clients on 1.6.4, 1.7.10, and 1.8.9. Consult the compatibility table before choosing one of these distinct legacy ecosystems.

## Reliability and playback

- Fixed audio skips caused by partially accepted chunk windows being retried with different bounds. Clients now reserve a complete authorized window and retry its exact original range.
- Hardened server fan-out with bounded per-listener queues, round-robin delivery, fixed worker limits, strict payload validation, and cleanup for clients that never negotiate Jammarr.
- Improved playback recovery so brief backend-clock noise is logged without unnecessarily interrupting correctly aligned audio; genuinely sustained drift still triggers bounded rebuffering.
- Added deterministic timing, fault-injection, mixed-client churn, reconnect, sound-reload, and two-client audio gates. The final sustained qualification completed 349/349 mixed-client cycles over two hours with no ordering, silence, recovery, or continuity failure.
- Added bounded Plex request, transcode, cache, retry, and diagnostic behavior so an outage or malformed response cannot grow work indefinitely or expose credentials.

## Interface and Plex behavior

- Added concise hover help for every actionable player-menu tab and control across legacy and modern interfaces.
- Fixed the Forge 1.7.10 search field losing unsubmitted text, focus, caret, or selection when playback refreshed the screen.
- Improved searching, queue feedback, long-title tooltips, station status, diagnostics, and explicit local audio retry behavior.
- Library selection now stays scoped to one Plex music section. Leaving `musicLibrary` blank prefers a music library named `Music`, then falls back to the first valid music library. Browse, search, queues, stations, metadata fallback, and transcodes all remain inside that selected section.

## Plex Pass

Normal browsing, manual queues, playback, and Library Shuffle do not require Plex Pass. Sonic Adventure requires Plex Pass and completed Plex sonic analysis.

Sonic Autoplay, Track Radio, Artist Radio, Album Radio, and Sonic Mix normally use Plex sonic recommendations. If those are unavailable, operators may enable `stationMetadataFallbackEnabled` to use lower-quality genre, style, related-item, and random-library metadata behavior without Plex Pass. This fallback is disabled by default and never substitutes for Sonic Adventure.

## Upgrading

1. Back up the world and its `serverconfig/jammarr-server.toml` file.
2. Install the exact 1.1.0 artifact for the server's Minecraft version and loader. Modern Fabric files also cover the declared Quilt runtime.
3. Update every Jammarr-enabled client to the matching 1.1.0 artifact. Alternatively, a player may remove Jammarr and join without its functionality.
4. Start the server and run `/jammarr reload`, `/jammarr diagnostics`, and `/jammarr station status` as an operator.
5. Confirm that `musicLibrary` selects the intended shared-safe Plex section before allowing players to browse it.

Jammarr 1.1.0 uses network protocol 6. Jammarr 1.0.x clients and servers use an older protocol and are not cross-compatible.

## Downloads

GitHub and Modrinth can represent all 78 exact artifacts. CurseForge receives the 70 artifacts whose Forge, Fabric/Quilt, or NeoForge loader identity it can describe accurately. Babric, Legacy Fabric, Ornithe, and LiteLoader downloads remain on GitHub and Modrinth rather than being mislabeled on CurseForge.

Use the compatibility table in the project description and the generated `manifest.json`/`SHA256SUMS` files to select and verify the exact artifact.
