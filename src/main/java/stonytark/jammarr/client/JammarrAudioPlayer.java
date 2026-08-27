package stonytark.jammarr.client;

import stonytark.jammarr.core.client.ChunkWindowTracker;
import stonytark.jammarr.core.client.ClockSynchronizer;
import stonytark.jammarr.core.client.DriftPolicy;
import stonytark.jammarr.core.client.AsyncStartGuard;
import stonytark.jammarr.core.network.Hashing;
import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundSource;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.network.JammarrPayloads;
import stonytark.jammarr.network.JammarrNetwork;
import java.lang.reflect.Field;
import java.util.UUID;

public final class JammarrAudioPlayer {
    private static final long START_BUFFER_MS = 5_000;
    private static final long DRIFT_REBUFFER_MS = 500;
    private static final long UNDERRUN_GRACE_MS = 1_500;
    private static final long MISSING_MANIFEST_RETRY_MS = 2_000;
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
    private long lastMissingManifestRequestMs;
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
        // Queue pause before a checkpoint change tears down the old channel.
        // Otherwise resetAudio wins the race and a small tail can escape after
        // the shared state has already become PAUSED.
        if (value.paused() && channel != null) {
            channel.execute(com.mojang.blaze3d.audio.Channel::pause);
        }
        if (manifest == null || !manifest.sessionId().equals(value.sessionId())) {
            resetAudio(); manifest = value; recoveryAttempts = 0; underruns = 0; lastHealthSentMs = 0; lastHealthState = ""; recoveryFailed = false;
            if (JammarrSettings.enabled()) beginStreaming();
        } else {
            boolean timelineChanged = value.firstChunk() != manifest.firstChunk() || Math.abs(value.startedAtEpochMs() - manifest.startedAtEpochMs()) > DRIFT_REBUFFER_MS;
            manifest = value;
            if (timelineChanged && JammarrSettings.enabled() && !recoveryFailed) rebuffer();
            else if (decoder == null && JammarrSettings.enabled() && !recoveryFailed) beginStreaming();
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
        if (ProtocolLimits.audioProbeEnabled() && (value.index() == manifest.firstChunk() || value.index() % 8 == 0)) {
            Jammarr.LOGGER.info("Acceptance audio chunk: index={} request={} bufferedMs={}",
                    value.index(), value.requestId(), decoder.bufferedMillis());
        }
        lastAudioDataMs = System.currentTimeMillis();
        window.received(value.requestId(), value.index()).ifPresent(ack -> JammarrNetwork.sendToServer(
                new JammarrPayloads.ChunkAcknowledgement(manifest.sessionId(), ack.requestId(), ack.receivedThroughIndex(), decoder.bufferedMillis())));
    }

    public void tick() {
        long now = System.currentTimeMillis();
        if (manifest == null) return;
        if (decoder == null || window == null) {
            // A recovery request can race another in-flight manifest response,
            // or be delayed while a loaded integrated/dedicated server catches
            // up. Do not leave the client in RECOVERING forever after one lost
            // response; retry at a bounded cadence until streaming restarts.
            if (recovering && !recoveryFailed && JammarrSettings.enabled()
                    && now - lastMissingManifestRequestMs >= MISSING_MANIFEST_RETRY_MS) {
                requestManifest();
            }
            sendHealthIfNeeded(now);
            return;
        }
        if (decoder.failure() != null && decoder.format() == null && now - lastRecoveryMs >= 2_000) {
            requestRebuffer("decoder failure");
            return;
        }
        window.request(now, decoder.bufferedMillis(), StreamingMp3Decoder.MAX_BUFFERED_MS).ifPresent(request -> {
            if (request.id() == 1) Jammarr.LOGGER.info("Jammarr requested the initial audio chunk window");
            if (ProtocolLimits.audioProbeEnabled()) Jammarr.LOGGER.info(
                    "Acceptance chunk request: id={} start={} count={} bufferedMs={}",
                    request.id(), request.startIndex(), request.count(), decoder.bufferedMillis());
            JammarrNetwork.sendToServer(new JammarrPayloads.ChunkRequest(manifest.sessionId(), request.id(), request.startIndex(), request.count()));
        });
        Minecraft minecraft = Minecraft.getInstance();
        if (JammarrSettings.enabled()) minecraft.getMusicManager().stopPlaying();
        long localStart = clock.toLocalTime(manifest.startedAtEpochMs() + Math.max(0, firstChunkStartMs));
        if (!started && !channelStarts.pending() && !manifest.paused() && JammarrSettings.enabled() && decoder.format() != null && decoder.bufferedMillis() >= START_BUFFER_MS && firstChunkStartMs >= 0 && now >= localStart) {
            startChannel(now);
        }
        if (channel != null) {
            float volume = JammarrSettings.enabled() ? (float)(JammarrSettings.volume() * minecraft.options.getSoundSourceVolume(SoundSource.MUSIC)) : 0;
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

    public void ensureStarted() { if (manifest != null && decoder == null && JammarrSettings.enabled() && !recoveryFailed) beginStreaming(); }
    public void playbackActive(boolean active) {
        if (!active || manifest != null || !JammarrSettings.enabled()) return;
        long now = System.currentTimeMillis();
        if (now - lastMissingManifestRequestMs < MISSING_MANIFEST_RETRY_MS) return;
        recovering = true;
        requestManifest();
    }
    public void listeningChanged() {
        if (!JammarrSettings.enabled()) {
            resetAudio();
        } else if (manifest != null) {
            resetAudio();
            recovering = true;
            requestManifest();
        }
    }
    public boolean active() { return manifest != null && JammarrSettings.enabled(); }
    public AudioPlaybackState state() {
        if (!JammarrSettings.enabled()) return AudioPlaybackState.DISABLED;
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
        if (manifest == null || !JammarrSettings.enabled()) return;
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
        ChannelAccess access = channelAccess(Minecraft.getInstance().getSoundManager());
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
            // Recovery attempts are consecutive failures, not a lifetime budget
            // for the current track. Reaching a working OpenAL channel proves the
            // previous attempt succeeded and restores the normal retry allowance.
            recoveryAttempts = 0;
            channelStartedLocalMs = now;
            channelStartedPositionMs = startingPosition;
            handle.execute(value -> {
                value.disableAttenuation(); value.setRelative(true); value.setVolume(0);
                value.attachBufferStream(new PcmAudioStream(startingDecoder)); value.play();
            });
        });
    }

