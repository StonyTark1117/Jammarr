package stonytark.jammarr.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.network.ClientPayloadBridge;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;

public final class JammarrClient {
    private static final KeyMapping OPEN = new KeyMapping("key.jammarr.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.jammarr");
    private boolean openOnNextTick;

    public JammarrClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::keys);
        modBus.addListener(this::soundEngineLoaded);
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new JammarrClientConfigScreen(parent));
        NeoForge.EVENT_BUS.register(this);
        ClientPayloadBridge.install(JammarrClientState.INSTANCE::accept);
    }
    private void keys(RegisterKeyMappingsEvent event) { event.register(OPEN); }
    @SubscribeEvent public void keyInput(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        InputConstants.Key pressed = InputConstants.getKey(event.getKey(), event.getScanCode());
        if (event.getAction() == GLFW.GLFW_PRESS && minecraft.screen == null && OPEN.getKey().equals(pressed)) {
            // Raw input remains unambiguous even when vanilla has another
            // mapping on P. The next post-tick runs after vanilla key handling.
            openOnNextTick = true;
        }
    }
    @SubscribeEvent public void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean openNow = openOnNextTick;
        openOnNextTick = false;
        while (OPEN.consumeClick()) { /* Prevent a duplicate open next tick. */ }
        if (openNow && minecraft.player != null) {
            minecraft.setScreen(new JammarrScreen(JammarrClientState.INSTANCE));
            JammarrNetwork.sendToServer(new JammarrPayloads.BrowseRequest(JammarrPayloads.BrowseKind.SEARCH, "", 0));
        }
        JammarrClientState.INSTANCE.tick();
    }
    @SubscribeEvent public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        JammarrNetwork.serverDisconnected();
        JammarrClientState.INSTANCE.stop();
    }
    @SubscribeEvent public void login(ClientPlayerNetworkEvent.LoggingIn event) {
        JammarrNetwork.serverConnected(event.getConnection());
        JammarrClientState.INSTANCE.hello();
    }
    private void soundEngineLoaded(SoundEngineLoadEvent event) { JammarrClientState.INSTANCE.audioEngineReloaded(); }
}
