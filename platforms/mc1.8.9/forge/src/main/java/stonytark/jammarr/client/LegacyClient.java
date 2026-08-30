package stonytark.jammarr.client;

import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
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
    private boolean vanillaMusicSuppressed;
    private boolean registered;

    public static synchronized void register() {
        if (INSTANCE.registered) return;
        awaitInitialSoundStartup();
        ClientRegistry.registerKeyBinding(INSTANCE.open);
        LegacyNetwork.setClientListener(LegacyClientState.INSTANCE);
        FMLCommonHandler.instance().bus().register(INSTANCE);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        INSTANCE.registered = true;
    }

    private static void awaitInitialSoundStartup() {
        long deadline = System.currentTimeMillis() + 10_000L;
        boolean waited = false;
        while (LegacySoundAccess.soundSystem(Minecraft.getMinecraft()) == null
                && System.currentTimeMillis() < deadline) {
            waited = true;
            try { Thread.sleep(25L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (waited) Jammarr.LOGGER.info(
                "Jammarr waited for the initial legacy sound loader before Forge's resource reload");
    }

    @SubscribeEvent public void keyInput(InputEvent.KeyInputEvent event) {
        if (open.isPressed() && Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().displayGuiScreen(new LegacyScreen(LegacyClientState.INSTANCE));
        }
    }

    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // Vanilla MusicTicker polls SoundSystem.playing() during its tick. On
            // LWJGL2 a failed duplicate OpenAL init makes that native call fatal;
            // suppress its handle before that poll while Jammarr owns music.
            updateVanillaMusicSuppression();
            return;
        }
        updateVanillaMusicSuppression();
        logDisconnectReason();
        if (Minecraft.getMinecraft().theWorld != null) LegacyClientState.INSTANCE.tick();
    }

    private void updateVanillaMusicSuppression() {
        boolean suppress = LegacyClientState.INSTANCE.suppressVanillaMusic();
        try {
            if (suppress) LegacySoundAccess.suppressVanillaMusic(Minecraft.getMinecraft());
            else if (vanillaMusicSuppressed) LegacySoundAccess.restoreVanillaMusic(Minecraft.getMinecraft());
            vanillaMusicSuppressed = suppress;
        } catch (RuntimeException unavailable) {
            Jammarr.LOGGER.warn("Unable to update legacy vanilla-music suppression", unavailable);
        }
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
