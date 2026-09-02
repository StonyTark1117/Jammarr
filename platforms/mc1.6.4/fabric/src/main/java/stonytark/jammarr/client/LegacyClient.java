package stonytark.jammarr.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.legacyfabric.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.legacyfabric.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.legacyfabric.fabric.api.resource.IdentifiableResourceReloadListener;
import net.legacyfabric.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.resource.ResourceManager;
import net.minecraft.text.ChatMessage;
import org.lwjgl.input.Keyboard;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.config.LegacyConfig;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.core.protocol.LegacyServerProbe;
import stonytark.jammarr.core.protocol.ProtocolLimits;

import java.util.UUID;

public final class LegacyClient implements ClientModInitializer {
    private static LegacyClient instance;
    private final KeyBinding open = new KeyBinding("key.jammarr.open", Keyboard.KEY_P);
    private boolean vanillaMusicSuppressed;
    private boolean acceptanceConnectAttempted;
    private boolean joinedWorldObserved;
    private long joinedWorldReadyAt;
    private boolean serverProbeSent;
    private long serverProbeDeadline;
    private String serverProbeNonce = "";

    @Override public void onInitializeClient() {
        instance = this;
        try { LegacyConfig.installClient(FabricLoader.getInstance().getConfigDir().toFile()); }
        catch (Exception error) { throw new IllegalStateException("Unable to load Jammarr client settings", error); }
        KeyBindingHelper.registerKeyBinding(open);
        LegacyNetwork.setClientListener(LegacyClientState.INSTANCE);
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
        if (open.wasPressed() && client.field_3805 != null) {
            client.setScreen(new LegacyScreen(LegacyClientState.INSTANCE));
        }
        if (client.world != null) {
            if (!joinedWorldObserved) {
                joinedWorldObserved = true;
                joinedWorldReadyAt = System.currentTimeMillis() + 1_000L;
                serverProbeNonce = UUID.randomUUID().toString().replace("-", "");
                Jammarr.LOGGER.info("Jammarr client observed the joined Legacy Fabric 1.6.4 world");
            }
            if (client.method_2960() != null && System.currentTimeMillis() >= joinedWorldReadyAt) {
                if (!serverProbeSent && client.field_3805 != null) {
                    serverProbeSent = true;
                    serverProbeDeadline = System.currentTimeMillis() + 1_500L;
                    client.field_3805.method_1262(LegacyServerProbe.command(Jammarr.PROTOCOL, serverProbeNonce));
                    Jammarr.LOGGER.info("Jammarr client sent a vanilla-safe Legacy Fabric server capability probe");
                    return;
                }
                if (!LegacyNetwork.serverAvailable() && System.currentTimeMillis() < serverProbeDeadline) return;
                LegacyClientState.INSTANCE.tick();
            }
        } else if (joinedWorldObserved) {
            resetServerProbe();
        }
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

    public static void disconnected(String reason, Object[] details) {
        String rendered = reason;
        if (details != null && details.length > 0 && details[0] != null) rendered = String.valueOf(details[0]);
        if (rendered == null || rendered.startsWith("disconnect.")) rendered = "Disconnected";
        Jammarr.LOGGER.info("Client disconnected with reason: {}", rendered);
        LegacyNetwork.clientDisconnected();
        LegacyClientState.INSTANCE.stop();
        if (instance != null) instance.resetServerProbe();
    }

    public static void loginSucceeded() {
        LegacyNetwork.clientDisconnected();
        if (instance != null) instance.resetServerProbe();
    }

    public static boolean receiveChat(String encoded) {
        if (encoded == null) return false;
        String message;
        try { message = ChatMessage.fromJson(encoded).toString(false); }
        catch (RuntimeException malformed) { message = encoded; }
        if (instance != null && instance.receiveServerProbe(message)) return true;
        acceptanceChat(message);
        return false;
    }

    private boolean receiveServerProbe(String message) {
        if (!serverProbeSent) return false;
        int protocol = LegacyServerProbe.responseProtocol(message, serverProbeNonce);
        if (protocol > 0) {
            if (protocol == Jammarr.PROTOCOL) {
                LegacyNetwork.clientConnected();
                Jammarr.LOGGER.info("Jammarr client verified Legacy Fabric server capability through vanilla chat");
            } else {
                Jammarr.LOGGER.warn("Jammarr protocol mismatch: server requires version {}", protocol);
            }
            return true;
        }
        if (System.currentTimeMillis() <= serverProbeDeadline && LegacyServerProbe.unknownCommand(message)) {
            serverProbeDeadline = 0L;
            Jammarr.LOGGER.info("Jammarr client verified that the Legacy Fabric server is unmodded");
            return true;
        }
        return false;
    }

    private void resetServerProbe() {
        joinedWorldObserved = false;
        joinedWorldReadyAt = 0L;
        serverProbeSent = false;
        serverProbeDeadline = 0L;
        serverProbeNonce = "";
    }

    private static void acceptanceChat(String message) {
        if (!ProtocolLimits.commandProbeEnabled() || message == null) return;
        Jammarr.LOGGER.info("Acceptance command response: {}", message);
        if (message.contains("JAMMARR_ACCEPTANCE_OPERATOR_READY")) {
            LegacyClientState.INSTANCE.operatorCommandProbe();
        }
    }
}
