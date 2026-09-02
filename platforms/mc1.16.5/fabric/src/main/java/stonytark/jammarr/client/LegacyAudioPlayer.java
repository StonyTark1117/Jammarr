package stonytark.jammarr.client;

import stonytark.jammarr.core.client.ChunkWindowTracker;
import stonytark.jammarr.core.client.ClockSynchronizer;
import stonytark.jammarr.core.client.DriftPolicy;
import stonytark.jammarr.core.client.AsyncStartGuard;
import stonytark.jammarr.core.client.PlaybackStartPolicy;
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
import stonytark.jammarr.core.protocol.AudioTimingTrace;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.core.protocol.TransportPackets;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.network.LegacyPacketTypes;
import java.lang.reflect.Field;
import java.util.UUID;

public final class LegacyAudioPlayer {
    // The server schedules playback five seconds ahead, but an MP3 chunk may
    // begin up to two seconds before that target at the supported 64 kbps
    // minimum. Requiring the full five-second lead here made an on-time start
    // impossible and allowed clients to settle hundreds of milliseconds apart.
    private static final long START_BUFFER_MS = 2_000;
    private static final long START_ALIGNMENT_TOLERANCE_MS = 2;
    private static final long DRIFT_REBUFFER_MS = 500;
    private static final long BACKEND_DRIFT_LOG_MS = 40;
    private static final long BACKEND_DRIFT_REBUFFER_MS = 150;
    private static final long BACKEND_PROBE_INTERVAL_MS = 500;
    private static final int BACKEND_DRIFT_REQUIRED_SAMPLES = 2;
    private static final long BACKEND_DRIFT_MINIMUM_PERSISTENCE_MS = 1_500;
    private static final long MAX_BACKEND_LEAD_MS = 1_000;
    private static final long UNDERRUN_GRACE_MS = 5_000;
    private static final long MISSING_MANIFEST_RETRY_MS = 2_000;
    private static final int MAX_RECOVERY_ATTEMPTS = 3;

    private final ClockSynchronizer clock;
    private TransportPackets.AudioManifest manifest;
    private LegacyStreamingMp3Decoder decoder;
    private ChunkWindowTracker window;
    private int chunksPerRequest = stonytark.jammarr.core.protocol.ProtocolCapabilities.CHUNKS_PER_REQUEST;
    private volatile ChannelAccess.ChannelHandle channel;
    private volatile LegacyPcmAudioStream pcmStream;
    private volatile BackendPosition backendPosition;
    private volatile boolean backendProbePending;
    private volatile long backendProbeGeneration;
    private volatile boolean backendCalibrating;
    private long backendCalibrationDeadlineMs;
    private long backendProbeRequestedMs;
    private BackendPosition lastEvaluatedBackendPosition;
    private final BackendDriftGuard backendDrift = new BackendDriftGuard(
            BACKEND_DRIFT_REBUFFER_MS, BACKEND_DRIFT_REQUIRED_SAMPLES,
            BACKEND_DRIFT_MINIMUM_PERSISTENCE_MS);
    private volatile long backendLeadCompensationMs;
    private long lastBackendLogMs;
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
    private volatile boolean started;
    private float appliedVolume = Float.NaN;
    private final AsyncStartGuard channelStarts = new AsyncStartGuard();

    public LegacyAudioPlayer(ClockSynchronizer clock) { this.clock = clock; }

    public void transportLimits(int negotiatedChunksPerRequest) {
        chunksPerRequest = Math.max(1, Math.min(
                stonytark.jammarr.core.protocol.ProtocolCapabilities.CHUNKS_PER_REQUEST,
                negotiatedChunksPerRequest));
    }

