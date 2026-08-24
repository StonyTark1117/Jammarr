package stonytark.jammarr.server;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.platform.CoreLogger;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.core.protocol.TransportPackets;
import stonytark.jammarr.core.server.AudioAsset;
import stonytark.jammarr.core.server.AudioCache;
import stonytark.jammarr.core.server.ChunkTransferPolicy;
import stonytark.jammarr.core.server.EmptyServerPausePolicy;
import stonytark.jammarr.core.server.Mp3FrameIndex;
import stonytark.jammarr.core.server.PlaybackTimeline;
import stonytark.jammarr.core.server.PlexException;
import stonytark.jammarr.core.server.PlexService;
import stonytark.jammarr.core.server.QueueOperations;
import stonytark.jammarr.core.server.RestartPolicy;
import stonytark.jammarr.core.server.RetryGate;
import stonytark.jammarr.core.server.SecretRedactor;
import stonytark.jammarr.core.server.SlidingWindowRateLimiter;
import stonytark.jammarr.core.server.StationGenerator;
import stonytark.jammarr.core.server.TrackFailurePolicy;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.network.LegacyPacketTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Server-authoritative Forge 1.7.10 playback coordinator. */
public final class LegacyGlobalPlayer implements AutoCloseable, LegacyNetwork.ServerListener {
    private static final int PAGE_SIZE = 20;
    private static final int GENERATED_PREVIEW_SIZE = 3;
    private static final long TRACK_START_DELAY_MS = 5_000L;
    private static final long PLEX_REVALIDATE_MS = 30_000L;

    private enum PlexHealth { VALIDATING, ONLINE, OFFLINE }

    private final MinecraftServer server;
    private final PlexService plex;
    private final StationGenerator stationGenerator;
    private final AudioCache cache;
    private final ExecutorService io = createIoExecutor();
    private final Queue<Runnable> mainThreadActions = new ConcurrentLinkedQueue<Runnable>();
    private final LegacySavedData saved;
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
    private final Map<UUID, ChunkTransferPolicy.State> transfers = new HashMap<UUID, ChunkTransferPolicy.State>();
    private final Map<UUID, ListenerStats> listenerStats = new HashMap<UUID, ListenerStats>();
    private final RetryGate preparationRetry = new RetryGate();
    private final Deque<QueueTrack> generated = new ArrayDeque<QueueTrack>();

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
    private long adventureLoadedGeneration = -1L;
    private long suspendedGeneration = -1L;
    private volatile PlexHealth plexHealth = PlexHealth.VALIDATING;
    private volatile StationModels.SonicCapability sonicCapability = StationModels.SonicCapability.CHECKING;
    private volatile String sonicMessage = "Plex sonic capability validation is pending";
    private volatile String generationMessage = "";
    private volatile String plexDiagnostic = "Plex validation is pending";

    public LegacyGlobalPlayer(MinecraftServer server) throws IOException {
        this(server, new PlexService());
    }

    LegacyGlobalPlayer(MinecraftServer server, PlexService plex) throws IOException {
        this.server = server;
        this.plex = plex;
        this.stationGenerator = new StationGenerator(plex);
        this.cache = new AudioCache(server.getFile("jammarr-cache").toPath(),
                JammarrSettings.cacheSizeMiB() * 1024L * 1024L, new CoreLogger() {
            @Override public void info(String message) { Jammarr.LOGGER.info(message); }
            @Override public void warn(String message, Throwable error) { Jammarr.LOGGER.warn(message, error); }
        });
        this.saved = LegacySavedData.get(server);
        RestartPolicy.Restoration restoration = RestartPolicy.restore(
                JammarrSettings.restartMode(), saved.checkpointMs(), saved.paused());
        if (restoration.clearQueue()) saved.clearAll();
        restorePositionMs = restoration.positionMs();
        restorePaused = restoration.paused();
        saved.update(restorePositionMs, restorePaused);
        LegacyNetwork.setServerListener(this);
        validatePlex();
    }

    @Override
    public void accept(EntityPlayerMP player, LegacyPacketTypes.Type<?> type, Object message) {
        if (type == LegacyPacketTypes.CLIENT_HELLO) playerJoined(player);
        else if (type == LegacyPacketTypes.BROWSE_REQUEST) browse(player, (ControlPackets.BrowseRequest) message);
        else if (type == LegacyPacketTypes.QUEUE_REQUEST) queue(player, (ControlPackets.QueueRequest) message);
        else if (type == LegacyPacketTypes.CONTROL_REQUEST) control(player, (ControlPackets.ControlRequest) message);
        else if (type == LegacyPacketTypes.STATION_REQUEST) station(player, (ControlPackets.StationRequest) message);
        else if (type == LegacyPacketTypes.CHUNK_REQUEST) chunks(player, (TransportPackets.ChunkRequest) message);
        else if (type == LegacyPacketTypes.CHUNK_ACKNOWLEDGEMENT) acknowledge(player, (TransportPackets.ChunkAcknowledgement) message);
        else if (type == LegacyPacketTypes.AUDIO_HEALTH) health(player, (StatePackets.AudioHealth) message);
        else if (type == LegacyPacketTypes.MANIFEST_REQUEST) sync(player);
    }

