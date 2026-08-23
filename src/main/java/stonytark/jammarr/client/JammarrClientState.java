package stonytark.jammarr.client;

import stonytark.jammarr.core.client.ClockSynchronizer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
    private JammarrPayloads.PlaybackState playback = new JammarrPayloads.PlaybackState(JammarrPayloads.PlaybackStatus.IDLE, "", "", "", true, 0, 0, 0, false, List.of());
    private JammarrPayloads.StationState station = new JammarrPayloads.StationState(JammarrPayloads.StationType.NONE, false, false, 0,
            JammarrPayloads.SonicCapability.CHECKING, "Checking Plex sonic capability", "", List.of(), List.of());
    private JammarrPayloads.AdventurePreview adventurePreview = new JammarrPayloads.AdventurePreview(0, "", List.of());
    private JammarrPayloads.BrowseResults browse = new JammarrPayloads.BrowseResults(JammarrPayloads.BrowseKind.SEARCH, "", 0, false, List.of());
    private long lastTimeSync;
    private String notice = "";

    public void accept(CustomPacketPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload instanceof JammarrPayloads.OpenScreen) {
            minecraft.setScreen(new JammarrScreen(this));
            JammarrNetwork.sendToServer(new JammarrPayloads.BrowseRequest(JammarrPayloads.BrowseKind.SEARCH, "", 0));
        } else if (payload instanceof JammarrPayloads.ServerHello value) {
            if (!JammarrNetwork.protocolMatches(value.protocolVersion()) && minecraft.getConnection() != null) {
                minecraft.getConnection().getConnection().disconnect(net.minecraft.network.chat.Component.literal("Jammarr protocol mismatch"));
            } else requestTimeSync();
        } else if (payload instanceof JammarrPayloads.TimeSyncResponse value) {
            clock.accept(value.clientSentEpochMs(), value.serverEpochMs(), System.currentTimeMillis());
        } else if (payload instanceof JammarrPayloads.BrowseResults value) {
            browse = value; refreshScreen(minecraft);
        } else if (payload instanceof JammarrPayloads.PlaybackState value) {
            playback = value;
            if (value.serverEpochMs() > 0 && !clock.initialized()) clock.accept(System.currentTimeMillis(), value.serverEpochMs(), System.currentTimeMillis());
            boolean queueBrowseChanged = refreshQueueBrowse();
            if (minecraft.screen instanceof JammarrScreen screen) {
                screen.playbackChanged();
                if (queueBrowseChanged) screen.queueChanged();
            }
        } else if (payload instanceof JammarrPayloads.StationState value) {
            station = value;
            if (minecraft.screen instanceof JammarrScreen screen) screen.stationChanged();
        } else if (payload instanceof JammarrPayloads.AdventurePreview value) {
            adventurePreview = value;
            if (minecraft.screen instanceof JammarrScreen screen) screen.adventurePreviewChanged();
        } else if (payload instanceof JammarrPayloads.AudioManifest value) {
            audio.manifest(value);
        } else if (payload instanceof JammarrPayloads.AudioChunk value) {
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
        int page = browse.page();
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
        long now = System.currentTimeMillis();
        if (now - lastTimeSync >= 10_000) requestTimeSync();
        audio.tick();
    }
    public void hello() { JammarrNetwork.sendToServer(new JammarrPayloads.ClientHello(JammarrNetwork.PROTOCOL)); requestTimeSync(); }
    public void ensureAudio() { audio.ensureStarted(); }
    public void listeningChanged() { audio.listeningChanged(); }
    public void retryAudio() { audio.retry(); refreshScreen(Minecraft.getInstance()); }
    public void audioEngineReloaded() { audio.audioEngineReloaded(); }
    public void stop() {
        audio.stop(); clock.reset(); notice = ""; lastTimeSync = 0;
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
    private static void refreshScreen(Minecraft minecraft) { if (minecraft.screen instanceof JammarrScreen screen) screen.resultsChanged(); }
    private JammarrClientState() {}
}
