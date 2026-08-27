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
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.glfw.GLFW;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.platform.CanonicalConfigFiles;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.network.ClientPayloadBridge;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;

import java.nio.file.Path;

public final class JammarrClient implements ClientModInitializer {
    private static final KeyMapping OPEN = new KeyMapping("key.jammarr.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J, "key.categories.jammarr");

    @Override public void onInitializeClient() {
        installClientSettings();
        KeyBindingHelper.registerKeyBinding(OPEN);
        ClientPayloadBridge.install(JammarrClientState.INSTANCE::accept);
        JammarrNetwork.installClientSender(ClientPlayNetworking::send);
        registerReceivers();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> JammarrClientState.INSTANCE.hello());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> JammarrClientState.INSTANCE.stop());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath(Jammarr.MODID, "sound_engine_reload");
            }
            @Override public void onResourceManagerReload(ResourceManager manager) {
                JammarrClientState.INSTANCE.audioEngineReloaded();
            }
        });
    }

    private static void installClientSettings() {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        try {
            CanonicalConfigFiles.ClientConfig config = CanonicalConfigFiles.loadClientForLoader(
                    configDirectory, "fabric");
            JammarrSettings.installClient(config);
            if (config.importedFrom() != null) {
                Jammarr.LOGGER.info("Imported legacy Jammarr client settings from {}", config.importedFrom());
            }
        } catch (Exception error) { throw new IllegalStateException("Unable to load Jammarr client settings", error); }
    }

    private void tick(Minecraft minecraft) {
        if (minecraft.screen == null && minecraft.player != null && OPEN.consumeClick()) {
            minecraft.setScreen(new JammarrScreen(JammarrClientState.INSTANCE));
            JammarrNetwork.sendToServer(new JammarrPayloads.BrowseRequest(JammarrPayloads.BrowseKind.SEARCH, "", 0));
        }
        JammarrClientState.INSTANCE.tick();
    }

    private static void registerReceivers() {
        receive(JammarrPayloads.OpenScreen.TYPE, JammarrPayloads.OpenScreen.CODEC);
        receive(JammarrPayloads.ServerHello.TYPE, JammarrPayloads.ServerHello.CODEC);
        receive(JammarrPayloads.TimeSyncResponse.TYPE, JammarrPayloads.TimeSyncResponse.CODEC);
        receive(JammarrPayloads.BrowseResults.TYPE, JammarrPayloads.BrowseResults.CODEC);
        receive(JammarrPayloads.AudioManifest.TYPE, JammarrPayloads.AudioManifest.CODEC);
        receive(JammarrPayloads.AudioChunk.TYPE, JammarrPayloads.AudioChunk.CODEC);
        receive(JammarrPayloads.PlaybackState.TYPE, JammarrPayloads.PlaybackState.CODEC);
        receive(JammarrPayloads.StationState.TYPE, JammarrPayloads.StationState.CODEC);
        receive(JammarrPayloads.AdventurePreview.TYPE, JammarrPayloads.AdventurePreview.CODEC);
        receive(JammarrPayloads.ErrorMessage.TYPE, JammarrPayloads.ErrorMessage.CODEC);
    }

    private static <T extends CustomPacketPayload & stonytark.jammarr.core.protocol.JammarrMessage> void receive(CustomPacketPayload.Type<T> type,
                                                                 StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) -> ClientPayloadBridge.accept(payload));
    }
}
