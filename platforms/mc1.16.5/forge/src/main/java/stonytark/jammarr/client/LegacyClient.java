package stonytark.jammarr.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.sound.SoundLoadEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import stonytark.jammarr.config.LegacyConfig;
import stonytark.jammarr.network.LegacyNetwork;

public final class LegacyClient {
    private static final LegacyClient INSTANCE = new LegacyClient();
    private final KeyBinding open = new KeyBinding("key.jammarr.open", GLFW.GLFW_KEY_J, "key.categories.jammarr");
    private boolean registered;
    private boolean acceptanceConnectAttempted;

    public static synchronized void register(IEventBus modBus) {
        if (INSTANCE.registered) return;
        try { LegacyConfig.installClient(FMLPaths.CONFIGDIR.get().toFile()); }
        catch (Exception error) { throw new IllegalStateException("Unable to load Jammarr client settings", error); }
        ClientRegistry.registerKeyBinding(INSTANCE.open);
        LegacyNetwork.setClientListener(LegacyClientState.INSTANCE);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::keyInput);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::clientTick);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::loggedIn);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::loggedOut);
        modBus.addListener(INSTANCE::soundLoaded);
        INSTANCE.registered = true;
    }

    private void keyInput(InputEvent.KeyInputEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null && minecraft.player != null && open.consumeClick()) {
            minecraft.setScreen(new LegacyScreen(LegacyClientState.INSTANCE));
        }
    }

    private void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        connectAcceptanceServer(minecraft);
        if (minecraft.level != null) LegacyClientState.INSTANCE.tick();
    }

    private void loggedIn(ClientPlayerNetworkEvent.LoggedInEvent event) {
        LegacyNetwork.clientConnected(event.getNetworkManager());
    }

    private void loggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        // Forge can publish LoggedOutEvent while ConnectingScreen is replacing
        // its initial connection and before it has assigned a NetworkManager.
        // Cleanup must still happen, but logging the optional disconnect reason
        // must never turn a deliberate protocol rejection into a client crash.
        net.minecraft.network.NetworkManager networkManager = event.getNetworkManager();
        net.minecraft.util.text.ITextComponent reason = networkManager == null
                ? null
                : networkManager.getDisconnectedReason();
        if (reason != null) stonytark.jammarr.Jammarr.LOGGER.info(
                "Client disconnected with reason: {}", reason.getString());
        LegacyNetwork.clientDisconnected();
        LegacyClientState.INSTANCE.stop();
    }

    private void soundLoaded(SoundLoadEvent event) {
        LegacyClientState.INSTANCE.audioEngineReloaded();
    }

    private void connectAcceptanceServer(Minecraft minecraft) {
        if (acceptanceConnectAttempted || minecraft.level != null || minecraft.screen == null) return;
        String address = System.getProperty("jammarr.acceptance.server", "").trim();
        if (address.isEmpty()) return;
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) {
            throw new IllegalArgumentException("jammarr.acceptance.server must use host:port syntax");
        }
        acceptanceConnectAttempted = true;
        String host = address.substring(0, separator);
        int port = Integer.parseInt(address.substring(separator + 1));
        minecraft.setScreen(new ConnectingScreen(minecraft.screen, minecraft, host, port));
    }

    private LegacyClient() {}
}
