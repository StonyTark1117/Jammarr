package stonytark.jammarr.client;

import net.minecraft.client.MinecraftClient;
import paulscode.sound.SoundSystem;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.client.ChunkWindowTracker;
import stonytark.jammarr.core.client.ClockSynchronizer;
import stonytark.jammarr.core.client.DriftPolicy;
import stonytark.jammarr.core.client.PlaybackStartPolicy;
import stonytark.jammarr.core.network.Hashing;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.core.protocol.TransportPackets;
import stonytark.jammarr.core.protocol.AudioTimingTrace;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.network.LegacyPacketTypes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/** PCM streaming backend using MinecraftClient 1.6.4's existing OpenAL context. */
final class LegacyAudioPlayer {
    // Keep enough of the server's five-second lead for the first MP3 chunk's
    // boundary offset so OpenAL can start at the authoritative timestamp.
    private static final long START_BUFFER_MS = 2_000L;
    private static final long START_ALIGNMENT_TOLERANCE_MS = 2L;
    // Keep several seconds in OpenAL so an ordinary client-thread hitch cannot
    // drain the complete queue between feed calls.
    private static final long TARGET_SOUND_QUEUE_MS = 3_000L;
    private static final long BACKEND_ACTIVATION_GRACE_MS = 1_000L;
    private static final long DRIFT_REBUFFER_MS = 500L;
    private static final long UNDERRUN_GRACE_MS = 5_000L;
    private static final long MISSING_MANIFEST_RETRY_MS = 2_000L;
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final int PCM_FEED_BYTES = 32 * 1024;
    private static final long SOUND_RELOAD_DEBOUNCE_MS = 500L;

    private final ClockSynchronizer clock;
    private TransportPackets.AudioManifest manifest;
    private LegacyStreamingMp3Decoder decoder;
    private ChunkWindowTracker window;
    private int chunksPerRequest = stonytark.jammarr.core.protocol.ProtocolCapabilities.CHUNKS_PER_REQUEST;
    private SoundSystem soundSystem;
    private LegacyOpenAlStream openAlStream;
    private long firstChunkStartMs = -1L;
    private long sourceStartedLocalMs;
    private long sourceStartedPositionMs;
    private long queuedUntilLocalMs;
    private long backendActivationGraceUntilMs;
    private long lastAudioDataMs;
    private long lastCorrectionMs;
    private long lastRecoveryMs;
    private long lastManifestRequestMs;
    private long lastHealthSentMs;
    private String lastHealthState = "";
    private int recoveryAttempts;
    private int receivedChunks;
    private int underruns;
    private boolean recovering;
    private boolean recoveryFailed;
    private boolean soundUnavailable;
    private long lastSoundReloadMs;
    private boolean started;
    private boolean timingDrainRecorded;
    private double appliedVolume = Double.NaN;
    private FileOutputStream acceptancePcmTrace;

    LegacyAudioPlayer(ClockSynchronizer clock) { this.clock = clock; }

    void transportLimits(int negotiatedChunksPerRequest) {
        chunksPerRequest = Math.max(1, Math.min(
                stonytark.jammarr.core.protocol.ProtocolCapabilities.CHUNKS_PER_REQUEST,
                negotiatedChunksPerRequest));
    }

    void manifest(TransportPackets.AudioManifest value) {
        AudioTimingTrace.record("manifest_received", "firstChunk", value.firstChunk(),
                "scheduledEpochMs", value.startedAtEpochMs());
        if (value.totalChunks() == 0 || value.sessionId().equals(new UUID(0L, 0L))) { stop(); return; }
        boolean wasPaused = manifest != null && manifest.paused();
        if (value.paused() && started && openAlStream != null) openAlStream.pause();
        if (manifest == null || !manifest.sessionId().equals(value.sessionId())) {
            resetAudio(); manifest = value; recoveryAttempts = 0; receivedChunks = 0; underruns = 0;
            lastHealthSentMs = 0L; lastHealthState = ""; recoveryFailed = false;
            if (JammarrSettings.enabled()) beginStreaming();
        } else {
            // The server timeline is exact. Cancel and rebuild even for a
            // short pause/resume or seek so the old source cannot overlap the
            // replacement timeline.
            boolean timelineChanged = value.firstChunk() != manifest.firstChunk()
                    || value.startedAtEpochMs() != manifest.startedAtEpochMs();
            manifest = value;
            if (timelineChanged && JammarrSettings.enabled() && !recoveryFailed) rebuffer();
            else if (decoder == null && JammarrSettings.enabled() && !recoveryFailed) beginStreaming();
        }
        if (started && openAlStream != null) {
            if (value.paused()) openAlStream.pause();
            else {
                if (wasPaused) backendActivationGraceUntilMs = System.currentTimeMillis() + BACKEND_ACTIVATION_GRACE_MS;
                openAlStream.play();
            }
        }
    }

