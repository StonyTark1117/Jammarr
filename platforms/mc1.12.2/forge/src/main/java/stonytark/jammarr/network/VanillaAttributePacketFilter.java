package stonytark.jammarr.network;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketEntityProperties;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

/** Removes Forge-only watched attributes from packets sent to an unmodified client. */
final class VanillaAttributePacketFilter extends ChannelOutboundHandlerAdapter {
    static final String PIPELINE_NAME = "jammarr:vanilla_attribute_filter";
    private static final String PACKET_HANDLER = "packet_handler";
    private static final String REACH_DISTANCE = "generic.reachDistance";
    private static final String SWIM_SPEED = "forge.swimSpeed";
    private static final Field SNAPSHOTS = ReflectionHelper.findField(
            SPacketEntityProperties.class, new String[] {"snapshots", "field_149444_b"});

    static boolean install(NetworkManager manager) {
        ChannelPipeline pipeline = manager.channel().pipeline();
        if (pipeline.get(PIPELINE_NAME) != null) return false;
        if (pipeline.get(PACKET_HANDLER) == null) {
            throw new IllegalStateException("Minecraft packet handler is unavailable during vanilla-client setup");
        }
        pipeline.addBefore(PACKET_HANDLER, PIPELINE_NAME, new VanillaAttributePacketFilter());
        return true;
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        if (!(message instanceof SPacketEntityProperties)) {
            context.write(message, promise);
            return;
        }

        SPacketEntityProperties filtered = filteredCopy((SPacketEntityProperties) message);
        if (filtered == null) {
            promise.setSuccess();
            return;
        }
        context.write(filtered, promise);
    }

    /** Returns a packet-private copy, or null when every snapshot was Forge-only. */
    static SPacketEntityProperties filteredCopy(SPacketEntityProperties original)
            throws IOException, IllegalAccessException {
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        SPacketEntityProperties copy = new SPacketEntityProperties();
        try {
            original.writePacketData(buffer);
            copy.readPacketData(buffer);
        } finally {
            buffer.release();
        }

        List<SPacketEntityProperties.Snapshot> snapshots = snapshots(copy);
        for (int index = snapshots.size() - 1; index >= 0; index--) {
            if (isForgeOnly(snapshots.get(index).getName())) snapshots.remove(index);
        }
        return snapshots.isEmpty() ? null : copy;
    }

    @SuppressWarnings("unchecked")
    static List<SPacketEntityProperties.Snapshot> snapshots(SPacketEntityProperties packet)
            throws IllegalAccessException {
        return (List<SPacketEntityProperties.Snapshot>) SNAPSHOTS.get(packet);
    }

    static boolean isForgeOnly(String name) {
        return REACH_DISTANCE.equals(name) || SWIM_SPEED.equals(name);
    }
}
