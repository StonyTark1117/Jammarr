package stonytark.jammarr.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class JammarrClientState {
    public static final JammarrClientState INSTANCE = new JammarrClientState();
    private final ClockSynchronizer clock = new ClockSynchronizer();
    private final JammarrAudioPlayer audio = new JammarrAudioPlayer(clock);
    private final AtomicLong timeNonce = new AtomicLong();
    private JammarrPayloads.PlaybackState playback = new JammarrPayloads.PlaybackState(JammarrPayloads.PlaybackStatus.IDLE, "", "", "", true, 0, 0, 0, false, List.of());
    private JammarrPayloads.BrowseResults browse = new JammarrPayloads.BrowseResults(JammarrPayloads.BrowseKind.SEARCH, "", 0, false, List.of());
    private long lastTimeSync;
    private String notice = "";

    public void accept(CustomPacketPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload instanceof JammarrPayloads.OpenScreen) {
            minecraft.setScreen(new JammarrScreen(this));
            PacketDistributor.sendToServer(new JammarrPayloads.BrowseRequest(JammarrPayloads.BrowseKind.SEARCH, "", 0));
        } else if (payload instanceof JammarrPayloads.ServerHello value) {
            if (value.protocolVersion() != JammarrNetwork.PROTOCOL && minecraft.getConnection() != null) {
                minecraft.getConnection().getConnection().disconnect(net.minecraft.network.chat.Component.literal("Jammarr protocol mismatch"));
            } else requestTimeSync();
        } else if (payload instanceof JammarrPayloads.TimeSyncResponse value) {
            clock.accept(value.clientSentEpochMs(), value.serverEpochMs(), System.currentTimeMillis());
        } else if (payload instanceof JammarrPayloads.BrowseResults value) {
            browse = value; refreshScreen(minecraft);
        } else if (payload instanceof JammarrPayloads.PlaybackState value) {
            playback = value;
            if (value.serverEpochMs() > 0 && !clock.initialized()) clock.accept(System.currentTimeMillis(), value.serverEpochMs(), System.currentTimeMillis());
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
    public String notice() { return notice; }
    public String audioStatus() { return audio.status(); }
    public AudioPlaybackState audioState() { return audio.state(); }
    public void clearNotice() { notice = ""; }
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastTimeSync >= 10_000) requestTimeSync();
        audio.tick();
    }
    public void hello() { PacketDistributor.sendToServer(new JammarrPayloads.ClientHello(JammarrNetwork.PROTOCOL)); requestTimeSync(); }
    public void ensureAudio() { audio.ensureStarted(); }
    public void listeningChanged() { audio.listeningChanged(); }
    public void retryAudio() { audio.retry(); }
    public void audioEngineReloaded() { audio.audioEngineReloaded(); }
    public void stop() {
        audio.stop(); clock.reset(); notice = ""; lastTimeSync = 0;
        playback = new JammarrPayloads.PlaybackState(JammarrPayloads.PlaybackStatus.IDLE, "", "", "", true, 0, 0, 0, false, List.of());
    }

    private void requestTimeSync() {
        if (Minecraft.getInstance().getConnection() == null) return;
        long now = System.currentTimeMillis();
        PacketDistributor.sendToServer(new JammarrPayloads.TimeSyncRequest(timeNonce.incrementAndGet(), now));
        lastTimeSync = now;
    }
    private static void refreshScreen(Minecraft minecraft) { if (minecraft.screen instanceof JammarrScreen screen) screen.resultsChanged(); }
    private JammarrClientState() {}
}
