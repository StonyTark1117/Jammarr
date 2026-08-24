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
import net.minecraft.network.NetworkManager;
import net.minecraft.util.IChatComponent;
import org.lwjgl.input.Keyboard;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.network.LegacyNetwork;

@SideOnly(Side.CLIENT)
public final class LegacyClient {
    private static final LegacyClient INSTANCE = new LegacyClient();
    private final KeyBinding open = new KeyBinding("key.jammarr.open", Keyboard.KEY_P, "key.categories.jammarr");
    private NetworkManager disconnectedManager;
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
        if (event.phase != TickEvent.Phase.END) return;
        logDisconnectReason();
        if (Minecraft.getMinecraft().theWorld != null) LegacyClientState.INSTANCE.tick();
    }

    @SubscribeEvent public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        disconnectedManager = event.manager;
        LegacyClientState.INSTANCE.stop();
    }

    private void logDisconnectReason() {
        NetworkManager manager = disconnectedManager;
        if (manager == null) return;
        IChatComponent reason = manager.getExitMessage();
        if (reason == null) return;
        Jammarr.LOGGER.info("Client disconnected with reason: {}", reason.getUnformattedText());
        disconnectedManager = null;
    }

    private LegacyClient() {}
}
