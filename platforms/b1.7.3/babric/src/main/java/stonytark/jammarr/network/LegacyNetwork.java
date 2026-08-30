package stonytark.jammarr.network;

import net.modificationstation.stationapi.api.network.packet.MessagePacket;
import net.modificationstation.stationapi.api.network.packet.PacketHelper;
import net.modificationstation.stationapi.api.util.Identifier;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.ProtocolException;

/** Client-safe half of the Babric/StationAPI Beta 1.7.3 transport. */
public final class LegacyNetwork {
    public interface ClientListener {
        void accept(LegacyPacketTypes.Type<?> type, Object message);
    }

    public static final Identifier CHANNEL = Identifier.of("jammarr:main");
    private static volatile ClientListener clientListener;
    private static volatile boolean serverAvailable;

    public static void setClientListener(ClientListener listener) { clientListener = listener; }
    public static void clientConnected() { serverAvailable = true; }
    public static void clientDisconnected() { serverAvailable = false; }
    public static boolean serverAvailable() { return serverAvailable; }

    public static void receiveClient(MessagePacket packet) {
        try {
            LegacyEnvelope envelope = LegacyEnvelope.read(packet == null ? null : packet.bytes);
            Object message = envelope.decode(LegacyPacketTypes.Direction.CLIENTBOUND);
            ClientListener listener = clientListener;
            if (listener != null) listener.accept(envelope.type(), message);
        } catch (ProtocolException malformed) {
            Jammarr.LOGGER.warn("Ignoring malformed Jammarr server packet", malformed);
        }
    }

    public static <T> void sendToServer(LegacyPacketTypes.Type<T> type, T message) {
        if (!serverAvailable) return;
        MessagePacket packet = new MessagePacket(CHANNEL);
        packet.bytes = LegacyEnvelope.encode(type, message).toByteArray();
        PacketHelper.send(packet);
    }

    private LegacyNetwork() {}
}
