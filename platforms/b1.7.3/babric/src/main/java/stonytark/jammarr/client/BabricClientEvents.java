package stonytark.jammarr.client;

import net.mine_diver.unsafeevents.listener.EventListener;
import net.modificationstation.stationapi.api.client.event.keyboard.KeyStateChangedEvent;
import net.modificationstation.stationapi.api.client.event.network.MultiplayerLogoutEvent;
import net.modificationstation.stationapi.api.client.event.network.ServerLoginSuccessEvent;
import net.modificationstation.stationapi.api.client.event.option.KeyBindingRegisterEvent;
import net.modificationstation.stationapi.api.client.event.resource.TexturePackLoadedEvent;
import org.lwjgl.input.Keyboard;

public final class BabricClientEvents {
    @EventListener
    public void registerKey(KeyBindingRegisterEvent event) { event.register(LegacyClient.INSTANCE.open); }

    @EventListener
    public void keyChanged(KeyStateChangedEvent event) {
        if (event.environment == KeyStateChangedEvent.Environment.IN_GAME
                && Keyboard.getEventKeyState()
                && Keyboard.getEventKey() == LegacyClient.INSTANCE.open.code) {
            LegacyClient.INSTANCE.openScreen();
        }
    }

    @EventListener
    public void login(ServerLoginSuccessEvent event) { LegacyClient.INSTANCE.loginSucceeded(); }

    @EventListener
    public void logout(MultiplayerLogoutEvent event) {
        LegacyClient.INSTANCE.disconnected(event.disconnectPacket.reason);
    }

    @EventListener
    public void textureReloaded(TexturePackLoadedEvent.After event) {
        LegacyClient.INSTANCE.audioEngineReloaded();
    }
}
