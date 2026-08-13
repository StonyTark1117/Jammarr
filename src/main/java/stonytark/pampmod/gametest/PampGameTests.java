package stonytark.pampmod.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import stonytark.pampmod.Pampmod;
import stonytark.pampmod.client.AsyncStartGuard;
import stonytark.pampmod.server.ChunkTransferPolicy;
import stonytark.pampmod.server.PampSavedData;
import stonytark.pampmod.server.PlaybackTimeline;
import stonytark.pampmod.server.QueueTrack;
import stonytark.pampmod.server.RetryGate;
import stonytark.pampmod.server.SlidingWindowRateLimiter;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@GameTestHolder(Pampmod.MODID)
@PrefixGameTestTemplate(false)
public final class PampGameTests {
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void serverLoadsAuthoritativeTimingAndRateLimitCode(GameTestHelper helper) {
        AtomicLong clock = new AtomicLong(1_000); PlaybackTimeline timeline = new PlaybackTimeline(clock::get);
        timeline.schedule(10_000, 0, false, 5_000); clock.set(7_000);
        helper.assertTrue(timeline.positionMs() == 1_000, "Authoritative server timeline did not advance from its scheduled start");
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(); UUID player = UUID.randomUUID();
        helper.assertTrue(limiter.allow(player, 1, 1_000) && !limiter.allow(player, 1, 1_100), "Server request rate limit was not enforced");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void serverTransportRequiresManifestOrderAndAcknowledgement(GameTestHelper helper) {
        UUID session = UUID.randomUUID();
        ChunkTransferPolicy.State state = ChunkTransferPolicy.initial(session, 4, 1_000);
        helper.assertTrue(!ChunkTransferPolicy.acceptsRequest(state, session, 1, 0, 8, 40, 1_000), "Transfer accepted a pre-manifest chunk index");
        helper.assertTrue(ChunkTransferPolicy.acceptsRequest(state, session, 1, 4, 8, 40, 1_000), "Transfer rejected the manifest window");
        state = ChunkTransferPolicy.begin(session, 1, 4, 8, 1_000);
        helper.assertTrue(!ChunkTransferPolicy.acceptsRequest(state, session, 2, 12, 8, 40, 1_100), "Transfer advanced without an acknowledgement");
        helper.assertTrue(!ChunkTransferPolicy.withinPlaybackLead(17_001, 5_000, 12_000), "Transfer ran ahead of the authoritative playback window");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void queueAndPlaybackCheckpointRoundTripThroughWorldData(GameTestHelper helper) {
        PampSavedData data = new PampSavedData();
        data.queue().add(new QueueTrack("42", "Track", "Artist", "Album", 90_000));
        data.update(12_345, true);
        PampSavedData restored = PampSavedData.load(data.save(new CompoundTag(), null), null);
        helper.assertTrue(restored.queue().size() == 1 && restored.queue().getFirst().key().equals("42"), "Queue did not survive saved-data serialization");
        helper.assertTrue(restored.checkpointMs() == 12_345 && restored.paused(), "Playback checkpoint did not survive saved-data serialization");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void retryAndAudioStartGatesRejectPrematureWork(GameTestHelper helper) {
        RetryGate retry = new RetryGate(); retry.deferUntil(30_000);
        helper.assertTrue(!retry.ready(29_999) && retry.ready(30_000), "Preparation retry gate ignored its deadline");
        AsyncStartGuard starts = new AsyncStartGuard(); long first = starts.begin();
        helper.assertTrue(starts.begin() < 0, "A duplicate audio-channel start was admitted");
        starts.cancel(); long second = starts.begin();
        helper.assertTrue(!starts.complete(first) && starts.complete(second), "A stale audio completion released the current start");
        helper.succeed();
    }
    private PampGameTests() {}
}
