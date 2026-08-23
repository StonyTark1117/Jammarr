package stonytark.jammarr.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.glfw.GLFW;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.platform.CanonicalConfigFiles;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.core.protocol.JammarrMessage;
import stonytark.jammarr.network.ClientPayloadBridge;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;

import java.nio.file.Path;

public final class JammarrClient implements ClientModInitializer {
    private static final KeyMapping OPEN = new KeyMapping("key.jammarr.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P, "key.categories.jammarr");

    @Override public void onInitializeClient() {
        installClientSettings();
        KeyBindingHelper.registerKeyBinding(OPEN);
        ClientPayloadBridge.install(JammarrClientState.INSTANCE::accept);
        JammarrNetwork.installClientSender(payload -> {
            FriendlyByteBuf buffer = PacketByteBufs.create();
            JammarrPayloads.write(payload, buffer);
            ClientPlayNetworking.send(JammarrPayloads.idOf(payload), buffer);
        });
        registerReceivers();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> JammarrClientState.INSTANCE.hello());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> JammarrClientState.INSTANCE.stop());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public ResourceLocation getFabricId() {
                return new ResourceLocation(Jammarr.MODID, "sound_engine_reload");
            }
            @Override public void onResourceManagerReload(ResourceManager manager) {
                JammarrClientState.INSTANCE.audioEngineReloaded();
            }
        });
    }

    private static void installClientSettings() {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        try {
            CanonicalConfigFiles.ClientConfig config = CanonicalConfigFiles.loadClient(
                    configDirectory.resolve(CanonicalConfigFiles.CLIENT_FILE_NAME),
                    configDirectory.resolve("jammarr-client-fabric.toml"),
                    configDirectory.resolve("pampmod-client.toml"));
            JammarrSettings.installClient(config);
            if (config.importedFrom() != null) {
                Jammarr.LOGGER.info("Imported legacy Jammarr client settings from {}", config.importedFrom());
            }
        } catch (Exception error) {
            Jammarr.LOGGER.error("Unable to load Jammarr client settings; using safe defaults", error);
        }
    }

    private void tick(Minecraft minecraft) {
        if (minecraft.screen == null && minecraft.player != null && OPEN.consumeClick()) {
            minecraft.setScreen(new JammarrScreen(JammarrClientState.INSTANCE));
            JammarrNetwork.sendToServer(new JammarrPayloads.BrowseRequest(JammarrPayloads.BrowseKind.SEARCH, "", 0));
        }
        JammarrClientState.INSTANCE.tick();
    }

    private static void registerReceivers() {
        receive(JammarrPayloads.OpenScreen.ID, buffer -> new JammarrPayloads.OpenScreen());
        receive(JammarrPayloads.ServerHello.ID, JammarrPayloads.ServerHello::read);
        receive(JammarrPayloads.TimeSyncResponse.ID, JammarrPayloads.TimeSyncResponse::read);
        receive(JammarrPayloads.BrowseResults.ID, JammarrPayloads.BrowseResults::read);
        receive(JammarrPayloads.AudioManifest.ID, JammarrPayloads.AudioManifest::read);
        receive(JammarrPayloads.AudioChunk.ID, JammarrPayloads.AudioChunk::read);
        receive(JammarrPayloads.PlaybackState.ID, JammarrPayloads.PlaybackState::read);
        receive(JammarrPayloads.StationState.ID, JammarrPayloads.StationState::read);
        receive(JammarrPayloads.AdventurePreview.ID, JammarrPayloads.AdventurePreview::read);
        receive(JammarrPayloads.ErrorMessage.ID, JammarrPayloads.ErrorMessage::read);
    }

    private static <T extends JammarrMessage> void receive(ResourceLocation id, JammarrNetwork.Decoder<T> decoder) {
        ClientPlayNetworking.registerGlobalReceiver(id, (client, handler, buffer, responseSender) -> {
            T payload = decoder.read(buffer);
            if (buffer.readableBytes() != 0) throw new IllegalArgumentException("Trailing bytes in Jammarr packet");
            client.execute(() -> ClientPayloadBridge.accept(payload));
        });
    }
}
