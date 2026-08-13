package stonytark.pampmod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import stonytark.pampmod.network.PampNetwork;
import stonytark.pampmod.network.PampPayloads;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class PampClientState {
    public static final PampClientState INSTANCE = new PampClientState();
    private final ClockSynchronizer clock = new ClockSynchronizer();
    private final PampAudioPlayer audio = new PampAudioPlayer(clock);
    private final AtomicLong timeNonce = new AtomicLong();
    private PampPayloads.PlaybackState playback = new PampPayloads.PlaybackState(PampPayloads.PlaybackStatus.IDLE, "", "", "", true, 0, 0, 0, false, List.of());
    private PampPayloads.BrowseResults browse = new PampPayloads.BrowseResults(PampPayloads.BrowseKind.SEARCH, "", 0, false, List.of());
    private long lastTimeSync;
    private String notice = "";

    public void accept(CustomPacketPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload instanceof PampPayloads.OpenScreen) {
            minecraft.setScreen(new PampScreen(this));
            PacketDistributor.sendToServer(new PampPayloads.BrowseRequest(PampPayloads.BrowseKind.SEARCH, "", 0));
        } else if (payload instanceof PampPayloads.ServerHello value) {
            if (value.protocolVersion() != PampNetwork.PROTOCOL && minecraft.getConnection() != null) {
                minecraft.getConnection().getConnection().disconnect(net.minecraft.network.chat.Component.literal("PAmpMod protocol mismatch"));
            } else requestTimeSync();
        } else if (payload instanceof PampPayloads.TimeSyncResponse value) {
            clock.accept(value.clientSentEpochMs(), value.serverEpochMs(), System.currentTimeMillis());
        } else if (payload instanceof PampPayloads.BrowseResults value) {
            browse = value; refreshScreen(minecraft);
        } else if (payload instanceof PampPayloads.PlaybackState value) {
            playback = value;
            if (value.serverEpochMs() > 0 && !clock.initialized()) clock.accept(System.currentTimeMillis(), value.serverEpochMs(), System.currentTimeMillis());
            refreshScreen(minecraft);
        } else if (payload instanceof PampPayloads.AudioManifest value) {
            audio.manifest(value);
        } else if (payload instanceof PampPayloads.AudioChunk value) {
            audio.chunk(value);
        } else if (payload instanceof PampPayloads.ErrorMessage value) {
            notice = value.message();
            if (minecraft.player != null) minecraft.player.displayClientMessage(net.minecraft.network.chat.Component.literal("PAmpMod: " + value.message()), false);
            refreshScreen(minecraft);
        }
    }

    public PampPayloads.PlaybackState playback() { return playback; }
    public PampPayloads.BrowseResults browse() { return browse; }
    public String notice() { return notice; }
    public String audioStatus() { return audio.status(); }
    public void clearNotice() { notice = ""; }
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastTimeSync >= 10_000) requestTimeSync();
        audio.tick();
    }
    public void hello() { PacketDistributor.sendToServer(new PampPayloads.ClientHello(PampNetwork.PROTOCOL)); requestTimeSync(); }
    public void ensureAudio() { audio.ensureStarted(); }
    public void listeningChanged() { audio.listeningChanged(); }
    public void audioEngineReloaded() { audio.audioEngineReloaded(); }
    public void stop() {
        audio.stop(); clock.reset(); notice = ""; lastTimeSync = 0;
        playback = new PampPayloads.PlaybackState(PampPayloads.PlaybackStatus.IDLE, "", "", "", true, 0, 0, 0, false, List.of());
    }

    private void requestTimeSync() {
        if (Minecraft.getInstance().getConnection() == null) return;
        long now = System.currentTimeMillis();
        PacketDistributor.sendToServer(new PampPayloads.TimeSyncRequest(timeNonce.incrementAndGet(), now));
        lastTimeSync = now;
    }
    private static void refreshScreen(Minecraft minecraft) { if (minecraft.screen instanceof PampScreen screen) screen.resultsChanged(); }
    private PampClientState() {}
}
