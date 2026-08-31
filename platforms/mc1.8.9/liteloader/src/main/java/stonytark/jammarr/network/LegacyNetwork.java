package stonytark.jammarr.network;

import com.mumfrey.liteloader.core.ClientPluginChannels;
import com.mumfrey.liteloader.core.PluginChannels.ChannelPolicy;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ChatComponentText;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.ByteArrayWireInput;
import stonytark.jammarr.core.protocol.ByteArrayWireOutput;
import stonytark.jammarr.core.protocol.ProtocolException;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Client-only raw bridge to Forge 1.8.9 SimpleNetworkWrapper channel framing. */
public final class LegacyNetwork {
    public interface ClientListener {
        void accept(LegacyPacketTypes.Type<?> type, Object message);
    }

    private static final int CLIENTBOUND_DISCRIMINATOR = 1;
    private static final int SERVERBOUND_DISCRIMINATOR = 0;
    private static final int MAX_ENVELOPE_BYTES = 1024 * 1024;
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
        ByteArrayWireOutput bodyOutput = new ByteArrayWireOutput();
        type.codec().encode(bodyOutput, message);
        byte[] body = bodyOutput.toByteArray();
        if (body.length > MAX_ENVELOPE_BYTES) throw new ProtocolException("Legacy packet exceeds envelope limit");

        PacketBuffer payload = new PacketBuffer(Unpooled.buffer());
        payload.writeByte(SERVERBOUND_DISCRIMINATOR);
        payload.writeVarIntToBuffer(type.id());
        payload.writeVarIntToBuffer(body.length);
        payload.writeBytes(body);
        if (!ClientPluginChannels.sendMessage(Jammarr.MOD_ID, payload, ChannelPolicy.DISPATCH_ALWAYS)) {
            Jammarr.LOGGER.warn("Unable to dispatch Jammarr payload because the client is no longer connected");
        }
    }

    public static void receive(String channel, PacketBuffer data) {
        if (!Jammarr.MOD_ID.equals(channel)) return;
        try {
            int discriminator = data.readUnsignedByte();
            if (discriminator != CLIENTBOUND_DISCRIMINATOR) {
                throw new ProtocolException("Unexpected Forge discriminator " + discriminator);
            }
            int id = data.readVarIntFromBuffer();
            LegacyPacketTypes.Type<?> type = LegacyPacketTypes.byId(id);
            if (type == null) throw new ProtocolException("Unknown legacy packet ID " + id);
            if (type.direction() != LegacyPacketTypes.Direction.CLIENTBOUND) {
                throw new ProtocolException("Legacy packet " + type.name() + " arrived in the wrong direction");
            }
            int length = data.readVarIntFromBuffer();
            if (length < 0 || length > MAX_ENVELOPE_BYTES || length != data.readableBytes()) {
                throw new ProtocolException("Invalid legacy packet length " + length);
            }
            byte[] bytes = new byte[length];
            data.readBytes(bytes);
            ByteArrayWireInput input = new ByteArrayWireInput(bytes);
            Object decoded = type.codec().decode(input);
            if (input.remaining() != 0) throw new ProtocolException("Legacy packet " + type.name() + " has trailing bytes");
            CLIENT_INBOX.add(new ClientIncoming(type, decoded));
        } catch (RuntimeException malformed) {
            Jammarr.LOGGER.error("Rejected malformed Jammarr packet from the paired Forge server", malformed);
            if (Minecraft.getMinecraft().getNetHandler() != null) {
                Minecraft.getMinecraft().getNetHandler().getNetworkManager().closeChannel(
                        new ChatComponentText("Malformed Jammarr packet: " + malformed.getMessage()));
            }
        }
    }

    public static void clientTick() {
        ClientListener listener = clientListener;
        ClientIncoming incoming;
        while ((incoming = CLIENT_INBOX.poll()) != null) {
            if (listener != null) listener.accept(incoming.type, incoming.message);
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
