package stonytark.pampmod.client;

import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.PacketDistributor;
import stonytark.pampmod.config.PampConfig;
import stonytark.pampmod.mixin.client.SoundEngineAccessor;
import stonytark.pampmod.mixin.client.SoundManagerAccessor;
import stonytark.pampmod.network.Hashing;
import stonytark.pampmod.network.PampPayloads;

import java.util.UUID;

public final class PampAudioPlayer {
    private static final long START_BUFFER_MS = 5_000;
    private static final long DRIFT_REBUFFER_MS = 500;
    private static final long UNDERRUN_GRACE_MS = 1_500;

    private final ClockSynchronizer clock;
    private PampPayloads.AudioManifest manifest;
    private StreamingMp3Decoder decoder;
    private ChunkWindowTracker window;
    private ChannelAccess.ChannelHandle channel;
    private long firstChunkStartMs = -1;
    private long channelStartedLocalMs;
    private long channelStartedPositionMs;
    private long lastAudioDataMs;
    private long lastCorrectionMs;
    private boolean started;
    private final AsyncStartGuard channelStarts = new AsyncStartGuard();

    public PampAudioPlayer(ClockSynchronizer clock) { this.clock = clock; }

    public void manifest(PampPayloads.AudioManifest value) {
        if (value.totalChunks() == 0 || value.sessionId().equals(new UUID(0, 0))) { stop(); return; }
        if (manifest == null || !manifest.sessionId().equals(value.sessionId())) {
            resetAudio(); manifest = value;
            if (PampConfig.ENABLED.get()) beginStreaming();
        } else {
            boolean timelineChanged = value.firstChunk() != manifest.firstChunk() || Math.abs(value.startedAtEpochMs() - manifest.startedAtEpochMs()) > DRIFT_REBUFFER_MS;
            manifest = value;
            if (timelineChanged && PampConfig.ENABLED.get()) rebuffer();
            else if (decoder == null && PampConfig.ENABLED.get()) beginStreaming();
        }
        if (channel != null) channel.execute(c -> { if (value.paused()) c.pause(); else c.unpause(); });
    }

    public void chunk(PampPayloads.AudioChunk value) {
        if (manifest == null || decoder == null || window == null || !manifest.sessionId().equals(value.sessionId())) return;
        if (!Hashing.matchesSha256(value.data(), value.sha256())) {
            window.reject(value.requestId());
            return;
        }
        if (firstChunkStartMs < 0 && value.index() == manifest.firstChunk()) firstChunkStartMs = value.startMs();
        if (!decoder.offer(value.index(), value.data())) {
            window.reject(value.requestId());
            return;
        }
        lastAudioDataMs = System.currentTimeMillis();
        window.received(value.requestId(), value.index()).ifPresent(ack -> PacketDistributor.sendToServer(
                new PampPayloads.ChunkAcknowledgement(manifest.sessionId(), ack.requestId(), ack.receivedThroughIndex(), decoder.bufferedMillis())));
    }

    public void tick() {
        if (manifest == null || decoder == null || window == null) return;
        long now = System.currentTimeMillis();
        window.request(now, decoder.bufferedMillis(), StreamingMp3Decoder.MAX_BUFFERED_MS).ifPresent(request -> PacketDistributor.sendToServer(
                new PampPayloads.ChunkRequest(manifest.sessionId(), request.id(), request.startIndex(), request.count())));
        Minecraft minecraft = Minecraft.getInstance();
        if (PampConfig.ENABLED.get()) minecraft.getMusicManager().stopPlaying();
        long localStart = clock.toLocalTime(manifest.startedAtEpochMs() + Math.max(0, firstChunkStartMs));
        if (!started && !channelStarts.pending() && !manifest.paused() && PampConfig.ENABLED.get() && decoder.format() != null && decoder.bufferedMillis() >= START_BUFFER_MS && firstChunkStartMs >= 0 && now >= localStart) {
            startChannel(now);
        }
        if (channel != null) {
            float volume = PampConfig.ENABLED.get() ? (float)(PampConfig.VOLUME.get() * minecraft.options.getSoundSourceVolume(SoundSource.MUSIC)) : 0;
            channel.execute(c -> c.setVolume(volume));
            if (channel.isStopped()) {
                if (!window.complete() && now - lastAudioDataMs > UNDERRUN_GRACE_MS) requestRebuffer();
                else stop();
                return;
            }
            if (!manifest.paused() && now - lastCorrectionMs >= 2_000) {
                long estimatedPosition = channelStartedPositionMs + Math.max(0, now - channelStartedLocalMs);
                long authoritativePosition = Math.max(0, clock.toServerTime(now) - manifest.startedAtEpochMs());
                if (DriftPolicy.shouldRebuffer(estimatedPosition, authoritativePosition, DRIFT_REBUFFER_MS)) requestRebuffer();
                lastCorrectionMs = now;
            }
        }
    }

