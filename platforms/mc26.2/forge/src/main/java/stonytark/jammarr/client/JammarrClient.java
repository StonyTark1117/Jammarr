package stonytark.jammarr.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.glfw.GLFW;
import stonytark.jammarr.network.ClientPayloadBridge;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;

public final class JammarrClient {
    private static final JammarrClient INSTANCE = new JammarrClient();
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(stonytark.jammarr.Jammarr.MODID, "controls"));
    private static final KeyMapping OPEN = new KeyMapping("key.jammarr.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J, CATEGORY);

    public static void register() {
        RegisterKeyMappingsEvent.BUS.addListener(INSTANCE::keys);
        RegisterClientReloadListenersEvent.BUS.addListener(INSTANCE::reloadListeners);
        TickEvent.ClientTickEvent.Post.BUS.addListener(INSTANCE::tick);
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(INSTANCE::login);
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(INSTANCE::logout);
        MinecraftForge.registerConfigScreen(JammarrClientConfigScreen::new);
        ClientPayloadBridge.install(JammarrClientState.INSTANCE::accept);
    }

    private void keys(RegisterKeyMappingsEvent event) { event.register(OPEN); }
    private void reloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener)this::resourcesReloaded);
    }
    private void resourcesReloaded(ResourceManager ignored) { JammarrClientState.INSTANCE.audioEngineReloaded(); }

    public void tick(TickEvent.ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() == null && minecraft.player != null && OPEN.consumeClick()) {
            minecraft.setScreenAndShow(new JammarrScreen(JammarrClientState.INSTANCE));
            JammarrNetwork.sendToServer(new JammarrPayloads.BrowseRequest(JammarrPayloads.BrowseKind.SEARCH, "", 0));
        }
        JammarrClientState.INSTANCE.tick();
    }
    public void login(ClientPlayerNetworkEvent.LoggingIn event) {
        JammarrNetwork.serverConnected(event.getConnection());
        JammarrClientState.INSTANCE.hello();
    }
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        net.minecraft.network.Connection connection = event.getConnection();
        net.minecraft.network.DisconnectionDetails details = connection == null ? null : connection.getDisconnectionDetails();
        net.minecraft.network.chat.Component reason = details == null ? null : details.reason();
        if (reason != null) stonytark.jammarr.Jammarr.LOGGER.info("Client disconnected with reason: {}", reason.getString());
        JammarrNetwork.serverDisconnected();
        JammarrClientState.INSTANCE.stop();
    }

    private JammarrClient() {}
}
