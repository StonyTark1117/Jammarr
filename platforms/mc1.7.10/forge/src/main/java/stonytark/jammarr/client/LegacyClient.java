package stonytark.jammarr.client;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import stonytark.jammarr.network.LegacyNetwork;

@SideOnly(Side.CLIENT)
public final class LegacyClient {
    private static final LegacyClient INSTANCE = new LegacyClient();
    private final KeyBinding open = new KeyBinding("key.jammarr.open", Keyboard.KEY_P, "key.categories.jammarr");
    private boolean registered;

    public static synchronized void register() {
        if (INSTANCE.registered) return;
        ClientRegistry.registerKeyBinding(INSTANCE.open);
        LegacyNetwork.setClientListener(LegacyClientState.INSTANCE);
        FMLCommonHandler.instance().bus().register(INSTANCE);
        INSTANCE.registered = true;
    }

    @SubscribeEvent public void keyInput(InputEvent.KeyInputEvent event) {
        if (open.isPressed() && Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().displayGuiScreen(new LegacyScreen(LegacyClientState.INSTANCE));
        }
    }

    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && Minecraft.getMinecraft().theWorld != null) {
            LegacyClientState.INSTANCE.tick();
        }
    }

    @SubscribeEvent public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        LegacyClientState.INSTANCE.stop();
    }

    private LegacyClient() {}
}
