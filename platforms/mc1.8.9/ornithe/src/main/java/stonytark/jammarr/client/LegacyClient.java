package stonytark.jammarr.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.text.Text;
import net.ornithemc.osl.entrypoints.api.client.ClientModInitializer;
import net.ornithemc.osl.keybinds.api.KeybindEvents;
import net.ornithemc.osl.keybinds.api.KeybindRegistry;
import net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents;
import net.ornithemc.osl.networking.api.client.ClientConnectionEvents;
import net.ornithemc.osl.networking.api.client.ClientPlayNetworking;
import net.ornithemc.osl.resource.loader.api.client.ClientResourceLoaderEvents;
import org.lwjgl.input.Keyboard;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.config.LegacyConfig;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.core.protocol.ProtocolLimits;

public final class LegacyClient implements ClientModInitializer {
    private final KeyBinding open = new KeyBinding("key.jammarr.open", Keyboard.KEY_P, "key.categories.jammarr");
    private boolean vanillaMusicSuppressed;
    private boolean acceptanceConnectAttempted;

    @Override public void initClient() {
        try { LegacyConfig.installClient(FabricLoader.getInstance().getConfigDir().toFile()); }
        catch (Exception error) { throw new IllegalStateException("Unable to load Jammarr client settings", error); }
        KeybindEvents.REGISTER_KEYBINDS.register(() -> KeybindRegistry.register(open));
        LegacyNetwork.setClientListener(LegacyClientState.INSTANCE);
        ClientPlayNetworking.registerListener(LegacyNetwork.CHANNEL,
                (context, buffer) -> {
                    context.ensureOnMainThread();
                    LegacyNetwork.receiveClient(buffer);
                });
        ClientConnectionEvents.PLAY_READY.register(context ->
                LegacyNetwork.clientConnected(ClientPlayNetworking.isPlayReady(LegacyNetwork.CHANNEL)));
        ClientConnectionEvents.DISCONNECT.register(context -> {
            Jammarr.LOGGER.info("Client disconnected with reason: Disconnected");
            LegacyNetwork.clientDisconnected();
            LegacyClientState.INSTANCE.stop();
        });
        MinecraftClientEvents.TICK_START.register(this::startTick);
        MinecraftClientEvents.TICK_END.register(this::endTick);
        ClientResourceLoaderEvents.END_RESOURCE_RELOAD.register((manager, context) ->
                LegacyClientState.INSTANCE.audioEngineReloaded());
    }

    private void startTick(Minecraft client) { updateVanillaMusicSuppression(client); }

    private void endTick(Minecraft client) {
        connectAcceptanceServer(client);
        updateVanillaMusicSuppression(client);
        if (open.consumeClick() && client.player != null) {
            client.openScreen(new LegacyScreen(LegacyClientState.INSTANCE));
        }
        if (client.world != null) LegacyClientState.INSTANCE.tick();
    }

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
        if (acceptanceConnectAttempted || client.world != null || client.screen == null) return;
        String address = System.getProperty("jammarr.acceptance.server", "").trim();
        if (address.isEmpty()) return;
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) {
            throw new IllegalArgumentException("jammarr.acceptance.server must use host:port syntax");
        }
        acceptanceConnectAttempted = true;
        client.openScreen(new ConnectScreen(client.screen, client,
                address.substring(0, separator), Integer.parseInt(address.substring(separator + 1))));
    }

    public static void acceptanceChat(Text text) {
        if (!ProtocolLimits.commandProbeEnabled() || text == null) return;
        String message = text.getString();
        Jammarr.LOGGER.info("Acceptance command response: " + message);
        if (message.contains("JAMMARR_ACCEPTANCE_OPERATOR_READY")) {
            LegacyClientState.INSTANCE.operatorCommandProbe();
        }
    }
}
