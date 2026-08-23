package stonytark.jammarr.core.protocol;

import stonytark.jammarr.core.model.StationModels.ItemKind;
import stonytark.jammarr.core.model.StationModels.MediaItem;
import stonytark.jammarr.core.model.StationModels.StationSeed;
import stonytark.jammarr.core.model.StationModels.StationType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared protocol-5 request and browsing packet models/codecs. */
public final class ControlPackets {
    public enum BrowseKind { SEARCH, ARTISTS, ALBUMS, PLAYLISTS, QUEUE }
    public enum ControlAction { PAUSE, RESUME, SKIP, CLEAR, REMOVE, MOVE_UP, MOVE_DOWN }
    public enum StationAction { START, START_NOW, STOP, SET_AUTOPLAY, PREVIEW_ADVENTURE }

    public static final WireCodec<MediaItem> MEDIA_ITEM = new WireCodec<MediaItem>() {
        @Override public MediaItem decode(WireInput input) { return readMediaItem(input); }
        @Override public void encode(WireOutput output, MediaItem value) { writeMediaItem(output, value); }
    };

    public static final WireCodec<StationSeed> STATION_SEED = new WireCodec<StationSeed>() {
        @Override public StationSeed decode(WireInput input) { return readStationSeed(input); }
        @Override public void encode(WireOutput output, StationSeed value) { writeStationSeed(output, value); }
    };

    public static final WireCodec<ClientHello> CLIENT_HELLO = new WireCodec<ClientHello>() {
        @Override public ClientHello decode(WireInput input) { return new ClientHello(input.readVarInt()); }
        @Override public void encode(WireOutput output, ClientHello value) { output.writeVarInt(value.protocolVersion()); }
    };

    public static final WireCodec<ServerHello> SERVER_HELLO = new WireCodec<ServerHello>() {
        @Override public ServerHello decode(WireInput input) { return new ServerHello(input.readVarInt(), input.readLong()); }
        @Override public void encode(WireOutput output, ServerHello value) {
            output.writeVarInt(value.protocolVersion());
            output.writeLong(value.serverEpochMs());
        }
    };

    public static final WireCodec<TimeSyncRequest> TIME_SYNC_REQUEST = new WireCodec<TimeSyncRequest>() {
        @Override public TimeSyncRequest decode(WireInput input) { return new TimeSyncRequest(input.readVarLong(), input.readLong()); }
        @Override public void encode(WireOutput output, TimeSyncRequest value) {
            output.writeVarLong(value.nonce());
            output.writeLong(value.clientSentEpochMs());
        }
    };

    public static final WireCodec<TimeSyncResponse> TIME_SYNC_RESPONSE = new WireCodec<TimeSyncResponse>() {
        @Override public TimeSyncResponse decode(WireInput input) {
            return new TimeSyncResponse(input.readVarLong(), input.readLong(), input.readLong());
        }
        @Override public void encode(WireOutput output, TimeSyncResponse value) {
            output.writeVarLong(value.nonce());
            output.writeLong(value.clientSentEpochMs());
            output.writeLong(value.serverEpochMs());
        }
    };

    public static final WireCodec<BrowseRequest> BROWSE_REQUEST = new WireCodec<BrowseRequest>() {
        @Override public BrowseRequest decode(WireInput input) {
            return new BrowseRequest(readEnum(input, BrowseKind.class), input.readUtf(128), input.readVarInt());
        }
        @Override public void encode(WireOutput output, BrowseRequest value) {
            writeEnum(output, value.kind());
            output.writeUtf(value.query(), 128);
            output.writeVarInt(value.page());
        }
    };

    public static final WireCodec<BrowseResults> BROWSE_RESULTS = new WireCodec<BrowseResults>() {
        @Override public BrowseResults decode(WireInput input) {
            BrowseKind kind = readEnum(input, BrowseKind.class);
            String query = input.readUtf(128);
            int page = input.readVarInt();
            boolean hasMore = input.readBoolean();
            int count = boundedCount(input, ProtocolLimits.MAX_BROWSE_RESULTS, "browse results");
            List<MediaItem> items = new ArrayList<MediaItem>(count);
            for (int i = 0; i < count; i++) items.add(readMediaItem(input));
            return new BrowseResults(kind, query, page, hasMore, items);
        }
        @Override public void encode(WireOutput output, BrowseResults value) {
            writeEnum(output, value.kind());
            output.writeUtf(value.query(), 128);
            output.writeVarInt(value.page());
            output.writeBoolean(value.hasMore());
            int count = Math.min(ProtocolLimits.MAX_BROWSE_RESULTS, value.items().size());
            output.writeVarInt(count);
            for (int i = 0; i < count; i++) writeMediaItem(output, value.items().get(i));
        }
    };

