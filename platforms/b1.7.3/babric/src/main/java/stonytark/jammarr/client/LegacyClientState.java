package stonytark.jammarr.client;

import net.minecraft.client.Minecraft;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.client.ClockSynchronizer;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.AcceptanceControlFile;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.core.protocol.TransportPackets;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.network.LegacyPacketTypes;
import stonytark.jammarr.core.platform.JammarrSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class LegacyClientState implements LegacyNetwork.ClientListener {
    static final LegacyClientState INSTANCE = new LegacyClientState();
    private static final int PAGE_SIZE = 20;
    private final ClockSynchronizer clock = new ClockSynchronizer();
    private final LegacyAudioPlayer audio = new LegacyAudioPlayer(clock);
    private final AtomicLong timeNonce = new AtomicLong();
    private final AcceptanceControlFile acceptanceControl = new AcceptanceControlFile();
    private StatePackets.PlaybackState playback = emptyPlayback();
    private StatePackets.StationState station = emptyStation();
    private StatePackets.AdventurePreview adventure = new StatePackets.AdventurePreview(
            0L, "", Collections.<StatePackets.QueueEntry>emptyList());
    private ControlPackets.BrowseResults browse = new ControlPackets.BrowseResults(
            ControlPackets.BrowseKind.SEARCH, "", 0, false, Collections.<StationModels.MediaItem>emptyList());
    private boolean helloSent;
    private long helloEligibleAt;
    private boolean commandProbeSent;
    private boolean operatorProbeSent;
    private boolean acceptanceAudioQueued;
    private boolean acceptanceScreenOpened;
    private boolean acceptanceScreenLogged;
    private int acceptanceScreenTicks;
    private boolean acceptanceConfigScreenOpened;
    private boolean acceptanceConfigScreenLogged;
    private int acceptanceConfigScreenTicks;
    private String lastAcceptanceAudioState = "";
    private long lastTimeSync;
    private String notice = "";
    private boolean serverHelloReceived;
    private boolean audioNegotiated;

    @Override public void accept(LegacyPacketTypes.Type<?> type, Object message) {
        Minecraft minecraft = LegacyClient.minecraft();
        if (type == LegacyPacketTypes.OPEN_SCREEN) {
            minecraft.setScreen(new LegacyScreen(this));
        } else if (type == LegacyPacketTypes.SERVER_HELLO) {
            ControlPackets.ServerHello hello = (ControlPackets.ServerHello) message;
            if (ProtocolLimits.clientHelloDelayMs() > 0L) {
                Jammarr.LOGGER.info("Acceptance client received server hello after delayed handshake");
            }
            if (hello.protocolVersion() != ProtocolLimits.clientHelloVersion() && minecraft.getNetworkHandler() != null) {
                Jammarr.LOGGER.warn("Jammarr protocol mismatch: server requires version {}", hello.protocolVersion());
                minecraft.getNetworkHandler().disconnect();
            } else {
                serverHelloReceived = true;
                stonytark.jammarr.core.protocol.ProtocolCapabilities.Negotiated negotiated =
                        stonytark.jammarr.core.protocol.ProtocolCapabilities.negotiate(
                                hello.features(), hello.audioChunkBytes(), hello.chunksPerRequest());
                audio.transportLimits(negotiated.chunksPerRequest());
                audioNegotiated = negotiated.supports(stonytark.jammarr.core.protocol.ProtocolCapabilities.AUDIO_STREAMING);
                notice = audioNegotiated ? "" : "The server did not negotiate Jammarr audio streaming";
                requestTimeSync();
                queueAcceptanceAudio();
            }
        } else if (type == LegacyPacketTypes.TIME_SYNC_RESPONSE) {
            ControlPackets.TimeSyncResponse response = (ControlPackets.TimeSyncResponse) message;
            ClockSynchronizer.Sample sample = clock.accept(
                    response.clientSentEpochMs(), response.serverEpochMs(), System.currentTimeMillis());
            if (ProtocolLimits.audioProbeEnabled() && clock.sampleCount() <= ClockSynchronizer.STARTUP_SAMPLE_TARGET) {
                Jammarr.LOGGER.info("Acceptance clock sample: count={} rttMs={} rawOffsetMs={} selectedOffsetMs={}",
                        clock.sampleCount(), sample.roundTripMs(), sample.rawOffsetMs(), sample.filteredOffsetMs());
            }
        } else if (type == LegacyPacketTypes.BROWSE_RESULTS) {
            browse = (ControlPackets.BrowseResults) message;
            screenResultsChanged();
        } else if (type == LegacyPacketTypes.PLAYBACK_STATE) {
            playback = (StatePackets.PlaybackState) message;
            logAcceptancePlayback(playback);
            refreshQueueBrowse(); screenChanged();
        } else if (type == LegacyPacketTypes.STATION_STATE) {
            station = (StatePackets.StationState) message;
            if (ProtocolLimits.audioProbeEnabled()) Jammarr.LOGGER.info(
                    "Acceptance station state: type={} active={} autoplay={} generation={} preview={}",
                    station.stationType(), station.active(), station.autoplayEnabled(), station.generation(), station.preview().size());
            screenChanged();
        } else if (type == LegacyPacketTypes.ADVENTURE_PREVIEW) {
            adventure = (StatePackets.AdventurePreview) message; screenChanged();
        } else if (type == LegacyPacketTypes.AUDIO_MANIFEST) {
            if (!audioNegotiated) return;
            TransportPackets.AudioManifest manifest = (TransportPackets.AudioManifest) message;
            if (ProtocolLimits.audioProbeEnabled()) Jammarr.LOGGER.info(
                    "Acceptance audio manifest: session={} title={} firstChunk={} paused={}",
                    manifest.sessionId(), manifest.title(), manifest.firstChunk(), manifest.paused());
            audio.manifest(manifest);
        } else if (type == LegacyPacketTypes.AUDIO_CHUNK) {
            if (!audioNegotiated) return;
            audio.chunk((TransportPackets.AudioChunk) message);
        } else if (type == LegacyPacketTypes.ERROR) {
            notice = ((StatePackets.ErrorMessage) message).message();
            if (minecraft.player != null) minecraft.player.sendMessage("Jammarr: " + notice);
            LegacyScreen screen = screen();
            if (screen != null) screen.requestFailed();
        }
    }

    void tick() {
        if (!helloSent) {
            hello();
            runAcceptanceScreenProbe();
            return;
        }
        if (serverHelloReceived && ProtocolLimits.commandProbeEnabled() && !commandProbeSent
                && LegacyClient.minecraft().player != null) {
            commandProbeSent = true;
            LegacyClient.minecraft().player.sendChatMessage("/jammarr status");
            LegacyClient.minecraft().player.sendChatMessage("/jammarr diagnostics");
            Jammarr.LOGGER.info("Acceptance client issued non-operator command probes");
        }
        runAcceptanceControl();
        logAcceptanceAudioState();
        long now = System.currentTimeMillis();
        long syncIntervalMs = clock.sampleCount() < ClockSynchronizer.STARTUP_SAMPLE_TARGET ? 500L : 10_000L;
        if (now - lastTimeSync >= syncIntervalMs) requestTimeSync();
        audio.tick();
        runAcceptanceScreenProbe();
    }

    private void hello() {
        if (!LegacyNetwork.serverAvailable()) {
            notice = "This server does not support Jammarr";
            return;
        }
        if (ProtocolLimits.clientHelloSuppressed()) return;
        long now = System.currentTimeMillis();
        long delayMs = ProtocolLimits.clientHelloDelayMs();
        if (helloEligibleAt == 0L && delayMs > 0L) {
            helloEligibleAt = now + delayMs;
            Jammarr.LOGGER.info("Acceptance client delaying Jammarr hello by {} ms", delayMs);
        }
        if (helloEligibleAt > now) return;
        LegacyNetwork.sendToServer(LegacyPacketTypes.CLIENT_HELLO, new ControlPackets.ClientHello(ProtocolLimits.clientHelloVersion()));
        helloSent = true;
        Jammarr.LOGGER.info("Jammarr client sent its Beta 1.7.3 protocol hello");
        if (delayMs > 0L) Jammarr.LOGGER.info("Acceptance client sent delayed Jammarr hello");
    }

    void stop() {
        audio.stop(); clock.reset(); notice = ""; helloSent = false; helloEligibleAt = 0L; commandProbeSent = false;
        serverHelloReceived = false; audioNegotiated = false;
        operatorProbeSent = false; lastTimeSync = 0L;
        acceptanceAudioQueued = false; lastAcceptanceAudioState = "";
        acceptanceScreenOpened = false; acceptanceScreenLogged = false; acceptanceScreenTicks = 0;
        acceptanceConfigScreenOpened = false; acceptanceConfigScreenLogged = false; acceptanceConfigScreenTicks = 0;
        acceptanceControl.reset();
        playback = emptyPlayback(); station = emptyStation();
        browse = new ControlPackets.BrowseResults(ControlPackets.BrowseKind.SEARCH, "", 0,
                false, Collections.<StationModels.MediaItem>emptyList());
        adventure = new StatePackets.AdventurePreview(0L, "", Collections.<StatePackets.QueueEntry>emptyList());
    }

    void operatorCommandProbe() {
        if (!ProtocolLimits.commandProbeEnabled() || operatorProbeSent
                || LegacyClient.minecraft().player == null) return;
        operatorProbeSent = true;
        LegacyClient.minecraft().player.sendChatMessage("/jammarr diagnostics");
        Jammarr.LOGGER.info("Acceptance client issued: /jammarr diagnostics");
    }

    private void queueAcceptanceAudio() {
        if (!ProtocolLimits.audioProbeLeader() || acceptanceAudioQueued) return;
        acceptanceAudioQueued = true;
        LegacyNetwork.sendToServer(LegacyPacketTypes.QUEUE_REQUEST, new ControlPackets.QueueRequest(
                StationModels.ItemKind.TRACK, "42"));
        Jammarr.LOGGER.info("Acceptance audio leader queued Plex track 42");
    }

    private void logAcceptanceAudioState() {
        if (!ProtocolLimits.audioProbeEnabled()) return;
        String state = audio.state();
        if (state.equals(lastAcceptanceAudioState)) return;
        lastAcceptanceAudioState = state;
        Jammarr.LOGGER.info("Acceptance audio state: {}", state);
    }

    private void logAcceptancePlayback(StatePackets.PlaybackState value) {
        if (!ProtocolLimits.audioProbeEnabled()) return;
        StringBuilder queue = new StringBuilder();
        for (StatePackets.QueueEntry entry : value.queue()) {
            if (queue.length() != 0) queue.append(',');
            queue.append(entry.key());
        }
        Jammarr.LOGGER.info("Acceptance playback state: status={} paused={} title={} origin={} queue={}",
                value.status(), value.paused(), value.title(), value.origin(), queue);
    }

    private void runAcceptanceScreenProbe() {
        if (Boolean.getBoolean("jammarr.acceptance.unmoddedServerProbe")
                && "This server does not support Jammarr".equals(notice)) {
            Minecraft minecraft = LegacyClient.minecraft();
            if (!acceptanceScreenOpened) {
                minecraft.setScreen(new LegacyScreen(this));
                acceptanceScreenOpened = true;
                return;
            }
            if (!acceptanceScreenLogged && minecraft.currentScreen instanceof LegacyScreen
                    && ++acceptanceScreenTicks >= 2) {
                acceptanceScreenLogged = true;
                Jammarr.LOGGER.info("Acceptance Jammarr unsupported-server screen remained open across client ticks");
            }
            return;
        }
        if (!ProtocolLimits.commandProbeEnabled() || !serverHelloReceived) return;
        Minecraft minecraft = LegacyClient.minecraft();
        if (!acceptanceScreenOpened) {
            minecraft.setScreen(new LegacyScreen(this));
            acceptanceScreenOpened = true;
            return;
        }
        if (!acceptanceScreenLogged) {
            if (!(minecraft.currentScreen instanceof LegacyScreen)) return;
            if (++acceptanceScreenTicks < 2) return;
            acceptanceScreenLogged = true;
            Jammarr.LOGGER.info("Acceptance legacy Jammarr screen remained open across client ticks");
            return;
        }
        if (!acceptanceConfigScreenOpened) {
            minecraft.setScreen(new LegacyClientConfigScreen(minecraft.currentScreen));
            acceptanceConfigScreenOpened = true;
            return;
        }
        if (!acceptanceConfigScreenLogged && minecraft.currentScreen instanceof LegacyClientConfigScreen
                && ++acceptanceConfigScreenTicks >= 2) {
            acceptanceConfigScreenLogged = true;
            Jammarr.LOGGER.info("Acceptance legacy Jammarr config screen remained open across client ticks");
        }
    }

    private void runAcceptanceControl() {
        String command = acceptanceControl.poll();
        if (command.length() == 0) return;
        try {
            if (command.startsWith("queue:")) {
                LegacyNetwork.sendToServer(LegacyPacketTypes.QUEUE_REQUEST, new ControlPackets.QueueRequest(
                        StationModels.ItemKind.TRACK, command.substring("queue:".length())));
            } else if (command.startsWith("control:")) {
                String[] parts = command.split(":", -1);
                int index = parts.length > 2 ? Integer.parseInt(parts[2]) : -1;
                String expectedKey = parts.length > 3 ? parts[3] : "";
                LegacyNetwork.sendToServer(LegacyPacketTypes.CONTROL_REQUEST, new ControlPackets.ControlRequest(
                        ControlPackets.ControlAction.valueOf(parts[1].toUpperCase(java.util.Locale.ROOT)), index, expectedKey));
            } else if ("mute".equals(command)) {
                JammarrSettings.enabled(false); listeningChanged();
            } else if ("unmute".equals(command)) {
                JammarrSettings.enabled(true); listeningChanged();
            } else if (command.startsWith("volume:")) {
                JammarrSettings.volume(Double.parseDouble(command.substring("volume:".length())));
            } else if ("station:library-shuffle".equals(command)) {
                LegacyNetwork.sendToServer(LegacyPacketTypes.STATION_REQUEST, new ControlPackets.StationRequest(
                        ControlPackets.StationAction.START, StationModels.StationType.LIBRARY_SHUFFLE,
                        false, station.generation(), Collections.<StationModels.StationSeed>emptyList()));
            } else if (command.startsWith("adventure:")) {
                String[] keys = command.split(":", -1);
                if (keys.length != 3) throw new IllegalArgumentException("Adventure needs two keys");
                LegacyNetwork.sendToServer(LegacyPacketTypes.STATION_REQUEST, new ControlPackets.StationRequest(
                        ControlPackets.StationAction.START_NOW, StationModels.StationType.SONIC_ADVENTURE,
                        false, station.generation(), Arrays.asList(
                        new StationModels.StationSeed(StationModels.ItemKind.TRACK, keys[1], "Gate Track " + keys[1], "Gate Artist"),
                        new StationModels.StationSeed(StationModels.ItemKind.TRACK, keys[2], "Gate Track " + keys[2], "Gate Artist"))));
            } else if ("reload".equals(command)) {
                ((stonytark.jammarr.mixin.client.MinecraftAccessor) LegacyClient.minecraft()).jammarr$forceResourceReload();
                Jammarr.LOGGER.info("Acceptance resource reload complete: success=true");
            } else if ("fault:underrun".equals(command)) {
                audio.acceptanceUnderrun();
            } else if ("fault:drift".equals(command)) {
                audio.acceptanceClockDrift();
            } else if ("fault:exhaust-retries".equals(command)) {
                audio.acceptanceExhaustRecovery();
            } else if ("retry".equals(command)) {
                audio.retry();
            } else {
                throw new IllegalArgumentException("Unknown acceptance operation");
            }
            Jammarr.LOGGER.info("Acceptance control applied: {}", command);
        } catch (RuntimeException error) {
            Jammarr.LOGGER.error("Acceptance control failed: " + command, error);
        }
    }

    StatePackets.PlaybackState playback() { return playback; }
    StatePackets.StationState station() { return station; }
    StatePackets.AdventurePreview adventure() { return adventure; }
    ControlPackets.BrowseResults browse() { return browse; }
    String notice() { return notice; }
    void clearNotice() { notice = ""; }
    String audioStatus() { return audio.status(); }
    String audioState() { return audio.state(); }
    boolean suppressVanillaMusic() { return audio.ownsMusic(); }
    void listeningChanged() { audio.listeningChanged(); }
    void retryAudio() { audio.retry(); }
    void audioEngineReloaded() { audio.audioEngineReloaded(); }

    void clearBrowse(ControlPackets.BrowseKind kind, String query) {
        browse = new ControlPackets.BrowseResults(kind, query, 0, false,
                Collections.<StationModels.MediaItem>emptyList());
    }

    void showQueuePage(int requestedPage) {
        int page = Math.max(0, requestedPage);
        if ((long) page * PAGE_SIZE >= playback.queue().size() && page > 0) page = 0;
        int first = page * PAGE_SIZE;
        int end = Math.min(playback.queue().size(), first + PAGE_SIZE);
        List<StationModels.MediaItem> items = new ArrayList<StationModels.MediaItem>();
        for (StatePackets.QueueEntry entry : playback.queue().subList(first, end)) {
            items.add(new StationModels.MediaItem(StationModels.ItemKind.TRACK, entry.key(),
                    entry.title(), entry.artist(), entry.durationMs()));
        }
        browse = new ControlPackets.BrowseResults(ControlPackets.BrowseKind.QUEUE, "", page,
                end < playback.queue().size(), items);
    }

    private void refreshQueueBrowse() {
        if (browse.kind() == ControlPackets.BrowseKind.QUEUE) showQueuePage(browse.page());
    }

    private void requestTimeSync() {
        if (LegacyClient.minecraft().getNetworkHandler() == null) return;
        long now = System.currentTimeMillis();
        LegacyNetwork.sendToServer(LegacyPacketTypes.TIME_SYNC_REQUEST,
                new ControlPackets.TimeSyncRequest(timeNonce.incrementAndGet(), now));
        lastTimeSync = now;
    }

    private LegacyScreen screen() {
        return LegacyClient.minecraft().currentScreen instanceof LegacyScreen
                ? (LegacyScreen) LegacyClient.minecraft().currentScreen : null;
    }
    private void screenChanged() { LegacyScreen screen = screen(); if (screen != null) screen.stateChanged(); }
    private void screenResultsChanged() { LegacyScreen screen = screen(); if (screen != null) screen.resultsChanged(); }

    private static StatePackets.PlaybackState emptyPlayback() {
        return new StatePackets.PlaybackState(StatePackets.PlaybackStatus.IDLE, "", "", "", true,
                0L, 0L, 0L, false, StatePackets.PlaybackOrigin.NONE, "",
                Collections.<StatePackets.QueueEntry>emptyList());
    }
    private static StatePackets.StationState emptyStation() {
        return new StatePackets.StationState(StationModels.StationType.NONE, false, false, 0L,
                StationModels.SonicCapability.CHECKING, "Checking Plex sonic capability", "",
                Collections.<StationModels.StationSeed>emptyList(), Collections.<StatePackets.QueueEntry>emptyList());
    }

    private LegacyClientState() {}
}
