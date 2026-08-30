package stonytark.jammarr.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.input.Keyboard;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.config.LegacyConfig;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.network.LegacyNetwork;

public final class LegacyClient implements ClientModInitializer {
    static final LegacyClient INSTANCE = new LegacyClient();
    final KeyBinding open = new KeyBinding("key.jammarr.open", Keyboard.KEY_P);
    private boolean vanillaMusicSuppressed;
    private boolean acceptanceConnectAttempted;
    private boolean joinedWorldObserved;
    private long joinedWorldReadyAt;
    private static boolean initialized;

    @Override public void onInitializeClient() { ensureInitialized(); }

    static synchronized void ensureInitialized() {
        if (initialized) return;
        try { LegacyConfig.installClient(FabricLoader.getInstance().getConfigDir().toFile()); }
        catch (Exception error) { throw new IllegalStateException("Unable to load Jammarr client settings", error); }
        LegacyNetwork.setClientListener(LegacyClientState.INSTANCE);
        initialized = true;
        Jammarr.LOGGER.info("Initialized the Jammarr Beta 1.7.3 client transport");
    }

    void tick() {
        Minecraft client = minecraft();
        connectAcceptanceServer(client);
        updateVanillaMusicSuppression(client);
        if (client.world != null) {
            // StationAPI's login-success event can be missed on Beta 1.7.3 even
            // after the play world is installed. The world is the authoritative
            // fallback signal that the client transport may send its hello.
            if (!LegacyNetwork.serverAvailable()) LegacyNetwork.clientConnected();
            if (!joinedWorldObserved) {
                joinedWorldObserved = true;
                joinedWorldReadyAt = System.currentTimeMillis() + 1_000L;
                Jammarr.LOGGER.info("Jammarr client observed the joined Beta 1.7.3 world");
            }
            if (client.getNetworkHandler() != null && System.currentTimeMillis() >= joinedWorldReadyAt) {
                LegacyClientState.INSTANCE.tick();
            }
        } else {
            joinedWorldObserved = false;
            joinedWorldReadyAt = 0L;
        }
    }

    public static void clientTick() { INSTANCE.tick(); }

    public static void resourceReloaded() { INSTANCE.audioEngineReloaded(); }

    void openScreen() {
        Minecraft client = minecraft();
        if (client.player != null) client.setScreen(new LegacyScreen(LegacyClientState.INSTANCE));
    }

    void disconnected(String reason) {
        Jammarr.LOGGER.info("Client disconnected with reason: {}", reason == null ? "Disconnected" : reason);
        LegacyNetwork.clientDisconnected();
        LegacyClientState.INSTANCE.stop();
    }

    void audioEngineReloaded() { LegacyClientState.INSTANCE.audioEngineReloaded(); }

    private void updateVanillaMusicSuppression(Minecraft client) {
        boolean suppress = LegacyClientState.INSTANCE.suppressVanillaMusic();
        try {
            if (suppress) LegacySoundAccess.suppressVanillaMusic(client);
            else if (vanillaMusicSuppressed) LegacySoundAccess.restoreVanillaMusic(client);
            vanillaMusicSuppressed = suppress;
        } catch (RuntimeException unavailable) {
            Jammarr.LOGGER.warn("Unable to update legacy vanilla-music suppression", unavailable);
        }
    }

    private void connectAcceptanceServer(Minecraft client) {
        if (acceptanceConnectAttempted || client.world != null || client.currentScreen == null) return;
        String address = System.getProperty("jammarr.acceptance.server", "").trim();
        if (address.isEmpty()) return;
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) {
            throw new IllegalArgumentException("jammarr.acceptance.server must use host:port syntax");
        }
        acceptanceConnectAttempted = true;
        client.setScreen(new ConnectScreen(client, address.substring(0, separator),
                Integer.parseInt(address.substring(separator + 1))));
    }

    public static void acceptanceChat(String message) {
        if (!ProtocolLimits.commandProbeEnabled() || message == null) return;
        String normalized = message.replaceAll("(?i)\\u00a7[0-9A-FK-OR]", "");
        Jammarr.LOGGER.info("Acceptance command response: {}", normalized);
        if (normalized.contains("JAMMARR_ACCEPTANCE_OPERATOR_READY")) {
            LegacyClientState.INSTANCE.operatorCommandProbe();
        }
    }

    static Minecraft minecraft() {
        return (Minecraft) FabricLoader.getInstance().getGameInstance();
    }
}