    void chunk(TransportPackets.AudioChunk value) {
        if (manifest == null || decoder == null || window == null
                || !manifest.sessionId().equals(value.sessionId())) return;
        if (!Hashing.matchesSha256(value.data(), value.sha256())) { window.reject(value.requestId()); return; }
        if (firstChunkStartMs < 0L && value.index() == manifest.firstChunk()) firstChunkStartMs = value.startMs();
        if (!decoder.offer(value.index(), value.data())) { window.reject(value.requestId()); return; }
        if (receivedChunks == 0) AudioTimingTrace.record("first_chunk_decoded", "index", value.index(),
                "request", value.requestId());
        if (receivedChunks++ == 0) Jammarr.LOGGER.info("Jammarr legacy client received the first audio chunk");
        lastAudioDataMs = System.currentTimeMillis();
        Optional<ChunkWindowTracker.Acknowledgement> acknowledgement = window.received(value.requestId(), value.index());
        if (acknowledgement.isPresent()) {
            ChunkWindowTracker.Acknowledgement accepted = acknowledgement.get();
            LegacyNetwork.sendToServer(LegacyPacketTypes.CHUNK_ACKNOWLEDGEMENT,
                    new TransportPackets.ChunkAcknowledgement(manifest.sessionId(), accepted.requestId(),
                            accepted.receivedThroughIndex(), decoder.bufferedMillis()));
        }
    }

    void tick() {
        if (soundUnavailable) return;
        try {
            tickUnsafe();
        } catch (LinkageError unavailable) {
            markSoundUnavailable(unavailable);
        }
    }

    private void tickUnsafe() {
        long now = System.currentTimeMillis();
        if (manifest == null) return;
        if (decoder == null || window == null) {
            if (recovering && !recoveryFailed && JammarrSettings.enabled()
                    && now - lastManifestRequestMs >= MISSING_MANIFEST_RETRY_MS) {
                requestManifest();
            }
            sendHealthIfNeeded(now);
            return;
        }
        if (decoder.failure() != null && decoder.format() == null && now - lastRecoveryMs >= 2_000L) {
            requestRebuffer("decoder failure"); return;
        }
        Optional<ChunkWindowTracker.Request> request = Optional.empty();
        if (decoder.canAcceptWindow(chunksPerRequest)) request = window.request(
                now, decoder.bufferedMillis(), LegacyStreamingMp3Decoder.MAX_BUFFERED_MS);
        if (request.isPresent()) {
            ChunkWindowTracker.Request value = request.get();
            LegacyNetwork.sendToServer(LegacyPacketTypes.CHUNK_REQUEST,
                    new TransportPackets.ChunkRequest(manifest.sessionId(), value.id(), value.startIndex(), value.count()));
            AudioTimingTrace.record("chunk_request_sent", "request", value.id(),
                    "start", value.startIndex(), "count", value.count());
        }
        MinecraftClient minecraft = MinecraftClient.getInstance();
        SoundSystem current = LegacySoundAccess.soundSystem(minecraft);
        if (started && current != soundSystem) {
            // Numeric OpenAL handles belong to the discarded context and must
            // not be deleted after MinecraftClient has installed the replacement.
            openAlStream = null;
            soundSystem = null;
            requestRebuffer("sound engine reload");
            return;
        }
        long decodedStartPosition = Math.max(0L, firstChunkStartMs)
                + decoder.initialPcmDelayMillis();
        long localStart = clock.toLocalTime(manifest.startedAtEpochMs() + decodedStartPosition);
        long authoritativePosition = Math.max(0L,
                clock.toServerTime(now) - manifest.startedAtEpochMs());
        PlaybackStartPolicy.Decision startDecision = PlaybackStartPolicy.evaluate(
                authoritativePosition, decodedStartPosition,
                decoder.bufferedMillis(), START_BUFFER_MS);
        if (!started && !manifest.paused() && JammarrSettings.enabled() && clock.readyForPlayback()
                && decoder.format() != null && startDecision.ready()
                && firstChunkStartMs >= 0L && now >= localStart) {
            startSource(current, now);
        }
        if (started && openAlStream != null) {
            if (!manifest.paused()) {
                boolean terminal = decoder.finished() && window.complete() && now >= queuedUntilLocalMs;
                boolean backendPlaying = openAlStream.playing();
                if (!backendPlaying && terminal) { stop(); return; }
                if (LegacyBackendQueueGuard.shouldRecover(false, terminal, backendPlaying,
                        now, backendActivationGraceUntilMs)) {
                    // Never restart a stopped source by appending to its old
                    // queue. Tear it down before supplying more PCM and resume
                    // from the authoritative position instead.
                    underruns++;
                    requestRebuffer("legacy backend underrun");
                    return;
                }
            }
            if (!manifest.paused() && !feedPcm(now)) return;
            if (!manifest.paused() && decoder.bufferedMillis() == 0L && !window.complete()
                    && now - lastAudioDataMs > UNDERRUN_GRACE_MS) {
                underruns++; requestRebuffer("decoder starvation"); return;
            }
            if (!manifest.paused() && now - lastCorrectionMs >= 2_000L) {
                long estimated = sourceStartedPositionMs + Math.max(0L, now - sourceStartedLocalMs);
                long authoritative = Math.max(0L, clock.toServerTime(now) - manifest.startedAtEpochMs());
                if (DriftPolicy.shouldRebuffer(estimated, authoritative, DRIFT_REBUFFER_MS)) {
                    requestRebuffer("clock drift"); return;
                }
                lastCorrectionMs = now;
            }
            if (!manifest.paused() && !openAlStream.playing() && decoder.finished()
                    && window.complete() && now >= queuedUntilLocalMs) stop();
        }
        sendHealthIfNeeded(now);
    }