    public void manifest(TransportPackets.AudioManifest value) {
        AudioTimingTrace.record("manifest_received", "firstChunk", value.firstChunk(),
                "scheduledEpochMs", value.startedAtEpochMs());
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
            // startedAtEpochMs is an authoritative timeline generation, not a
            // noisy measurement. Even a short pause/resume or seek must cancel
            // a pending channel so old and new PCM can never overlap.
            boolean timelineChanged = value.firstChunk() != manifest.firstChunk()
                    || value.startedAtEpochMs() != manifest.startedAtEpochMs();
            manifest = value;
            if (timelineChanged && JammarrSettings.enabled() && !recoveryFailed) rebuffer();
            else if (decoder == null && JammarrSettings.enabled() && !recoveryFailed) beginStreaming();
        }
        if (channel != null) channel.execute(c -> { if (value.paused()) c.pause(); else c.unpause(); });
    }

    public void chunk(TransportPackets.AudioChunk value) {
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
        if (receivedChunks == 0) AudioTimingTrace.record("first_chunk_decoded", "index", value.index(),
                "request", value.requestId());
        if (receivedChunks++ == 0) Jammarr.LOGGER.info("Jammarr received the first audio chunk");
        if (ProtocolLimits.audioProbeEnabled() && (value.index() == manifest.firstChunk() || value.index() % 8 == 0)) {
            Jammarr.LOGGER.info("Acceptance audio chunk: index={} request={} bufferedMs={}",
                    value.index(), value.requestId(), decoder.bufferedMillis());
        }
        lastAudioDataMs = System.currentTimeMillis();
        window.received(value.requestId(), value.index()).ifPresent(ack -> LegacyNetwork.sendToServer(
                LegacyPacketTypes.CHUNK_ACKNOWLEDGEMENT,
                new TransportPackets.ChunkAcknowledgement(manifest.sessionId(), ack.requestId(), ack.receivedThroughIndex(), decoder.bufferedMillis())));
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
        if (decoder.canAcceptWindow(chunksPerRequest)) window.request(
                now, decoder.bufferedMillis(), LegacyStreamingMp3Decoder.MAX_BUFFERED_MS).ifPresent(request -> {
            if (request.id() == 1) Jammarr.LOGGER.info("Jammarr requested the initial audio chunk window");
            if (ProtocolLimits.audioProbeEnabled()) Jammarr.LOGGER.info(
                    "Acceptance chunk request: id={} start={} count={} bufferedMs={}",
                    request.id(), request.startIndex(), request.count(), decoder.bufferedMillis());
            LegacyNetwork.sendToServer(LegacyPacketTypes.CHUNK_REQUEST,
                    new TransportPackets.ChunkRequest(manifest.sessionId(), request.id(), request.startIndex(), request.count()));
            AudioTimingTrace.record("chunk_request_sent", "request", request.id(),
                    "start", request.startIndex(), "count", request.count());
        });
        Minecraft minecraft = Minecraft.getInstance();
        if (JammarrSettings.enabled()) minecraft.getMusicManager().stopPlaying();
        long decodedStartPosition = Math.max(0, firstChunkStartMs) + decoder.initialPcmDelayMillis();
        long localStart = clock.toLocalTime(manifest.startedAtEpochMs() + decodedStartPosition);
        long authoritativePosition = Math.max(0, clock.toServerTime(now) - manifest.startedAtEpochMs());
        PlaybackStartPolicy.Decision startDecision = PlaybackStartPolicy.evaluate(
                authoritativePosition, decodedStartPosition, decoder.bufferedMillis(), START_BUFFER_MS);
        // Read the synchronized guard before the volatile publication flag.
        // The async sound thread clears pending only after publishing started;
        // the reverse order can use a stale false and create a second channel.
        if (!channelStarts.pending() && !started && !manifest.paused() && JammarrSettings.enabled()
                && clock.readyForPlayback() && decoder.format() != null
                && startDecision.ready() && firstChunkStartMs >= 0
                && now >= localStart) {
            startChannel(now);
        }
        if (channel != null) {
            if (backendCalibrating && now >= backendCalibrationDeadlineMs) {
                backendCalibrating = false;
                appliedVolume = Float.NaN;
                Jammarr.LOGGER.debug("Jammarr backend clock unavailable during muted startup calibration");
            }
            float volume = JammarrSettings.enabled() && !backendCalibrating
                    ? (float)(JammarrSettings.volume() * minecraft.options.getSoundSourceVolume(SoundSource.MUSIC)) : 0;
            if (Float.compare(volume, appliedVolume) != 0) {
                appliedVolume = volume;
                channel.execute(c -> {
                    c.setVolume(volume);
                    if (ProtocolLimits.audioProbeEnabled()) {
                        Jammarr.LOGGER.info("Acceptance backend volume applied: {}", volume);
                    }
                });
            }
            // OpenAL may still hold audible PCM while the server intentionally
            // defers the next compressed window. Avoid querying the backend
            // while its streaming read is waiting; recover only if that wait
            // outlives the bounded delivery grace.
            boolean waitingForDecoder = started && !manifest.paused()
                    && decoder.bufferedMillis() == 0 && !window.complete();
            if (waitingForDecoder && now - lastAudioDataMs > UNDERRUN_GRACE_MS) {
                underruns++;
                requestRebuffer("decoder starvation");
                return;
            }
            if (!waitingForDecoder && channel.isStopped()) {
                if (!window.complete() && now - lastAudioDataMs > UNDERRUN_GRACE_MS) {
                    underruns++;
                    requestRebuffer("audio underrun");
                }
                else stop();
                return;
            }
            if (!manifest.paused()) {
                scheduleBackendProbe(now);
                BackendPosition measured = backendPosition;
                if (measured != null && measured != lastEvaluatedBackendPosition) {
                    lastEvaluatedBackendPosition = measured;
                    if (evaluateBackendPosition(measured)) return;
                }
            }
            if (!manifest.paused() && now - lastCorrectionMs >= 2_000) {
                long estimatedPosition = channelStartedPositionMs + Math.max(0, now - channelStartedLocalMs);
                long authoritativePlaybackPosition = Math.max(0,
                        clock.toServerTime(now) - manifest.startedAtEpochMs());
                if (DriftPolicy.shouldRebuffer(estimatedPosition,
                        authoritativePlaybackPosition, DRIFT_REBUFFER_MS)) requestRebuffer("clock drift");
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
        return started && !backendCalibrating ? AudioPlaybackState.PLAYING : AudioPlaybackState.BUFFERING;
    }
    public String status() {
        AudioPlaybackState value = state();
        if (value == AudioPlaybackState.DISABLED) return "Listening disabled locally";
        if (value == AudioPlaybackState.NO_STREAM) return "No active audio stream";
        if (value == AudioPlaybackState.PAUSED) return "Paused";
        if (value == AudioPlaybackState.RECOVERING) return "Recovering audio…";
        if (value == AudioPlaybackState.ERROR) return "Audio needs attention; retry";
        if (value == AudioPlaybackState.BUFFERING) return "Buffering "
                + (decoder == null ? 0 : Math.min(100, decoder.bufferedMillis() * 100 / START_BUFFER_MS)) + "%";
        return "Playing";
    }

    public void retry() {
        if (manifest == null || !JammarrSettings.enabled()) return;
        recoveryAttempts = 0;
        recoveryFailed = false;
        requestRebuffer("manual retry");
    }

    private void beginStreaming() {
        AudioTimingTrace.record("decoder_started", "firstChunk", manifest.firstChunk());
        firstChunkStartMs = -1;
        decoder = new LegacyStreamingMp3Decoder(manifest.firstChunk(), manifest.totalChunks());
        window = new ChunkWindowTracker(manifest.firstChunk(), manifest.totalChunks(), chunksPerRequest, 1_500);
        lastAudioDataMs = System.currentTimeMillis();
        lastRecoveryMs = 0;
        receivedChunks = 0;
        recovering = false;
    }

    private void startChannel(long now) {
        long startToken = channelStarts.begin();
        if (startToken < 0) return;
        LegacyStreamingMp3Decoder startingDecoder = decoder;
        UUID startingSession = manifest.sessionId();
        long startingPosition = Math.max(0, firstChunkStartMs) + startingDecoder.initialPcmDelayMillis();
        long startingEpochMs = manifest.startedAtEpochMs();
        long retainedBackendLeadMs = backendLeadCompensationMs;
        ChannelAccess access = channelAccess(Minecraft.getInstance().getSoundManager());
        access.createHandle(Library.Pool.STREAMING).whenComplete((handle, error) -> {
            if (error != null || handle == null) {
                if (channelStarts.complete(startToken)) requestRebuffer("audio channel creation");
                return;
            }
            handle.execute(value -> {
                long readyNow = System.currentTimeMillis();
                long measuredDeviceLatencyMs = OpenAlPlaybackClock.deviceLatencyMillis();
                long startingBackendLeadMs = Math.min(MAX_BACKEND_LEAD_MS,
                        measuredDeviceLatencyMs >= 0 ? measuredDeviceLatencyMs : retainedBackendLeadMs);
                long requiredSkipMs = Math.max(0,
                        clock.toServerTime(readyNow) - startingEpochMs - startingPosition
                                + startingBackendLeadMs);
                long bufferedBeforeSkipMs = startingDecoder.bufferedMillis();
                long skippedMillis = startingDecoder.discardMillis(requiredSkipMs);
                if (!PlaybackStartPolicy.caughtUp(requiredSkipMs, skippedMillis,
                        START_ALIGNMENT_TOLERANCE_MS)) {
                    Jammarr.LOGGER.warn("Jammarr channel could not align before start: "
                                    + "requiredSkipMs={} discardedMs={} bufferedBeforeMs={} bufferedAfterMs={}",
                            requiredSkipMs, skippedMillis, bufferedBeforeSkipMs,
                            startingDecoder.bufferedMillis());
                    boolean current = channelStarts.complete(startToken);
                    value.stop();
                    if (current) requestRebuffer("late audio channel startup");
                    return;
                }
                LegacyPcmAudioStream startingStream = new LegacyPcmAudioStream(startingDecoder);
                boolean published = channelStarts.complete(startToken, () -> {
                    long actualPosition = startingPosition + skippedMillis;
                    AudioTimingTrace.record("channel_started", "positionMs", actualPosition,
                            "scheduledLocalMs", now, "readyLocalMs", readyNow,
                            "requiredSkipMs", requiredSkipMs, "skippedMs", skippedMillis,
                            "backendLeadMs", startingBackendLeadMs,
                            "decoderWarmupMs", startingDecoder.initialPcmDelayMillis(),
                            "session", startingSession);
                    // Recovery attempts are consecutive failures, not a lifetime budget
                    // for the current track. Reaching a working OpenAL channel proves the
                    // previous attempt succeeded and restores the normal retry allowance.
                    recoveryAttempts = 0;
                    backendLeadCompensationMs = startingBackendLeadMs;
                    backendCalibrating = startingBackendLeadMs <= 0;
                    backendCalibrationDeadlineMs = readyNow + 1_500;
                    channelStartedLocalMs = readyNow;
                    channelStartedPositionMs = actualPosition;
                    lastCorrectionMs = readyNow;
                    value.disableAttenuation(); value.setRelative(true); value.setVolume(0);
                    pcmStream = startingStream;
                    value.attachBufferStream(startingStream); value.play();
                    // Publish only after the backend initialization command has run,
                    // while the start guard is still held. Until this point the handle
                    // can legitimately report stopped and must not be polled by tick().
                    appliedVolume = Float.NaN;
                    channel = handle;
                    started = true;
                });
                if (!published) {
                    startingStream.close();
                    value.stop();
                }
            });
        });
    }

    private void scheduleBackendProbe(long now) {
        if (now - backendProbeRequestedMs < BACKEND_PROBE_INTERVAL_MS) return;
        if (backendProbePending && now - backendProbeRequestedMs < 3_000) return;
        ChannelAccess.ChannelHandle activeChannel = channel;
        LegacyPcmAudioStream activeStream = pcmStream;
        if (activeChannel == null || activeStream == null) return;
        backendProbePending = true;
        backendProbeRequestedMs = now;
        long probe = ++backendProbeGeneration;
        try {
            activeChannel.execute(value -> {
                try {
                    OpenAlPlaybackClock.Position measured = OpenAlPlaybackClock.sample(value, activeStream);
                    if (measured != null && channel == activeChannel && pcmStream == activeStream) {
                        backendPosition = new BackendPosition(activeStream, System.currentTimeMillis(),
                                System.nanoTime() / 1_000_000L, measured);
                    }
                } catch (RuntimeException unavailable) {
                    if (ProtocolLimits.audioProbeEnabled()) {
                        Jammarr.LOGGER.debug("Acceptance backend clock sample unavailable", unavailable);
                    }
                } finally {
                    if (backendProbeGeneration == probe) backendProbePending = false;
                }
            });
        } catch (RuntimeException unavailable) {
            if (backendProbeGeneration == probe) backendProbePending = false;
        }
    }

    private boolean evaluateBackendPosition(BackendPosition measured) {
        if (measured.stream != pcmStream || manifest == null) return false;
        backendLeadCompensationMs = Math.min(MAX_BACKEND_LEAD_MS,
                measured.position.deviceLatencyMillis());
        long estimatedPosition = channelStartedPositionMs + measured.position.playedMillis();
        long authoritativePosition = Math.max(0,
                clock.toServerTime(measured.observedAtMs) - manifest.startedAtEpochMs());
        long drift = estimatedPosition - authoritativePosition;
        if (ProtocolLimits.audioProbeEnabled()
                && (lastBackendLogMs == 0 || measured.observedAtMs - lastBackendLogMs >= 5_000
                || Math.abs(drift) > BACKEND_DRIFT_LOG_MS)) {
            lastBackendLogMs = measured.observedAtMs;
            Jammarr.LOGGER.info("Acceptance backend clock: playedMs={} latencyMs={} estimatedMs={} authoritativeMs={} driftMs={}",
                    measured.position.playedMillis(), measured.position.deviceLatencyMillis(),
                    estimatedPosition, authoritativePosition, drift);
        }
        if (backendCalibrating) {
            backendCalibrating = false;
            if (measured.position.deviceLatencyMillis() > 0) {
                requestRebuffer("backend latency calibration");
                return true;
            }
            appliedVolume = Float.NaN;
        }
        if (!backendDrift.observe(drift, measured.observedAtMonotonicMs)) return false;
        Jammarr.LOGGER.warn("Jammarr detected sustained backend playback drift: driftMs={} latencyMs={}",
                drift, measured.position.deviceLatencyMillis());
        requestRebuffer("backend playback drift");
        return true;
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
        LegacyNetwork.sendToServer(LegacyPacketTypes.MANIFEST_REQUEST, new StatePackets.ManifestRequest(true));
    }

    public void acceptanceUnderrun() {
        if (!ProtocolLimits.audioProbeEnabled() || manifest == null || !started) return;
        underruns++;
        requestRebuffer("acceptance decoder starvation");
    }

    public void acceptanceClockDrift() {
        if (!ProtocolLimits.audioProbeEnabled() || manifest == null || !started) return;
        channelStartedPositionMs += DRIFT_REBUFFER_MS + 2_000;
        lastCorrectionMs = System.currentTimeMillis();
        backendDrift.reset();
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
            LegacyNetwork.sendToServer(LegacyPacketTypes.AUDIO_HEALTH,
                    new StatePackets.AudioHealth(manifest.sessionId(), state, recoveryAttempts, underruns, receivedChunks, buffered));
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
        pcmStream = null;
        backendPosition = null;
        lastEvaluatedBackendPosition = null;
        backendProbePending = false;
        backendProbeRequestedMs = 0;
        backendProbeGeneration++;
        backendCalibrating = false;
        backendCalibrationDeadlineMs = 0;
        backendDrift.reset();
        lastBackendLogMs = 0;
        started = false;
        appliedVolume = Float.NaN;
        firstChunkStartMs = -1;
        channelStartedLocalMs = 0;
        channelStartedPositionMs = 0;
    }

    private static final class BackendPosition {
        private final LegacyPcmAudioStream stream;
        private final long observedAtMs;
        private final long observedAtMonotonicMs;
        private final OpenAlPlaybackClock.Position position;

        private BackendPosition(LegacyPcmAudioStream stream, long observedAtMs,
                                long observedAtMonotonicMs,
                                OpenAlPlaybackClock.Position position) {
            this.stream = stream;
            this.observedAtMs = observedAtMs;
            this.observedAtMonotonicMs = observedAtMonotonicMs;
            this.position = position;
        }
    }
}
