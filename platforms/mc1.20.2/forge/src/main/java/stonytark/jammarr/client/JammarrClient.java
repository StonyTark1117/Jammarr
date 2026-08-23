package stonytark.jammarr.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModLoadingContext;
import org.lwjgl.glfw.GLFW;
import stonytark.jammarr.network.ClientPayloadBridge;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;

public final class JammarrClient {
    private static final JammarrClient INSTANCE = new JammarrClient();
    private static final KeyMapping OPEN = new KeyMapping("key.jammarr.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P, "key.categories.jammarr");

    public static void register(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        modBus.addListener(INSTANCE::keys);
        modBus.addListener(INSTANCE::reloadListeners);
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new JammarrClientConfigScreen(parent)));
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        ClientPayloadBridge.install(JammarrClientState.INSTANCE::accept);
    }

    private void keys(RegisterKeyMappingsEvent event) { event.register(OPEN); }
    private void reloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener)this::resourcesReloaded);
    }
    private void resourcesReloaded(ResourceManager ignored) { JammarrClientState.INSTANCE.audioEngineReloaded(); }

    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null && minecraft.player != null && OPEN.consumeClick()) {
            minecraft.setScreen(new JammarrScreen(JammarrClientState.INSTANCE));
            JammarrNetwork.sendToServer(new JammarrPayloads.BrowseRequest(JammarrPayloads.BrowseKind.SEARCH, "", 0));
        }
        JammarrClientState.INSTANCE.tick();
    }
    @SubscribeEvent public void login(ClientPlayerNetworkEvent.LoggingIn event) { JammarrClientState.INSTANCE.hello(); }
    @SubscribeEvent public void logout(ClientPlayerNetworkEvent.LoggingOut event) { JammarrClientState.INSTANCE.stop(); }

    private JammarrClient() {}
}
