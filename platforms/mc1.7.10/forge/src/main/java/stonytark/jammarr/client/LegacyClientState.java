package stonytark.jammarr.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.client.ClockSynchronizer;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.core.protocol.TransportPackets;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.network.LegacyPacketTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class LegacyClientState implements LegacyNetwork.ClientListener {
    static final LegacyClientState INSTANCE = new LegacyClientState();
    private static final int PAGE_SIZE = 20;
    private final ClockSynchronizer clock = new ClockSynchronizer();
    private final LegacyAudioPlayer audio = new LegacyAudioPlayer(clock);
    private final AtomicLong timeNonce = new AtomicLong();
    private StatePackets.PlaybackState playback = emptyPlayback();
    private StatePackets.StationState station = emptyStation();
    private StatePackets.AdventurePreview adventure = new StatePackets.AdventurePreview(
            0L, "", Collections.<StatePackets.QueueEntry>emptyList());
    private ControlPackets.BrowseResults browse = new ControlPackets.BrowseResults(
            ControlPackets.BrowseKind.SEARCH, "", 0, false, Collections.<StationModels.MediaItem>emptyList());
    private boolean helloSent;
    private long lastTimeSync;
    private String notice = "";

    @Override public void accept(LegacyPacketTypes.Type<?> type, Object message) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (type == LegacyPacketTypes.OPEN_SCREEN) {
            minecraft.displayGuiScreen(new LegacyScreen(this));
        } else if (type == LegacyPacketTypes.SERVER_HELLO) {
            ControlPackets.ServerHello hello = (ControlPackets.ServerHello) message;
            if (hello.protocolVersion() != Jammarr.PROTOCOL && minecraft.getNetHandler() != null) {
                minecraft.getNetHandler().getNetworkManager().closeChannel(
                        new ChatComponentText("Jammarr protocol mismatch"));
            } else requestTimeSync();
        } else if (type == LegacyPacketTypes.TIME_SYNC_RESPONSE) {
            ControlPackets.TimeSyncResponse response = (ControlPackets.TimeSyncResponse) message;
            clock.accept(response.clientSentEpochMs(), response.serverEpochMs(), System.currentTimeMillis());
        } else if (type == LegacyPacketTypes.BROWSE_RESULTS) {
            browse = (ControlPackets.BrowseResults) message;
            screenResultsChanged();
        } else if (type == LegacyPacketTypes.PLAYBACK_STATE) {
            playback = (StatePackets.PlaybackState) message;
            if (playback.serverEpochMs() > 0L && !clock.initialized()) {
                long now = System.currentTimeMillis();
                clock.accept(now, playback.serverEpochMs(), now);
            }
            refreshQueueBrowse(); screenChanged();
        } else if (type == LegacyPacketTypes.STATION_STATE) {
            station = (StatePackets.StationState) message; screenChanged();
        } else if (type == LegacyPacketTypes.ADVENTURE_PREVIEW) {
            adventure = (StatePackets.AdventurePreview) message; screenChanged();
        } else if (type == LegacyPacketTypes.AUDIO_MANIFEST) {
            audio.manifest((TransportPackets.AudioManifest) message);
        } else if (type == LegacyPacketTypes.AUDIO_CHUNK) {
            audio.chunk((TransportPackets.AudioChunk) message);
        } else if (type == LegacyPacketTypes.ERROR) {
            notice = ((StatePackets.ErrorMessage) message).message();
            if (minecraft.thePlayer != null) minecraft.thePlayer.addChatMessage(
                    new ChatComponentText("Jammarr: " + notice));
            LegacyScreen screen = screen();
            if (screen != null) screen.requestFailed();
        }
    }

    void tick() {
        if (!helloSent) {
            hello();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTimeSync >= 10_000L) requestTimeSync();
        audio.tick();
    }

    private void hello() {
        if (ProtocolLimits.clientHelloSuppressed()) return;
        LegacyNetwork.sendToServer(LegacyPacketTypes.CLIENT_HELLO, new ControlPackets.ClientHello(ProtocolLimits.clientHelloVersion()));
        helloSent = true;
    }

    void stop() {
        audio.stop(); clock.reset(); notice = ""; helloSent = false; lastTimeSync = 0L;
        playback = emptyPlayback(); station = emptyStation();
        browse = new ControlPackets.BrowseResults(ControlPackets.BrowseKind.SEARCH, "", 0,
                false, Collections.<StationModels.MediaItem>emptyList());
        adventure = new StatePackets.AdventurePreview(0L, "", Collections.<StatePackets.QueueEntry>emptyList());
    }

    StatePackets.PlaybackState playback() { return playback; }
    StatePackets.StationState station() { return station; }
    StatePackets.AdventurePreview adventure() { return adventure; }
    ControlPackets.BrowseResults browse() { return browse; }
    String notice() { return notice; }
    void clearNotice() { notice = ""; }
    String audioStatus() { return audio.status(); }
    String audioState() { return audio.state(); }
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
        if (Minecraft.getMinecraft().getNetHandler() == null) return;
        long now = System.currentTimeMillis();
        LegacyNetwork.sendToServer(LegacyPacketTypes.TIME_SYNC_REQUEST,
                new ControlPackets.TimeSyncRequest(timeNonce.incrementAndGet(), now));
        lastTimeSync = now;
    }

    private LegacyScreen screen() {
        return Minecraft.getMinecraft().currentScreen instanceof LegacyScreen
                ? (LegacyScreen) Minecraft.getMinecraft().currentScreen : null;
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