    public void validatePlex() {
        if (!validating.compareAndSet(false, true)) return;
        plexHealth = PlexHealth.VALIDATING;
        sonicCapability = StationModels.SonicCapability.CHECKING;
        sonicMessage = "Checking Plex Pass and sonic analysis";
        lastPlexValidation = System.currentTimeMillis();
        broadcastState();
        CompletableFuture.supplyAsync(new Supplier<PlexService.SonicStatus>() {
            @Override public PlexService.SonicStatus get() {
                try { plex.validate(); return plex.sonicStatus(); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        }, io).whenComplete((status, error) -> schedule(new Runnable() {
            @Override public void run() {
                validating.set(false);
                nextPlexValidation = System.currentTimeMillis() + PLEX_REVALIDATE_MS;
                if (error == null) {
                    plexHealth = PlexHealth.ONLINE;
                    sonicCapability = status.capability();
                    sonicMessage = status.message();
                    plexDiagnostic = "Connected to the configured Plex music library";
                    preparationRetry.clear();
                    suspendedGeneration = -1L;
                    Jammarr.LOGGER.info("Jammarr connected to Plex; sonic capability is {}", sonicCapability);
                    ensureCurrent();
                    requestGeneration();
                } else {
                    markPlexFailure(error);
                    Jammarr.LOGGER.error("Jammarr Plex validation failed: {}", safe(error));
                }
                broadcastState();
            }
        }));
    }

    public void tick() {
        Runnable action;
        while ((action = mainThreadActions.poll()) != null) action.run();
        long now = System.currentTimeMillis();
        boolean empty = playerCount() == 0;
        if (EmptyServerPausePolicy.shouldPause(JammarrSettings.pauseWhenEmpty(), empty,
                timeline.active(), timeline.paused())) pauseInternal(true);
        else if (EmptyServerPausePolicy.shouldResume(autoPaused, empty)) resumeInternal();
        if (asset == null && saved.current() != null && preparationRetry.ready(now)) prepareCurrent();
        if (asset == null && saved.current() == null) ensureCurrent();
        if (timeline.ended()) finishCurrent();
        if (generated.size() < GENERATED_PREVIEW_SIZE) requestGeneration();
        if (plexHealth == PlexHealth.OFFLINE && now >= nextPlexValidation) validatePlex();
        if (now - lastCheckpoint >= 5_000L) {
            saved.update(positionMs(), timeline.paused());
            lastCheckpoint = now;
        }
        if (now - lastStateBroadcast >= 2_000L) {
            broadcastState();
            lastStateBroadcast = now;
        }
        Iterator<Map.Entry<UUID, ChunkTransferPolicy.State>> iterator = transfers.entrySet().iterator();
        while (iterator.hasNext()) if (now - iterator.next().getValue().lastSeenMs() > 30_000L) iterator.remove();
    }

    public void browse(final EntityPlayerMP player, final ControlPackets.BrowseRequest request) {
        if (!allow(browseLimits, player, 8)) return;
        final int page = Math.max(0, Math.min(request.page(), 10_000));
        if (request.kind() == ControlPackets.BrowseKind.QUEUE) {
            List<StatePackets.QueueEntry> all = visibleQueue();
            int first = Math.min(all.size(), page * PAGE_SIZE);
            int last = Math.min(all.size(), first + PAGE_SIZE + 1);
            List<StationModels.MediaItem> values = new ArrayList<StationModels.MediaItem>();
            for (StatePackets.QueueEntry entry : all.subList(first, last)) {
                values.add(new StationModels.MediaItem(StationModels.ItemKind.TRACK, entry.key(), entry.title(),
                        entry.artist(), entry.durationMs()));
            }
            boolean more = values.size() > PAGE_SIZE;
            if (more) values = new ArrayList<StationModels.MediaItem>(values.subList(0, PAGE_SIZE));
            send(player, LegacyPacketTypes.BROWSE_RESULTS,
                    new ControlPackets.BrowseResults(request.kind(), "", page, more, values));
            return;
        }
        if (!requirePlex(player)) return;
        final String query = request.query().trim();
        if (request.kind() == ControlPackets.BrowseKind.SEARCH && query.length() < 2) {
            send(player, LegacyPacketTypes.BROWSE_RESULTS, new ControlPackets.BrowseResults(
                    request.kind(), query, page, false, Collections.<StationModels.MediaItem>emptyList()));
            return;
        }
        CompletableFuture.supplyAsync(new Supplier<PlexService.Page>() {
            @Override public PlexService.Page get() {
                try { return plex.browse(request.kind(), query, page, PAGE_SIZE); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        }, io).whenComplete((result, error) -> schedule(new Runnable() {
            @Override public void run() {
                if (error != null) {
                    markPlexFailure(error);
                    sendError(player, StatePackets.ErrorCode.PLEX_OFFLINE, "Plex browsing is currently unavailable");
                } else {
                    send(player, LegacyPacketTypes.BROWSE_RESULTS,
                            new ControlPackets.BrowseResults(request.kind(), query, page, result.hasMore(), result.items()));
                }
            }
        }));
    }

    public void queue(final EntityPlayerMP player, final ControlPackets.QueueRequest request) {
        if (!allow(queueLimits, player, 4) || !requirePlex(player)) return;
        final int available = JammarrSettings.queueLimit() - manualCount();
        if (available <= 0) {
            sendError(player, StatePackets.ErrorCode.QUEUE_FULL, "The global manual queue is full");
            return;
        }
        CompletableFuture.supplyAsync(new Supplier<List<QueueTrack>>() {
            @Override public List<QueueTrack> get() {
                try { return plex.expand(request.kind(), request.key(), available); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        }, io).whenComplete((tracks, error) -> schedule(new Runnable() {
            @Override public void run() {
                if (error != null) {
                    markPlexFailure(error);
                    sendError(player, StatePackets.ErrorCode.PLEX_OFFLINE, "Unable to queue that Plex item");
                    return;
                }
                if (tracks.isEmpty()) {
                    sendError(player, StatePackets.ErrorCode.INVALID_REQUEST, "That Plex item contains no playable tracks");
                    return;
                }
                int pendingLimit = JammarrSettings.queueLimit()
                        - (saved.currentOrigin() == StatePackets.PlaybackOrigin.MANUAL ? 1 : 0);
                QueueOperations.AppendResult append = QueueOperations.append(saved.queue(), tracks, pendingLimit);
                if (append.accepted() == 0) {
                    sendError(player, StatePackets.ErrorCode.QUEUE_FULL, "The global manual queue is full");
                    return;
                }
                saved.markDirty();
                prefetched = null;
                chat(player, "Queued " + append.accepted() + (append.accepted() == 1 ? " track" : " tracks"));
                broadcastState("Queued " + append.accepted() + (append.accepted() == 1 ? " track" : " tracks"));
                ensureCurrent();
                prefetchNext();
            }
        }));
    }

    public void control(EntityPlayerMP player, ControlPackets.ControlRequest request) {
        if (!operator(player)) return;
        List<StatePackets.QueueEntry> visible = visibleQueue();
        if (request.index() >= 0 && !blank(request.expectedKey())
                && (request.index() >= visible.size()
                || !visible.get(request.index()).key().equals(request.expectedKey()))) {
            sendError(player, StatePackets.ErrorCode.INVALID_REQUEST, "The queue changed; refresh and try again");
            return;
        }
        switch (request.action()) {
            case PAUSE: pauseInternal(false); break;
            case RESUME: resumeInternal(); break;
            case SKIP: finishCurrent(); break;
            case CLEAR:
                generated.clear(); prefetched = null; suspendedGeneration = -1L;
                saved.clearAll(); stopAudio(); generationMessage = ""; chat(player, "Jammarr playback cleared");
                break;
            case REMOVE:
                int pendingIndex = pendingIndex(request.index());
                if (pendingIndex < 0 || pendingIndex >= saved.queue().size()) {
                    sendError(player, StatePackets.ErrorCode.INVALID_REQUEST,
                            "Only pending manual requests can be removed");
                    return;
                }
                saved.queue().remove(pendingIndex); saved.markDirty(); prefetched = null; prefetchNext();
                break;
            case MOVE_UP: movePending(request.index(), -1); break;
            case MOVE_DOWN: movePending(request.index(), 1); break;
            default: return;
        }
        String result = controlMessage(request.action());
        chat(player, "Jammarr: " + result);
        broadcastState(result);
    }

    public void station(final EntityPlayerMP player, final ControlPackets.StationRequest request) {
        if (!operator(player)) return;
        if (!allow(stationLimits, player, 2)) return;
        if (request.expectedGeneration() != saved.station().generation()) {
            sendError(player, StatePackets.ErrorCode.INVALID_REQUEST,
                    "The active station changed; refresh and try again");
            return;
        }
        if (request.action() == ControlPackets.StationAction.SET_AUTOPLAY) {
            saved.autoplayEnabled(request.enabled());
            if (!saved.station().active()) saved.station(StationModels.StationDefinition.none(saved.station().generation() + 1L));
            generated.clear(); prefetched = null; suspendedGeneration = -1L;
            generationMessage = request.enabled() ? "Sonic autoplay enabled" : "Sonic autoplay disabled";
            requestGeneration(); broadcastState(generationMessage); return;
        }
        if (request.action() == ControlPackets.StationAction.STOP) {
            saved.station(StationModels.StationDefinition.none(saved.station().generation() + 1L));
            saved.autoplayEnabled(false); generated.clear(); prefetched = null; suspendedGeneration = -1L;
            generationMessage = "Station generation stopped; the current track will finish";
            broadcastState(generationMessage); return;
        }
        final StationModels.StationDefinition requested = definition(
                request.stationType(), request.seeds(), saved.station().generation() + 1L);
        try { StationGenerator.validate(requested); }
        catch (PlexException invalid) {
            sendError(player, StatePackets.ErrorCode.INVALID_REQUEST, invalid.getMessage()); return;
        }
        if (request.action() == ControlPackets.StationAction.PREVIEW_ADVENTURE) {
            previewAdventure(player, requested); return;
        }
        if (request.action() != ControlPackets.StationAction.START
                && request.action() != ControlPackets.StationAction.START_NOW) {
            sendError(player, StatePackets.ErrorCode.INVALID_REQUEST, "Unsupported station action"); return;
        }
        if (request.stationType() != StationModels.StationType.LIBRARY_SHUFFLE
                && sonicCapability != StationModels.SonicCapability.READY
                && (!JammarrSettings.stationMetadataFallbackEnabled()
                || request.stationType() == StationModels.StationType.SONIC_ADVENTURE)) {
            sendError(player, StatePackets.ErrorCode.INVALID_REQUEST, sonicMessage); return;
        }
        if (request.action() == ControlPackets.StationAction.START_NOW) {
            saved.queue().clear();
            saved.current(null, StatePackets.PlaybackOrigin.NONE, "");
            stopAudio(); restorePositionMs = 0L; restorePaused = false;
        }
        saved.station(requested); generated.clear(); prefetched = null;
        adventureLoadedGeneration = -1L; suspendedGeneration = -1L;
        generationMessage = "Starting " + requested.name();
        requestGeneration(); ensureCurrent(); broadcastState(generationMessage);
    }

    private void previewAdventure(final EntityPlayerMP player,
                                  final StationModels.StationDefinition requested) {
        if (requested.type() != StationModels.StationType.SONIC_ADVENTURE) {
            sendError(player, StatePackets.ErrorCode.INVALID_REQUEST,
                    "Adventure preview requires track waypoints"); return;
        }
        if (sonicCapability != StationModels.SonicCapability.READY) {
            sendError(player, StatePackets.ErrorCode.INVALID_REQUEST, sonicMessage); return;
        }
        CompletableFuture.supplyAsync(new Supplier<StationGenerator.GeneratedBatch>() {
            @Override public StationGenerator.GeneratedBatch get() {
                try { return stationGenerator.generate(requested, Collections.<QueueTrack>emptyList(), sonicCapability, false); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        }, io).whenComplete((batch, error) -> schedule(new Runnable() {
            @Override public void run() {
                if (error != null) {
                    sendError(player, StatePackets.ErrorCode.INVALID_REQUEST, safe(root(error))); return;
                }
                List<StatePackets.QueueEntry> path = new ArrayList<StatePackets.QueueEntry>();
                for (QueueTrack track : batch.tracks()) {
                    if (path.size() >= 100) break;
                    path.add(networkEntry(track, StatePackets.PlaybackOrigin.ADVENTURE, false));
                }
                send(player, LegacyPacketTypes.ADVENTURE_PREVIEW,
                        new StatePackets.AdventurePreview(requested.generation(),
                                path.size() + (path.size() == 1 ? " track" : " tracks")
                                        + " in this Sonic Adventure", path));
            }
        }));
    }

    public void chunks(EntityPlayerMP player, TransportPackets.ChunkRequest request) {
        chunkRequests++;
        ListenerStats stats = stats(player);
        stats.requests++; stats.lastSeenMs = System.currentTimeMillis();
        if (asset == null || sessionId == null || !sessionId.equals(request.sessionId())
                || !allow(chunkLimits, player, 4)) return;
        long now = System.currentTimeMillis();
        ChunkTransferPolicy.State previous = transfers.get(player.getUniqueID());
        if (!ChunkTransferPolicy.acceptsRequest(previous, sessionId, request.requestId(),
                request.startIndex(), request.count(), asset.chunks().size(), now)) {
            rejectedChunkRequests++; stats.rejected++; return;
        }
        int start = request.startIndex();
        if (!ChunkTransferPolicy.withinPlaybackLead(asset.chunks().get(start).startMs(), positionMs(),
                TRACK_START_DELAY_MS + ChunkTransferPolicy.MAX_BUFFERED_MS)) {
            rejectedChunkRequests++; stats.rejected++; return;
        }
        stats.accepted++;
        int end = start + request.count();
        transfers.put(player.getUniqueID(), ChunkTransferPolicy.begin(
                sessionId, request.requestId(), start, request.count(), now));
        if (request.requestId() == 1L) Jammarr.LOGGER.info(
                "Jammarr sent the initial audio chunk window to {}", player.getUniqueID());
        for (int index = start; index < end; index++) {
            Mp3FrameIndex.Chunk chunk = asset.chunks().get(index);
            send(player, LegacyPacketTypes.AUDIO_CHUNK, new TransportPackets.AudioChunk(sessionId,
                    request.requestId(), chunk.index(), chunk.startMs(), chunk.sha256(), chunk.data()));
        }
    }

    public void acknowledge(EntityPlayerMP player, TransportPackets.ChunkAcknowledgement acknowledgement) {
        if (!allow(acknowledgementLimits, player, 8)) return;
        Optional<ChunkTransferPolicy.State> accepted = ChunkTransferPolicy.acknowledge(
                transfers.get(player.getUniqueID()), acknowledgement.sessionId(), acknowledgement.requestId(),
                acknowledgement.receivedThroughIndex(), acknowledgement.bufferedMs(), System.currentTimeMillis());
        if (accepted.isPresent()) {
            chunkAcknowledgements++;
            ListenerStats stats = stats(player);
            stats.acknowledgements++; stats.lastSeenMs = System.currentTimeMillis();
            transfers.put(player.getUniqueID(), accepted.get());
        }
    }

    public void health(EntityPlayerMP player, StatePackets.AudioHealth health) {
        if (sessionId == null || !sessionId.equals(health.sessionId())) return;
        ListenerStats stats = stats(player);
        stats.state = health.state();
        stats.recoveries = Math.max(0, Math.min(health.recoveryAttempts(), 100));
        stats.underruns = Math.max(0, Math.min(health.underruns(), 100_000));
        stats.receivedChunks = Math.max(0, Math.min(health.receivedChunks(),
                asset == null ? 0 : asset.chunks().size()));
        stats.bufferedMs = Math.max(0L, Math.min(health.bufferedMs(), ChunkTransferPolicy.MAX_BUFFERED_MS));
        stats.lastSeenMs = System.currentTimeMillis();
    }

    public void playerJoined(EntityPlayerMP player) {
        stats(player); sendState(player); if (asset != null) sendManifest(player);
    }

    public void sync(EntityPlayerMP player) {
        if (!allow(manifestLimits, player, 2)) return;
        sendState(player); if (asset != null) sendManifest(player);
    }

    public void playerLeft(EntityPlayerMP player) {
        UUID id = player.getUniqueID();
        transfers.remove(id); listenerStats.remove(id); browseLimits.remove(id); queueLimits.remove(id);
        stationLimits.remove(id); chunkLimits.remove(id); acknowledgementLimits.remove(id); manifestLimits.remove(id);
    }

    public long cacheSize() { return cache.size(); }

    public String status() {
        QueueTrack current = saved.current();
        if (current == null) {
            if (activeDefinition().active()) return "Waiting for " + activeDefinition().name();
            return plexHealth == PlexHealth.OFFLINE ? "Queue is empty; Plex is offline" : "Queue is empty";
        }
        String prefix = asset == null ? "Preparing: " : timeline.paused() ? "Paused: " : "Playing: ";
        return prefix + current.title() + " at " + positionMs() / 1_000L + "s"
                + (activeDefinition().active() ? " | " + activeDefinition().name() : "");
    }

    public String stationStatus() {
        StationModels.StationDefinition active = activeDefinition();
        return active.active() ? active.name() + ", generated=" + generated.size()
                + ", capability=" + sonicCapability + ", detail=" + stationDetail()
                : "No active station; autoplay=" + saved.autoplayEnabled();
    }

    public long stationGeneration() { return saved.station().generation(); }

    public String diagnostics() {
        AudioCache.CacheStats stats = cache.stats();
        return "Plex=" + plexHealth + ", sonic=" + sonicCapability + ", lastCheck="
                + (lastPlexValidation == 0L ? "never" : String.valueOf(lastPlexValidation))
                + ", position=" + positionMs() + "/" + durationMs() + "ms, cache="
                + cache.size() / 1024L / 1024L + " MiB, cacheHits=" + stats.loads()
                + ", cacheMisses=" + stats.misses() + ", cacheInvalid=" + stats.invalidEntries()
                + ", cacheInstalls=" + stats.installs() + ", listeners=" + listenerStats.size()
                + ", chunkRequests=" + chunkRequests + ", chunkAcks=" + chunkAcknowledgements
                + ", chunkRejected=" + rejectedChunkRequests + ", preparing=" + preparing.get()
                + ", prefetching=" + prefetching.get() + ", generating=" + generating.get()
                + ", current=" + cacheState(saved.current()) + ", next=" + cacheState(nextTrack())
                + ", station=" + stationStatus() + ", listenerHealth=" + listenerHealth()
                + ", detail=" + plexDiagnostic;
    }

    private void ensureCurrent() {
        if (saved.current() != null) { prepareCurrent(); return; }
        QueueTrack track;
        StatePackets.PlaybackOrigin origin;
        if (!saved.queue().isEmpty()) {
            track = saved.queue().remove(0);
            origin = StatePackets.PlaybackOrigin.MANUAL;
        } else {
            track = generated.pollFirst();
            origin = track == null ? StatePackets.PlaybackOrigin.NONE
                    : activeDefinition().adventure() ? StatePackets.PlaybackOrigin.ADVENTURE
                    : StatePackets.PlaybackOrigin.STATION;
        }
        if (track == null) { requestGeneration(); return; }
        String sourceName = origin == StatePackets.PlaybackOrigin.MANUAL
                ? "Manual request" : activeDefinition().name();
        saved.current(track, origin, sourceName);
        saved.update(0L, false); restorePositionMs = 0L; restorePaused = false;
        prepareCurrent(); requestGeneration();
    }

    private void prepareCurrent() {
        final QueueTrack track = saved.current();
        if (track == null || asset != null || !preparing.compareAndSet(false, true)) return;
        if (prefetched != null && prefetched.track.key().equals(track.key())) {
            PreparedAsset ready = prefetched; prefetched = null; preparing.set(false);
            activate(track, ready.asset); return;
        }
        final int bitrate = JammarrSettings.audioBitrateKbps();
        final Set<Path> pinned = pinnedPaths(cache.target(track.key(), bitrate));
        CompletableFuture.supplyAsync(new Supplier<PreparedAsset>() {
            @Override public PreparedAsset get() { return prepare(track, bitrate, pinned); }
        }, io).whenComplete((prepared, error) -> schedule(new Runnable() {
            @Override public void run() {
                preparing.set(false);
                if (saved.current() == null || !saved.current().key().equals(track.key())) return;
                if (error != null) {
                    if (TrackFailurePolicy.action(error) == TrackFailurePolicy.Action.WAIT_FOR_PLEX) {
                        markPlexFailure(error); preparationRetry.deferUntil(nextPlexValidation); broadcastState(); return;
                    }
                    Jammarr.LOGGER.error("Skipping unplayable Plex track {}: {}", track.key(), safe(error), error);
                    saved.current(null, StatePackets.PlaybackOrigin.NONE, "");
                    sendErrorToOperators(StatePackets.ErrorCode.TRACK_FAILED, "Skipped an unplayable track");
                    ensureCurrent(); return;
                }
                plexHealth = PlexHealth.ONLINE;
                activate(track, prepared.asset);
            }
        }));
    }

    private PreparedAsset prepare(QueueTrack track, int bitrate, Set<Path> pinned) {
        Path target = cache.target(track.key(), bitrate);
        try {
            if (Files.isRegularFile(target)) {
                try { return new PreparedAsset(track, cache.load(target, bitrate)); }
                catch (IOException invalidCache) { Files.deleteIfExists(target); }
            }
            cache.recordMiss();
            Exception last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
                try {
                    plex.transcode(track, temporary, bitrate);
                    return new PreparedAsset(track, cache.install(temporary, target, pinned, bitrate));
                } catch (Exception error) {
                    last = error; Files.deleteIfExists(temporary);
                    if (TrackFailurePolicy.action(error) == TrackFailurePolicy.Action.WAIT_FOR_PLEX) break;
                    if (attempt < 3) Thread.sleep(500L << (attempt - 1));
                }
            }
            throw new IOException("Plex preparation failed after three attempts", last);
        } catch (Exception error) { throw new RuntimeException(error); }
    }

    private void activate(QueueTrack track, AudioAsset prepared) {
        asset = prepared; sessionId = UUID.randomUUID();
        long duration = asset.durationMs() > 0L ? asset.durationMs() : track.durationMs();
        long restore = Math.min(restorePositionMs, Math.max(0L, duration - 1L));
        boolean initiallyPaused = restorePaused;
        boolean emptyPause = JammarrSettings.pauseWhenEmpty() && playerCount() == 0 && !initiallyPaused;
        timeline.schedule(duration, restore, initiallyPaused || emptyPause, TRACK_START_DELAY_MS);
        autoPaused = emptyPause; restorePositionMs = 0L; restorePaused = false;
        broadcastManifest(); broadcastState(); requestGeneration(); prefetchNext();
    }

    private void requestGeneration() {
        final StationModels.StationDefinition definition = activeDefinition();
        if (!definition.active() || generated.size() >= StationGenerator.LOOKAHEAD_TARGET
                || plexHealth != PlexHealth.ONLINE || suspendedGeneration == definition.generation()
                || definition.adventure() && adventureLoadedGeneration == definition.generation()
                || !generating.compareAndSet(false, true)) return;
        final long generation = definition.generation();
        final List<QueueTrack> history = new ArrayList<QueueTrack>(saved.history());
        CompletableFuture.supplyAsync(new Supplier<StationGenerator.GeneratedBatch>() {
            @Override public StationGenerator.GeneratedBatch get() {
                try { return stationGenerator.generate(definition, history, sonicCapability,
                        JammarrSettings.stationMetadataFallbackEnabled()); }
                catch (Exception error) { throw new RuntimeException(error); }
            }
        }, io).whenComplete((batch, error) -> schedule(new Runnable() {
            @Override public void run() {
                generating.set(false);
                if (activeDefinition().generation() != generation
                        || activeDefinition().type() != definition.type()) return;
                if (error != null) {
                    Throwable cause = root(error); generationMessage = safe(cause);
                    if (cause instanceof PlexException
                            && (((PlexException) cause).kind() == PlexException.Kind.OFFLINE
                            || ((PlexException) cause).kind() == PlexException.Kind.AUTHENTICATION)) {
                        markPlexFailure(cause);
                    } else suspendedGeneration = generation;
                    sendErrorToOperators(StatePackets.ErrorCode.TRACK_FAILED, generationMessage);
                    broadcastState(); return;
                }
                suspendedGeneration = -1L;
                Set<String> existing = new HashSet<String>();
                for (QueueTrack track : generated) existing.add(track.key());
                if (saved.current() != null) existing.add(saved.current().key());
                for (QueueTrack track : saved.queue()) existing.add(track.key());
                for (QueueTrack track : saved.history()) existing.add(track.key());
                for (QueueTrack track : batch.tracks()) if (existing.add(track.key())) generated.addLast(track);
                if (batch.adventurePath()) adventureLoadedGeneration = generation;
                generationMessage = batch.message(); ensureCurrent(); prefetchNext(); broadcastState();
            }
        }));
    }

    private void prefetchNext() {
        final QueueTrack track = nextTrack();
        if (asset == null || track == null || prefetching.get()) return;
        if (prefetched != null && prefetched.track.key().equals(track.key())) return;
        if (!prefetching.compareAndSet(false, true)) return;
        final int bitrate = JammarrSettings.audioBitrateKbps();
        final Set<Path> pinned = pinnedPaths(cache.target(track.key(), bitrate));
        CompletableFuture.supplyAsync(new Supplier<PreparedAsset>() {
            @Override public PreparedAsset get() { return prepare(track, bitrate, pinned); }
        }, io).whenComplete((prepared, error) -> schedule(new Runnable() {
            @Override public void run() {
                prefetching.set(false);
                QueueTrack expected = nextTrack();
                if (expected == null || !expected.key().equals(track.key())) { prefetchNext(); return; }
                if (error == null) { prefetched = prepared; cache.trim(pinnedPaths(prepared.asset.path())); }
                else if (TrackFailurePolicy.action(error) == TrackFailurePolicy.Action.WAIT_FOR_PLEX) markPlexFailure(error);
            }
        }));
    }

    private QueueTrack nextTrack() {
        return !saved.queue().isEmpty() ? saved.queue().get(0) : generated.peekFirst();
    }

    private Set<Path> pinnedPaths(Path additional) {
        Set<Path> pins = new HashSet<Path>();
        if (additional != null) pins.add(additional);
        if (asset != null) pins.add(asset.path());
        if (prefetched != null) pins.add(prefetched.asset.path());
        return Collections.unmodifiableSet(pins);
    }

    private void finishCurrent() {
        QueueTrack finished = saved.current();
        if (finished != null) saved.remember(finished);
        saved.current(null, StatePackets.PlaybackOrigin.NONE, "");
        stopAudio(); restorePositionMs = 0L; restorePaused = false; autoPaused = false;
        saved.update(0L, false); completeAdventureIfNeeded(); ensureCurrent(); requestGeneration();
    }

    private void completeAdventureIfNeeded() {
        StationModels.StationDefinition definition = saved.station();
        if (!definition.adventure() || adventureLoadedGeneration != definition.generation()
                || !generated.isEmpty()) return;
        StationModels.StationSeed finalWaypoint = definition.seeds().get(definition.seeds().size() - 1);
        saved.station(new StationModels.StationDefinition(StationModels.StationType.TRACK_RADIO,
                "Track Radio: " + finalWaypoint.title(), Collections.singletonList(finalWaypoint),
                definition.generation() + 1L));
        adventureLoadedGeneration = -1L; suspendedGeneration = -1L;
        generationMessage = "Sonic Adventure complete; continuing with Track Radio";
    }

    private void pauseInternal(boolean automatic) {
        if (!timeline.active() || timeline.paused()) return;
        timeline.pause(); autoPaused = automatic; saved.update(positionMs(), true); broadcastManifest();
    }

    private void resumeInternal() {
        if (!timeline.active() || !timeline.paused()) return;
        timeline.resume(); autoPaused = false; saved.update(positionMs(), false); broadcastManifest();
    }

    private void stopAudio() {
        asset = null; sessionId = null; timeline.stop(); transfers.clear();
        LegacyNetwork.sendToAll(LegacyPacketTypes.AUDIO_MANIFEST, emptyManifest());
    }

    private void movePending(int visibleIndex, int delta) {
        List<StatePackets.QueueEntry> visible = visibleQueue();
        int targetVisible = visibleIndex + delta;
        if (visibleIndex < 0 || targetVisible < 0 || visibleIndex >= visible.size()
                || targetVisible >= visible.size() || !visible.get(visibleIndex).editable()
                || !visible.get(targetVisible).editable()) return;
        int index = pendingIndex(visibleIndex), target = index + delta;
        if (index < 0 || target < 0 || index >= saved.queue().size() || target >= saved.queue().size()) return;
        QueueTrack value = saved.queue().remove(index); saved.queue().add(target, value);
        saved.markDirty(); prefetched = null; prefetchNext();
    }

    private int pendingIndex(int visibleIndex) { return visibleIndex - (saved.current() == null ? 0 : 1); }
    private int manualCount() {
        return saved.queue().size() + (saved.currentOrigin() == StatePackets.PlaybackOrigin.MANUAL ? 1 : 0);
    }

    private StationModels.StationDefinition activeDefinition() {
        if (saved.station().active()) return saved.station();
        if (saved.autoplayEnabled()) return new StationModels.StationDefinition(
                StationModels.StationType.AUTOPLAY, "Sonic Autoplay",
                Collections.<StationModels.StationSeed>emptyList(), saved.station().generation());
        return saved.station();
    }

    private static StationModels.StationDefinition definition(StationModels.StationType type,
                                                               List<StationModels.StationSeed> seeds,
                                                               long generation) {
        String name;
        switch (type) {
            case NONE: name = ""; break;
            case AUTOPLAY: name = "Sonic Autoplay"; break;
            case LIBRARY_SHUFFLE: name = "Library Shuffle"; break;
            case TRACK_RADIO: name = "Track Radio: " + seedName(seeds); break;
            case ARTIST_RADIO: name = "Artist Radio: " + seedName(seeds); break;
            case ALBUM_RADIO: name = "Album Radio: " + seedName(seeds); break;
            case SONIC_MIX: name = "Sonic Mix"; break;
            case SONIC_ADVENTURE: name = "Sonic Adventure"; break;
            default: name = "";
        }
        return new StationModels.StationDefinition(type, name, seeds, generation);
    }

    private static String seedName(List<StationModels.StationSeed> seeds) {
        return seeds.isEmpty() ? "" : seeds.get(0).title();
    }

    private long positionMs() { return timeline.positionMs(); }
    private long durationMs() { return timeline.durationMs(); }

    private void broadcastManifest() {
        for (EntityPlayerMP player : players()) sendManifest(player);
    }

    private void sendManifest(EntityPlayerMP player) {
        QueueTrack track = saved.current();
        if (asset == null || track == null) return;
        long now = System.currentTimeMillis();
        long target = timeline.paused() ? timeline.pausedPositionMs()
                : now < timeline.startedAtMs() ? 0L : positionMs() + TRACK_START_DELAY_MS;
        int firstChunk = Mp3FrameIndex.chunkAt(asset.chunks(),
                Math.min(target, Math.max(0L, durationMs() - 1L)));
        transfers.put(player.getUniqueID(), ChunkTransferPolicy.initial(sessionId, firstChunk, now));
        send(player, LegacyPacketTypes.AUDIO_MANIFEST, new TransportPackets.AudioManifest(sessionId,
                track.title(), track.artist(), asset.chunks().size(), firstChunk, durationMs(),
                timeline.startedAtMs(), timeline.paused(), timeline.pausedPositionMs(), asset.sha256()));
    }

    private TransportPackets.AudioManifest emptyManifest() {
        return new TransportPackets.AudioManifest(new UUID(0L, 0L), "", "", 0, 0,
                0L, 0L, true, 0L, "");
    }

    private void broadcastState() { broadcastState(""); }
    private void broadcastState(String notice) {
        for (EntityPlayerMP player : players()) sendState(player, notice);
    }
    private void sendState(EntityPlayerMP player) { sendState(player, ""); }

    private void sendState(EntityPlayerMP player, String notice) {
        QueueTrack current = saved.current();
        StatePackets.PlaybackStatus status = playbackStatus();
        String detail = blank(notice)
                ? status == StatePackets.PlaybackStatus.PLEX_OFFLINE
                ? "Plex is currently unavailable" : playbackDetail() : notice;
        if (isOperator(player)) detail += " | " + plexDiagnostic;
        send(player, LegacyPacketTypes.PLAYBACK_STATE, new StatePackets.PlaybackState(status, detail,
                current == null ? "" : current.title(), current == null ? "" : current.artist(),
                timeline.paused(), positionMs(), durationMs(), System.currentTimeMillis(), isOperator(player),
                saved.currentOrigin(), saved.currentSourceName(), visibleQueue()));
        StationModels.StationDefinition active = activeDefinition();
        List<StatePackets.QueueEntry> preview = new ArrayList<StatePackets.QueueEntry>();
        StatePackets.PlaybackOrigin origin = active.adventure()
                ? StatePackets.PlaybackOrigin.ADVENTURE : StatePackets.PlaybackOrigin.STATION;
        for (QueueTrack track : generated) {
            if (preview.size() >= GENERATED_PREVIEW_SIZE) break;
            preview.add(networkEntry(track, origin, false));
        }
        send(player, LegacyPacketTypes.STATION_STATE, new StatePackets.StationState(active.type(),
                active.active(), saved.autoplayEnabled(), active.generation(), sonicCapability,
                sonicMessage, active.name(), active.seeds(), preview));
    }

    private List<StatePackets.QueueEntry> visibleQueue() {
        List<StatePackets.QueueEntry> result = new ArrayList<StatePackets.QueueEntry>();
        if (saved.current() != null) result.add(networkEntry(saved.current(), saved.currentOrigin(), false));
        for (QueueTrack track : saved.queue()) {
            result.add(networkEntry(track, StatePackets.PlaybackOrigin.MANUAL, true));
        }
        StatePackets.PlaybackOrigin generatedOrigin = activeDefinition().adventure()
                ? StatePackets.PlaybackOrigin.ADVENTURE : StatePackets.PlaybackOrigin.STATION;
        for (QueueTrack track : generated) {
            if (result.size() >= saved.queue().size() + (saved.current() == null ? 0 : 1)
                    + GENERATED_PREVIEW_SIZE) break;
            result.add(networkEntry(track, generatedOrigin, false));
        }
        return Collections.unmodifiableList(result);
    }

    private static StatePackets.QueueEntry networkEntry(QueueTrack track,
                                                         StatePackets.PlaybackOrigin origin,
                                                         boolean editable) {
        return new StatePackets.QueueEntry(track.key(), track.title(), track.artist(),
                track.durationMs(), origin, editable);
    }

    private String playbackDetail() {
        if (saved.current() == null) return activeDefinition().active() ? stationDetail() : "";
        if (asset == null) return "Preparing audio from Plex";
        if (timeline.paused()) return "Playback paused";
        if (!saved.queue().isEmpty()) return prefetched != null
                ? "Manual request is next and prefetched" : "Preparing next manual request";
        if (activeDefinition().active()) return stationDetail();
        return "Audio ready";
    }

    private String stationDetail() { return blank(generationMessage) ? sonicMessage : generationMessage; }

    private StatePackets.PlaybackStatus playbackStatus() {
        if (asset != null) return timeline.paused()
                ? StatePackets.PlaybackStatus.PAUSED : StatePackets.PlaybackStatus.PLAYING;
        if (plexHealth == PlexHealth.OFFLINE) return StatePackets.PlaybackStatus.PLEX_OFFLINE;
        if (preparing.get() || generating.get() || saved.current() != null || !saved.queue().isEmpty()) {
            return StatePackets.PlaybackStatus.PREPARING;
        }
        return StatePackets.PlaybackStatus.IDLE;
    }

    private boolean requirePlex(EntityPlayerMP player) {
        if (plexHealth == PlexHealth.ONLINE) return true;
        sendError(player, StatePackets.ErrorCode.PLEX_OFFLINE, plexHealth == PlexHealth.VALIDATING
                ? "Plex validation is still in progress"
                : "Plex is offline; ask an operator to run /jammarr diagnostics");
        return false;
    }

    private boolean operator(EntityPlayerMP player) {
        if (isOperator(player)) return true;
        sendError(player, StatePackets.ErrorCode.PERMISSION_DENIED, "Operator permission is required");
        return false;
    }

    private boolean isOperator(EntityPlayerMP player) {
        return player.canCommandSenderUseCommand(JammarrSettings.operatorPermissionLevel(), "jammarr");
    }

    private boolean allow(SlidingWindowRateLimiter limiter, EntityPlayerMP player, int perSecond) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueID();
        if (limiter.allow(id, perSecond, now)) return true;
        if (limiter.count(id, now) == perSecond + 1) {
            sendError(player, StatePackets.ErrorCode.RATE_LIMITED, "Jammarr request rate limit exceeded");
        }
        return false;
    }

    private void markPlexFailure(Throwable error) {
        plexHealth = PlexHealth.OFFLINE;
        sonicCapability = StationModels.SonicCapability.PLEX_OFFLINE;
        plexDiagnostic = actionable(error);
        sonicMessage = "Plex is offline; station generation will resume after reconnection";
        nextPlexValidation = System.currentTimeMillis() + PLEX_REVALIDATE_MS;
    }

    private String actionable(Throwable error) {
        Throwable cause = root(error);
        if (!(cause instanceof PlexException)) return "Plex operation failed: " + safe(cause);
        switch (((PlexException) cause).kind()) {
            case AUTHENTICATION: return "Plex rejected the configured token; update JAMMARR_PLEX_TOKEN or jammarr-server.toml";
            case CONFIGURATION: return safe(cause);
            case NOT_FOUND: return "A Plex item no longer exists";
            case OFFLINE: return "Plex is unreachable or timed out";
            case INVALID_RESPONSE: return "Plex returned invalid metadata or audio";
            case TRANSCODE: return "Plex could not prepare the requested MP3 rendition";
            default: return safe(cause);
        }
    }

    private void sendErrorToOperators(StatePackets.ErrorCode code, String message) {
        for (EntityPlayerMP player : players()) if (isOperator(player)) sendError(player, code, message);
    }

    private String cacheState(QueueTrack track) {
        if (track == null) return "none";
        Path path = cache.target(track.key(), JammarrSettings.audioBitrateKbps());
        boolean cached = Files.isRegularFile(path);
        if (saved.current() != null && saved.current().key().equals(track.key()) && asset != null) {
            return "active," + (cached ? "cached" : "memory");
        }
        return prefetched != null && prefetched.track.key().equals(track.key())
                ? "prefetched,cached" : cached ? "cached" : "missing";
    }

    private String listenerHealth() {
        if (listenerStats.isEmpty()) return "none";
        StringBuilder value = new StringBuilder();
        for (Map.Entry<UUID, ListenerStats> entry : listenerStats.entrySet()) {
            if (value.length() > 0) value.append(';');
            ListenerStats stats = entry.getValue();
            value.append(entry.getKey().toString(), 0, 8).append(':').append(stats.state)
                    .append("/requests=").append(stats.requests).append("/acks=").append(stats.acknowledgements)
                    .append("/rejected=").append(stats.rejected).append("/recovery=").append(stats.recoveries)
                    .append("/underrun=").append(stats.underruns).append("/buffer=").append(stats.bufferedMs).append("ms");
        }
        return value.toString();
    }

    private static String controlMessage(ControlPackets.ControlAction action) {
        switch (action) {
            case PAUSE: return "Playback paused";
            case RESUME: return "Playback resumed";
            case SKIP: return "Track skipped";
            case REMOVE: return "Manual request removed";
            case MOVE_UP: return "Manual request moved up";
            case MOVE_DOWN: return "Manual request moved down";
            case CLEAR: return "Playback cleared";
            default: return "Playback updated";
        }
    }

    private static void sendError(EntityPlayerMP player, StatePackets.ErrorCode code, String message) {
        send(player, LegacyPacketTypes.ERROR, new StatePackets.ErrorMessage(code, message));
    }

    private static <T> void send(EntityPlayerMP player, LegacyPacketTypes.Type<T> type, T message) {
        LegacyNetwork.sendToPlayer(player, type, message);
    }

    private static void chat(EntityPlayerMP player, String message) {
        player.addChatMessage(new ChatComponentText(message));
    }

    @SuppressWarnings("unchecked")
    private List<EntityPlayerMP> players() {
        return new ArrayList<EntityPlayerMP>((List<EntityPlayerMP>) server.getConfigurationManager().playerEntityList);
    }

    private int playerCount() { return server.getConfigurationManager().getCurrentPlayerCount(); }

    private ListenerStats stats(EntityPlayerMP player) {
        ListenerStats value = listenerStats.get(player.getUniqueID());
        if (value == null) {
            value = new ListenerStats();
            listenerStats.put(player.getUniqueID(), value);
        }
        return value;
    }

    private void schedule(Runnable action) { mainThreadActions.add(action); }
    private String safe(Throwable error) { return SecretRedactor.message(error, JammarrSettings.plexToken()); }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static Throwable root(Throwable error) {
        Throwable value = error;
        while (value.getCause() != null) value = value.getCause();
        return value;
    }

    private static ExecutorService createIoExecutor() {
        final AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "jammarr-legacy-io-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    private static final class PreparedAsset {
        private final QueueTrack track;
        private final AudioAsset asset;
        private PreparedAsset(QueueTrack track, AudioAsset asset) { this.track = track; this.asset = asset; }
    }

    private static final class ListenerStats {
        private long requests, accepted, rejected, acknowledgements, lastSeenMs;
        private String state = "UNKNOWN";
        private int recoveries, underruns, receivedChunks;
        private long bufferedMs;
    }

    @Override public void close() {
        saved.update(positionMs(), timeline.paused());
        stopAudio();
        LegacyNetwork.setServerListener(null);
        io.shutdownNow();
        mainThreadActions.clear();
    }
}
