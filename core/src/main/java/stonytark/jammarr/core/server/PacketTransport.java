package stonytark.jammarr.core.server;

import stonytark.jammarr.core.protocol.JammarrMessage;

/** Loader-native delivery boundary for canonical protocol-6 messages. */
public interface PacketTransport<P> {
    void send(P player, JammarrMessage message);
}