    public static final WireCodec<QueueRequest> QUEUE_REQUEST = new WireCodec<QueueRequest>() {
        @Override public QueueRequest decode(WireInput input) {
            return new QueueRequest(readEnum(input, ItemKind.class), input.readUtf(256));
        }
        @Override public void encode(WireOutput output, QueueRequest value) {
            writeEnum(output, value.kind());
            output.writeUtf(value.key(), 256);
        }
    };

    public static final WireCodec<ControlRequest> CONTROL_REQUEST = new WireCodec<ControlRequest>() {
        @Override public ControlRequest decode(WireInput input) {
            return new ControlRequest(readEnum(input, ControlAction.class), input.readVarInt(), input.readUtf(256));
        }
        @Override public void encode(WireOutput output, ControlRequest value) {
            writeEnum(output, value.action());
            output.writeVarInt(value.index());
            output.writeUtf(value.expectedKey(), 256);
        }
    };

    public static final WireCodec<StationRequest> STATION_REQUEST = new WireCodec<StationRequest>() {
        @Override public StationRequest decode(WireInput input) {
            StationAction action = readEnum(input, StationAction.class);
            StationType type = readEnum(input, StationType.class);
            boolean enabled = input.readBoolean();
            long generation = input.readVarLong();
            int count = boundedCount(input, ProtocolLimits.MAX_STATION_SEEDS, "station seeds");
            List<StationSeed> seeds = new ArrayList<StationSeed>(count);
            for (int i = 0; i < count; i++) seeds.add(readStationSeed(input));
            return new StationRequest(action, type, enabled, generation, seeds);
        }
        @Override public void encode(WireOutput output, StationRequest value) {
            writeEnum(output, value.action());
            writeEnum(output, value.stationType());
            output.writeBoolean(value.enabled());
            output.writeVarLong(value.expectedGeneration());
            int count = Math.min(ProtocolLimits.MAX_STATION_SEEDS, value.seeds().size());
            output.writeVarInt(count);
            for (int i = 0; i < count; i++) writeStationSeed(output, value.seeds().get(i));
        }
    };

    public static final class ClientHello {
        private final int protocolVersion;
        public ClientHello(int protocolVersion) { this.protocolVersion = protocolVersion; }
        public int protocolVersion() { return protocolVersion; }
    }

    public static final class ServerHello {
        private final int protocolVersion;
        private final long serverEpochMs;
        public ServerHello(int protocolVersion, long serverEpochMs) {
            this.protocolVersion = protocolVersion;
            this.serverEpochMs = serverEpochMs;
        }
        public int protocolVersion() { return protocolVersion; }
        public long serverEpochMs() { return serverEpochMs; }
    }

    public static final class TimeSyncRequest {
        private final long nonce;
        private final long clientSentEpochMs;
        public TimeSyncRequest(long nonce, long clientSentEpochMs) {
            this.nonce = nonce;
            this.clientSentEpochMs = clientSentEpochMs;
        }
        public long nonce() { return nonce; }
        public long clientSentEpochMs() { return clientSentEpochMs; }
    }

    public static final class TimeSyncResponse {
        private final long nonce;
        private final long clientSentEpochMs;
        private final long serverEpochMs;
        public TimeSyncResponse(long nonce, long clientSentEpochMs, long serverEpochMs) {
            this.nonce = nonce;
            this.clientSentEpochMs = clientSentEpochMs;
            this.serverEpochMs = serverEpochMs;
        }
        public long nonce() { return nonce; }
        public long clientSentEpochMs() { return clientSentEpochMs; }
        public long serverEpochMs() { return serverEpochMs; }
    }

