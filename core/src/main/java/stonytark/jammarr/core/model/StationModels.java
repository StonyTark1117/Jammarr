package stonytark.jammarr.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Minecraft-independent station and Plex result models shared by every loader. */
public final class StationModels {
    public enum ItemKind { TRACK, ARTIST, ALBUM, PLAYLIST }
    /** Why a playlist can or cannot be queued by the selected Plex library. */
    public enum PlaylistAvailability { NONE, QUEUEABLE, EMPTY, OVERSIZED,
        OUTSIDE_SELECTED_LIBRARY, INCOMPATIBLE_CONTENT, UNAVAILABLE }
    public enum StationType { NONE, AUTOPLAY, LIBRARY_SHUFFLE, TRACK_RADIO, ARTIST_RADIO, ALBUM_RADIO, SONIC_MIX, SONIC_ADVENTURE }
    public enum SonicCapability { CHECKING, READY, NO_PLEX_PASS, ANALYSIS_INCOMPLETE, UNSUPPORTED, PLEX_OFFLINE }

    public static final class MediaItem {
        private final ItemKind kind;
        private final String key;
        private final String title;
        private final String subtitle;
        private final long durationMs;
        private final PlaylistAvailability availability;

        public MediaItem(ItemKind kind, String key, String title, String subtitle, long durationMs) {
            this(kind, key, title, subtitle, durationMs, PlaylistAvailability.NONE);
        }

        public MediaItem(ItemKind kind, String key, String title, String subtitle, long durationMs,
                         PlaylistAvailability availability) {
            this.kind = kind == null ? ItemKind.TRACK : kind;
            this.key = safe(key);
            this.title = safe(title);
            this.subtitle = safe(subtitle);
            this.durationMs = Math.max(0, durationMs);
            this.availability = availability == null ? PlaylistAvailability.NONE : availability;
        }

        public ItemKind kind() { return kind; }
        public String key() { return key; }
        public String title() { return title; }
        public String subtitle() { return subtitle; }
        public long durationMs() { return durationMs; }
        public PlaylistAvailability availability() { return availability; }
    }

    public static final class StationSeed {
        private final ItemKind kind;
        private final String key;
        private final String title;
        private final String subtitle;

        public StationSeed(ItemKind kind, String key, String title, String subtitle) {
            this.kind = kind == null ? ItemKind.TRACK : kind;
            this.key = safe(key);
            this.title = safe(title);
            this.subtitle = safe(subtitle);
        }

        public ItemKind kind() { return kind; }
        public String key() { return key; }
        public String title() { return title; }
        public String subtitle() { return subtitle; }
    }

    public static final class StationDefinition {
        private final StationType type;
        private final String name;
        private final List<StationSeed> seeds;
        private final long generation;

        public StationDefinition(StationType type, String name, List<StationSeed> seeds, long generation) {
            this.type = type == null ? StationType.NONE : type;
            this.name = safe(name);
            List<StationSeed> bounded = seeds == null ? Collections.<StationSeed>emptyList() : seeds;
            int size = Math.min(5, bounded.size());
            this.seeds = Collections.unmodifiableList(new ArrayList<StationSeed>(bounded.subList(0, size)));
            this.generation = Math.max(0, generation);
        }

        public static StationDefinition none(long generation) {
            return new StationDefinition(StationType.NONE, "", Collections.<StationSeed>emptyList(), generation);
        }

        public StationType type() { return type; }
        public String name() { return name; }
        public List<StationSeed> seeds() { return seeds; }
        public long generation() { return generation; }
        public boolean active() { return type != StationType.NONE; }
        public boolean adventure() { return type == StationType.SONIC_ADVENTURE; }
    }

    public static final class SonicResult {
        private final MediaItem item;
        private final double distance;

        public SonicResult(MediaItem item, double distance) {
            if (item == null) throw new IllegalArgumentException("item");
            this.item = item;
            this.distance = distance;
        }

        public MediaItem item() { return item; }
        public double distance() { return distance; }
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private StationModels() {}
}
