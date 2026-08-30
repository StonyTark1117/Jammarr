package stonytark.jammarr.client;

import com.mojang.brigadier.tree.CommandNode;
import stonytark.jammarr.core.client.ClockSynchronizer;
import net.minecraft.client.Minecraft;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.JammarrMessage;
import stonytark.jammarr.core.protocol.AcceptanceControlFile;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class JammarrClientState {
    private static final int BROWSE_PAGE_SIZE = 20;
    public static final JammarrClientState INSTANCE = new JammarrClientState();
    private final ClockSynchronizer clock = new ClockSynchronizer();
    private final JammarrAudioPlayer audio = new JammarrAudioPlayer(clock);
    private final AtomicLong timeNonce = new AtomicLong();
    private final AcceptanceControlFile acceptanceControl = new AcceptanceControlFile();
    private JammarrPayloads.PlaybackState playback = new JammarrPayloads.PlaybackState(JammarrPayloads.PlaybackStatus.IDLE, "", "", "", true, 0, 0, 0, false, List.of());
    private JammarrPayloads.StationState station = new JammarrPayloads.StationState(JammarrPayloads.StationType.NONE, false, false, 0,
            JammarrPayloads.SonicCapability.CHECKING, "Checking Plex sonic capability", "", List.of(), List.of());
    private JammarrPayloads.AdventurePreview adventurePreview = new JammarrPayloads.AdventurePreview(0, "", List.of());
    private JammarrPayloads.BrowseResults browse = new JammarrPayloads.BrowseResults(JammarrPayloads.BrowseKind.SEARCH, "", 0, false, List.of());
    private long lastTimeSync;
    private long helloDueMs = -1L;
    private String notice = "";
    private boolean nonOperatorCommandsVerified;
    private boolean operatorCommandsVerified;
    private boolean acceptanceScreenProbeSent;
    private boolean acceptanceScreenVerified;
    private int acceptanceScreenTicks;
    private boolean acceptanceConfigScreenVerified;
    private int acceptanceConfigScreenTicks;
    private boolean acceptanceAudioQueued;
    private AudioPlaybackState lastAcceptanceAudioState;
    private boolean audioNegotiated;

    public void accept(JammarrMessage payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload instanceof JammarrPayloads.OpenScreen) {
            minecraft.setScreen(new JammarrScreen(this));
            JammarrNetwork.sendToServer(new JammarrPayloads.BrowseRequest(JammarrPayloads.BrowseKind.SEARCH, "", 0));
        } else if (payload instanceof JammarrPayloads.ServerHello value) {
            if (ProtocolLimits.clientHelloDelayMs() > 0L) {
                Jammarr.LOGGER.info("Acceptance client received server hello after delayed handshake");
            }
            if (value.protocolVersion() != ProtocolLimits.clientHelloVersion() && minecraft.getConnection() != null) {
                minecraft.getConnection().getConnection().disconnect(net.minecraft.network.chat.Component.literal(
                        "Jammarr protocol mismatch: server requires version " + value.protocolVersion()));
            } else {
                notice = "";
                stonytark.jammarr.core.protocol.ProtocolCapabilities.Negotiated negotiated =
                        stonytark.jammarr.core.protocol.ProtocolCapabilities.negotiate(
                                value.features(), value.audioChunkBytes(), value.chunksPerRequest());
                audio.transportLimits(negotiated.chunksPerRequest());
                audioNegotiated = negotiated.supports(stonytark.jammarr.core.protocol.ProtocolCapabilities.AUDIO_STREAMING);
                if (!audioNegotiated) {
                    notice = "The server did not negotiate Jammarr audio streaming";
                }
                requestTimeSync();
                queueAcceptanceAudio();
            }
        } else if (payload instanceof JammarrPayloads.TimeSyncResponse value) {
            ClockSynchronizer.Sample sample = clock.accept(
                    value.clientSentEpochMs(), value.serverEpochMs(), System.currentTimeMillis());
            if (ProtocolLimits.audioProbeEnabled() && clock.sampleCount() <= ClockSynchronizer.STARTUP_SAMPLE_TARGET) {
                Jammarr.LOGGER.info("Acceptance clock sample: count={} rttMs={} rawOffsetMs={} selectedOffsetMs={}",
                        clock.sampleCount(), sample.roundTripMs(), sample.rawOffsetMs(), sample.filteredOffsetMs());
            }
        } else if (payload instanceof JammarrPayloads.BrowseResults value) {
            browse = value; refreshScreen(minecraft);
        } else if (payload instanceof JammarrPayloads.PlaybackState value) {
            playback = value;
            audio.playbackActive(value.status() == JammarrPayloads.PlaybackStatus.PLAYING
                    || value.status() == JammarrPayloads.PlaybackStatus.PAUSED);
            logAcceptancePlayback(value);
            boolean queueBrowseChanged = refreshQueueBrowse();
            if (minecraft.screen instanceof JammarrScreen screen) {
                screen.playbackChanged();
                if (queueBrowseChanged) screen.queueChanged();
            }
        } else if (payload instanceof JammarrPayloads.StationState value) {
            station = value;
            if (ProtocolLimits.audioProbeEnabled()) Jammarr.LOGGER.info(
                    "Acceptance station state: type={} active={} autoplay={} generation={} preview={}",
                    value.stationType(), value.active(), value.autoplayEnabled(), value.generation(), value.preview().size());
            if (minecraft.screen instanceof JammarrScreen screen) screen.stationChanged();
        } else if (payload instanceof JammarrPayloads.AdventurePreview value) {
            adventurePreview = value;
            if (minecraft.screen instanceof JammarrScreen screen) screen.adventurePreviewChanged();
        } else if (payload instanceof JammarrPayloads.AudioManifest value) {
            if (!audioNegotiated) return;
            if (ProtocolLimits.audioProbeEnabled()) Jammarr.LOGGER.info(
                    "Acceptance audio manifest: session={} title={} firstChunk={} paused={}",
                    value.sessionId(), value.title(), value.firstChunk(), value.paused());
            audio.manifest(value);
        } else if (payload instanceof JammarrPayloads.AudioChunk value) {
            if (!audioNegotiated) return;
            audio.chunk(value);
        } else if (payload instanceof JammarrPayloads.ErrorMessage value) {
            notice = value.message();
            if (minecraft.player != null) minecraft.player.displayClientMessage(net.minecraft.network.chat.Component.literal("Jammarr: " + value.message()), false);
            refreshScreen(minecraft);
            if (minecraft.screen instanceof JammarrScreen screen) screen.requestFailed();
        }
    }

    public JammarrPayloads.PlaybackState playback() { return playback; }
    public JammarrPayloads.BrowseResults browse() { return browse; }
    public JammarrPayloads.StationState station() { return station; }
    public JammarrPayloads.AdventurePreview adventurePreview() { return adventurePreview; }
    public String notice() { return notice; }
    public String audioStatus() { return audio.status(); }
    public AudioPlaybackState audioState() { return audio.state(); }
    public void clearNotice() { notice = ""; }
    public void clearBrowse(JammarrPayloads.BrowseKind kind, String query) {
        browse = new JammarrPayloads.BrowseResults(kind, query, 0, false, List.of());
    }

    private boolean refreshQueueBrowse() {
        if (browse.kind() != JammarrPayloads.BrowseKind.QUEUE) return false;
        return showQueuePage(browse.page());
    }
    public boolean showQueuePage(int requestedPage) {
        int page = Math.max(0, requestedPage);
        if ((long)page * BROWSE_PAGE_SIZE >= playback.queue().size() && page > 0) page = 0;
        int start = page * BROWSE_PAGE_SIZE;
        int end = Math.min(playback.queue().size(), start + BROWSE_PAGE_SIZE);
        List<JammarrPayloads.MediaItem> items = playback.queue().subList(start, end).stream()
                .map(entry -> new JammarrPayloads.MediaItem(JammarrPayloads.ItemKind.TRACK, entry.key(), entry.title(), entry.artist(), entry.durationMs()))
                .toList();
        JammarrPayloads.BrowseResults updated = new JammarrPayloads.BrowseResults(JammarrPayloads.BrowseKind.QUEUE, "", page, end < playback.queue().size(), items);
        if (browse.equals(updated)) return false;
        browse = updated;
        return true;
    }
    public void tick() {
        sendDelayedHello();
        probeCommandPermissions();
        probeScreenRendering();
        runAcceptanceControl();
        logAcceptanceAudioState();
        long now = System.currentTimeMillis();
        long syncIntervalMs = clock.sampleCount() < ClockSynchronizer.STARTUP_SAMPLE_TARGET ? 500L : 10_000L;
        if (now - lastTimeSync >= syncIntervalMs) requestTimeSync();
        audio.tick();
    }
    public void hello() {
        if (!JammarrNetwork.serverAvailable()) {
            notice = "This server does not support Jammarr";
            return;
        }
        if (ProtocolLimits.clientHelloSuppressed()) return;
        long delayMs = ProtocolLimits.clientHelloDelayMs();
        if (delayMs > 0L) {
            helloDueMs = System.currentTimeMillis() + delayMs;
            Jammarr.LOGGER.info("Acceptance client delaying Jammarr hello by {} ms", delayMs);
            return;
        }
        sendHello();
    }
    public void ensureAudio() { audio.ensureStarted(); }
    public void listeningChanged() { audio.listeningChanged(); }
    public void retryAudio() { audio.retry(); refreshScreen(Minecraft.getInstance()); }
    public void audioEngineReloaded() { audio.audioEngineReloaded(); }
    public void stop() {
        audio.stop(); clock.reset(); notice = ""; lastTimeSync = 0; helloDueMs = -1L;
        nonOperatorCommandsVerified = false; operatorCommandsVerified = false;
        acceptanceScreenProbeSent = false; acceptanceScreenVerified = false; acceptanceScreenTicks = 0;
        acceptanceConfigScreenVerified = false; acceptanceConfigScreenTicks = 0;
        acceptanceAudioQueued = false; lastAcceptanceAudioState = null;
        audioNegotiated = false;
        acceptanceControl.reset();
        playback = new JammarrPayloads.PlaybackState(JammarrPayloads.PlaybackStatus.IDLE, "", "", "", true, 0, 0, 0, false, List.of());
        station = new JammarrPayloads.StationState(JammarrPayloads.StationType.NONE, false, false, 0,
                JammarrPayloads.SonicCapability.CHECKING, "Checking Plex sonic capability", "", List.of(), List.of());
        adventurePreview = new JammarrPayloads.AdventurePreview(0, "", List.of());
    }

    private void requestTimeSync() {
        if (Minecraft.getInstance().getConnection() == null) return;
        long now = System.currentTimeMillis();
        JammarrNetwork.sendToServer(new JammarrPayloads.TimeSyncRequest(timeNonce.incrementAndGet(), now));
        lastTimeSync = now;
    }
    private void sendDelayedHello() {
        if (helloDueMs < 0L || System.currentTimeMillis() < helloDueMs) return;
        helloDueMs = -1L;
        sendHello();
        Jammarr.LOGGER.info("Acceptance client sent delayed Jammarr hello");
    }
    private void sendHello() {
        JammarrNetwork.sendToServer(new JammarrPayloads.ClientHello(ProtocolLimits.clientHelloVersion()));
        requestTimeSync();
    }
    private void probeCommandPermissions() {
        if (!ProtocolLimits.commandProbeEnabled()) return;
        net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        CommandNode<?> root = connection.getCommands().getRoot().getChild("jammarr");
        if (root == null || root.getChild("status") == null) return;
        boolean operatorVisible = root.getChild("diagnostics") != null;
        if (!nonOperatorCommandsVerified && !operatorVisible) {
            nonOperatorCommandsVerified = true;
            Jammarr.LOGGER.info("Acceptance command permissions: non-operator public=true operator=false");
            connection.sendCommand("jammarr");
            acceptanceScreenProbeSent = true;
            Jammarr.LOGGER.info("Acceptance client issued: /jammarr");
        } else if (nonOperatorCommandsVerified && !operatorCommandsVerified && operatorVisible) {
            operatorCommandsVerified = true;
            Jammarr.LOGGER.info("Acceptance command permissions: operator public=true operator=true");
            connection.sendCommand("jammarr diagnostics");
            Jammarr.LOGGER.info("Acceptance client issued: /jammarr diagnostics");
        }
    }
    private void probeScreenRendering() {
        if (!acceptanceScreenProbeSent || acceptanceConfigScreenVerified) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!acceptanceScreenVerified) {
            if (!(minecraft.screen instanceof JammarrScreen)) {
                acceptanceScreenTicks = 0;
                return;
            }
            if (++acceptanceScreenTicks < 20) return;
            acceptanceScreenVerified = true;
            Jammarr.LOGGER.info("Acceptance Jammarr screen remained open across rendered frames");
            minecraft.setScreen(new JammarrClientConfigScreen(minecraft.screen));
            return;
        }
        if (!(minecraft.screen instanceof JammarrClientConfigScreen)) {
            acceptanceConfigScreenTicks = 0;
            return;
        }
        if (++acceptanceConfigScreenTicks < 20) return;
        acceptanceConfigScreenVerified = true;
        Jammarr.LOGGER.info("Acceptance Jammarr config screen remained open across rendered frames");
    }
    private void queueAcceptanceAudio() {
        if (!ProtocolLimits.audioProbeLeader() || acceptanceAudioQueued) return;
        acceptanceAudioQueued = true;
        JammarrNetwork.sendToServer(new JammarrPayloads.QueueRequest(JammarrPayloads.ItemKind.TRACK, "42"));
        Jammarr.LOGGER.info("Acceptance audio leader queued Plex track 42");
    }
    private void logAcceptanceAudioState() {
        if (!ProtocolLimits.audioProbeEnabled()) return;
        AudioPlaybackState state = audio.state();
        if (state == lastAcceptanceAudioState) return;
        lastAcceptanceAudioState = state;
        Jammarr.LOGGER.info("Acceptance audio state: {}", state);
    }
    private void logAcceptancePlayback(JammarrPayloads.PlaybackState value) {
        if (!ProtocolLimits.audioProbeEnabled()) return;
        StringBuilder queue = new StringBuilder();
        for (JammarrPayloads.QueueEntry entry : value.queue()) {
            if (queue.length() != 0) queue.append(',');
            queue.append(entry.key());
        }
        Jammarr.LOGGER.info("Acceptance playback state: status={} paused={} title={} origin={} queue={}",
                value.status(), value.paused(), value.title(), value.origin(), queue);
    }
    private void runAcceptanceControl() {
        String command = acceptanceControl.poll();
        if (command.isEmpty()) return;
        try {
            if (command.startsWith("queue:")) {
                JammarrNetwork.sendToServer(new JammarrPayloads.QueueRequest(
                        JammarrPayloads.ItemKind.TRACK, command.substring("queue:".length())));
            } else if (command.startsWith("control:")) {
                String[] parts = command.split(":", -1);
                int index = parts.length > 2 ? Integer.parseInt(parts[2]) : -1;
                String expectedKey = parts.length > 3 ? parts[3] : "";
                JammarrNetwork.sendToServer(new JammarrPayloads.ControlRequest(
                        JammarrPayloads.ControlAction.valueOf(parts[1].toUpperCase(java.util.Locale.ROOT)), index, expectedKey));
            } else if (command.equals("mute")) {
                JammarrSettings.enabled(false); listeningChanged();
            } else if (command.equals("unmute")) {
                JammarrSettings.enabled(true); listeningChanged();
            } else if (command.startsWith("volume:")) {
                JammarrSettings.volume(Double.parseDouble(command.substring("volume:".length())));
            } else if (command.equals("station:library-shuffle")) {
                JammarrNetwork.sendToServer(new JammarrPayloads.StationRequest(
                        JammarrPayloads.StationAction.START, JammarrPayloads.StationType.LIBRARY_SHUFFLE,
                        false, station.generation(), List.of()));
            } else if (command.startsWith("adventure:")) {
                String[] keys = command.split(":", -1);
                if (keys.length != 3) throw new IllegalArgumentException("Adventure needs two keys");
                JammarrNetwork.sendToServer(new JammarrPayloads.StationRequest(
                        JammarrPayloads.StationAction.START_NOW, JammarrPayloads.StationType.SONIC_ADVENTURE,
                        false, station.generation(), List.of(
                        new JammarrPayloads.StationSeed(JammarrPayloads.ItemKind.TRACK, keys[1], "Gate Track " + keys[1], "Gate Artist"),
                        new JammarrPayloads.StationSeed(JammarrPayloads.ItemKind.TRACK, keys[2], "Gate Track " + keys[2], "Gate Artist"))));
            } else if (command.equals("reload")) {
                Minecraft.getInstance().reloadResourcePacks().whenComplete((unused, error) ->
                        Jammarr.LOGGER.info("Acceptance resource reload complete: success={}", error == null));
            } else if (command.equals("fault:underrun")) {
                audio.acceptanceUnderrun();
            } else if (command.equals("fault:drift")) {
                audio.acceptanceClockDrift();
            } else if (command.equals("fault:exhaust-retries")) {
                audio.acceptanceExhaustRecovery();
            } else if (command.equals("retry")) {
                audio.retry();
            } else {
                throw new IllegalArgumentException("Unknown acceptance operation");
            }
            Jammarr.LOGGER.info("Acceptance control applied: {}", command);
        } catch (RuntimeException error) {
            Jammarr.LOGGER.error("Acceptance control failed: {}", command, error);
        }
    }
    private static void refreshScreen(Minecraft minecraft) { if (minecraft.screen instanceof JammarrScreen screen) screen.resultsChanged(); }
    private JammarrClientState() {}
}
