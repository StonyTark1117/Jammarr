package stonytark.jammarr.network;

import com.mojang.authlib.GameProfile;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.local.LocalChannel;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.login.server.SPacketEnableCompression;
import net.minecraft.network.login.server.SPacketLoginSuccess;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LoginProtocolOrderTest {
    @Test
    void queuedLoginPacketsKeepTheirProtocolUntilThePlayTransition() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop();
        LocalChannel channel = new LocalChannel();
        CountDownLatch release = new CountDownLatch(1);
        try {
            loop.register(channel).get(5, TimeUnit.SECONDS);
            NetworkManager manager = new NetworkManager(EnumPacketDirection.SERVERBOUND);
            channel.pipeline().addLast(manager);
            loop.submit(() -> {
                channel.pipeline().fireChannelActive();
                manager.setConnectionState(EnumConnectionState.LOGIN);
            }).get(5, TimeUnit.SECONDS);

            CountDownLatch blocked = new CountDownLatch(1);
            loop.submit(() -> {
                blocked.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            io.netty.util.concurrent.Future<?> loginWrites = loop.submit(() -> {
                EnumConnectionState state = channel.attr(NetworkManager.PROTOCOL_ATTRIBUTE_KEY).get();
                assertEquals(EnumConnectionState.LOGIN, state);
                assertNotNull(state.getPacketId(EnumPacketDirection.CLIENTBOUND, new SPacketEnableCompression(256)));
                assertNotNull(state.getPacketId(EnumPacketDirection.CLIENTBOUND,
                        new SPacketLoginSuccess(new GameProfile(UUID.randomUUID(), "JammarrVanilla"))));
                return null;
            });
            LoginProtocolOrder.setConnectionState(manager, EnumConnectionState.PLAY);
            io.netty.util.concurrent.Future<?> playWrites = loop.submit(() ->
                    assertEquals(EnumConnectionState.PLAY, channel.attr(NetworkManager.PROTOCOL_ATTRIBUTE_KEY).get()));
            release.countDown();
            loginWrites.get(5, TimeUnit.SECONDS);
            playWrites.get(5, TimeUnit.SECONDS);

            // Calls already on the network thread must take effect before the next inline write.
            loop.submit(() -> {
                manager.setConnectionState(EnumConnectionState.LOGIN);
                LoginProtocolOrder.setConnectionState(manager, EnumConnectionState.PLAY);
                assertEquals(EnumConnectionState.PLAY, channel.attr(NetworkManager.PROTOCOL_ATTRIBUTE_KEY).get());
            }).get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            channel.close().awaitUninterruptibly();
            loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        }
    }
}
