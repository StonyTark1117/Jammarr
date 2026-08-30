package stonytark.jammarr.network;

import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.core.protocol.TransportPackets;
import stonytark.jammarr.core.protocol.WireCodec;
import stonytark.jammarr.core.protocol.WireInput;
import stonytark.jammarr.core.protocol.WireOutput;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable message identifiers carried inside the Ornithe 1.6.4 networking channel. */
public final class LegacyPacketTypes {
    public enum Direction { CLIENTBOUND, SERVERBOUND }

    public static final class OpenScreen {
        public static final OpenScreen INSTANCE = new OpenScreen();
        private OpenScreen() {}
    }

    public static final class Type<T> {
        private final int id;
        private final String name;
        private final Direction direction;
        private final WireCodec<T> codec;

        private Type(int id, String name, Direction direction, WireCodec<T> codec) {
            this.id = id;
            this.name = name;
            this.direction = direction;
            this.codec = codec;
        }

        public int id() { return id; }
        public String name() { return name; }
        public Direction direction() { return direction; }
        public WireCodec<T> codec() { return codec; }
    }

    private static final WireCodec<OpenScreen> OPEN_SCREEN_CODEC = new WireCodec<OpenScreen>() {
        @Override public OpenScreen decode(WireInput input) { return OpenScreen.INSTANCE; }
        @Override public void encode(WireOutput output, OpenScreen value) {}
    };

    private static final Map<Integer, Type<?>> MUTABLE_TYPES = new LinkedHashMap<Integer, Type<?>>();

    public static final Type<OpenScreen> OPEN_SCREEN = type(0, "open_screen", Direction.CLIENTBOUND, OPEN_SCREEN_CODEC);
    public static final Type<ControlPackets.ClientHello> CLIENT_HELLO = type(1, "client_hello", Direction.SERVERBOUND, ControlPackets.CLIENT_HELLO);
    public static final Type<ControlPackets.ServerHello> SERVER_HELLO = type(2, "server_hello", Direction.CLIENTBOUND, ControlPackets.SERVER_HELLO);
    public static final Type<ControlPackets.TimeSyncRequest> TIME_SYNC_REQUEST = type(3, "time_sync_request", Direction.SERVERBOUND, ControlPackets.TIME_SYNC_REQUEST);
    public static final Type<ControlPackets.TimeSyncResponse> TIME_SYNC_RESPONSE = type(4, "time_sync_response", Direction.CLIENTBOUND, ControlPackets.TIME_SYNC_RESPONSE);
    public static final Type<ControlPackets.BrowseRequest> BROWSE_REQUEST = type(5, "browse_request", Direction.SERVERBOUND, ControlPackets.BROWSE_REQUEST);
    public static final Type<ControlPackets.BrowseResults> BROWSE_RESULTS = type(6, "browse_results", Direction.CLIENTBOUND, ControlPackets.BROWSE_RESULTS);
    public static final Type<ControlPackets.QueueRequest> QUEUE_REQUEST = type(7, "queue_request", Direction.SERVERBOUND, ControlPackets.QUEUE_REQUEST);
    public static final Type<ControlPackets.ControlRequest> CONTROL_REQUEST = type(8, "control_request", Direction.SERVERBOUND, ControlPackets.CONTROL_REQUEST);
    public static final Type<ControlPackets.StationRequest> STATION_REQUEST = type(9, "station_request", Direction.SERVERBOUND, ControlPackets.STATION_REQUEST);
    public static final Type<TransportPackets.ChunkRequest> CHUNK_REQUEST = type(10, "chunk_request", Direction.SERVERBOUND, TransportPackets.CHUNK_REQUEST);
    public static final Type<TransportPackets.ChunkAcknowledgement> CHUNK_ACKNOWLEDGEMENT = type(11, "chunk_ack", Direction.SERVERBOUND, TransportPackets.CHUNK_ACKNOWLEDGEMENT);
    public static final Type<StatePackets.AudioHealth> AUDIO_HEALTH = type(12, "audio_health", Direction.SERVERBOUND, StatePackets.AUDIO_HEALTH);
    public static final Type<StatePackets.ManifestRequest> MANIFEST_REQUEST = type(13, "manifest_request", Direction.SERVERBOUND, StatePackets.MANIFEST_REQUEST);
    public static final Type<TransportPackets.AudioManifest> AUDIO_MANIFEST = type(14, "audio_manifest", Direction.CLIENTBOUND, TransportPackets.AUDIO_MANIFEST);
    public static final Type<TransportPackets.AudioChunk> AUDIO_CHUNK = type(15, "audio_chunk", Direction.CLIENTBOUND, TransportPackets.AUDIO_CHUNK);
    public static final Type<StatePackets.PlaybackState> PLAYBACK_STATE = type(16, "playback_state", Direction.CLIENTBOUND, StatePackets.PLAYBACK_STATE);
    public static final Type<StatePackets.StationState> STATION_STATE = type(17, "station_state", Direction.CLIENTBOUND, StatePackets.STATION_STATE);
    public static final Type<StatePackets.AdventurePreview> ADVENTURE_PREVIEW = type(18, "adventure_preview", Direction.CLIENTBOUND, StatePackets.ADVENTURE_PREVIEW);
    public static final Type<StatePackets.ErrorMessage> ERROR = type(19, "error", Direction.CLIENTBOUND, StatePackets.ERROR_MESSAGE);

    public static final Map<Integer, Type<?>> TYPES = Collections.unmodifiableMap(MUTABLE_TYPES);

    private static <T> Type<T> type(int id, String name, Direction direction, WireCodec<T> codec) {
        Type<T> value = new Type<T>(id, name, direction, codec);
        if (MUTABLE_TYPES.put(id, value) != null) throw new IllegalStateException("Duplicate legacy packet ID " + id);
        return value;
    }

    public static Type<?> byId(int id) { return TYPES.get(id); }

    private LegacyPacketTypes() {}
}
