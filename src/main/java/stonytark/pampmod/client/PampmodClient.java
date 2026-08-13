package stonytark.pampmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import stonytark.pampmod.Pampmod;
import stonytark.pampmod.network.ClientPayloadBridge;
import stonytark.pampmod.network.PampPayloads;

@Mod(value = Pampmod.MODID, dist = net.neoforged.api.distmarker.Dist.CLIENT)
public final class PampmodClient {
    private static final KeyMapping OPEN = new KeyMapping("key.pampmod.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "key.categories.pampmod");
    private boolean openOnNextTick;

    public PampmodClient(IEventBus modBus) {
        modBus.addListener(this::keys);
        modBus.addListener(this::soundEngineLoaded);
        NeoForge.EVENT_BUS.register(this);
        ClientPayloadBridge.install(PampClientState.INSTANCE::accept);
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
            minecraft.setScreen(new PampScreen(PampClientState.INSTANCE));
            PacketDistributor.sendToServer(new PampPayloads.BrowseRequest(PampPayloads.BrowseKind.SEARCH, "", 0));
        }
        PampClientState.INSTANCE.tick();
    }
    @SubscribeEvent public void logout(ClientPlayerNetworkEvent.LoggingOut event) { PampClientState.INSTANCE.stop(); }
    @SubscribeEvent public void login(ClientPlayerNetworkEvent.LoggingIn event) { PampClientState.INSTANCE.hello(); }
    private void soundEngineLoaded(SoundEngineLoadEvent event) { PampClientState.INSTANCE.audioEngineReloaded(); }
}
