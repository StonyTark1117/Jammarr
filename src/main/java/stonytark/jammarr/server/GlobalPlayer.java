package stonytark.jammarr.server;

import stonytark.jammarr.core.model.QueueTrack;


import stonytark.jammarr.core.server.Mp3FrameIndex;
import stonytark.jammarr.core.server.EmptyServerPausePolicy;
import stonytark.jammarr.core.server.SecretRedactor;
import stonytark.jammarr.core.server.ChunkTransferPolicy;
import stonytark.jammarr.core.server.PlexException;
import stonytark.jammarr.core.server.SlidingWindowRateLimiter;
import stonytark.jammarr.core.server.PlaybackTimeline;
import stonytark.jammarr.core.server.RetryGate;
import stonytark.jammarr.core.server.TrackFailurePolicy;
import stonytark.jammarr.core.server.QueueOperations;
import stonytark.jammarr.core.server.RestartPolicy;
import stonytark.jammarr.core.server.AudioAsset;
import stonytark.jammarr.core.server.AudioCache;
import stonytark.jammarr.core.platform.CoreLogger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import java.util.concurrent.atomic.AtomicInteger;

public final class GlobalPlayer implements AutoCloseable {
    private static final int PAGE_SIZE = 20;
    private static final int GENERATED_PREVIEW_SIZE = 3;
    private static final long TRACK_START_DELAY_MS = 5_000;
    private static final long PLEX_REVALIDATE_MS = 30_000;

    private enum PlexHealth { VALIDATING, ONLINE, OFFLINE }

    private final MinecraftServer server;
    private final PlexClient plex;
    private final StationGenerator stationGenerator;
    private final AudioCache cache;
    private final ExecutorService io = createIoExecutor();
    private final JammarrSavedData saved;
    private final PlaybackTimeline timeline = new PlaybackTimeline(System::currentTimeMillis);
    private final AtomicBoolean preparing = new AtomicBoolean();
    private final AtomicBoolean prefetching = new AtomicBoolean();
    private final AtomicBoolean validating = new AtomicBoolean();
    private final AtomicBoolean generating = new AtomicBoolean();
    private final SlidingWindowRateLimiter browseLimits = new SlidingWindowRateLimiter();
    private final SlidingWindowRateLimiter queueLimits = new SlidingWindowRateLimiter();
    private final SlidingWindowRateLimiter stationLimits = new SlidingWindowRateLimiter();
    private final SlidingWindowRateLimiter chunkLimits = new SlidingWindowRateLimiter();
    private final SlidingWindowRateLimiter acknowledgementLimits = new SlidingWindowRateLimiter();
    private final SlidingWindowRateLimiter manifestLimits = new SlidingWindowRateLimiter();
    private final Map<UUID, ChunkTransferPolicy.State> transfers = new HashMap<>();
    private final Map<UUID, ListenerStats> listenerStats = new HashMap<>();
    private final RetryGate preparationRetry = new RetryGate();
    private final Deque<QueueTrack> generated = new ArrayDeque<>();

    private AudioAsset asset;
    private PreparedAsset prefetched;
    private UUID sessionId;
    private long restorePositionMs;
    private boolean restorePaused;
    private boolean autoPaused;
    private long lastStateBroadcast;
    private long lastCheckpoint;
    private long nextPlexValidation;
    private volatile long lastPlexValidation;
    private long chunkRequests;
    private long chunkAcknowledgements;
    private long rejectedChunkRequests;
    private long adventureLoadedGeneration = -1;
    private long suspendedGeneration = -1;
    private volatile PlexHealth plexHealth = PlexHealth.VALIDATING;
    private volatile JammarrPayloads.SonicCapability sonicCapability = JammarrPayloads.SonicCapability.CHECKING;
    private volatile String sonicMessage = "Plex sonic capability validation is pending";
    private volatile String generationMessage = "";
    private volatile String plexDiagnostic = "Plex validation is pending";

    public GlobalPlayer(MinecraftServer server) throws IOException { this(server, new PlexClient()); }

    GlobalPlayer(MinecraftServer server, PlexClient plex) throws IOException {
        this.server = server; this.plex = plex; this.stationGenerator = new StationGenerator(plex);
        this.cache = new AudioCache(java.nio.file.Paths.get(server.getServerDirectory().toString()).resolve("jammarr-cache"), JammarrSettings.cacheSizeMiB() * 1024L * 1024L,
                new CoreLogger() {
                    @Override public void info(String message) { Jammarr.LOGGER.info(message); }
                    @Override public void warn(String message, Throwable error) { Jammarr.LOGGER.warn(message, error); }
                });
        this.saved = JammarrSavedData.get(server);
        RestartPolicy.Restoration restoration = RestartPolicy.restore(JammarrSettings.restartMode(), saved.checkpointMs(), saved.paused());
        if (restoration.clearQueue()) saved.clearAll();
        restorePositionMs = restoration.positionMs(); restorePaused = restoration.paused();
        saved.update(restorePositionMs, restorePaused);
        validatePlex();
    }