    private void startSource(SoundSystem system, long now) {
        if (system == null) { requestRebuffer("sound engine unavailable"); return; }
        soundSystem = system;
        try {
            openAlStream = new LegacyOpenAlStream(decoder.format());
        } catch (RuntimeException unavailable) {
            soundSystem = null;
            Jammarr.LOGGER.warn("Unable to create Jammarr's legacy OpenAL stream", unavailable);
            requestRebuffer("sound engine unavailable");
            return;
        }
        long decodedStartPosition = firstChunkStartMs + decoder.initialPcmDelayMillis();
        long authoritativePosition = Math.max(decodedStartPosition,
                clock.toServerTime(now) - manifest.startedAtEpochMs());
        long requiredSkipMs = authoritativePosition - decodedStartPosition;
        long bufferedBeforeSkipMs = decoder.bufferedMillis();
        long skippedMillis = decoder.discardMillis(requiredSkipMs);
        if (!PlaybackStartPolicy.caughtUp(requiredSkipMs, skippedMillis,
                START_ALIGNMENT_TOLERANCE_MS)) {
            Jammarr.LOGGER.warn("Jammarr legacy source could not align before start: "
                    + "requiredSkipMs={} discardedMs={} bufferedBeforeMs={} bufferedAfterMs={}",
                    requiredSkipMs, skippedMillis, bufferedBeforeSkipMs, decoder.bufferedMillis());
            requestRebuffer("late audio source startup");
            return;
        }
        sourceStartedLocalMs = now;
        sourceStartedPositionMs = Math.max(0L, decodedStartPosition + skippedMillis);
        lastCorrectionMs = now;
        queuedUntilLocalMs = now;
        backendActivationGraceUntilMs = now + BACKEND_ACTIVATION_GRACE_MS;
        started = true;
        openAcceptancePcmTrace();
        AudioTimingTrace.record("channel_started", "positionMs", sourceStartedPositionMs,
                "scheduledLocalMs", now, "requiredSkipMs", requiredSkipMs,
                "skippedMs", skippedMillis, "decoderWarmupMs", decoder.initialPcmDelayMillis());
        // A live OpenAL source proves the previous recovery succeeded, so
        // only consecutive failures consume the retry allowance.
        recoveryAttempts = 0;
        if (!feedPcm(now) || openAlStream == null) return;
        openAlStream.play();
    }