    public void ensureStarted() { if (manifest != null && decoder == null && PampConfig.ENABLED.get()) beginStreaming(); }
    public void listeningChanged() { if (!PampConfig.ENABLED.get()) resetAudio(); else ensureStarted(); }
    public boolean active() { return manifest != null && PampConfig.ENABLED.get(); }
    public String status() {
        if (!PampConfig.ENABLED.get()) return "Listening disabled locally";
        if (manifest == null) return "No active audio stream";
        if (manifest.paused()) return "Paused";
        if (!started) return "Buffering " + (decoder == null ? 0 : Math.min(100, decoder.bufferedMillis() * 100 / START_BUFFER_MS)) + "%";
        return "Playing";
    }

    private void beginStreaming() {
        firstChunkStartMs = -1;
        decoder = new StreamingMp3Decoder(manifest.firstChunk(), manifest.totalChunks());
        window = new ChunkWindowTracker(manifest.firstChunk(), manifest.totalChunks(), 8, 1_500);
        lastAudioDataMs = System.currentTimeMillis();
    }

    private void startChannel(long now) {
        long startToken = channelStarts.begin();
        if (startToken < 0) return;
        StreamingMp3Decoder startingDecoder = decoder;
        UUID startingSession = manifest.sessionId();
        long startingPosition = Math.max(0, firstChunkStartMs);
        ChannelAccess access = ((SoundEngineAccessor)((SoundManagerAccessor)(Object)Minecraft.getInstance().getSoundManager()).pampmod$soundEngine()).pampmod$channelAccess();
        access.createHandle(Library.Pool.STREAMING).whenComplete((handle, error) -> {
            boolean current = decoder == startingDecoder && manifest != null && manifest.sessionId().equals(startingSession);
            if (!current || !channelStarts.complete(startToken)) {
                if (handle != null) handle.execute(com.mojang.blaze3d.audio.Channel::stop);
                return;
            }
            if (error != null || handle == null) {
                requestRebuffer();
                return;
            }
            if (decoder != startingDecoder || manifest == null || !manifest.sessionId().equals(startingSession)) {
                handle.execute(com.mojang.blaze3d.audio.Channel::stop);
                return;
            }
            channel = handle;
            started = true;
            channelStartedLocalMs = now;
            channelStartedPositionMs = startingPosition;
            handle.execute(value -> {
                value.disableAttenuation(); value.setRelative(true); value.setVolume(0);
                value.attachBufferStream(new PcmAudioStream(startingDecoder)); value.play();
            });
        });
    }

    private void requestRebuffer() {
        resetAudio();
        PacketDistributor.sendToServer(new PampPayloads.ManifestRequest(true));
    }

    private void rebuffer() { resetAudio(); beginStreaming(); }
    public void stop() { resetAudio(); manifest = null; }
    public void audioEngineReloaded() { if (manifest != null) { resetAudio(); if (PampConfig.ENABLED.get()) PacketDistributor.sendToServer(new PampPayloads.ManifestRequest(true)); } }

    private void resetAudio() {
        channelStarts.cancel();
        if (channel != null) {
            if (!channel.isStopped()) channel.execute(com.mojang.blaze3d.audio.Channel::stop);
            channel = null;
        }
        if (decoder != null) { decoder.close(); decoder = null; }
        window = null;
        started = false;
        firstChunkStartMs = -1;
        channelStartedLocalMs = 0;
        channelStartedPositionMs = 0;
    }
}
