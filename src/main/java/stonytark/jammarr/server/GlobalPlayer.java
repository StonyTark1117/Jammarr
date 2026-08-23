package stonytark.jammarr.server;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.config.JammarrConfig;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GlobalPlayer implements AutoCloseable {
    private static final int PAGE_SIZE = 20;
    private static final long TRACK_START_DELAY_MS = 5_000;
    private static final long PLEX_REVALIDATE_MS = 30_000;

    private enum PlexHealth { VALIDATING, ONLINE, OFFLINE }

    private final MinecraftServer server;
    private final PlexClient plex;
    private final AudioCache cache;
    private final ExecutorService io = Executors.newFixedThreadPool(3, Thread.ofPlatform().name("jammarr-io-", 0).factory());
    private final JammarrSavedData saved;
    private final PlaybackTimeline timeline = new PlaybackTimeline(System::currentTimeMillis);
    private final AtomicBoolean preparing = new AtomicBoolean();
    private final AtomicBoolean prefetching = new AtomicBoolean();
    private final AtomicBoolean validating = new AtomicBoolean();
    private final SlidingWindowRateLimiter browseLimits = new SlidingWindowRateLimiter();
    private final SlidingWindowRateLimiter queueLimits = new SlidingWindowRateLimiter();
    private final SlidingWindowRateLimiter chunkLimits = new SlidingWindowRateLimiter();
    private final SlidingWindowRateLimiter acknowledgementLimits = new SlidingWindowRateLimiter();
    private final SlidingWindowRateLimiter manifestLimits = new SlidingWindowRateLimiter();
    private final Map<UUID, ChunkTransferPolicy.State> transfers = new HashMap<>();
    private final RetryGate preparationRetry = new RetryGate();

    private AudioAsset asset;
    private PreparedAsset prefetched;
    private UUID sessionId;
    private long restorePositionMs;
    private boolean restorePaused;
    private boolean autoPaused;
    private long lastStateBroadcast;
    private long lastCheckpoint;
    private long nextPlexValidation;
    private volatile PlexHealth plexHealth = PlexHealth.VALIDATING;
    private volatile String plexDiagnostic = "Plex validation is pending";

    public GlobalPlayer(MinecraftServer server) throws IOException {
        this(server, new PlexClient());
    }

    GlobalPlayer(MinecraftServer server, PlexClient plex) throws IOException {
        this.server = server;
        this.plex = plex;
        this.cache = new AudioCache(server.getServerDirectory().resolve("jammarr-cache"), JammarrConfig.CACHE_MIB.get() * 1024L * 1024L);
        this.saved = server.overworld().getDataStorage().computeIfAbsent(JammarrSavedData.FACTORY, "jammarr_global_queue");
        RestartPolicy.Restoration restoration = RestartPolicy.restore(JammarrConfig.RESTART_MODE.get(), saved.checkpointMs(), saved.paused());
        if (restoration.clearQueue()) saved.queue().clear();
        restorePositionMs = restoration.positionMs();
        restorePaused = restoration.paused();
        saved.update(restorePositionMs, restorePaused);
        validatePlex();
    }

    public void validatePlex() {
        if (!validating.compareAndSet(false, true)) return;
        plexHealth = PlexHealth.VALIDATING;
        broadcastState();
        CompletableFuture.runAsync(() -> {
            try { plex.validate(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, io).whenComplete((unused, error) -> server.execute(() -> {
            validating.set(false);
            nextPlexValidation = System.currentTimeMillis() + PLEX_REVALIDATE_MS;
            if (error == null) {
                plexHealth = PlexHealth.ONLINE;
                plexDiagnostic = "Connected to the configured Plex music library";
                preparationRetry.clear();
                Jammarr.LOGGER.info("Jammarr connected to the configured Plex music library");
                prepareCurrent();
            } else {
                markPlexFailure(error);
                Jammarr.LOGGER.error("Jammarr Plex validation failed: {}", safe(error));
            }
            broadcastState();
        }));
    }

    public void tick() {
        long now = System.currentTimeMillis();
        boolean empty = server.getPlayerList().getPlayerCount() == 0;
        if (EmptyServerPausePolicy.shouldPause(JammarrConfig.PAUSE_WHEN_EMPTY.get(), empty, timeline.active(), timeline.paused())) {
            pauseInternal(true);
        } else if (EmptyServerPausePolicy.shouldResume(autoPaused, empty)) {
            resumeInternal();
        }
        if (asset == null && !saved.queue().isEmpty() && preparationRetry.ready(now)) prepareCurrent();
        if (timeline.ended()) skipInternal();
        if (plexHealth == PlexHealth.OFFLINE && now >= nextPlexValidation) validatePlex();
        if (now - lastCheckpoint >= 5_000) {
            saved.update(positionMs(), timeline.paused());
            lastCheckpoint = now;
        }
        if (now - lastStateBroadcast >= 2_000) {
            broadcastState();
            lastStateBroadcast = now;
        }
        transfers.entrySet().removeIf(entry -> now - entry.getValue().lastSeenMs() > 30_000);
    }

    public void hello(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new JammarrPayloads.ServerHello(JammarrNetwork.PROTOCOL, System.currentTimeMillis()));
        playerJoined(player);
    }

    public void browse(ServerPlayer player, JammarrPayloads.BrowseRequest request) {
        if (!allow(browseLimits, player, 8)) return;
        int page = Math.max(0, Math.min(request.page(), 10_000));
        if (request.kind() == JammarrPayloads.BrowseKind.QUEUE) {
            List<JammarrPayloads.MediaItem> values = saved.queue().stream().skip((long)page * PAGE_SIZE).limit(PAGE_SIZE + 1L)
                    .map(t -> new JammarrPayloads.MediaItem(JammarrPayloads.ItemKind.TRACK, t.key(), t.title(), t.artist(), t.durationMs())).toList();
            boolean more = values.size() > PAGE_SIZE;
            if (more) values = values.subList(0, PAGE_SIZE);
            PacketDistributor.sendToPlayer(player, new JammarrPayloads.BrowseResults(request.kind(), "", page, more, values));
            return;
        }
        if (!requirePlex(player)) return;
        String query = request.query().trim();
        if (request.kind() == JammarrPayloads.BrowseKind.SEARCH && query.length() < 2) {
            PacketDistributor.sendToPlayer(player, new JammarrPayloads.BrowseResults(request.kind(), query, page, false, List.of()));
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try { return plex.browse(request.kind(), query, page, PAGE_SIZE); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, io).whenComplete((result, error) -> server.execute(() -> {
            if (error != null) {
                markPlexFailure(error);
                sendError(player, JammarrPayloads.ErrorCode.PLEX_OFFLINE, "Plex browsing is currently unavailable");
            } else {
                PacketDistributor.sendToPlayer(player, new JammarrPayloads.BrowseResults(request.kind(), query, page, result.hasMore(), result.items()));
            }
        }));
    }

    public void queue(ServerPlayer player, JammarrPayloads.QueueRequest request) {
        if (!allow(queueLimits, player, 4) || !requirePlex(player)) return;
        int available = JammarrConfig.QUEUE_LIMIT.get() - saved.queue().size();
        if (available <= 0) {
            sendError(player, JammarrPayloads.ErrorCode.QUEUE_FULL, "The global queue is full");
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try { return plex.expand(request.kind(), request.key(), available); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, io).whenComplete((tracks, error) -> server.execute(() -> {
            if (error != null) {
                markPlexFailure(error);
                sendError(player, JammarrPayloads.ErrorCode.PLEX_OFFLINE, "Unable to queue that Plex item");
                return;
            }
            if (tracks.isEmpty()) {
                sendError(player, JammarrPayloads.ErrorCode.INVALID_REQUEST, "That Plex item contains no playable tracks");
                return;
            }
            int room = JammarrConfig.QUEUE_LIMIT.get() - saved.queue().size();
            if (room <= 0) {
                sendError(player, JammarrPayloads.ErrorCode.QUEUE_FULL, "The global queue is full");
                return;
            }
            QueueOperations.AppendResult append = QueueOperations.append(saved.queue(), tracks, JammarrConfig.QUEUE_LIMIT.get());
            int acceptedCount = append.accepted();
            saved.setDirty();
            player.sendSystemMessage(Component.literal("Queued " + acceptedCount + (acceptedCount == 1 ? " track" : " tracks")));
            broadcastState();
            prepareCurrent();
            prefetchNext();
        }));
    }

    public void control(ServerPlayer player, JammarrPayloads.ControlRequest request) {
        if (!player.hasPermissions(JammarrConfig.OP_PERMISSION.get())) {
            sendError(player, JammarrPayloads.ErrorCode.PERMISSION_DENIED, "Operator permission is required");
            return;
        }
        switch (request.action()) {
            case PAUSE -> pauseInternal(false);
            case RESUME -> resumeInternal();
            case SKIP -> skipInternal();
            case CLEAR -> {
                saved.queue().clear();
                prefetched = null;
                stopAudio();
                saved.update(0, false);
            }
            case REMOVE -> {
                if (request.index() < 0 || request.index() >= saved.queue().size()) {
                    sendError(player, JammarrPayloads.ErrorCode.INVALID_REQUEST, "Queue index is out of range");
                    return;
                }
                if (request.index() == 0) skipInternal();
                else {
                    saved.queue().remove(request.index());
                    saved.setDirty();
                    prefetched = null;
                    prefetchNext();
                }
            }
            case MOVE_UP -> move(request.index(), -1);
            case MOVE_DOWN -> move(request.index(), 1);
        }
        broadcastState();
    }

    public void chunks(ServerPlayer player, JammarrPayloads.ChunkRequest request) {
        if (asset == null || sessionId == null || !sessionId.equals(request.sessionId())) return;
        if (!allow(chunkLimits, player, 4)) return;
        long now = System.currentTimeMillis();
        ChunkTransferPolicy.State previous = transfers.get(player.getUUID());
        if (!ChunkTransferPolicy.acceptsRequest(previous, sessionId, request.requestId(), request.startIndex(), request.count(), asset.chunks().size(), now)) return;
        int start = request.startIndex();
        if (!ChunkTransferPolicy.withinPlaybackLead(asset.chunks().get(start).startMs(), positionMs(),
                TRACK_START_DELAY_MS + ChunkTransferPolicy.MAX_BUFFERED_MS)) return;
        int count = request.count();
        int end = start + count;
        transfers.put(player.getUUID(), ChunkTransferPolicy.begin(sessionId, request.requestId(), start, count, now));
        if (request.requestId() == 1) Jammarr.LOGGER.info("Jammarr sent the initial audio chunk window to {}", player.getUUID());
        for (int i = start; i < end; i++) {
            Mp3FrameIndex.Chunk chunk = asset.chunks().get(i);
            PacketDistributor.sendToPlayer(player, new JammarrPayloads.AudioChunk(sessionId, request.requestId(), chunk.index(), chunk.startMs(), chunk.sha256(), chunk.data()));
        }
    }

    public void acknowledge(ServerPlayer player, JammarrPayloads.ChunkAcknowledgement acknowledgement) {
        if (!allow(acknowledgementLimits, player, 8)) return;
        ChunkTransferPolicy.acknowledge(transfers.get(player.getUUID()), acknowledgement.sessionId(), acknowledgement.requestId(),
                acknowledgement.receivedThroughIndex(), acknowledgement.bufferedMs(), System.currentTimeMillis())
                .ifPresent(state -> transfers.put(player.getUUID(), state));
    }

    public void playerJoined(ServerPlayer player) { sendState(player); if (asset != null) sendManifest(player); }
    public void sync(ServerPlayer player) {
        if (!allow(manifestLimits, player, 2)) return;
        sendState(player);
        if (asset != null) sendManifest(player);
    }
    public void playerLeft(ServerPlayer player) {
        transfers.remove(player.getUUID());
        browseLimits.remove(player.getUUID()); queueLimits.remove(player.getUUID()); chunkLimits.remove(player.getUUID());
        acknowledgementLimits.remove(player.getUUID()); manifestLimits.remove(player.getUUID());
    }
    public long cacheSize() { return cache.size(); }
    public String status() {
        if (saved.queue().isEmpty()) return plexHealth == PlexHealth.OFFLINE ? "Queue is empty; Plex is offline" : "Queue is empty";
        String prefix = asset == null ? "Preparing: " : timeline.paused() ? "Paused: " : "Playing: ";
        return prefix + saved.queue().getFirst().title() + " at " + positionMs() / 1000 + "s";
    }
    public String diagnostics() { return "Plex=" + plexHealth + ", cache=" + cache.size() / 1024 / 1024 + " MiB, listeners=" + transfers.size() + ", detail=" + plexDiagnostic; }

    private boolean requirePlex(ServerPlayer player) {
        if (plexHealth == PlexHealth.ONLINE) return true;
        sendError(player, JammarrPayloads.ErrorCode.PLEX_OFFLINE, plexHealth == PlexHealth.VALIDATING ? "Plex validation is still in progress" : "Plex is offline; ask an operator to run /jammarr diagnostics");
        return false;
    }

    private void prepareCurrent() {
        if (saved.queue().isEmpty() || asset != null || !preparing.compareAndSet(false, true)) return;
        QueueTrack track = saved.queue().getFirst();
        if (prefetched != null && prefetched.track.key().equals(track.key())) {
            PreparedAsset ready = prefetched;
            prefetched = null;
            preparing.set(false);
            activate(track, ready.asset);
            return;
        }
        int bitrate = JammarrConfig.BITRATE.get();
        Set<Path> pinned = pinnedPaths(cache.target(track.key(), bitrate));
        CompletableFuture.supplyAsync(() -> prepare(track, bitrate, pinned), io).whenComplete((prepared, error) -> server.execute(() -> {
            preparing.set(false);
            if (saved.queue().isEmpty() || !saved.queue().getFirst().key().equals(track.key())) return;
            if (error != null) {
                if (TrackFailurePolicy.action(error) == TrackFailurePolicy.Action.WAIT_FOR_PLEX) {
                    markPlexFailure(error);
                    preparationRetry.deferUntil(nextPlexValidation);
                    broadcastState();
                    return;
                }
                Jammarr.LOGGER.error("Skipping unplayable Plex track {}: {}", track.key(), safe(error), error);
                saved.queue().removeFirst();
                saved.setDirty();
                sendErrorToOperators(JammarrPayloads.ErrorCode.TRACK_FAILED, "Skipped an unplayable queued track");
                prepareCurrent();
                return;
            }
            plexHealth = PlexHealth.ONLINE;
            activate(track, prepared.asset);
        }));
    }

    private PreparedAsset prepare(QueueTrack track, int bitrate, Set<Path> pinned) {
        Path target = cache.target(track.key(), bitrate);
        try {
            if (Files.isRegularFile(target)) {
                try { return new PreparedAsset(track, cache.load(target, bitrate)); }
                catch (IOException invalidCache) { Files.deleteIfExists(target); }
            }
            Exception last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
                try {
                    plex.transcode(track, temporary, bitrate);
                    return new PreparedAsset(track, cache.install(temporary, target, pinned, bitrate));
                } catch (Exception e) {
                    last = e;
                    Files.deleteIfExists(temporary);
                    if (TrackFailurePolicy.action(e) == TrackFailurePolicy.Action.WAIT_FOR_PLEX) break;
                    if (attempt < 3) Thread.sleep(500L << (attempt - 1));
                }
            }
            throw new IOException("Plex preparation failed after three attempts", last);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void activate(QueueTrack track, AudioAsset prepared) {
        asset = prepared;
        sessionId = UUID.randomUUID();
        long duration = asset.durationMs() > 0 ? asset.durationMs() : track.durationMs();
        long restore = Math.min(restorePositionMs, Math.max(0, duration - 1));
        boolean initiallyPaused = restorePaused;
        boolean emptyPause = JammarrConfig.PAUSE_WHEN_EMPTY.get() && server.getPlayerList().getPlayerCount() == 0 && !initiallyPaused;
        timeline.schedule(duration, restore, initiallyPaused || emptyPause, TRACK_START_DELAY_MS);
        autoPaused = emptyPause;
        restorePositionMs = 0;
        restorePaused = false;
        broadcastManifest();
        broadcastState();
        prefetchNext();
    }

    private void prefetchNext() {
        if (asset == null || saved.queue().size() < 2 || prefetching.get()) return;
        QueueTrack track = saved.queue().get(1);
        if (prefetched != null && prefetched.track.key().equals(track.key())) return;
        if (!prefetching.compareAndSet(false, true)) return;
        int bitrate = JammarrConfig.BITRATE.get();
        Set<Path> pinned = pinnedPaths(cache.target(track.key(), bitrate));
        CompletableFuture.supplyAsync(() -> prepare(track, bitrate, pinned), io).whenComplete((prepared, error) -> server.execute(() -> {
            prefetching.set(false);
            if (saved.queue().size() < 2 || !saved.queue().get(1).key().equals(track.key())) { prefetchNext(); return; }
            if (error == null) {
                prefetched = prepared;
                cache.trim(pinnedPaths(prepared.asset.path()));
            } else {
                Jammarr.LOGGER.warn("Unable to prefetch Plex track {}: {}", track.key(), safe(error));
                if (TrackFailurePolicy.action(error) == TrackFailurePolicy.Action.WAIT_FOR_PLEX) markPlexFailure(error);
            }
        }));
    }

    private Set<Path> pinnedPaths(Path additional) {
        Set<Path> pins = new HashSet<>();
        if (additional != null) pins.add(additional);
        if (asset != null) pins.add(asset.path());
        if (prefetched != null) pins.add(prefetched.asset.path());
        return Set.copyOf(pins);
    }

    private void skipInternal() {
        if (!saved.queue().isEmpty()) saved.queue().removeFirst();
        stopAudio();
        restorePositionMs = 0;
        restorePaused = false;
        autoPaused = false;
        saved.update(0, false);
        prepareCurrent();
    }

    private void pauseInternal(boolean automatic) {
        if (!timeline.active() || timeline.paused()) return;
        timeline.pause();
        autoPaused = automatic;
        saved.update(positionMs(), true);
        broadcastManifest();
    }

    private void resumeInternal() {
        if (!timeline.active() || !timeline.paused()) return;
        timeline.resume();
        autoPaused = false;
        saved.update(positionMs(), false);
        broadcastManifest();
    }

    private void stopAudio() {
        asset = null;
        sessionId = null;
        timeline.stop();
        transfers.clear();
        PacketDistributor.sendToAllPlayers(emptyManifest());
    }

    private void move(int index, int delta) {
        int target = index + delta;
        if (index <= 0 || target <= 0 || index >= saved.queue().size() || target >= saved.queue().size()) return;
        QueueTrack value = saved.queue().remove(index);
        saved.queue().add(target, value);
        saved.setDirty();
        prefetched = null;
        prefetchNext();
    }

    private long positionMs() { return timeline.positionMs(); }
    private long durationMs() { return timeline.durationMs(); }

    private void broadcastManifest() { for (ServerPlayer player : server.getPlayerList().getPlayers()) sendManifest(player); }
    private void sendManifest(ServerPlayer player) {
        if (asset == null || saved.queue().isEmpty()) return;
        QueueTrack track = saved.queue().getFirst();
        long now = System.currentTimeMillis();
        long target = timeline.paused() ? timeline.pausedPositionMs() : (now < timeline.startedAtMs() ? 0 : positionMs() + TRACK_START_DELAY_MS);
        int firstChunk = Mp3FrameIndex.chunkAt(asset.chunks(), Math.min(target, Math.max(0, durationMs() - 1)));
        transfers.put(player.getUUID(), ChunkTransferPolicy.initial(sessionId, firstChunk, now));
        PacketDistributor.sendToPlayer(player, new JammarrPayloads.AudioManifest(sessionId, track.title(), track.artist(), asset.chunks().size(), firstChunk,
                durationMs(), timeline.startedAtMs(), timeline.paused(), timeline.pausedPositionMs(), asset.sha256()));
    }

    private JammarrPayloads.AudioManifest emptyManifest() { return new JammarrPayloads.AudioManifest(new UUID(0, 0), "", "", 0, 0, 0, 0, true, 0, ""); }
    private void broadcastState() { for (ServerPlayer player : server.getPlayerList().getPlayers()) sendState(player); }
    private void sendState(ServerPlayer player) {
        QueueTrack current = saved.queue().isEmpty() ? null : saved.queue().getFirst();
        List<JammarrPayloads.QueueEntry> queue = saved.queue().stream().map(QueueTrack::networkEntry).toList();
        JammarrPayloads.PlaybackStatus status = playbackStatus();
        String detail = player.hasPermissions(JammarrConfig.OP_PERMISSION.get()) ? plexDiagnostic : status == JammarrPayloads.PlaybackStatus.PLEX_OFFLINE ? "Plex is currently unavailable" : "";
        PacketDistributor.sendToPlayer(player, new JammarrPayloads.PlaybackState(status, detail, current == null ? "" : current.title(), current == null ? "" : current.artist(),
                timeline.paused(), positionMs(), durationMs(), System.currentTimeMillis(), player.hasPermissions(JammarrConfig.OP_PERMISSION.get()), queue));
    }

    private JammarrPayloads.PlaybackStatus playbackStatus() {
        if (asset != null) return timeline.paused() ? JammarrPayloads.PlaybackStatus.PAUSED : JammarrPayloads.PlaybackStatus.PLAYING;
        if (plexHealth == PlexHealth.OFFLINE) return JammarrPayloads.PlaybackStatus.PLEX_OFFLINE;
        if (preparing.get() || !saved.queue().isEmpty()) return JammarrPayloads.PlaybackStatus.PREPARING;
        return JammarrPayloads.PlaybackStatus.IDLE;
    }

    private boolean allow(SlidingWindowRateLimiter limiter, ServerPlayer player, int perSecond) {
        long now = System.currentTimeMillis();
        if (limiter.allow(player.getUUID(), perSecond, now)) return true;
        if (limiter.count(player.getUUID(), now) == perSecond + 1) sendError(player, JammarrPayloads.ErrorCode.RATE_LIMITED, "Jammarr request rate limit exceeded");
        return false;
    }

    private void markPlexFailure(Throwable error) {
        plexHealth = PlexHealth.OFFLINE;
        plexDiagnostic = actionable(error);
        nextPlexValidation = System.currentTimeMillis() + PLEX_REVALIDATE_MS;
    }

    private String actionable(Throwable error) {
        Throwable root = root(error);
        if (root instanceof PlexException plexError) {
            return switch (plexError.kind()) {
                case AUTHENTICATION -> "Plex rejected the configured token; update JAMMARR_PLEX_TOKEN or jammarr-server.toml";
                case CONFIGURATION -> safe(root);
                case NOT_FOUND -> "A queued Plex item no longer exists";
                case OFFLINE -> "Plex is unreachable or timed out";
                case INVALID_RESPONSE -> "Plex returned invalid metadata or audio";
                case TRANSCODE -> "Plex could not prepare the requested MP3 rendition";
            };
        }
        return "Plex operation failed: " + safe(root);
    }

    private void sendErrorToOperators(JammarrPayloads.ErrorCode code, String message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) if (player.hasPermissions(JammarrConfig.OP_PERMISSION.get())) sendError(player, code, message);
    }
    private static void sendError(ServerPlayer player, JammarrPayloads.ErrorCode code, String message) { PacketDistributor.sendToPlayer(player, new JammarrPayloads.ErrorMessage(code, message)); }
    private String safe(Throwable error) { return SecretRedactor.message(error, JammarrConfig.plexToken()); }
    private static Throwable root(Throwable error) { Throwable value = error; while (value.getCause() != null) value = value.getCause(); return value; }

    private record PreparedAsset(QueueTrack track, AudioAsset asset) {}
    @Override public void close() {
        saved.update(positionMs(), timeline.paused());
        stopAudio();
        io.shutdownNow();
    }
}
