package stonytark.jammarr.client;

import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.PacketDistributor;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.config.JammarrConfig;
import stonytark.jammarr.mixin.client.SoundEngineAccessor;
import stonytark.jammarr.mixin.client.SoundManagerAccessor;
import stonytark.jammarr.network.Hashing;
import stonytark.jammarr.network.JammarrPayloads;

import java.util.UUID;

public final class JammarrAudioPlayer {
    private static final long START_BUFFER_MS = 5_000;
    private static final long DRIFT_REBUFFER_MS = 500;
    private static final long UNDERRUN_GRACE_MS = 1_500;
    private static final int MAX_RECOVERY_ATTEMPTS = 3;

    private final ClockSynchronizer clock;
    private JammarrPayloads.AudioManifest manifest;
    private StreamingMp3Decoder decoder;
    private ChunkWindowTracker window;
    private ChannelAccess.ChannelHandle channel;
    private long firstChunkStartMs = -1;
    private long channelStartedLocalMs;
    private long channelStartedPositionMs;
    private long lastAudioDataMs;
    private long lastCorrectionMs;
    private long lastRecoveryMs;
    private long lastHealthSentMs;
    private String lastHealthState = "";
    private int recoveryAttempts;
    private boolean recovering;
    private boolean recoveryFailed;
    private int receivedChunks;
    private int underruns;
    private boolean started;
    private final AsyncStartGuard channelStarts = new AsyncStartGuard();

    public JammarrAudioPlayer(ClockSynchronizer clock) { this.clock = clock; }

    public void manifest(JammarrPayloads.AudioManifest value) {
        if (value.totalChunks() == 0 || value.sessionId().equals(new UUID(0, 0))) { stop(); return; }
        if (manifest == null || !manifest.sessionId().equals(value.sessionId())) {
            resetAudio(); manifest = value; recoveryAttempts = 0; underruns = 0; lastHealthSentMs = 0; lastHealthState = ""; recoveryFailed = false;
            if (JammarrConfig.ENABLED.get()) beginStreaming();
        } else {
            boolean timelineChanged = value.firstChunk() != manifest.firstChunk() || Math.abs(value.startedAtEpochMs() - manifest.startedAtEpochMs()) > DRIFT_REBUFFER_MS;
            manifest = value;
            if (timelineChanged && JammarrConfig.ENABLED.get()) rebuffer();
            else if (decoder == null && JammarrConfig.ENABLED.get()) beginStreaming();
        }
        if (channel != null) channel.execute(c -> { if (value.paused()) c.pause(); else c.unpause(); });
    }

    public void chunk(JammarrPayloads.AudioChunk value) {
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
        if (receivedChunks++ == 0) Jammarr.LOGGER.info("Jammarr received the first audio chunk");
        lastAudioDataMs = System.currentTimeMillis();
        window.received(value.requestId(), value.index()).ifPresent(ack -> PacketDistributor.sendToServer(
                new JammarrPayloads.ChunkAcknowledgement(manifest.sessionId(), ack.requestId(), ack.receivedThroughIndex(), decoder.bufferedMillis())));
    }

    public void tick() {
        long now = System.currentTimeMillis();
        if (manifest == null) return;
        if (decoder == null || window == null) {
            sendHealthIfNeeded(now);
            return;
        }
        if (decoder.failure() != null && decoder.format() == null && now - lastRecoveryMs >= 2_000) {
            requestRebuffer("decoder failure");
            return;
        }
        window.request(now, decoder.bufferedMillis(), StreamingMp3Decoder.MAX_BUFFERED_MS).ifPresent(request -> {
            if (request.id() == 1) Jammarr.LOGGER.info("Jammarr requested the initial audio chunk window");
            PacketDistributor.sendToServer(new JammarrPayloads.ChunkRequest(manifest.sessionId(), request.id(), request.startIndex(), request.count()));
        });
        Minecraft minecraft = Minecraft.getInstance();
        if (JammarrConfig.ENABLED.get()) minecraft.getMusicManager().stopPlaying();
        long localStart = clock.toLocalTime(manifest.startedAtEpochMs() + Math.max(0, firstChunkStartMs));
        if (!started && !channelStarts.pending() && !manifest.paused() && JammarrConfig.ENABLED.get() && decoder.format() != null && decoder.bufferedMillis() >= START_BUFFER_MS && firstChunkStartMs >= 0 && now >= localStart) {
            startChannel(now);
        }
        if (channel != null) {
            float volume = JammarrConfig.ENABLED.get() ? (float)(JammarrConfig.VOLUME.get() * minecraft.options.getSoundSourceVolume(SoundSource.MUSIC)) : 0;
            channel.execute(c -> c.setVolume(volume));
            if (started && !manifest.paused() && decoder.bufferedMillis() == 0 && !window.complete() && now - lastAudioDataMs > UNDERRUN_GRACE_MS) {
                underruns++;
                requestRebuffer("decoder starvation");
                return;
            }
            if (channel.isStopped()) {
                if (!window.complete() && now - lastAudioDataMs > UNDERRUN_GRACE_MS) {
                    underruns++;
                    requestRebuffer("audio underrun");
                }
                else stop();
                return;
            }
            if (!manifest.paused() && now - lastCorrectionMs >= 2_000) {
                long estimatedPosition = channelStartedPositionMs + Math.max(0, now - channelStartedLocalMs);
                long authoritativePosition = Math.max(0, clock.toServerTime(now) - manifest.startedAtEpochMs());
                if (DriftPolicy.shouldRebuffer(estimatedPosition, authoritativePosition, DRIFT_REBUFFER_MS)) requestRebuffer("clock drift");
                lastCorrectionMs = now;
            }
        }
        sendHealthIfNeeded(now);
    }