    private static ChannelAccess channelAccess(SoundManager manager) {
        SoundEngine engine = fieldValue(manager, SoundEngine.class);
        return fieldValue(engine, ChannelAccess.class);
    }

    private static <T> T fieldValue(Object owner, Class<T> type) {
        for (Class<?> current = owner.getClass(); current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!type.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    return type.cast(field.get(owner));
                } catch (ReflectiveOperationException | RuntimeException error) {
                    throw new IllegalStateException("Unable to access Minecraft audio field " + type.getName(), error);
                }
            }
        }
        throw new IllegalStateException("Minecraft audio field is missing: " + type.getName());
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
        if (ProtocolLimits.audioProbeEnabled()) {
            Jammarr.LOGGER.info("Acceptance audio state: RECOVERING reason={}", reason);
        }
        resetAudio();
        requestManifest();
    }

    private void requestManifest() {
        lastMissingManifestRequestMs = System.currentTimeMillis();
        JammarrNetwork.sendToServer(new JammarrPayloads.ManifestRequest(true));
    }

    public void acceptanceUnderrun() {
        if (!ProtocolLimits.audioProbeEnabled() || manifest == null || !started) return;
        underruns++;
        requestRebuffer("acceptance decoder starvation");
    }

    public void acceptanceClockDrift() {
        if (!ProtocolLimits.audioProbeEnabled() || manifest == null || !started) return;
        channelStartedLocalMs -= DRIFT_REBUFFER_MS + 2_000;
        lastCorrectionMs = 0;
        Jammarr.LOGGER.info("Acceptance clock drift injected beyond {} ms", DRIFT_REBUFFER_MS);
    }

    public void acceptanceExhaustRecovery() {
        if (!ProtocolLimits.audioProbeEnabled() || manifest == null) return;
        recoveryAttempts = 0;
        recoveryFailed = false;
        for (int attempt = 0; attempt <= MAX_RECOVERY_ATTEMPTS; attempt++) {
            requestRebuffer("acceptance forced recovery failure");
        }
    }

    private void rebuffer() { resetAudio(); beginStreaming(); }
    public void stop() { resetAudio(); manifest = null; recoveryAttempts = 0; underruns = 0; recovering = false; recoveryFailed = false; lastHealthSentMs = 0; lastMissingManifestRequestMs = 0; lastHealthState = ""; }
    public void audioEngineReloaded() { if (manifest != null) { resetAudio(); if (JammarrSettings.enabled()) { recovering = true; requestManifest(); } } }

    private void sendHealthIfNeeded(long now) {
        if (manifest == null) return;
        String state = state().name();
        if (!state.equals(lastHealthState) || now - lastHealthSentMs >= 5_000) {
            long buffered = decoder == null ? 0 : decoder.bufferedMillis();
            JammarrNetwork.sendToServer(new JammarrPayloads.AudioHealth(manifest.sessionId(), state, recoveryAttempts, underruns, receivedChunks, buffered));
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
