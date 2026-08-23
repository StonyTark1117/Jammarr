package stonytark.jammarr.gametest;

import stonytark.jammarr.core.model.QueueTrack;


import stonytark.jammarr.core.server.ChunkTransferPolicy;
import stonytark.jammarr.core.server.SlidingWindowRateLimiter;
import stonytark.jammarr.core.server.PlaybackTimeline;
import stonytark.jammarr.core.server.RetryGate;
import stonytark.jammarr.core.client.AsyncStartGuard;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.server.JammarrSavedData;
import stonytark.jammarr.core.server.QueueOperations;
import stonytark.jammarr.server.StationDefinition;
import stonytark.jammarr.core.server.StationSelection;
import stonytark.jammarr.server.StationGenerator;
import stonytark.jammarr.network.JammarrPayloads;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;

@GameTestHolder(Jammarr.MODID)
@PrefixGameTestTemplate(false)
public final class JammarrGameTests {
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
        JammarrSavedData data = new JammarrSavedData();
        data.queue().add(new QueueTrack("42", "Track", "Artist", "Album", 90_000));
        data.update(12_345, true);
        JammarrSavedData restored = JammarrSavedData.load(data.save(new CompoundTag(), null), null);
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

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void operatorQueueActionsPreserveCurrentTrackAndPlaybackPosition(GameTestHelper helper) {
        var queue = new java.util.ArrayList<QueueTrack>();
        queue.add(new QueueTrack("current", "Current", "Artist", "Album", 90_000));
        queue.add(new QueueTrack("next", "Next", "Artist", "Album", 90_000));
        queue.add(new QueueTrack("last", "Last", "Artist", "Album", 90_000));
        helper.assertTrue(QueueOperations.move(queue, 2, -1, true) == QueueOperations.Result.APPLIED && queue.get(1).key().equals("last"), "Operator reorder did not move a queued entry");
        helper.assertTrue(QueueOperations.remove(queue, 1, true) == QueueOperations.Result.APPLIED && queue.getFirst().key().equals("current"), "Operator remove changed the current track unexpectedly");
        AtomicLong clock = new AtomicLong(1_000);
        PlaybackTimeline timeline = new PlaybackTimeline(clock::get);
        timeline.schedule(90_000, 0, false, 5_000);
        clock.set(8_000);
        timeline.pause();
        long paused = timeline.positionMs();
        clock.set(18_000);
        helper.assertTrue(timeline.positionMs() == paused, "Pause did not hold the authoritative position");
        timeline.resume();
        clock.set(19_000);
        helper.assertTrue(timeline.positionMs() > paused, "Resume did not advance the authoritative position");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void stationPersistenceAndAdventureOrderingRoundTrip(GameTestHelper helper) {
        JammarrSavedData data = new JammarrSavedData();
        var start = new JammarrPayloads.StationSeed(JammarrPayloads.ItemKind.TRACK, "1", "Start", "Artist A");
        var end = new JammarrPayloads.StationSeed(JammarrPayloads.ItemKind.TRACK, "3", "End", "Artist B");
        try { StationGenerator.validate(new StationDefinition(JammarrPayloads.StationType.SONIC_ADVENTURE, "Adventure", List.of(start, end), 4)); }
        catch (Exception failure) { helper.fail("Valid Adventure definition was rejected: " + failure.getMessage()); return; }
        data.station(new StationDefinition(JammarrPayloads.StationType.SONIC_ADVENTURE, "Adventure", List.of(start, end), 4));
        data.current(new QueueTrack("1", "Start", "Artist A", "Album", 1_000), JammarrPayloads.PlaybackOrigin.ADVENTURE);
        JammarrSavedData restored = JammarrSavedData.load(data.save(new CompoundTag(), null), null);
        helper.assertTrue(restored.station().type() == JammarrPayloads.StationType.SONIC_ADVENTURE && restored.station().seeds().size() == 2,
                "Adventure state did not survive world-data serialization");
        List<QueueTrack> path = StationSelection.deduplicatePath(List.of(
                List.of(new QueueTrack("1", "Start", "A", "", 1), new QueueTrack("2", "Middle", "B", "", 1)),
                List.of(new QueueTrack("2", "Middle", "B", "", 1), new QueueTrack("3", "End", "C", "", 1))), 100);
        helper.assertTrue(path.stream().map(QueueTrack::key).toList().equals(List.of("1", "2", "3")), "Adventure segment join duplicated or reordered a waypoint");
        helper.succeed();
    }
    private JammarrGameTests() {}
}
