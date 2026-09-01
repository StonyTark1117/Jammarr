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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic real-coordinator load coverage for the 1/8/32 listener release gate. */
class GlobalPlaybackLoadTest {
    private static final int WINDOW_CHUNKS = 8;
    private static final Pattern DIAGNOSTIC_FIELD = Pattern.compile("(?:^|, )([A-Za-z]+)=([0-9]+)");

    @TempDir Path temporary;

    @Test void oneEightAndThirtyTwoListenersRemainFairAndBounded() throws Exception {
        JammarrSettings.installServer(new LoadSettings());
        long descriptorsBefore = openFileDescriptors();
        long previousBacklogBytes = 0L;
        for (int listenerCount : new int[] {1, 8, 32}) {
            ScenarioMetrics metrics = runScenario(listenerCount);
            assertEquals(listenerCount * WINDOW_CHUNKS, metrics.maximumBacklogItems);
            assertTrue(metrics.maximumBacklogBytes > previousBacklogBytes,
                    "egress bytes should grow monotonically with listener count");
            assertTrue(metrics.maximumBacklogBytes <= 16L * 1024L * 1024L,
                    "egress bytes must stay inside the coordinator's hard heap budget");
            assertTrue(metrics.maximumWorkActive <= 3,
                    "background work must stay inside the three-worker limit");
            assertTrue(metrics.maximumWorkQueued <= 64,
                    "background work must stay inside the bounded executor queue");
            assertTrue(metrics.maximumChunksPerTick <= 32,
                    "a server tick must not emit more than the fixed egress item budget");
            assertTrue(metrics.maximumTickNanos < 250_000_000L,
                    "in-memory 32-listener drain must remain well below one server stall interval");
            previousBacklogBytes = metrics.maximumBacklogBytes;
        }
        long descriptorsAfter = openFileDescriptors();
        if (descriptorsBefore >= 0L && descriptorsAfter >= 0L) {
            assertTrue(descriptorsAfter <= descriptorsBefore + 4L,
                    "coordinator load scenarios leaked file descriptors: before="
                            + descriptorsBefore + ", after=" + descriptorsAfter);
        }
    }

    private ScenarioMetrics runScenario(int listenerCount) throws Exception {
        LoadRuntime runtime = new LoadRuntime(temporary.resolve("listeners-" + listenerCount), listenerCount);
        MemoryStore store = new MemoryStore();
        GlobalPlaybackCoordinator<TestPlayer> coordinator =
                new GlobalPlaybackCoordinator<TestPlayer>(runtime, store, new LoadPlex());
        try {
            await(() -> coordinator.diagnostics().contains("Plex=ONLINE"));
            TestPlayer controller = runtime.players().get(0);
            coordinator.queue(controller,
                    new ControlPackets.QueueRequest(StationModels.ItemKind.TRACK, "load-track"));
            await(() -> runtime.last(controller, TransportPackets.AudioManifest.class) != null);
            TransportPackets.AudioManifest manifest =
                    runtime.last(controller, TransportPackets.AudioManifest.class);
            assertNotNull(manifest);
            assertTrue(manifest.totalChunks() >= WINDOW_CHUNKS,
                    "load fixture must expose a complete chunk request window");

            for (TestPlayer player : runtime.players()) coordinator.playerJoined(player);
            runtime.sent.clear();
            for (TestPlayer player : runtime.players()) {
                coordinator.chunks(player, new TransportPackets.ChunkRequest(
                        manifest.sessionId(), 1L, 0, WINDOW_CHUNKS));
            }

            String queued = coordinator.diagnostics();
            int maximumBacklogItems = field(queued, "egressItems");
            long maximumBacklogBytes = field(queued, "egressBytes");
            int maximumWorkActive = field(queued, "workActive");
            int maximumWorkQueued = field(queued, "workQueued");
            assertEquals(listenerCount, field(queued, "capableListeners"));
            assertEquals(listenerCount, field(queued, "listenerStats"));
            assertEquals(0, field(queued, "vanillaListeners"));

            int maximumChunksPerTick = 0;
            long maximumTickNanos = 0L;
            while (field(coordinator.diagnostics(), "egressItems") > 0) {
                int sentBefore = runtime.count(TransportPackets.AudioChunk.class);
                long started = System.nanoTime();
                coordinator.tick();
                maximumTickNanos = Math.max(maximumTickNanos, System.nanoTime() - started);
                int sentThisTick = runtime.count(TransportPackets.AudioChunk.class) - sentBefore;
                maximumChunksPerTick = Math.max(maximumChunksPerTick, sentThisTick);
                assertFair(runtime);
                String current = coordinator.diagnostics();
                maximumWorkActive = Math.max(maximumWorkActive, field(current, "workActive"));
                maximumWorkQueued = Math.max(maximumWorkQueued, field(current, "workQueued"));
            }
            for (TestPlayer player : runtime.players()) {
                assertEquals(WINDOW_CHUNKS,
                        runtime.count(player, TransportPackets.AudioChunk.class));
            }

            List<TestPlayer> connected = new ArrayList<TestPlayer>(runtime.players());
            for (TestPlayer player : connected) coordinator.playerLeft(player);
            runtime.players.clear();
            String cleaned = coordinator.diagnostics();
            assertEquals(0, field(cleaned, "capableListeners"));
            assertEquals(0, field(cleaned, "listenerStats"));
            assertEquals(0, field(cleaned, "egressItems"));
            assertEquals(0, field(cleaned, "egressBytes"));
            return new ScenarioMetrics(maximumBacklogItems, maximumBacklogBytes,
                    maximumWorkActive, maximumWorkQueued, maximumChunksPerTick, maximumTickNanos);
        } finally {
            coordinator.close();
        }
    }

