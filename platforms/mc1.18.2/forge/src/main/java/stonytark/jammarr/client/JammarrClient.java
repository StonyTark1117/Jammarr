package stonytark.jammarr.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.client.ConfigGuiHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;
import stonytark.jammarr.network.ClientPayloadBridge;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;

public final class JammarrClient {
    private static final JammarrClient INSTANCE = new JammarrClient();
    private static final KeyMapping OPEN = new KeyMapping("key.jammarr.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J, "key.categories.jammarr");
    private boolean acceptanceConnectAttempted;

    public static void register(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        modBus.addListener(INSTANCE::clientSetup);
        modBus.addListener(INSTANCE::reloadListeners);
        ModLoadingContext.get().registerExtensionPoint(ConfigGuiHandler.ConfigGuiFactory.class,
                () -> new ConfigGuiHandler.ConfigGuiFactory(JammarrClientConfigScreen::new));
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        ClientPayloadBridge.install(JammarrClientState.INSTANCE::accept);
    }

    private void clientSetup(FMLClientSetupEvent event) { event.enqueueWork(() -> ClientRegistry.registerKeyBinding(OPEN)); }
    private void reloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener)this::resourcesReloaded);
    }
    private void resourcesReloaded(ResourceManager ignored) { JammarrClientState.INSTANCE.audioEngineReloaded(); }

    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        connectAcceptanceServer(minecraft);
        if (minecraft.screen == null && minecraft.player != null && OPEN.consumeClick()) {
            minecraft.setScreen(new JammarrScreen(JammarrClientState.INSTANCE));
            JammarrNetwork.sendToServer(new JammarrPayloads.BrowseRequest(JammarrPayloads.BrowseKind.SEARCH, "", 0));
        }
        JammarrClientState.INSTANCE.tick();
    }

    private void connectAcceptanceServer(Minecraft minecraft) {
        if (acceptanceConnectAttempted || minecraft.level != null || minecraft.screen == null) return;
        String address = System.getProperty("jammarr.acceptance.server", "").trim();
        if (address.isEmpty()) return;
        acceptanceConnectAttempted = true;
        ServerAddress parsed = ServerAddress.parseString(address);
        ConnectScreen.startConnecting(minecraft.screen, minecraft, parsed,
                new ServerData("Jammarr acceptance", address, false));
    }
    @SubscribeEvent public void login(ClientPlayerNetworkEvent.LoggedInEvent event) {
        JammarrNetwork.serverConnected(event.getConnection());
        JammarrClientState.INSTANCE.hello();
    }
    @SubscribeEvent public void logout(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        net.minecraft.network.Connection connection = event.getConnection();
        net.minecraft.network.chat.Component reason = connection == null ? null : connection.getDisconnectedReason();
        if (reason != null) stonytark.jammarr.Jammarr.LOGGER.info("Client disconnected with reason: {}", reason.getString());
        JammarrNetwork.serverDisconnected();
        JammarrClientState.INSTANCE.stop();
    }

    private JammarrClient() {}
}