    public void ensureStarted() { if (manifest != null && decoder == null && JammarrConfig.ENABLED.get()) beginStreaming(); }
    public void listeningChanged() { if (!JammarrConfig.ENABLED.get()) resetAudio(); else ensureStarted(); }
    public boolean active() { return manifest != null && JammarrConfig.ENABLED.get(); }
    public AudioPlaybackState state() {
        if (!JammarrConfig.ENABLED.get()) return AudioPlaybackState.DISABLED;
        if (manifest == null) return AudioPlaybackState.NO_STREAM;
        if (recoveryFailed) return AudioPlaybackState.ERROR;
        if (manifest.paused()) return AudioPlaybackState.PAUSED;
        if (recovering) return AudioPlaybackState.RECOVERING;
        if (decoder != null && decoder.failure() != null && decoder.format() == null) return AudioPlaybackState.ERROR;
        return started ? AudioPlaybackState.PLAYING : AudioPlaybackState.BUFFERING;
    }
    public String status() {
        return switch (state()) {
            case DISABLED -> "Listening disabled locally";
            case NO_STREAM -> "No active audio stream";
            case PAUSED -> "Paused";
            case RECOVERING -> "Recovering audio…";
            case ERROR -> "Audio needs attention; retry";
            case BUFFERING -> "Buffering " + (decoder == null ? 0 : Math.min(100, decoder.bufferedMillis() * 100 / START_BUFFER_MS)) + "%";
            case PLAYING -> "Playing";
        };
    }

    public void retry() {
        if (manifest == null || !JammarrConfig.ENABLED.get()) return;
        recoveryAttempts = 0;
        recoveryFailed = false;
        requestRebuffer("manual retry");
    }

    private void beginStreaming() {
        firstChunkStartMs = -1;
        decoder = new StreamingMp3Decoder(manifest.firstChunk(), manifest.totalChunks());
        window = new ChunkWindowTracker(manifest.firstChunk(), manifest.totalChunks(), 8, 1_500);
        lastAudioDataMs = System.currentTimeMillis();
        lastRecoveryMs = 0;
        receivedChunks = 0;
        recovering = false;
    }

    private void startChannel(long now) {
        long startToken = channelStarts.begin();
        if (startToken < 0) return;
        StreamingMp3Decoder startingDecoder = decoder;
        UUID startingSession = manifest.sessionId();
        long startingPosition = Math.max(0, firstChunkStartMs);
        ChannelAccess access = ((SoundEngineAccessor)((SoundManagerAccessor)(Object)Minecraft.getInstance().getSoundManager()).jammarr$soundEngine()).jammarr$channelAccess();
        access.createHandle(Library.Pool.STREAMING).whenComplete((handle, error) -> {
            boolean current = decoder == startingDecoder && manifest != null && manifest.sessionId().equals(startingSession);
            if (!current || !channelStarts.complete(startToken)) {
                if (handle != null) handle.execute(com.mojang.blaze3d.audio.Channel::stop);
                return;
            }
            if (error != null || handle == null) {
                requestRebuffer("audio channel creation");
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

    private void requestRebuffer(String reason) {
        if (manifest == null || recoveryFailed) return;
        if (++recoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
            recoveryFailed = true;
            recovering = false;
            resetAudio();
            return;
        }
        lastRecoveryMs = System.currentTimeMillis();
        recovering = true;
        Jammarr.LOGGER.warn("Jammarr audio recovery attempt {}/{}: {}", recoveryAttempts, MAX_RECOVERY_ATTEMPTS, reason);
        resetAudio();
        PacketDistributor.sendToServer(new JammarrPayloads.ManifestRequest(true));
    }

    private void rebuffer() { resetAudio(); beginStreaming(); }
    public void stop() { resetAudio(); manifest = null; recoveryAttempts = 0; underruns = 0; recovering = false; recoveryFailed = false; lastHealthSentMs = 0; lastHealthState = ""; }
    public void audioEngineReloaded() { if (manifest != null) { resetAudio(); if (JammarrConfig.ENABLED.get()) { recovering = true; PacketDistributor.sendToServer(new JammarrPayloads.ManifestRequest(true)); } } }

    private void sendHealthIfNeeded(long now) {
        if (manifest == null) return;
        String state = state().name();
        if (!state.equals(lastHealthState) || now - lastHealthSentMs >= 5_000) {
            long buffered = decoder == null ? 0 : decoder.bufferedMillis();
            PacketDistributor.sendToServer(new JammarrPayloads.AudioHealth(manifest.sessionId(), state, recoveryAttempts, underruns, receivedChunks, buffered));
            lastHealthState = state;
            lastHealthSentMs = now;
        }
    }

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
