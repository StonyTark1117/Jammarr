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
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.sound.SoundLoadEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Keyboard;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.network.LegacyNetwork;

@SideOnly(Side.CLIENT)
public final class LegacyClient {
    private static final LegacyClient INSTANCE = new LegacyClient();
    private final KeyBinding open = new KeyBinding("key.jammarr.open", Keyboard.KEY_P, "key.categories.jammarr");
    private NetworkManager disconnectedManager;
    private boolean registered;

    public static synchronized void register() {
        if (INSTANCE.registered) return;
        awaitAcceptanceSoundStartup();
        ClientRegistry.registerKeyBinding(INSTANCE.open);
        LegacyNetwork.setClientListener(LegacyClientState.INSTANCE);
        FMLCommonHandler.instance().bus().register(INSTANCE);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        INSTANCE.registered = true;
    }

    private static void awaitAcceptanceSoundStartup() {
        if (!ProtocolLimits.audioProbeEnabled()) return;
        long deadline = System.currentTimeMillis() + 10_000L;
        while (LegacySoundAccess.soundSystem(Minecraft.getMinecraft()) == null
                && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(25L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Jammarr.LOGGER.info("Acceptance client let the initial legacy sound loader settle before FML reload");
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

    @SubscribeEvent public void chat(ClientChatReceivedEvent event) {
        if (!ProtocolLimits.commandProbeEnabled()) return;
        String message = event.message.getUnformattedText();
        Jammarr.LOGGER.info("Acceptance command response: {}", message);
        if (message.contains("JAMMARR_ACCEPTANCE_OPERATOR_READY")) {
            LegacyClientState.INSTANCE.operatorCommandProbe();
        }
    }

    @SubscribeEvent public void soundLoaded(SoundLoadEvent event) {
        LegacyClientState.INSTANCE.audioEngineReloaded();
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
