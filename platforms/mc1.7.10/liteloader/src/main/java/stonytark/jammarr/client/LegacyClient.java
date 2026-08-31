package stonytark.jammarr.client;

import com.mumfrey.liteloader.core.LiteLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import paulscode.sound.SoundSystem;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.network.LegacyNetwork;

/** LiteLoader lifecycle adapter for the shared legacy client implementation. */
public final class LegacyClient {
    private static final KeyBinding OPEN = new KeyBinding(
            "key.jammarr.open", Keyboard.KEY_P, "key.categories.jammarr");
    private static boolean registered;
    private static boolean joined;
    private static boolean vanillaMusicSuppressed;
    private static SoundSystem observedSoundSystem;

    public static synchronized void register() {
        if (registered) return;
        LiteLoader.getInput().registerKeyBinding(OPEN);
        LegacyNetwork.setClientListener(LegacyClientState.INSTANCE);
        registered = true;
    }

    public static void joined() { joined = true; }

    public static void tick(Minecraft minecraft, boolean inGame) {
        LegacyNetwork.clientTick();
        observeSoundReload(minecraft);
        updateVanillaMusicSuppression(minecraft);
        if (!inGame || minecraft.theWorld == null) {
            if (joined) {
                joined = false;
                LegacyNetwork.disconnected();
                LegacyClientState.INSTANCE.stop();
            }
            return;
        }
        if (OPEN.isPressed() && minecraft.thePlayer != null) {
            minecraft.displayGuiScreen(new LegacyScreen(LegacyClientState.INSTANCE));
        }
        LegacyClientState.INSTANCE.tick();
    }

    public static void chat(String message) {
        if (!ProtocolLimits.commandProbeEnabled() || message == null) return;
        Jammarr.LOGGER.info("Acceptance command response: {}", message);
        if (message.contains("JAMMARR_ACCEPTANCE_OPERATOR_READY")) {
            LegacyClientState.INSTANCE.operatorCommandProbe();
        }
    }

    private static void observeSoundReload(Minecraft minecraft) {
        SoundSystem current;
        try { current = LegacySoundAccess.soundSystem(minecraft); }
        catch (RuntimeException unavailable) { return; }
        if (current == observedSoundSystem) return;
        boolean reloaded = observedSoundSystem != null;
        observedSoundSystem = current;
        if (reloaded && current != null) LegacyClientState.INSTANCE.audioEngineReloaded();
    }

    private static void updateVanillaMusicSuppression(Minecraft minecraft) {
        boolean suppress = LegacyClientState.INSTANCE.suppressVanillaMusic();
        try {
            if (suppress) LegacySoundAccess.suppressVanillaMusic(minecraft);
            else if (vanillaMusicSuppressed) LegacySoundAccess.restoreVanillaMusic(minecraft);
            vanillaMusicSuppressed = suppress;
        } catch (RuntimeException unavailable) {
            Jammarr.LOGGER.warn("Unable to update legacy vanilla-music suppression", unavailable);
        }
    }

    private LegacyClient() {}
}
