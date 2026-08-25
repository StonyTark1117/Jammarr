package stonytark.jammarr.quilt;

import net.fabricmc.fabric.impl.networking.CustomPayloadTypeProvider;
import net.fabricmc.fabric.impl.networking.FabricCustomPayloadStreamCodec;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QuiltNetworkingCodecRepair {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private QuiltNetworkingCodecRepair() {}

    public static void install() {
        if (!FabricLoader.getInstance().isModLoaded("quilt_loader") || !INSTALLED.compareAndSet(false, true)) return;
        if (PayloadTypeRegistryImpl.CLIENTBOUND_PLAY.get(RegistrationPayload.REGISTER) == null) {
            FabricLoaderImpl.INSTANCE.invokeEntrypoints("main", ModInitializer.class, ModInitializer::onInitialize);
        }
        install(ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC,
                (RegistryFriendlyByteBuf buffer, net.minecraft.resources.Identifier id) ->
                        PayloadTypeRegistryImpl.CLIENTBOUND_PLAY.get(id));
        install(ClientboundCustomPayloadPacket.CONFIG_STREAM_CODEC,
                (FriendlyByteBuf buffer, net.minecraft.resources.Identifier id) ->
                        PayloadTypeRegistryImpl.CLIENTBOUND_CONFIGURATION.get(id));
        install(ServerboundCustomPayloadPacket.STREAM_CODEC,
                (FriendlyByteBuf buffer, net.minecraft.resources.Identifier id) ->
                        (net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec) (buffer instanceof RegistryFriendlyByteBuf
                                ? PayloadTypeRegistryImpl.SERVERBOUND_PLAY.get(id)
                                : PayloadTypeRegistryImpl.SERVERBOUND_CONFIGURATION.get(id)));
    }

    private static <B extends FriendlyByteBuf> void install(StreamCodec<?, ?> root,
                                                            CustomPayloadTypeProvider<B> provider) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!installRecursive(root, provider, visited, 0)) {
            throw new IllegalStateException("Unable to locate Fabric custom-payload codec on Quilt");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean installRecursive(Object value, CustomPayloadTypeProvider provider,
                                            Set<Object> visited, int depth) {
        if (value == null || depth > 6 || !visited.add(value)) return false;
        if (value instanceof FabricCustomPayloadStreamCodec codec) {
            try {
                codec.fabric_setCustomPayloadTypeProvider(provider);
            } catch (IllegalStateException alreadyInstalled) {
                // Fabric's own wrapper installed this provider successfully.
            }
            return true;
        }
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            try {
                field.setAccessible(true);
                if (installRecursive(field.get(value), provider, visited, depth + 1)) return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Synthetic codec wrappers may expose inaccessible bookkeeping fields.
            }
        }
        return false;
    }
}
