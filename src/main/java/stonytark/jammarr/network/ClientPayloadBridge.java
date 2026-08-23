package stonytark.jammarr.network;

import stonytark.jammarr.core.protocol.JammarrMessage;
import java.util.function.Consumer;

public final class ClientPayloadBridge {
    private static Consumer<JammarrMessage> receiver = payload -> {};
    public static void install(Consumer<JammarrMessage> value) { receiver = value; }
    public static void accept(JammarrMessage payload) { receiver.accept(payload); }
    private ClientPayloadBridge() {}
}