    private static void assertFair(LoadRuntime runtime) {
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (TestPlayer player : runtime.players()) {
            int count = runtime.count(player, TransportPackets.AudioChunk.class);
            minimum = Math.min(minimum, count);
            maximum = Math.max(maximum, count);
        }
        assertTrue(maximum - minimum <= 1,
                "round-robin egress let one listener advance by more than one chunk");
    }

    private static int field(String diagnostics, String name) {
        Matcher matcher = DIAGNOSTIC_FIELD.matcher(diagnostics);
        while (matcher.find()) if (name.equals(matcher.group(1))) return Integer.parseInt(matcher.group(2));
        throw new AssertionError("Missing diagnostics field " + name + ": " + diagnostics);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(10L);
        assertTrue(condition.getAsBoolean(), "asynchronous coordinator action did not complete");
    }

    private static long openFileDescriptors() {
        Path directory = java.nio.file.Paths.get("/proc/self/fd");
        if (!Files.isDirectory(directory)) return -1L;
        try (Stream<Path> files = Files.list(directory)) { return files.count(); }
        catch (Exception ignored) { return -1L; }
    }

    private static final class ScenarioMetrics {
        private final int maximumBacklogItems;
        private final long maximumBacklogBytes;
        private final int maximumWorkActive;
        private final int maximumWorkQueued;
        private final int maximumChunksPerTick;
        private final long maximumTickNanos;

        private ScenarioMetrics(int maximumBacklogItems, long maximumBacklogBytes,
                                int maximumWorkActive, int maximumWorkQueued,
                                int maximumChunksPerTick, long maximumTickNanos) {
            this.maximumBacklogItems = maximumBacklogItems;
            this.maximumBacklogBytes = maximumBacklogBytes;
            this.maximumWorkActive = maximumWorkActive;
            this.maximumWorkQueued = maximumWorkQueued;
            this.maximumChunksPerTick = maximumChunksPerTick;
            this.maximumTickNanos = maximumTickNanos;
        }
    }

