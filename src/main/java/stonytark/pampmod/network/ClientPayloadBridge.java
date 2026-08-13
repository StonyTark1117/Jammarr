package stonytark.pampmod.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.function.Consumer;

public final class ClientPayloadBridge {
    private static Consumer<CustomPacketPayload> receiver = payload -> {};
    public static void install(Consumer<CustomPacketPayload> value) { receiver = value; }
    public static void accept(CustomPacketPayload payload) { receiver.accept(payload); }
    private ClientPayloadBridge() {}
}