    private boolean feedPcm(long now) {
        if (openAlStream == null) return false;
        queuedUntilLocalMs = Math.max(now, queuedUntilLocalMs);
        try {
            while (queuedUntilLocalMs - now < TARGET_SOUND_QUEUE_MS) {
                byte[] pcm = decoder.drain(PCM_FEED_BYTES);
                if (pcm == null) break;
                if (!timingDrainRecorded) {
                    timingDrainRecorded = true;
                    AudioTimingTrace.record("pcm_drained", "bytes", pcm.length,
                            "bufferedMs", decoder.bufferedMillis());
                }
                traceAcceptancePcm(pcm);
                double volume = JammarrSettings.volume()
                        * MinecraftClient.getInstance().options.musicVolume;
                if (Double.compare(volume, appliedVolume) != 0) {
                    appliedVolume = volume;
                    openAlStream.gain((float) volume);
                    if (ProtocolLimits.audioProbeEnabled()) {
                        Jammarr.LOGGER.info("Acceptance backend volume applied: {}", volume);
                    }
                }
                openAlStream.feed(pcm);
                queuedUntilLocalMs += decoder.durationMs(pcm);
            }
            return true;
        } catch (RuntimeException unavailable) {
            Jammarr.LOGGER.warn("Jammarr legacy sound engine changed during PCM feed", unavailable);
            requestRebuffer("sound engine reload");
            return false;
        }
    }

    void listeningChanged() {
        if (!JammarrSettings.enabled()) resetAudio();
        else if (manifest != null) {
            resetAudio(); recovering = true;
            requestManifest();
        }
    }

    void retry() {
        if (manifest == null || !JammarrSettings.enabled()) return;
        recoveryAttempts = 0; recoveryFailed = false; requestRebuffer("manual retry");
    }

    void audioEngineReloaded() {
        long now = System.currentTimeMillis();
        if (now - lastSoundReloadMs < SOUND_RELOAD_DEBOUNCE_MS) return;
        lastSoundReloadMs = now;
        soundUnavailable = false;
        // The event may arrive after MinecraftClient replaced the OpenAL context;
        // discard old numeric handles without touching the new context.
        openAlStream = null;
        soundSystem = null;
        if (manifest != null) requestRebuffer("sound engine reload");
    }

    private void beginStreaming() {
        AudioTimingTrace.record("decoder_started", "firstChunk", manifest.firstChunk());
        timingDrainRecorded = false;
        firstChunkStartMs = -1L;
        decoder = new LegacyStreamingMp3Decoder(manifest.firstChunk(), manifest.totalChunks());
        window = new ChunkWindowTracker(manifest.firstChunk(), manifest.totalChunks(), chunksPerRequest, 1_500L);
        lastAudioDataMs = System.currentTimeMillis();
        lastRecoveryMs = 0L; receivedChunks = 0; recovering = false;
    }

    private void requestRebuffer(String reason) {
        if (manifest == null || recoveryFailed) return;
        if (++recoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
            recoveryFailed = true; recovering = false; resetAudio(); return;
        }
        lastRecoveryMs = System.currentTimeMillis(); recovering = true;
        Jammarr.LOGGER.warn("Jammarr legacy audio recovery attempt {}/{}: {}",
                recoveryAttempts, MAX_RECOVERY_ATTEMPTS, reason);
        if (stonytark.jammarr.core.protocol.ProtocolLimits.audioProbeEnabled()) {
            Jammarr.LOGGER.info("Acceptance audio state: RECOVERING reason={}", reason);
        }
        resetAudio();
        requestManifest();
    }

    private void requestManifest() {
        lastManifestRequestMs = System.currentTimeMillis();
        LegacyNetwork.sendToServer(LegacyPacketTypes.MANIFEST_REQUEST, new StatePackets.ManifestRequest(true));
    }

    void acceptanceUnderrun() {
        if (!stonytark.jammarr.core.protocol.ProtocolLimits.audioProbeEnabled()
                || manifest == null || !started) return;
        underruns++;
        requestRebuffer("acceptance decoder starvation");
    }

    void acceptanceClockDrift() {
        if (!stonytark.jammarr.core.protocol.ProtocolLimits.audioProbeEnabled()
                || manifest == null || !started) return;
        sourceStartedLocalMs -= DRIFT_REBUFFER_MS + 2_000L;
        lastCorrectionMs = 0L;
        Jammarr.LOGGER.info("Acceptance clock drift injected beyond {} ms", DRIFT_REBUFFER_MS);
    }

    void acceptanceExhaustRecovery() {
        if (!stonytark.jammarr.core.protocol.ProtocolLimits.audioProbeEnabled() || manifest == null) return;
        recoveryAttempts = 0;
        recoveryFailed = false;
        for (int attempt = 0; attempt <= MAX_RECOVERY_ATTEMPTS; attempt++) {
            requestRebuffer("acceptance forced recovery failure");
        }
    }

