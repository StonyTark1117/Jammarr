package stonytark.jammarr.network;

import io.netty.channel.EventLoop;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;

/** Keeps Forge's PLAY transition behind the login packets already queued by vanilla. */
public final class LoginProtocolOrder {
    private LoginProtocolOrder() { }

    public static void setConnectionState(final NetworkManager manager, final EnumConnectionState state) {
        EventLoop loop = manager.channel().eventLoop();
        if (loop.inEventLoop()) {
            manager.setConnectionState(state);
        } else {
            // NetworkManager queues LOGIN writes here too. Changing the state on the server
            // thread first makes their encoder see PLAY and reject unregistered login packets.
            loop.execute(new Runnable() {
                @Override public void run() { manager.setConnectionState(state); }
            });
        }
    }
}
