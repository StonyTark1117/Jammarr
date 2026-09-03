package stonytark.jammarr.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.glfw.GLFW;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.config.LegacyConfig;
import stonytark.jammarr.network.LegacyNetwork;

public final class LegacyClient implements ClientModInitializer {
    private static final KeyMapping OPEN = new KeyMapping("key.jammarr.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J, "key.categories.jammarr");
    private boolean acceptanceConnectAttempted;

    @Override public void onInitializeClient() {
        try { LegacyConfig.installClient(FabricLoader.getInstance().getConfigDir().toFile()); }
        catch (Exception error) { throw new IllegalStateException("Unable to load Jammarr client settings", error); }
        KeyBindingHelper.registerKeyBinding(OPEN);
        LegacyNetwork.setClientListener(LegacyClientState.INSTANCE);
        ClientPlayNetworking.registerGlobalReceiver(LegacyNetwork.CHANNEL,
                (client, handler, buffer, responseSender) -> {
                    final net.minecraft.network.FriendlyByteBuf copy = new net.minecraft.network.FriendlyByteBuf(buffer.copy());
                    client.execute(() -> {
                        try { LegacyNetwork.receiveClient(copy); }
                        finally { copy.release(); }
                    });
                });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                LegacyNetwork.clientConnected(ClientPlayNetworking.canSend(LegacyNetwork.CHANNEL)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            net.minecraft.network.chat.Component reason = handler.getConnection().getDisconnectedReason();
            if (reason != null) Jammarr.LOGGER.info("Client disconnected with reason: {}", reason.getString());
            LegacyNetwork.clientDisconnected();
            LegacyClientState.INSTANCE.stop();
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override public ResourceLocation getFabricId() {
                        return new ResourceLocation(Jammarr.MOD_ID, "sound_engine_reload");
                    }
                    @Override public void onResourceManagerReload(ResourceManager manager) {
                        LegacyClientState.INSTANCE.audioEngineReloaded();
                    }
                });
    }

    private void tick(Minecraft minecraft) {
        connectAcceptanceServer(minecraft);
        if (minecraft.screen == null && minecraft.player != null && OPEN.consumeClick()) {
            minecraft.setScreen(new LegacyScreen(LegacyClientState.INSTANCE));
        }
        if (minecraft.level != null) LegacyClientState.INSTANCE.tick();
    }

    private void connectAcceptanceServer(Minecraft minecraft) {
        // A generic non-null screen includes startup loading screens.  On the
        // 1.16 client those can precede ModelManager initialization, so joining
        // then can race incoming world packets into an unready renderer.
        if (acceptanceConnectAttempted || minecraft.level != null || !(minecraft.screen instanceof TitleScreen)) return;
        String address = System.getProperty("jammarr.acceptance.server", "").trim();
        if (address.isEmpty()) return;
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) {
            throw new IllegalArgumentException("jammarr.acceptance.server must use host:port syntax");
        }
        acceptanceConnectAttempted = true;
        String host = address.substring(0, separator);
        int port = Integer.parseInt(address.substring(separator + 1));
        minecraft.setScreen(new ConnectScreen(minecraft.screen, minecraft, host, port));
    }
}