    private void rebuffer() { resetAudio(); beginStreaming(); }

    void stop() {
        resetAudio(); manifest = null; recoveryAttempts = 0; underruns = 0;
        recovering = false; recoveryFailed = false; lastHealthSentMs = 0L;
        lastManifestRequestMs = 0L; lastHealthState = "";
    }

    private void sendHealthIfNeeded(long now) {
        if (manifest == null) return;
        String state = state();
        if (!state.equals(lastHealthState) || now - lastHealthSentMs >= 5_000L) {
            LegacyNetwork.sendToServer(LegacyPacketTypes.AUDIO_HEALTH,
                    new StatePackets.AudioHealth(manifest.sessionId(), state, recoveryAttempts, underruns,
                            receivedChunks, decoder == null ? 0L : decoder.bufferedMillis()));
            lastHealthState = state; lastHealthSentMs = now;
        }
    }

    String state() {
        if (!JammarrSettings.enabled()) return "DISABLED";
        if (manifest == null) return "NO_STREAM";
        if (recoveryFailed) return "ERROR";
        if (manifest.paused()) return "PAUSED";
        if (recovering) return "RECOVERING";
        if (decoder != null && decoder.failure() != null && decoder.format() == null) return "ERROR";
        return started ? "PLAYING" : "BUFFERING";
    }

    boolean ownsMusic() { return JammarrSettings.enabled() && manifest != null; }

    String status() {
        String state = state();
        if ("DISABLED".equals(state)) return "Listening disabled locally";
        if ("NO_STREAM".equals(state)) return "No active audio stream";
        if ("PAUSED".equals(state)) return "Paused";
        if ("RECOVERING".equals(state)) return "Recovering audio...";
        if ("ERROR".equals(state)) return "Audio needs attention; retry";
        if ("BUFFERING".equals(state)) return "Buffering "
                + (decoder == null ? 0L : Math.min(100L, decoder.bufferedMillis() * 100L / START_BUFFER_MS)) + "%";
        return "Playing";
    }

    private void resetAudio() {
        closeAcceptancePcmTrace();
        LegacyOpenAlStream previous = openAlStream;
        openAlStream = null;
        soundSystem = null;
        if (previous != null) {
            try { previous.close(); }
            catch (Throwable unavailable) {
                Jammarr.LOGGER.warn("Jammarr legacy sound engine changed during cleanup", unavailable);
            }
        }
        if (decoder != null) { decoder.close(); decoder = null; }
        window = null; started = false; firstChunkStartMs = -1L;
        appliedVolume = Double.NaN;
        sourceStartedLocalMs = 0L; sourceStartedPositionMs = 0L; queuedUntilLocalMs = 0L;
        backendActivationGraceUntilMs = 0L;
    }

    private void openAcceptancePcmTrace() {
        if (!ProtocolLimits.audioProbeEnabled() || manifest == null) return;
        String traceDirectory = System.getProperty("jammarr.acceptance.pcmTraceDir", "");
        if (traceDirectory.length() == 0) return;
        File directory = new File(traceDirectory);
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        File trace = new File(directory, manifest.sessionId() + "-" + manifest.firstChunk()
                + "-" + System.nanoTime() + ".s16le");
        try {
            acceptancePcmTrace = new FileOutputStream(trace);
        } catch (IOException error) {
            Jammarr.LOGGER.warn("Unable to open the legacy acceptance PCM trace", error);
        }
    }

    private void traceAcceptancePcm(byte[] pcm) {
        if (acceptancePcmTrace == null) return;
        try {
            acceptancePcmTrace.write(pcm);
            acceptancePcmTrace.flush();
        } catch (IOException error) {
            Jammarr.LOGGER.warn("Unable to write the legacy acceptance PCM trace", error);
            closeAcceptancePcmTrace();
        }
    }

    private void closeAcceptancePcmTrace() {
        if (acceptancePcmTrace == null) return;
        try {
            acceptancePcmTrace.close();
        } catch (IOException ignored) {
        }
        acceptancePcmTrace = null;
    }

    private void markSoundUnavailable(LinkageError unavailable) {
        if (soundUnavailable) return;
        soundUnavailable = true;
        recoveryFailed = true;
        openAlStream = null;
        soundSystem = null;
        resetAudio();
        Jammarr.LOGGER.error("Jammarr legacy audio disabled after an unusable OpenAL context", unavailable);
    }
}