    private static final class TestPlayer {
        private final UUID id;
        private TestPlayer(String name) {
            id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class Sent {
        private final TestPlayer player;
        private final JammarrMessage message;
        private Sent(TestPlayer player, JammarrMessage message) {
            this.player = player;
            this.message = message;
        }
    }

    private static final class LoadRuntime implements CoordinatorRuntime<TestPlayer> {
        private final Path cache;
        private final List<TestPlayer> players = new ArrayList<TestPlayer>();
        private final List<Sent> sent = new CopyOnWriteArrayList<Sent>();

        private LoadRuntime(Path cache, int listenerCount) {
            this.cache = cache;
            for (int index = 0; index < listenerCount; index++) {
                players.add(new TestPlayer("listener-" + listenerCount + "-" + index));
            }
        }
        @Override public UUID playerId(TestPlayer player) { return player.id; }
        @Override public boolean isOperator(TestPlayer player, int permissionLevel) { return true; }
        @Override public List<TestPlayer> players() { return Collections.unmodifiableList(players); }
        @Override public int playerCount() { return players.size(); }
        @Override public int totalPlayerCount() { return players.size(); }
        @Override public Path cacheDirectory() { return cache; }
        @Override public void execute(Runnable action) { action.run(); }
        @Override public void send(TestPlayer player, JammarrMessage message) {
            sent.add(new Sent(player, message));
        }
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
        private int count(TestPlayer player, Class<?> type) {
            int count = 0;
            for (Sent value : sent) {
                if (value.player == player && type.isInstance(value.message)) count++;
            }
            return count;
        }
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
            current = track;
            origin = track == null ? StatePackets.PlaybackOrigin.NONE : value;
            source = track == null ? "" : name;
        }
        @Override public void station(StationModels.StationDefinition value) { station = value; }
        @Override public void autoplayEnabled(boolean enabled) { autoplay = enabled; }
        @Override public void remember(QueueTrack track) { if (track != null) history.add(track); }
        @Override public void update(long value, boolean isPaused) { checkpoint = value; paused = isPaused; }
        @Override public void clearAll() {
            queue.clear();
            history.clear();
            current = null;
            origin = StatePackets.PlaybackOrigin.NONE;
            source = "";
            station = StationModels.StationDefinition.none(station.generation() + 1L);
            autoplay = false;
            checkpoint = 0L;
            paused = false;
        }
        @Override public void markChanged() {}
    }

    private static final class LoadPlex implements PlexGateway {
        @Override public void validate() {}
        @Override public PlexService.SonicStatus sonicStatus() {
            return new PlexService.SonicStatus(
                    StationModels.SonicCapability.READY, "Sonic analysis ready");
        }
        @Override public PlexService.Page browse(ControlPackets.BrowseKind kind, String query,
                                                  int page, int pageSize) {
            return new PlexService.Page(Collections.<StationModels.MediaItem>emptyList(), false);
        }
        @Override public List<QueueTrack> expand(StationModels.ItemKind kind, String key, int limit) {
            return Collections.singletonList(track(key));
        }
        @Override public void transcode(QueueTrack track, Path output, int bitrate) throws java.io.IOException {
            byte[] data = new byte[522 * 600];
            for (int offset = 0; offset < data.length; offset += 522) {
                data[offset] = (byte) 0xff;
                data[offset + 1] = (byte) 0xfb;
                data[offset + 2] = (byte) 0xa0;
                data[offset + 3] = 0;
            }
            Files.write(output, data);
        }
        @Override public List<QueueTrack> nativeRadioTracks(
                StationModels.StationSeed seed, int limit) { return Collections.emptyList(); }
        @Override public boolean hasSonicAnalysis(String key) { return true; }
        @Override public List<StationModels.SonicResult> nearest(
                StationModels.ItemKind kind, String key, int limit, double maxDistance) {
            return Collections.emptyList();
        }
        @Override public List<QueueTrack> nearestTracks(
                String key, int limit, double maxDistance) { return Collections.emptyList(); }
        @Override public List<QueueTrack> sonicPath(
                String startKey, String endKey, int limit) { return Collections.emptyList(); }
        @Override public List<QueueTrack> randomTracks(
                int limit, Set<String> excluded) { return Collections.emptyList(); }
        @Override public List<QueueTrack> metadataFallback(
                List<StationModels.StationSeed> seeds, int limit, Set<String> excluded) {
            return Collections.emptyList();
        }
        private static QueueTrack track(String key) {
            return new QueueTrack(key, "Load Song", "Load Artist", "Load Album", 15_000L);
        }
    }

    private static final class LoadSettings implements JammarrSettings.ServerValues {
        @Override public String plexUrl() { return "http://127.0.0.1:32400"; }
        @Override public String plexToken() { return "test-token"; }
        @Override public String musicLibrary() { return "Music"; }
        @Override public RestartMode restartMode() { return RestartMode.RESTART_TRACK; }
        @Override public boolean pauseWhenEmpty() { return false; }
        @Override public int operatorPermissionLevel() { return 2; }
        @Override public int queueLimit() { return 500; }
        @Override public int audioBitrateKbps() { return 160; }
        @Override public long cacheSizeMiB() { return 64L; }
        @Override public boolean stationMetadataFallbackEnabled() { return false; }
    }
}
