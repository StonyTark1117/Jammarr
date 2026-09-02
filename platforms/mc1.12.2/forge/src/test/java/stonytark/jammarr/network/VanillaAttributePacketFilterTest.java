package stonytark.jammarr.network;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.entity.ai.attributes.AttributeMap;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.ai.attributes.RangedAttribute;
import net.minecraft.network.play.server.SPacketEntityProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VanillaAttributePacketFilterTest {
    @Test
    void clonedPacketRetainsVanillaAttributesAndDoesNotMutateOriginal() throws Exception {
        SPacketEntityProperties original = packet(
                "generic.maxHealth", "generic.reachDistance", "forge.swimSpeed");

        SPacketEntityProperties filtered = VanillaAttributePacketFilter.filteredCopy(original);

        assertNotSame(original, filtered);
        assertEquals(Arrays.asList("generic.maxHealth"), names(filtered));
        assertEquals(Arrays.asList(
                "generic.maxHealth", "generic.reachDistance", "forge.swimSpeed"), names(original));
    }

    @Test
    void dropsPacketWhoseEverySnapshotIsForgeOnly() throws Exception {
        assertNull(VanillaAttributePacketFilter.filteredCopy(
                packet("generic.reachDistance", "forge.swimSpeed")));
    }

    @Test
    void outboundHandlerPassesUnrelatedMessagesByIdentity() {
        EmbeddedChannel channel = new EmbeddedChannel(new VanillaAttributePacketFilter());
        Object marker = new Object();

        assertTrue(channel.writeOutbound(marker));
        assertSame(marker, channel.readOutbound());
        assertFalse(channel.finish());
    }

    private static SPacketEntityProperties packet(String... names) {
        AttributeMap attributes = new AttributeMap();
        List<IAttributeInstance> instances = new ArrayList<IAttributeInstance>();
        for (String name : names) {
            instances.add(attributes.registerAttribute(
                    new RangedAttribute(null, name, 1.0D, 0.0D, 1024.0D).setShouldWatch(true)));
        }
        return new SPacketEntityProperties(7, instances);
    }

    private static List<String> names(SPacketEntityProperties packet) throws Exception {
        List<String> names = new ArrayList<String>();
        for (SPacketEntityProperties.Snapshot snapshot : VanillaAttributePacketFilter.snapshots(packet)) {
            names.add(snapshot.getName());
        }
        return names;
    }
}
