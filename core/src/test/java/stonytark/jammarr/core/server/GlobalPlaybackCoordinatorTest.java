package stonytark.jammarr.core.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.RestartMode;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.platform.CoreLogger;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.JammarrMessage;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.core.protocol.TransportPackets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalPlaybackCoordinatorTest {
    @TempDir Path temporary;

    @Test void oneCoordinatorOwnsProtocolStateAndPersistenceBehindPlatformContracts() throws Exception {
        JammarrSettings.installServer(new TestSettings());
        TestRuntime runtime = new TestRuntime(temporary);
        MemoryStore store = new MemoryStore();
        GlobalPlaybackCoordinator<TestPlayer> coordinator =
                new GlobalPlaybackCoordinator<TestPlayer>(runtime, store, new FakePlex());
        try {
            await(() -> coordinator.diagnostics().contains("Plex=ONLINE"));

            coordinator.playerJoined(runtime.listener);
            assertNotNull(runtime.last(runtime.listener, StatePackets.PlaybackState.class));
            assertNotNull(runtime.last(runtime.listener, StatePackets.StationState.class));

            coordinator.browse(runtime.listener,
                    new ControlPackets.BrowseRequest(ControlPackets.BrowseKind.SEARCH, "song", 0));
            await(() -> runtime.last(runtime.listener, ControlPackets.BrowseResults.class) != null);
            assertEquals("plex-track", runtime.last(runtime.listener, ControlPackets.BrowseResults.class)
                    .items().get(0).key());

            coordinator.station(runtime.listener, new ControlPackets.StationRequest(
                    ControlPackets.StationAction.SET_AUTOPLAY, StationModels.StationType.AUTOPLAY,
                    true, 0L, Collections.<StationModels.StationSeed>emptyList()));
            assertEquals(StatePackets.ErrorCode.PERMISSION_DENIED,
                    runtime.last(runtime.listener, StatePackets.ErrorMessage.class).code());

            coordinator.station(runtime.operator, new ControlPackets.StationRequest(
                    ControlPackets.StationAction.SET_AUTOPLAY, StationModels.StationType.AUTOPLAY,
                    true, 0L, Collections.<StationModels.StationSeed>emptyList()));
            assertTrue(store.autoplayEnabled());
            assertEquals(1L, store.station().generation());

            coordinator.queue(runtime.listener,
                    new ControlPackets.QueueRequest(StationModels.ItemKind.TRACK, "plex-track"));
            await(() -> store.current() != null);
            assertEquals("plex-track", store.current().key());
            assertEquals(StatePackets.PlaybackOrigin.MANUAL, store.currentOrigin());
            await(() -> runtime.last(runtime.listener, TransportPackets.AudioManifest.class) != null);
            TransportPackets.AudioManifest manifest =
                    runtime.last(runtime.listener, TransportPackets.AudioManifest.class);
            coordinator.chunks(runtime.listener,
                    new TransportPackets.ChunkRequest(manifest.sessionId(), 1L, 0, 1));
            assertNull(runtime.last(runtime.listener, TransportPackets.AudioChunk.class));
            coordinator.tick();
            assertNotNull(runtime.last(runtime.listener, TransportPackets.AudioChunk.class));
            assertTrue(store.dirtyCount > 0);
        } finally {
            coordinator.close();
        }
    }

    @Test void backgroundCompletionCannotReenterRuntimeAfterCoordinatorClose() throws Exception {
        JammarrSettings.installServer(new TestSettings());
        TestRuntime runtime = new TestRuntime(temporary);
        MemoryStore store = new MemoryStore();
        BlockingPlex plex = new BlockingPlex();
        GlobalPlaybackCoordinator<TestPlayer> coordinator =
                new GlobalPlaybackCoordinator<TestPlayer>(runtime, store, plex);
        try {
            await(() -> coordinator.diagnostics().contains("Plex=ONLINE"));
            coordinator.queue(runtime.listener,
                    new ControlPackets.QueueRequest(StationModels.ItemKind.TRACK, "blocked-track"));
            assertTrue(plex.transcodeStarted.await(3L, TimeUnit.SECONDS));

            coordinator.close();
            int executionsAfterClose = runtime.executions.get();
            int packetsAfterClose = runtime.sent.size();
            plex.releaseTranscode.countDown();
            assertTrue(plex.transcodeFinished.await(3L, TimeUnit.SECONDS));
            Thread.sleep(100L);

            assertEquals(executionsAfterClose, runtime.executions.get());
            assertEquals(packetsAfterClose, runtime.sent.size());
        } finally {
            plex.releaseTranscode.countDown();
            coordinator.close();
        }
    }

    @Test void obsoletePreparationCannotReactivateAReplacedCopyOfTheSameTrack() throws Exception {
        JammarrSettings.installServer(new TestSettings());
        TestRuntime runtime = new TestRuntime(temporary);
        MemoryStore store = new MemoryStore();
        BlockingFirstTranscodePlex plex = new BlockingFirstTranscodePlex();
        GlobalPlaybackCoordinator<TestPlayer> coordinator =
                new GlobalPlaybackCoordinator<TestPlayer>(runtime, store, plex);
        try {
            await(() -> coordinator.diagnostics().contains("Plex=ONLINE"));
            ControlPackets.QueueRequest sameTrack =
                    new ControlPackets.QueueRequest(StationModels.ItemKind.TRACK, "same-track");
            coordinator.queue(runtime.listener, sameTrack);
            assertTrue(plex.firstStarted.await(3L, TimeUnit.SECONDS));
            coordinator.queue(runtime.listener, sameTrack);
            await(() -> store.queue().size() == 1);

            coordinator.control(runtime.operator,
                    new ControlPackets.ControlRequest(ControlPackets.ControlAction.SKIP, -1, ""));
            await(() -> {
                TransportPackets.AudioManifest manifest =
                        runtime.last(runtime.listener, TransportPackets.AudioManifest.class);
                return manifest != null && manifest.totalChunks() > 0;
            });
            int manifestsBeforeOldCompletion = runtime.count(TransportPackets.AudioManifest.class);

            plex.releaseFirst.countDown();
            assertTrue(plex.firstFinished.await(3L, TimeUnit.SECONDS));
            Thread.sleep(100L);
            assertEquals(manifestsBeforeOldCompletion,
                    runtime.count(TransportPackets.AudioManifest.class));
        } finally {
            plex.releaseFirst.countDown();
            coordinator.close();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(10L);
        assertTrue(condition.getAsBoolean(), "asynchronous coordinator action did not complete");
    }

    private static final class TestPlayer {
        private final UUID id;
        private final boolean operator;
        private TestPlayer(String id, boolean operator) {
            this.id = UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.operator = operator;
        }
    }

    private static final class TestRuntime implements CoordinatorRuntime<TestPlayer> {
        private final Path cache;
        private final TestPlayer operator = new TestPlayer("operator", true);
        private final TestPlayer listener = new TestPlayer("listener", false);
        private final List<Sent> sent = new CopyOnWriteArrayList<Sent>();
        private final AtomicInteger executions = new AtomicInteger();
        private TestRuntime(Path temporary) { cache = temporary.resolve("cache"); }
        @Override public UUID playerId(TestPlayer player) { return player.id; }
        @Override public boolean isOperator(TestPlayer player, int permissionLevel) { return player.operator; }
        @Override public List<TestPlayer> players() { return java.util.Arrays.asList(operator, listener); }
        @Override public int playerCount() { return 2; }
        @Override public Path cacheDirectory() { return cache; }
        @Override public void execute(Runnable action) { executions.incrementAndGet(); action.run(); }
        @Override public void send(TestPlayer player, JammarrMessage message) { sent.add(new Sent(player, message)); }
        @Override public void chat(TestPlayer player, String message) {}
        @Override public CoreLogger logger() { return CoreLogger.NO_OP; }
        private <T> T last(TestPlayer player, Class<T> type) {
            for (int index = sent.size() - 1; index >= 0; index--) {
                Sent value = sent.get(index);
                if (value.player == player && type.isInstance(value.message)) return type.cast(value.message);
            }
            return null;
        }
        private int count(Class<?> type) {
            int count = 0;
            for (Sent value : sent) if (type.isInstance(value.message)) count++;
            return count;
        }
    }

    private static final class Sent {
        private final TestPlayer player;
        private final JammarrMessage message;
        private Sent(TestPlayer player, JammarrMessage message) { this.player = player; this.message = message; }
    }

    private static final class MemoryStore implements PlaybackStore {
        private final List<QueueTrack> queue = new ArrayList<QueueTrack>();
        private final List<QueueTrack> history = new ArrayList<QueueTrack>();
        private QueueTrack current;
        private StatePackets.PlaybackOrigin origin = StatePackets.PlaybackOrigin.NONE;
        private String source = "";
        private StationModels.StationDefinition station = StationModels.StationDefinition.none(0L);
        private boolean autoplay;
        private long checkpoint;
        private boolean paused;
        private int dirtyCount;
        @Override public List<QueueTrack> queue() { return queue; }
        @Override public List<QueueTrack> history() { return history; }
        @Override public QueueTrack current() { return current; }
        @Override public StatePackets.PlaybackOrigin currentOrigin() { return origin; }
        @Override public String currentSourceName() { return source; }
        @Override public StationModels.StationDefinition station() { return station; }
        @Override public boolean autoplayEnabled() { return autoplay; }
        @Override public long checkpointMs() { return checkpoint; }
        @Override public boolean paused() { return paused; }
        @Override public void current(QueueTrack track, StatePackets.PlaybackOrigin value, String name) {
            current = track; origin = track == null ? StatePackets.PlaybackOrigin.NONE : value;
            source = track == null ? "" : name; markChanged();
        }
        @Override public void station(StationModels.StationDefinition value) { station = value; markChanged(); }
        @Override public void autoplayEnabled(boolean enabled) { autoplay = enabled; markChanged(); }
        @Override public void remember(QueueTrack track) { if (track != null) history.add(track); markChanged(); }
        @Override public void update(long value, boolean isPaused) { checkpoint = value; paused = isPaused; markChanged(); }
        @Override public void clearAll() {
            queue.clear(); history.clear(); current = null; origin = StatePackets.PlaybackOrigin.NONE;
            source = ""; station = StationModels.StationDefinition.none(station.generation() + 1L);
            autoplay = false; checkpoint = 0L; paused = false; markChanged();
        }
        @Override public void markChanged() { dirtyCount++; }
    }

    private static class FakePlex implements PlexGateway {
        @Override public void validate() {}
        @Override public PlexService.SonicStatus sonicStatus() {
            return new PlexService.SonicStatus(StationModels.SonicCapability.READY, "Sonic analysis ready");
        }
        @Override public PlexService.Page browse(ControlPackets.BrowseKind kind, String query, int page, int pageSize) {
            return new PlexService.Page(Collections.singletonList(new StationModels.MediaItem(
                    StationModels.ItemKind.TRACK, "plex-track", "Song", "Artist", 2_000L)), false);
        }
        @Override public List<QueueTrack> expand(StationModels.ItemKind kind, String key, int limit) {
            return Collections.singletonList(track(key));
        }
        @Override public void transcode(QueueTrack track, Path output, int bitrate) throws java.io.IOException {
            byte[] data = new byte[522 * 4];
            for (int offset = 0; offset < data.length; offset += 522) {
                data[offset] = (byte) 0xff; data[offset + 1] = (byte) 0xfb;
                data[offset + 2] = (byte) 0xa0; data[offset + 3] = 0;
            }
            Files.write(output, data);
        }
        @Override public List<QueueTrack> nativeRadioTracks(StationModels.StationSeed seed, int limit) { return Collections.emptyList(); }
        @Override public boolean hasSonicAnalysis(String key) { return true; }
        @Override public List<StationModels.SonicResult> nearest(StationModels.ItemKind kind, String key, int limit, double maxDistance) { return Collections.emptyList(); }
        @Override public List<QueueTrack> nearestTracks(String key, int limit, double maxDistance) { return Collections.emptyList(); }
        @Override public List<QueueTrack> sonicPath(String startKey, String endKey, int limit) { return Collections.emptyList(); }
        @Override public List<QueueTrack> randomTracks(int limit, Set<String> excluded) { return Collections.emptyList(); }
        @Override public List<QueueTrack> metadataFallback(List<StationModels.StationSeed> seeds, int limit, Set<String> excluded) { return Collections.emptyList(); }
        private static QueueTrack track(String key) { return new QueueTrack(key, "Song", "Artist", "Album", 2_000L); }
    }

    private static final class BlockingPlex extends FakePlex {
        private final CountDownLatch transcodeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseTranscode = new CountDownLatch(1);
        private final CountDownLatch transcodeFinished = new CountDownLatch(1);

        @Override public void transcode(QueueTrack track, Path output, int bitrate) throws java.io.IOException {
            transcodeStarted.countDown();
            boolean released = false;
            while (!released) {
                try { released = releaseTranscode.await(3L, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.interrupted(); }
            }
            try { super.transcode(track, output, bitrate); }
            finally { transcodeFinished.countDown(); }
        }
    }

    private static final class BlockingFirstTranscodePlex extends FakePlex {
        private final AtomicInteger transcodes = new AtomicInteger();
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch firstFinished = new CountDownLatch(1);

        @Override public void transcode(QueueTrack track, Path output, int bitrate) throws java.io.IOException {
            if (transcodes.getAndIncrement() != 0) {
                super.transcode(track, output, bitrate);
                return;
            }
            firstStarted.countDown();
            boolean released = false;
            while (!released) {
                try { released = releaseFirst.await(3L, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.interrupted(); }
            }
            try { super.transcode(track, output, bitrate); }
            finally { firstFinished.countDown(); }
        }
    }

    private static final class TestSettings implements JammarrSettings.ServerValues {
        @Override public String plexUrl() { return "http://127.0.0.1:32400"; }
        @Override public String plexToken() { return "test-token"; }
        @Override public String musicLibrary() { return "Music"; }
        @Override public RestartMode restartMode() { return RestartMode.RESTART_TRACK; }
        @Override public boolean pauseWhenEmpty() { return true; }
        @Override public int operatorPermissionLevel() { return 2; }
        @Override public int queueLimit() { return 500; }
        @Override public int audioBitrateKbps() { return 160; }
        @Override public long cacheSizeMiB() { return 64L; }
        @Override public boolean stationMetadataFallbackEnabled() { return false; }
    }
}