    public void validatePlex() {
        if (!validating.compareAndSet(false, true)) return;
        plexHealth = PlexHealth.VALIDATING; sonicCapability = JammarrPayloads.SonicCapability.CHECKING;
        sonicMessage = "Checking Plex Pass and sonic analysis"; lastPlexValidation = System.currentTimeMillis(); broadcastState();
        CompletableFuture.supplyAsync(() -> {
            try { plex.validate(); return plex.sonicStatus(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, io).whenComplete((status, error) -> server.execute(() -> {
            validating.set(false); nextPlexValidation = System.currentTimeMillis() + PLEX_REVALIDATE_MS;
            if (error == null) {
                plexHealth = PlexHealth.ONLINE; sonicCapability = status.capability(); sonicMessage = status.message();
                plexDiagnostic = "Connected to the configured Plex music library"; preparationRetry.clear();
                suspendedGeneration = -1;
                Jammarr.LOGGER.info("Jammarr connected to Plex; sonic capability is {}", sonicCapability);
                ensureCurrent(); requestGeneration();
            } else {
                markPlexFailure(error); Jammarr.LOGGER.error("Jammarr Plex validation failed: {}", safe(error));
            }
            broadcastState();
        }));
    }

    public void tick() {
        long now = System.currentTimeMillis(); boolean empty = server.getPlayerList().getPlayerCount() == 0;
        if (EmptyServerPausePolicy.shouldPause(JammarrSettings.pauseWhenEmpty(), empty, timeline.active(), timeline.paused())) pauseInternal(true);
        else if (EmptyServerPausePolicy.shouldResume(autoPaused, empty)) resumeInternal();
        if (asset == null && saved.current() != null && preparationRetry.ready(now)) prepareCurrent();
        if (asset == null && saved.current() == null) ensureCurrent();
        if (timeline.ended()) finishCurrent();
        if (generated.size() < GENERATED_PREVIEW_SIZE) requestGeneration();
        if (plexHealth == PlexHealth.OFFLINE && now >= nextPlexValidation) validatePlex();
        if (now - lastCheckpoint >= 5_000) { saved.update(positionMs(), timeline.paused()); lastCheckpoint = now; }
        if (now - lastStateBroadcast >= 2_000) { broadcastState(); lastStateBroadcast = now; }
        transfers.entrySet().removeIf(entry -> now - entry.getValue().lastSeenMs() > 30_000);
    }

    public void hello(ServerPlayer player) {
        JammarrNetwork.sendToPlayer(player, new JammarrPayloads.ServerHello(JammarrNetwork.PROTOCOL, System.currentTimeMillis())); playerJoined(player);
    }

    public void browse(ServerPlayer player, JammarrPayloads.BrowseRequest request) {
        if (!allow(browseLimits, player, 8)) return;
        int page = Math.max(0, Math.min(request.page(), 10_000));
        if (request.kind() == JammarrPayloads.BrowseKind.QUEUE) {
            List<JammarrPayloads.QueueEntry> all = visibleQueue();
            List<JammarrPayloads.MediaItem> values = all.stream().skip((long)page * PAGE_SIZE).limit(PAGE_SIZE + 1L)
                    .map(t -> new JammarrPayloads.MediaItem(JammarrPayloads.ItemKind.TRACK, t.key(), t.title(), t.artist(), t.durationMs())).toList();
            boolean more = values.size() > PAGE_SIZE; if (more) values = values.subList(0, PAGE_SIZE);
            JammarrNetwork.sendToPlayer(player, new JammarrPayloads.BrowseResults(request.kind(), "", page, more, values)); return;
        }
        if (!requirePlex(player)) return;
        String query = request.query().trim();
        if (request.kind() == JammarrPayloads.BrowseKind.SEARCH && query.length() < 2) {
            JammarrNetwork.sendToPlayer(player, new JammarrPayloads.BrowseResults(request.kind(), query, page, false, List.of())); return;
        }
        CompletableFuture.supplyAsync(() -> {
            try { return plex.browse(request.kind(), query, page, PAGE_SIZE); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, io).whenComplete((result, error) -> server.execute(() -> {
            if (error != null) { markPlexFailure(error); sendError(player, JammarrPayloads.ErrorCode.PLEX_OFFLINE, "Plex browsing is currently unavailable"); }
            else JammarrNetwork.sendToPlayer(player, new JammarrPayloads.BrowseResults(request.kind(), query, page, result.hasMore(), result.items()));
        }));
    }

    public void queue(ServerPlayer player, JammarrPayloads.QueueRequest request) {
        if (!allow(queueLimits, player, 4) || !requirePlex(player)) return;
        int available = JammarrSettings.queueLimit() - manualCount();
        if (available <= 0) { sendError(player, JammarrPayloads.ErrorCode.QUEUE_FULL, "The global manual queue is full"); return; }
        CompletableFuture.supplyAsync(() -> {
            try { return plex.expand(request.kind(), request.key(), available); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, io).whenComplete((tracks, error) -> server.execute(() -> {
            if (error != null) { markPlexFailure(error); sendError(player, JammarrPayloads.ErrorCode.PLEX_OFFLINE, "Unable to queue that Plex item"); return; }
            if (tracks.isEmpty()) { sendError(player, JammarrPayloads.ErrorCode.INVALID_REQUEST, "That Plex item contains no playable tracks"); return; }
            QueueOperations.AppendResult append = QueueOperations.append(saved.queue(), tracks, JammarrSettings.queueLimit() - (saved.currentOrigin() == JammarrPayloads.PlaybackOrigin.MANUAL ? 1 : 0));
            if (append.accepted() == 0) { sendError(player, JammarrPayloads.ErrorCode.QUEUE_FULL, "The global manual queue is full"); return; }
            saved.setDirty(); prefetched = null;
            player.sendSystemMessage(Component.literal("Queued " + append.accepted() + (append.accepted() == 1 ? " track" : " tracks")));
            broadcastState("Queued " + append.accepted() + (append.accepted() == 1 ? " track" : " tracks"));
            ensureCurrent(); prefetchNext();
        }));
    }

    public void control(ServerPlayer player, JammarrPayloads.ControlRequest request) {
        if (!operator(player)) return;
        List<JammarrPayloads.QueueEntry> visible = visibleQueue();
        if (request.index() >= 0 && (!request.expectedKey().isBlank()
                && (request.index() >= visible.size() || !visible.get(request.index()).key().equals(request.expectedKey())))) {
            sendError(player, JammarrPayloads.ErrorCode.INVALID_REQUEST, "The queue changed; refresh and try again"); return;
        }
        switch (request.action()) {
            case PAUSE -> pauseInternal(false);
            case RESUME -> resumeInternal();
            case SKIP -> finishCurrent();
            case CLEAR -> { generated.clear(); prefetched = null; suspendedGeneration = -1; saved.clearAll(); stopAudio(); generationMessage = ""; player.sendSystemMessage(Component.literal("Jammarr playback cleared")); }
            case REMOVE -> {
                int pendingIndex = pendingIndex(request.index());
                if (pendingIndex < 0 || pendingIndex >= saved.queue().size()) { sendError(player, JammarrPayloads.ErrorCode.INVALID_REQUEST, "Only pending manual requests can be removed"); return; }
                saved.queue().remove(pendingIndex); saved.setDirty(); prefetched = null; prefetchNext();
            }
            case MOVE_UP -> movePending(request.index(), -1);
            case MOVE_DOWN -> movePending(request.index(), 1);
        }
        String result = controlMessage(request.action()); player.sendSystemMessage(Component.literal("Jammarr: " + result)); broadcastState(result);
    }

    public void station(ServerPlayer player, JammarrPayloads.StationRequest request) {
        StationControlPolicy.Decision decision = StationControlPolicy.assess(
                player.hasPermissions(JammarrSettings.operatorPermissionLevel()), request.expectedGeneration(), saved.station().generation());
        if (decision == StationControlPolicy.Decision.PERMISSION_DENIED) {
            sendError(player, JammarrPayloads.ErrorCode.PERMISSION_DENIED, "Operator permission is required"); return;
        }
        if (!allow(stationLimits, player, 2)) return;
        if (decision == StationControlPolicy.Decision.STALE_GENERATION) {
            sendError(player, JammarrPayloads.ErrorCode.INVALID_REQUEST, "The active station changed; refresh and try again"); return;
        }
        if (request.action() == JammarrPayloads.StationAction.SET_AUTOPLAY) {
            saved.autoplayEnabled(request.enabled());
            if (!saved.station().active()) saved.station(StationDefinition.none(saved.station().generation() + 1));
            generated.clear(); prefetched = null; suspendedGeneration = -1; generationMessage = request.enabled() ? "Sonic autoplay enabled" : "Sonic autoplay disabled";
            requestGeneration(); broadcastState(generationMessage); return;
        }
        if (request.action() == JammarrPayloads.StationAction.STOP) {
            saved.station(StationDefinition.none(saved.station().generation() + 1)); saved.autoplayEnabled(false); generated.clear(); prefetched = null; suspendedGeneration = -1;
            generationMessage = "Station generation stopped; the current track will finish"; broadcastState(generationMessage); return;
        }
        StationDefinition requested = definition(request.stationType(), request.seeds(), saved.station().generation() + 1);
        try { StationGenerator.validate(requested); }
        catch (PlexException invalid) { sendError(player, JammarrPayloads.ErrorCode.INVALID_REQUEST, invalid.getMessage()); return; }
        if (request.action() == JammarrPayloads.StationAction.PREVIEW_ADVENTURE) {
            previewAdventure(player, requested); return;
        }
        if (request.action() != JammarrPayloads.StationAction.START && request.action() != JammarrPayloads.StationAction.START_NOW) {
            sendError(player, JammarrPayloads.ErrorCode.INVALID_REQUEST, "Unsupported station action"); return;
        }
        if (request.stationType() != JammarrPayloads.StationType.LIBRARY_SHUFFLE && sonicCapability != JammarrPayloads.SonicCapability.READY
                && (!JammarrSettings.stationMetadataFallbackEnabled() || request.stationType() == JammarrPayloads.StationType.SONIC_ADVENTURE)) {
            sendError(player, JammarrPayloads.ErrorCode.INVALID_REQUEST, sonicMessage); return;
        }
        if (StationControlPolicy.replacesCurrentPlayback(request.action())) {
            saved.queue().clear(); saved.current(null, JammarrPayloads.PlaybackOrigin.NONE); stopAudio(); restorePositionMs = 0; restorePaused = false;
        }
        saved.station(requested); generated.clear(); prefetched = null; adventureLoadedGeneration = -1; suspendedGeneration = -1;
        generationMessage = "Starting " + requested.name(); requestGeneration(); ensureCurrent(); broadcastState(generationMessage);
    }

    private void previewAdventure(ServerPlayer player, StationDefinition requested) {
        if (requested.type() != JammarrPayloads.StationType.SONIC_ADVENTURE) { sendError(player, JammarrPayloads.ErrorCode.INVALID_REQUEST, "Adventure preview requires track waypoints"); return; }
        if (sonicCapability != JammarrPayloads.SonicCapability.READY) { sendError(player, JammarrPayloads.ErrorCode.INVALID_REQUEST, sonicMessage); return; }
        CompletableFuture.supplyAsync(() -> {
            try { return stationGenerator.generate(requested, List.of(), sonicCapability, false); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, io).whenComplete((batch, error) -> server.execute(() -> {
            if (error != null) { sendError(player, JammarrPayloads.ErrorCode.INVALID_REQUEST, safe(root(error))); return; }
            List<JammarrPayloads.QueueEntry> path = batch.tracks().stream().limit(100)
                    .map(track -> QueueTrackCodec.networkEntry(track, JammarrPayloads.PlaybackOrigin.ADVENTURE, false)).toList();
            JammarrNetwork.sendToPlayer(player, new JammarrPayloads.AdventurePreview(requested.generation(),
                    path.size() + (path.size() == 1 ? " track" : " tracks") + " in this Sonic Adventure", path));
        }));
    }

    public void chunks(ServerPlayer player, JammarrPayloads.ChunkRequest request) {
        chunkRequests++; ListenerStats stats = listenerStats.computeIfAbsent(player.getUUID(), ignored -> new ListenerStats());
        stats.requests++; stats.lastSeenMs = System.currentTimeMillis();
        if (asset == null || sessionId == null || !sessionId.equals(request.sessionId()) || !allow(chunkLimits, player, 4)) return;
        long now = System.currentTimeMillis(); ChunkTransferPolicy.State previous = transfers.get(player.getUUID());
        if (!ChunkTransferPolicy.acceptsRequest(previous, sessionId, request.requestId(), request.startIndex(), request.count(), asset.chunks().size(), now)) {
            rejectedChunkRequests++; stats.rejected++; return;
        }
        int start = request.startIndex();
        if (!ChunkTransferPolicy.withinPlaybackLead(asset.chunks().get(start).startMs(), positionMs(), TRACK_START_DELAY_MS + ChunkTransferPolicy.MAX_BUFFERED_MS)) {
            rejectedChunkRequests++; stats.rejected++; return;
        }
        stats.accepted++; int end = start + request.count();
        transfers.put(player.getUUID(), ChunkTransferPolicy.begin(sessionId, request.requestId(), start, request.count(), now));
        if (request.requestId() == 1) Jammarr.LOGGER.info("Jammarr sent the initial audio chunk window to {}", player.getUUID());
        for (int i = start; i < end; i++) {
            Mp3FrameIndex.Chunk chunk = asset.chunks().get(i);
            JammarrNetwork.sendToPlayer(player, new JammarrPayloads.AudioChunk(sessionId, request.requestId(), chunk.index(), chunk.startMs(), chunk.sha256(), chunk.data()));
        }
    }

    public void acknowledge(ServerPlayer player, JammarrPayloads.ChunkAcknowledgement acknowledgement) {
        if (!allow(acknowledgementLimits, player, 8)) return;
        ChunkTransferPolicy.acknowledge(transfers.get(player.getUUID()), acknowledgement.sessionId(), acknowledgement.requestId(),
                acknowledgement.receivedThroughIndex(), acknowledgement.bufferedMs(), System.currentTimeMillis()).ifPresent(state -> {
            chunkAcknowledgements++; ListenerStats stats = listenerStats.computeIfAbsent(player.getUUID(), ignored -> new ListenerStats());
            stats.acknowledgements++; stats.lastSeenMs = System.currentTimeMillis(); transfers.put(player.getUUID(), state);
        });
    }

    public void health(ServerPlayer player, JammarrPayloads.AudioHealth health) {
        if (sessionId == null || !sessionId.equals(health.sessionId())) return;
        ListenerStats stats = listenerStats.computeIfAbsent(player.getUUID(), ignored -> new ListenerStats()); stats.state = health.state();
        stats.recoveries = Math.max(0, Math.min(health.recoveryAttempts(), 100)); stats.underruns = Math.max(0, Math.min(health.underruns(), 100_000));
        stats.receivedChunks = Math.max(0, Math.min(health.receivedChunks(), asset == null ? 0 : asset.chunks().size()));
        stats.bufferedMs = Math.max(0, Math.min(health.bufferedMs(), ChunkTransferPolicy.MAX_BUFFERED_MS)); stats.lastSeenMs = System.currentTimeMillis();
    }

    public void playerJoined(ServerPlayer player) { listenerStats.computeIfAbsent(player.getUUID(), ignored -> new ListenerStats()); sendState(player); if (asset != null) sendManifest(player); }
    public void sync(ServerPlayer player) { if (!allow(manifestLimits, player, 2)) return; sendState(player); if (asset != null) sendManifest(player); }
    public void playerLeft(ServerPlayer player) {
        transfers.remove(player.getUUID()); listenerStats.remove(player.getUUID()); browseLimits.remove(player.getUUID()); queueLimits.remove(player.getUUID());
        stationLimits.remove(player.getUUID()); chunkLimits.remove(player.getUUID()); acknowledgementLimits.remove(player.getUUID()); manifestLimits.remove(player.getUUID());
    }

    public long cacheSize() { return cache.size(); }
    public String status() {
        QueueTrack current = saved.current();
        if (current == null) return activeDefinition().active() ? "Waiting for " + activeDefinition().name() : plexHealth == PlexHealth.OFFLINE ? "Queue is empty; Plex is offline" : "Queue is empty";
        String prefix = asset == null ? "Preparing: " : timeline.paused() ? "Paused: " : "Playing: ";
        return prefix + current.title() + " at " + positionMs() / 1000 + "s" + (activeDefinition().active() ? " | " + activeDefinition().name() : "");
    }
    public String stationStatus() {
        StationDefinition active = activeDefinition();
        return active.active() ? active.name() + ", generated=" + generated.size() + ", capability=" + sonicCapability + ", detail=" + stationDetail() : "No active station; autoplay=" + saved.autoplayEnabled();
    }
    public long stationGeneration() { return saved.station().generation(); }
    public String diagnostics() {
        AudioCache.CacheStats stats = cache.stats();
        return "Plex=" + plexHealth + ", sonic=" + sonicCapability + ", lastCheck=" + (lastPlexValidation == 0 ? "never" : Instant.ofEpochMilli(lastPlexValidation))
                + ", position=" + positionMs() + "/" + durationMs() + "ms, cache=" + cache.size() / 1024 / 1024 + " MiB, cacheHits=" + stats.loads()
                + ", cacheMisses=" + stats.misses() + ", cacheInvalid=" + stats.invalidEntries() + ", cacheInstalls=" + stats.installs()
                + ", listeners=" + listenerStats.size() + ", chunkRequests=" + chunkRequests + ", chunkAcks=" + chunkAcknowledgements
                + ", chunkRejected=" + rejectedChunkRequests + ", preparing=" + preparing.get() + ", prefetching=" + prefetching.get()
                + ", generating=" + generating.get() + ", current=" + cacheState(saved.current()) + ", next=" + cacheState(nextTrack())
                + ", station=" + stationStatus() + ", listenerHealth=" + listenerHealth() + ", detail=" + plexDiagnostic;
    }

    private void ensureCurrent() {
        if (saved.current() != null) { prepareCurrent(); return; }
        PlaybackSourcePolicy.Selection selection = PlaybackSourcePolicy.takeNext(saved.queue(), generated, activeDefinition().adventure());
        if (selection.track() == null) { requestGeneration(); return; }
        String sourceName = selection.origin() == JammarrPayloads.PlaybackOrigin.MANUAL ? "Manual request" : activeDefinition().name();
        saved.current(selection.track(), selection.origin(), sourceName); saved.update(0, false); restorePositionMs = 0; restorePaused = false; prepareCurrent(); requestGeneration();
    }

    private void prepareCurrent() {
        QueueTrack track = saved.current();
        if (track == null || asset != null || !preparing.compareAndSet(false, true)) return;
        if (prefetched != null && prefetched.track.key().equals(track.key())) {
            PreparedAsset ready = prefetched; prefetched = null; preparing.set(false); activate(track, ready.asset); return;
        }
        int bitrate = JammarrSettings.audioBitrateKbps(); Set<Path> pinned = pinnedPaths(cache.target(track.key(), bitrate));
        CompletableFuture.supplyAsync(() -> prepare(track, bitrate, pinned), io).whenComplete((prepared, error) -> server.execute(() -> {
            preparing.set(false); if (saved.current() == null || !saved.current().key().equals(track.key())) return;
            if (error != null) {
                if (TrackFailurePolicy.action(error) == TrackFailurePolicy.Action.WAIT_FOR_PLEX) {
                    markPlexFailure(error); preparationRetry.deferUntil(nextPlexValidation); broadcastState(); return;
                }
                Jammarr.LOGGER.error("Skipping unplayable Plex track {}: {}", track.key(), safe(error), error);
                saved.current(null, JammarrPayloads.PlaybackOrigin.NONE); sendErrorToOperators(JammarrPayloads.ErrorCode.TRACK_FAILED, "Skipped an unplayable track"); ensureCurrent(); return;
            }
            plexHealth = PlexHealth.ONLINE; activate(track, prepared.asset);
        }));
    }

    private PreparedAsset prepare(QueueTrack track, int bitrate, Set<Path> pinned) {
        Path target = cache.target(track.key(), bitrate);
        try {
            if (Files.isRegularFile(target)) {
                try { return new PreparedAsset(track, cache.load(target, bitrate)); }
                catch (IOException invalidCache) { Files.deleteIfExists(target); }
            }
            cache.recordMiss(); Exception last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
                try { plex.transcode(track, temporary, bitrate); return new PreparedAsset(track, cache.install(temporary, target, pinned, bitrate)); }
                catch (Exception e) { last = e; Files.deleteIfExists(temporary); if (TrackFailurePolicy.action(e) == TrackFailurePolicy.Action.WAIT_FOR_PLEX) break; if (attempt < 3) Thread.sleep(500L << (attempt - 1)); }
            }
            throw new IOException("Plex preparation failed after three attempts", last);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void activate(QueueTrack track, AudioAsset prepared) {
        asset = prepared; sessionId = UUID.randomUUID(); long duration = asset.durationMs() > 0 ? asset.durationMs() : track.durationMs();
        long restore = Math.min(restorePositionMs, Math.max(0, duration - 1)); boolean initiallyPaused = restorePaused;
        boolean emptyPause = JammarrSettings.pauseWhenEmpty() && server.getPlayerList().getPlayerCount() == 0 && !initiallyPaused;
        timeline.schedule(duration, restore, initiallyPaused || emptyPause, TRACK_START_DELAY_MS); autoPaused = emptyPause;
        restorePositionMs = 0; restorePaused = false; broadcastManifest(); broadcastState(); requestGeneration(); prefetchNext();
    }

    private void requestGeneration() {
        StationDefinition definition = activeDefinition();
        if (!definition.active() || generated.size() >= StationGenerator.LOOKAHEAD_TARGET || plexHealth != PlexHealth.ONLINE
                || suspendedGeneration == definition.generation()
                || definition.adventure() && adventureLoadedGeneration == definition.generation()
                || !generating.compareAndSet(false, true)) return;
        long generation = definition.generation(); List<QueueTrack> history = List.copyOf(saved.history());
        CompletableFuture.supplyAsync(() -> {
            try { return stationGenerator.generate(definition, history, sonicCapability, JammarrSettings.stationMetadataFallbackEnabled()); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, io).whenComplete((batch, error) -> server.execute(() -> {
            generating.set(false); if (activeDefinition().generation() != generation || activeDefinition().type() != definition.type()) return;
            if (error != null) {
                Throwable cause = root(error); generationMessage = safe(cause);
                if (cause instanceof PlexException plexError && (plexError.kind() == PlexException.Kind.OFFLINE || plexError.kind() == PlexException.Kind.AUTHENTICATION)) markPlexFailure(cause);
                else suspendedGeneration = generation;
                sendErrorToOperators(JammarrPayloads.ErrorCode.TRACK_FAILED, generationMessage); broadcastState(); return;
            }
            suspendedGeneration = -1;
            Set<String> existing = new HashSet<>(); generated.forEach(track -> existing.add(track.key()));
            if (saved.current() != null) existing.add(saved.current().key()); saved.queue().forEach(track -> existing.add(track.key()));
            saved.history().forEach(track -> existing.add(track.key()));
            for (QueueTrack track : batch.tracks()) if (existing.add(track.key())) generated.addLast(track);
            if (batch.adventurePath()) adventureLoadedGeneration = generation;
            generationMessage = batch.message(); ensureCurrent(); prefetchNext(); broadcastState();
        }));
    }

    private void prefetchNext() {
        QueueTrack track = nextTrack();
        if (asset == null || track == null || prefetching.get()) return;
        if (prefetched != null && prefetched.track.key().equals(track.key())) return;
        if (!prefetching.compareAndSet(false, true)) return;
        int bitrate = JammarrSettings.audioBitrateKbps(); Set<Path> pinned = pinnedPaths(cache.target(track.key(), bitrate));
        CompletableFuture.supplyAsync(() -> prepare(track, bitrate, pinned), io).whenComplete((prepared, error) -> server.execute(() -> {
            prefetching.set(false); QueueTrack expected = nextTrack();
            if (expected == null || !expected.key().equals(track.key())) { prefetchNext(); return; }
            if (error == null) { prefetched = prepared; cache.trim(pinnedPaths(prepared.asset.path())); }
            else if (TrackFailurePolicy.action(error) == TrackFailurePolicy.Action.WAIT_FOR_PLEX) markPlexFailure(error);
        }));
    }

    private QueueTrack nextTrack() { return !saved.queue().isEmpty() ? saved.queue().get(0) : generated.peekFirst(); }
    private Set<Path> pinnedPaths(Path additional) {
        Set<Path> pins = new HashSet<>(); if (additional != null) pins.add(additional); if (asset != null) pins.add(asset.path()); if (prefetched != null) pins.add(prefetched.asset.path()); return Set.copyOf(pins);
    }

    private void finishCurrent() {
        QueueTrack finished = saved.current(); if (finished != null) saved.remember(finished);
        saved.current(null, JammarrPayloads.PlaybackOrigin.NONE); stopAudio(); restorePositionMs = 0; restorePaused = false; autoPaused = false; saved.update(0, false);
        completeAdventureIfNeeded(); ensureCurrent(); requestGeneration();
    }

    private void completeAdventureIfNeeded() {
        StationDefinition definition = saved.station();
        if (!definition.adventure() || adventureLoadedGeneration != definition.generation() || !generated.isEmpty()) return;
        JammarrPayloads.StationSeed finalWaypoint = definition.seeds().get(definition.seeds().size() - 1);
        saved.station(new StationDefinition(JammarrPayloads.StationType.TRACK_RADIO,
                "Track Radio: " + finalWaypoint.title(), List.of(finalWaypoint), definition.generation() + 1));
        adventureLoadedGeneration = -1; suspendedGeneration = -1; generationMessage = "Sonic Adventure complete; continuing with Track Radio";
    }

    private void pauseInternal(boolean automatic) { if (!timeline.active() || timeline.paused()) return; timeline.pause(); autoPaused = automatic; saved.update(positionMs(), true); broadcastManifest(); }
    private void resumeInternal() { if (!timeline.active() || !timeline.paused()) return; timeline.resume(); autoPaused = false; saved.update(positionMs(), false); broadcastManifest(); }
    private void stopAudio() { asset = null; sessionId = null; timeline.stop(); transfers.clear(); JammarrNetwork.sendToAllPlayers(emptyManifest()); }

    private void movePending(int visibleIndex, int delta) {
        if (!PlaybackSourcePolicy.canMove(visibleQueue(), visibleIndex, delta)) return;
        int index = pendingIndex(visibleIndex), target = index + delta;
        if (index < 0 || target < 0 || index >= saved.queue().size() || target >= saved.queue().size()) return;
        QueueTrack value = saved.queue().remove(index); saved.queue().add(target, value); saved.setDirty(); prefetched = null; prefetchNext();
    }
    private int pendingIndex(int visibleIndex) { return visibleIndex - (saved.current() == null ? 0 : 1); }
    private int manualCount() { return saved.queue().size() + (saved.currentOrigin() == JammarrPayloads.PlaybackOrigin.MANUAL ? 1 : 0); }

    private StationDefinition activeDefinition() {
        if (saved.station().active()) return saved.station();
        if (saved.autoplayEnabled()) return new StationDefinition(JammarrPayloads.StationType.AUTOPLAY, "Sonic Autoplay", List.of(), saved.station().generation());
        return saved.station();
    }
    private static StationDefinition definition(JammarrPayloads.StationType type, List<JammarrPayloads.StationSeed> seeds, long generation) {
        String name = switch (type) {
            case NONE -> ""; case AUTOPLAY -> "Sonic Autoplay"; case LIBRARY_SHUFFLE -> "Library Shuffle";
            case TRACK_RADIO -> "Track Radio: " + seedName(seeds); case ARTIST_RADIO -> "Artist Radio: " + seedName(seeds);
            case ALBUM_RADIO -> "Album Radio: " + seedName(seeds); case SONIC_MIX -> "Sonic Mix"; case SONIC_ADVENTURE -> "Sonic Adventure";
        };
        return new StationDefinition(type, name, seeds, generation);
    }
    private static String seedName(List<JammarrPayloads.StationSeed> seeds) { return seeds.isEmpty() ? "" : seeds.get(0).title(); }

    private long positionMs() { return timeline.positionMs(); }
    private long durationMs() { return timeline.durationMs(); }
    private void broadcastManifest() { for (ServerPlayer player : server.getPlayerList().getPlayers()) sendManifest(player); }
    private void sendManifest(ServerPlayer player) {
        QueueTrack track = saved.current(); if (asset == null || track == null) return;
        long now = System.currentTimeMillis(); long target = timeline.paused() ? timeline.pausedPositionMs() : (now < timeline.startedAtMs() ? 0 : positionMs() + TRACK_START_DELAY_MS);
        int firstChunk = Mp3FrameIndex.chunkAt(asset.chunks(), Math.min(target, Math.max(0, durationMs() - 1)));
        transfers.put(player.getUUID(), ChunkTransferPolicy.initial(sessionId, firstChunk, now));
        JammarrNetwork.sendToPlayer(player, new JammarrPayloads.AudioManifest(sessionId, track.title(), track.artist(), asset.chunks().size(), firstChunk,
                durationMs(), timeline.startedAtMs(), timeline.paused(), timeline.pausedPositionMs(), asset.sha256()));
    }
    private JammarrPayloads.AudioManifest emptyManifest() { return new JammarrPayloads.AudioManifest(new UUID(0, 0), "", "", 0, 0, 0, 0, true, 0, ""); }
    private void broadcastState() { broadcastState(""); }
    private void broadcastState(String notice) { for (ServerPlayer player : server.getPlayerList().getPlayers()) sendState(player, notice); }
    private void sendState(ServerPlayer player) { sendState(player, ""); }
    private void sendState(ServerPlayer player, String notice) {
        QueueTrack current = saved.current(); JammarrPayloads.PlaybackStatus status = playbackStatus();
        String detail = notice.isBlank() ? (status == JammarrPayloads.PlaybackStatus.PLEX_OFFLINE ? "Plex is currently unavailable" : playbackDetail()) : notice;
        if (player.hasPermissions(JammarrSettings.operatorPermissionLevel())) detail += " | " + plexDiagnostic;
        JammarrNetwork.sendToPlayer(player, new JammarrPayloads.PlaybackState(status, detail, current == null ? "" : current.title(), current == null ? "" : current.artist(),
                timeline.paused(), positionMs(), durationMs(), System.currentTimeMillis(), player.hasPermissions(JammarrSettings.operatorPermissionLevel()),
                saved.currentOrigin(), saved.currentSourceName(), visibleQueue()));
        StationDefinition active = activeDefinition();
        JammarrNetwork.sendToPlayer(player, new JammarrPayloads.StationState(active.type(), active.active(), saved.autoplayEnabled(), active.generation(),
                sonicCapability, sonicMessage, active.name(), active.seeds(), generated.stream().limit(GENERATED_PREVIEW_SIZE)
                .map(track -> QueueTrackCodec.networkEntry(track, active.adventure() ? JammarrPayloads.PlaybackOrigin.ADVENTURE : JammarrPayloads.PlaybackOrigin.STATION, false)).toList()));
    }

    private List<JammarrPayloads.QueueEntry> visibleQueue() {
        List<JammarrPayloads.QueueEntry> result = new ArrayList<>();
        if (saved.current() != null) result.add(QueueTrackCodec.networkEntry(saved.current(), saved.currentOrigin(), false));
        saved.queue().forEach(track -> result.add(QueueTrackCodec.networkEntry(track, JammarrPayloads.PlaybackOrigin.MANUAL, true)));
        JammarrPayloads.PlaybackOrigin generatedOrigin = activeDefinition().adventure() ? JammarrPayloads.PlaybackOrigin.ADVENTURE : JammarrPayloads.PlaybackOrigin.STATION;
        generated.stream().limit(GENERATED_PREVIEW_SIZE).forEach(track -> result.add(QueueTrackCodec.networkEntry(track, generatedOrigin, false)));
        return List.copyOf(result);
    }

    private String playbackDetail() {
        if (saved.current() == null) return activeDefinition().active() ? stationDetail() : "";
        if (asset == null) return "Preparing audio from Plex";
        if (timeline.paused()) return "Playback paused";
        if (!saved.queue().isEmpty()) return prefetched != null ? "Manual request is next and prefetched" : "Preparing next manual request";
        if (activeDefinition().active()) return stationDetail();
        return "Audio ready";
    }
    private String stationDetail() { return generationMessage.isBlank() ? sonicMessage : generationMessage; }
    private JammarrPayloads.PlaybackStatus playbackStatus() {
        if (asset != null) return timeline.paused() ? JammarrPayloads.PlaybackStatus.PAUSED : JammarrPayloads.PlaybackStatus.PLAYING;
        if (plexHealth == PlexHealth.OFFLINE) return JammarrPayloads.PlaybackStatus.PLEX_OFFLINE;
        if (preparing.get() || generating.get() || saved.current() != null || !saved.queue().isEmpty()) return JammarrPayloads.PlaybackStatus.PREPARING;
        return JammarrPayloads.PlaybackStatus.IDLE;
    }

    private boolean requirePlex(ServerPlayer player) {
        if (plexHealth == PlexHealth.ONLINE) return true;
        sendError(player, JammarrPayloads.ErrorCode.PLEX_OFFLINE, plexHealth == PlexHealth.VALIDATING ? "Plex validation is still in progress" : "Plex is offline; ask an operator to run /jammarr diagnostics"); return false;
    }
    private boolean operator(ServerPlayer player) {
        if (player.hasPermissions(JammarrSettings.operatorPermissionLevel())) return true;
        sendError(player, JammarrPayloads.ErrorCode.PERMISSION_DENIED, "Operator permission is required"); return false;
    }
    private boolean allow(SlidingWindowRateLimiter limiter, ServerPlayer player, int perSecond) {
        long now = System.currentTimeMillis(); if (limiter.allow(player.getUUID(), perSecond, now)) return true;
        if (limiter.count(player.getUUID(), now) == perSecond + 1) sendError(player, JammarrPayloads.ErrorCode.RATE_LIMITED, "Jammarr request rate limit exceeded"); return false;
    }

    private void markPlexFailure(Throwable error) {
        plexHealth = PlexHealth.OFFLINE; sonicCapability = JammarrPayloads.SonicCapability.PLEX_OFFLINE;
        plexDiagnostic = actionable(error); sonicMessage = "Plex is offline; station generation will resume after reconnection";
        nextPlexValidation = System.currentTimeMillis() + PLEX_REVALIDATE_MS;
    }
    private String actionable(Throwable error) {
        Throwable cause = root(error);
        if (cause instanceof PlexException plexError) return switch (plexError.kind()) {
            case AUTHENTICATION -> "Plex rejected the configured token; update JAMMARR_PLEX_TOKEN or jammarr-server.toml";
            case CONFIGURATION -> safe(cause); case NOT_FOUND -> "A Plex item no longer exists";
            case OFFLINE -> "Plex is unreachable or timed out"; case INVALID_RESPONSE -> "Plex returned invalid metadata or audio";
            case TRANSCODE -> "Plex could not prepare the requested MP3 rendition";
        };
        return "Plex operation failed: " + safe(cause);
    }
    private void sendErrorToOperators(JammarrPayloads.ErrorCode code, String message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) if (player.hasPermissions(JammarrSettings.operatorPermissionLevel())) sendError(player, code, message);
    }
    private String cacheState(QueueTrack track) {
        if (track == null) return "none"; Path path = cache.target(track.key(), JammarrSettings.audioBitrateKbps()); boolean cached = Files.isRegularFile(path);
        if (saved.current() != null && saved.current().key().equals(track.key()) && asset != null) return "active," + (cached ? "cached" : "memory");
        return prefetched != null && prefetched.track.key().equals(track.key()) ? "prefetched,cached" : cached ? "cached" : "missing";
    }
    private String listenerHealth() {
        if (listenerStats.isEmpty()) return "none"; StringBuilder value = new StringBuilder();
        listenerStats.forEach((uuid, stats) -> { if (value.length() > 0) value.append(';'); value.append(uuid.toString(), 0, 8).append(':').append(stats.state)
                .append("/requests=").append(stats.requests).append("/acks=").append(stats.acknowledgements).append("/rejected=").append(stats.rejected)
                .append("/recovery=").append(stats.recoveries).append("/underrun=").append(stats.underruns).append("/buffer=").append(stats.bufferedMs).append("ms"); });
        return value.toString();
    }
    private static String controlMessage(JammarrPayloads.ControlAction action) { return switch (action) {
        case PAUSE -> "Playback paused"; case RESUME -> "Playback resumed"; case SKIP -> "Track skipped"; case REMOVE -> "Manual request removed";
        case MOVE_UP -> "Manual request moved up"; case MOVE_DOWN -> "Manual request moved down"; case CLEAR -> "Playback cleared";
    }; }
    private static void sendError(ServerPlayer player, JammarrPayloads.ErrorCode code, String message) { JammarrNetwork.sendToPlayer(player, new JammarrPayloads.ErrorMessage(code, message)); }
    private String safe(Throwable error) { return SecretRedactor.message(error, JammarrSettings.plexToken()); }
    private static Throwable root(Throwable error) { Throwable value = error; while (value.getCause() != null) value = value.getCause(); return value; }

    private static ExecutorService createIoExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(3, runnable -> new Thread(runnable, "jammarr-io-" + sequence.getAndIncrement()));
    }

    private record PreparedAsset(QueueTrack track, AudioAsset asset) {}
    private static final class ListenerStats {
        private long requests, accepted, rejected, acknowledgements, lastSeenMs; private String state = "UNKNOWN";
        private int recoveries, underruns, receivedChunks; private long bufferedMs;
    }
    @Override public void close() { saved.update(positionMs(), timeline.paused()); stopAudio(); io.shutdownNow(); }
}
