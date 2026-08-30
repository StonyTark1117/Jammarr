package stonytark.jammarr.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.legacyfabric.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.legacyfabric.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.legacyfabric.fabric.api.client.networking.v1.C2SPlayChannelEvents;
import net.legacyfabric.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.legacyfabric.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.legacyfabric.fabric.api.networking.v1.PacketByteBufs;
import net.legacyfabric.fabric.api.resource.IdentifiableResourceReloadListener;
import net.legacyfabric.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.resource.ResourceManager;
import net.minecraft.text.Text;
import net.minecraft.util.PacketByteBuf;
import org.lwjgl.input.Keyboard;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.config.LegacyConfig;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.core.protocol.ProtocolLimits;

public final class LegacyClient implements ClientModInitializer {
    private final KeyBinding open = new KeyBinding("key.jammarr.open", Keyboard.KEY_P, "key.categories.jammarr");
    private boolean vanillaMusicSuppressed;
    private boolean acceptanceConnectAttempted;

    @Override public void onInitializeClient() {
        try { LegacyConfig.installClient(FabricLoader.getInstance().getConfigDir().toFile()); }
        catch (Exception error) { throw new IllegalStateException("Unable to load Jammarr client settings", error); }
        KeyBindingHelper.registerKeyBinding(open);
        LegacyNetwork.setClientListener(LegacyClientState.INSTANCE);
        ClientPlayNetworking.registerGlobalReceiver(LegacyNetwork.CHANNEL,
                (client, handler, buffer, responseSender) -> {
                    final PacketByteBuf copy = PacketByteBufs.copy(buffer);
                    client.submit(() -> {
                        try { LegacyNetwork.receiveClient(copy); }
                        finally { copy.release(); }
                    });
                });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                LegacyNetwork.clientConnected(ClientPlayNetworking.canSend(LegacyNetwork.CHANNEL)));
        C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
            if (channels.contains(LegacyNetwork.CHANNEL)) LegacyNetwork.clientConnected(true);
        });
        C2SPlayChannelEvents.UNREGISTER.register((handler, sender, client, channels) -> {
            if (channels.contains(LegacyNetwork.CHANNEL)) LegacyNetwork.clientConnected(false);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Jammarr.LOGGER.info("Client disconnected with reason: Disconnected");
            LegacyNetwork.clientDisconnected();
            LegacyClientState.INSTANCE.stop();
        });
        ClientTickEvents.START_CLIENT_TICK.register(this::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::endTick);
        ResourceManagerHelper.getInstance().registerReloadListener(new IdentifiableResourceReloadListener() {
            @Override public net.legacyfabric.fabric.api.util.Identifier getFabricId() {
                return new net.legacyfabric.fabric.api.util.Identifier(Jammarr.MOD_ID, "sound_engine_reload");
            }
            @Override public void reload(ResourceManager manager) {
                LegacyClientState.INSTANCE.audioEngineReloaded();
            }
        });
    }

    private void startTick(MinecraftClient client) { updateVanillaMusicSuppression(client); }

    private void endTick(MinecraftClient client) {
        connectAcceptanceServer(client);
        updateVanillaMusicSuppression(client);
        if (open.wasPressed() && client.player != null) {
            client.setScreen(new LegacyScreen(LegacyClientState.INSTANCE));
        }
        if (client.world != null) LegacyClientState.INSTANCE.tick();
    }

    private void updateVanillaMusicSuppression(MinecraftClient client) {
        boolean suppress = LegacyClientState.INSTANCE.suppressVanillaMusic();
        try {
            if (suppress) LegacySoundAccess.suppressVanillaMusic(client);
            else if (vanillaMusicSuppressed) LegacySoundAccess.restoreVanillaMusic(client);
            vanillaMusicSuppressed = suppress;
        } catch (RuntimeException unavailable) {
            Jammarr.LOGGER.warn("Unable to update legacy vanilla-music suppression", unavailable);
        }
    }

    private void connectAcceptanceServer(MinecraftClient client) {
        if (acceptanceConnectAttempted || client.world != null || client.currentScreen == null) return;
        String address = System.getProperty("jammarr.acceptance.server", "").trim();
        if (address.isEmpty()) return;
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) {
            throw new IllegalArgumentException("jammarr.acceptance.server must use host:port syntax");
        }
        acceptanceConnectAttempted = true;
        client.setScreen(new ConnectScreen(client.currentScreen, client,
                address.substring(0, separator), Integer.parseInt(address.substring(separator + 1))));
    }

    public static void acceptanceChat(Text text) {
        if (!ProtocolLimits.commandProbeEnabled() || text == null) return;
        String message = text.asUnformattedString();
        Jammarr.LOGGER.info("Acceptance command response: {}", message);
        if (message.contains("JAMMARR_ACCEPTANCE_OPERATOR_READY")) {
            LegacyClientState.INSTANCE.operatorCommandProbe();
        }
    }
}
