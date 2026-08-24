# Migration

Back up the world and configuration before changing Minecraft versions or loaders.

1. Stop the server cleanly.
2. Install the Jammarr artifact matching the destination Minecraft version and loader on the server and every client.
3. Keep `world/serverconfig/jammarr-server.toml` with the world and keep each client's `config/jammarr-client.toml` locally.
4. Start the server and inspect `/jammarr diagnostics` before allowing players to queue music.

Jammarr saved data is named `jammarr_global_queue` and writes schema 4. Readers retain schema 1-3 queue/current/checkpoint data and schema 2-3 source, station, Adventure, autoplay, and history fields where present. Queue entries, active source, station seeds/waypoints, generation, autoplay, checkpoint, pause state, and repeat-suppression history survive supported loader migrations. Generated lookahead is intentionally rebuilt.

When a canonical config is absent, Jammarr imports a recognized older PAmpMod/Jammarr loader-specific file once, validates and clamps every value, writes the canonical file, and leaves the source untouched. `JAMMARR_PLEX_TOKEN` always overrides a file token. Tokens, cache paths, cached media, and private Plex addresses are not stored in world saved data.

Minecraft itself controls whether a world may be upgraded between game versions. Do not use Jammarr migration support as a promise that vanilla world downgrades are safe.
