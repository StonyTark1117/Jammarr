package stonytark.jammarr.client;

import net.mine_diver.unsafeevents.listener.EventListener;
import net.modificationstation.stationapi.api.event.registry.MessageListenerRegistryEvent;
import stonytark.jammarr.network.LegacyNetwork;

public final class BabricClientNetworkEvents {
    @EventListener
    public void registerMessages(MessageListenerRegistryEvent event) {
        LegacyClient.ensureInitialized();
        stonytark.jammarr.Jammarr.LOGGER.info("Registering the Jammarr Beta 1.7.3 client message channel");
        event.register(LegacyNetwork.CHANNEL, (player, packet) -> LegacyNetwork.receiveClient(packet));
    }
}
