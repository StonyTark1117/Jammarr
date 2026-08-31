package stonytark.jammarr.network;

import com.mumfrey.liteloader.core.PluginChannels;
import net.minecraft.client.Minecraft;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.ProtocolException;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Client-only raw bridge to Forge 1.6.4 Packet250 channel framing. */
public final class LegacyNetwork {
    public interface ClientListener {
        void accept(LegacyPacketTypes.Type<?> type, Object message);
    }

    private static final Queue<ClientIncoming> CLIENT_INBOX = new ConcurrentLinkedQueue<ClientIncoming>();
    private static volatile ClientListener clientListener;
    private static volatile boolean connected;

    public static void register() {}
    public static void setClientListener(ClientListener listener) { clientListener = listener; }
    public static boolean serverAvailable() { return connected; }

    public static void connected() {
        CLIENT_INBOX.clear();
        connected = true;
    }

    public static void disconnected() {
        CLIENT_INBOX.clear();
        connected = false;
    }

    public static <T> void sendToServer(LegacyPacketTypes.Type<T> type, T message) {
        if (!connected || type == null || type.direction() != LegacyPacketTypes.Direction.SERVERBOUND) return;
        PluginChannels.sendMessage(Jammarr.MOD_ID, LegacyEnvelope.encode(type, message).toPacket());
    }

    public static void receive(String channel, int declaredLength, byte[] data) {
        if (!Jammarr.MOD_ID.equals(channel)) return;
        try {
            if (data == null || declaredLength != data.length) {
                throw new ProtocolException("Invalid legacy plugin-channel length " + declaredLength);
            }
            LegacyEnvelope envelope = LegacyEnvelope.fromPacket(data);
            CLIENT_INBOX.add(new ClientIncoming(envelope.type(),
                    envelope.decode(LegacyPacketTypes.Direction.CLIENTBOUND)));
        } catch (RuntimeException malformed) {
            Jammarr.LOGGER.error("Rejected malformed Jammarr packet from the paired Forge server", malformed);
            disconnectClient("Malformed Jammarr packet: " + malformed.getMessage());
        }
    }

    public static void clientTick() {
        ClientListener listener = clientListener;
        ClientIncoming incoming;
        while ((incoming = CLIENT_INBOX.poll()) != null) {
            if (listener != null) listener.accept(incoming.type, incoming.message);
        }
    }

    public static void disconnectClient(String reason) {
        if (Minecraft.getMinecraft().getNetHandler() != null) {
            Minecraft.getMinecraft().getNetHandler().getNetManager().networkShutdown(reason, new Object[0]);
        }
    }

    private static final class ClientIncoming {
        private final LegacyPacketTypes.Type<?> type;
        private final Object message;
        private ClientIncoming(LegacyPacketTypes.Type<?> type, Object message) {
            this.type = type;
            this.message = message;
        }
    }

    private LegacyNetwork() {}
}
