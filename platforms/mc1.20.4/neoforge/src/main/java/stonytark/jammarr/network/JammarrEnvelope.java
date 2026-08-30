package stonytark.jammarr.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.JammarrMessage;

/** NeoForge 20.4 envelope for the pre-StreamCodec custom-payload API. */
public record JammarrEnvelope(JammarrMessage message) implements CustomPacketPayload {
    public static final ResourceLocation ID = new ResourceLocation(Jammarr.MODID, "main");

    public static JammarrEnvelope read(FriendlyByteBuf buffer) {
        ResourceLocation messageId = buffer.readResourceLocation();
        return new JammarrEnvelope(JammarrPayloads.read(messageId, buffer));
    }

    @Override public void write(FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(JammarrPayloads.idOf(message));
        JammarrPayloads.write(message, buffer);
    }

    @Override public ResourceLocation id() { return ID; }
}