    public static final class BrowseRequest {
        private final BrowseKind kind;
        private final String query;
        private final int page;
        public BrowseRequest(BrowseKind kind, String query, int page) {
            this.kind = require(kind, "kind");
            this.query = safe(query);
            this.page = page;
        }
        public BrowseKind kind() { return kind; }
        public String query() { return query; }
        public int page() { return page; }
    }

    public static final class BrowseResults {
        private final BrowseKind kind;
        private final String query;
        private final int page;
        private final boolean hasMore;
        private final List<MediaItem> items;
        public BrowseResults(BrowseKind kind, String query, int page, boolean hasMore, List<MediaItem> items) {
            this.kind = require(kind, "kind");
            this.query = safe(query);
            this.page = page;
            this.hasMore = hasMore;
            this.items = immutable(items);
        }
        public BrowseKind kind() { return kind; }
        public String query() { return query; }
        public int page() { return page; }
        public boolean hasMore() { return hasMore; }
        public List<MediaItem> items() { return items; }
    }

    public static final class QueueRequest {
        private final ItemKind kind;
        private final String key;
        public QueueRequest(ItemKind kind, String key) {
            this.kind = require(kind, "kind");
            this.key = safe(key);
        }
        public ItemKind kind() { return kind; }
        public String key() { return key; }
    }

    public static final class ControlRequest {
        private final ControlAction action;
        private final int index;
        private final String expectedKey;
        public ControlRequest(ControlAction action, int index, String expectedKey) {
            this.action = require(action, "action");
            this.index = index;
            this.expectedKey = safe(expectedKey);
        }
        public ControlAction action() { return action; }
        public int index() { return index; }
        public String expectedKey() { return expectedKey; }
    }

    public static final class StationRequest {
        private final StationAction action;
        private final StationType stationType;
        private final boolean enabled;
        private final long expectedGeneration;
        private final List<StationSeed> seeds;
        public StationRequest(StationAction action, StationType stationType, boolean enabled,
                              long expectedGeneration, List<StationSeed> seeds) {
            this.action = require(action, "action");
            this.stationType = require(stationType, "stationType");
            this.enabled = enabled;
            this.expectedGeneration = expectedGeneration;
            this.seeds = immutable(seeds);
        }
        public StationAction action() { return action; }
        public StationType stationType() { return stationType; }
        public boolean enabled() { return enabled; }
        public long expectedGeneration() { return expectedGeneration; }
        public List<StationSeed> seeds() { return seeds; }
    }

    private static MediaItem readMediaItem(WireInput input) {
        return new MediaItem(readEnum(input, ItemKind.class), input.readUtf(256), input.readUtf(256),
                input.readUtf(256), input.readVarLong());
    }

    private static void writeMediaItem(WireOutput output, MediaItem item) {
        writeEnum(output, item.kind());
        output.writeUtf(item.key(), 256);
        output.writeUtf(item.title(), 256);
        output.writeUtf(item.subtitle(), 256);
        output.writeVarLong(item.durationMs());
    }

    private static StationSeed readStationSeed(WireInput input) {
        return new StationSeed(readEnum(input, ItemKind.class), input.readUtf(256), input.readUtf(256), input.readUtf(256));
    }

    private static void writeStationSeed(WireOutput output, StationSeed seed) {
        writeEnum(output, seed.kind());
        output.writeUtf(seed.key(), 256);
        output.writeUtf(seed.title(), 256);
        output.writeUtf(seed.subtitle(), 256);
    }

    private static int boundedCount(WireInput input, int maximum, String field) {
        int count = input.readVarInt();
        if (count < 0 || count > maximum) throw new ProtocolException("Jammarr " + field + " count exceeds " + maximum);
        return count;
    }

    private static <T extends Enum<T>> T readEnum(WireInput input, Class<T> type) {
        int ordinal = input.readVarInt();
        T[] values = type.getEnumConstants();
        if (ordinal < 0 || ordinal >= values.length) throw new ProtocolException("Invalid " + type.getSimpleName() + " ordinal " + ordinal);
        return values[ordinal];
    }

    private static void writeEnum(WireOutput output, Enum<?> value) {
        if (value == null) throw new ProtocolException("Enum value is null");
        output.writeVarInt(value.ordinal());
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name);
        return value;
    }
    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values == null ? Collections.<T>emptyList() : values));
    }
    private ControlPackets() {}
}
