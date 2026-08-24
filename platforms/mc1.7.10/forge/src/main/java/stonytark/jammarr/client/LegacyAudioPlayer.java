package stonytark.jammarr.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import paulscode.sound.SoundSystem;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.client.ChunkWindowTracker;
import stonytark.jammarr.core.client.ClockSynchronizer;
import stonytark.jammarr.core.client.DriftPolicy;
import stonytark.jammarr.core.client.PcmGain;
import stonytark.jammarr.core.network.Hashing;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.core.protocol.TransportPackets;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.network.LegacyPacketTypes;

import java.util.Optional;
import java.util.UUID;

/** PCM streaming backend for Minecraft 1.7.10's Paulscode/OpenAL sound engine. */
final class LegacyAudioPlayer {
    private static final String SOURCE = "jammarr:global_music";
    private static final long START_BUFFER_MS = 5_000L;
    private static final long TARGET_SOUND_QUEUE_MS = 1_000L;
    private static final long DRIFT_REBUFFER_MS = 500L;
    private static final long UNDERRUN_GRACE_MS = 1_500L;
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final int PCM_FEED_BYTES = 32 * 1024;

    private final ClockSynchronizer clock;
    private TransportPackets.AudioManifest manifest;
    private LegacyStreamingMp3Decoder decoder;
    private ChunkWindowTracker window;
    private SoundSystem soundSystem;
    private long firstChunkStartMs = -1L;
    private long sourceStartedLocalMs;
    private long sourceStartedPositionMs;
    private long queuedUntilLocalMs;
    private long lastAudioDataMs;
    private long lastCorrectionMs;
    private long lastRecoveryMs;
    private long lastHealthSentMs;
    private String lastHealthState = "";
    private int recoveryAttempts;
    private int receivedChunks;
    private int underruns;
    private boolean recovering;
    private boolean recoveryFailed;
    private boolean started;

    LegacyAudioPlayer(ClockSynchronizer clock) { this.clock = clock; }

    void manifest(TransportPackets.AudioManifest value) {
        if (value.totalChunks() == 0 || value.sessionId().equals(new UUID(0L, 0L))) { stop(); return; }
        // Queue pause ahead of any timeline rebuffer/cleanup. Paulscode runs
        // these commands asynchronously, and cleanup-first can let a fragment
        // of the old raw stream escape after the shared state says PAUSED.
        if (value.paused() && started && soundSystem != null) soundSystem.pause(SOURCE);
        if (manifest == null || !manifest.sessionId().equals(value.sessionId())) {
            resetAudio(); manifest = value; recoveryAttempts = 0; receivedChunks = 0; underruns = 0;
            lastHealthSentMs = 0L; lastHealthState = ""; recoveryFailed = false;
            if (JammarrSettings.enabled()) beginStreaming();
        } else {
            boolean timelineChanged = value.firstChunk() != manifest.firstChunk()
                    || Math.abs(value.startedAtEpochMs() - manifest.startedAtEpochMs()) > DRIFT_REBUFFER_MS;
            manifest = value;
            if (timelineChanged && JammarrSettings.enabled()) rebuffer();
            else if (decoder == null && JammarrSettings.enabled()) beginStreaming();
        }
        if (started && soundSystem != null) {
            if (value.paused()) soundSystem.pause(SOURCE); else soundSystem.play(SOURCE);
        }
    }

    void chunk(TransportPackets.AudioChunk value) {
        if (manifest == null || decoder == null || window == null
                || !manifest.sessionId().equals(value.sessionId())) return;
        if (!Hashing.matchesSha256(value.data(), value.sha256())) { window.reject(value.requestId()); return; }
        if (firstChunkStartMs < 0L && value.index() == manifest.firstChunk()) firstChunkStartMs = value.startMs();
        if (!decoder.offer(value.index(), value.data())) { window.reject(value.requestId()); return; }
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
        long now = System.currentTimeMillis();
        if (manifest == null) return;
        if (decoder == null || window == null) { sendHealthIfNeeded(now); return; }
        if (decoder.failure() != null && decoder.format() == null && now - lastRecoveryMs >= 2_000L) {
            requestRebuffer("decoder failure"); return;
        }
        Optional<ChunkWindowTracker.Request> request = window.request(
                now, decoder.bufferedMillis(), LegacyStreamingMp3Decoder.MAX_BUFFERED_MS);
        if (request.isPresent()) {
            ChunkWindowTracker.Request value = request.get();
            LegacyNetwork.sendToServer(LegacyPacketTypes.CHUNK_REQUEST,
                    new TransportPackets.ChunkRequest(manifest.sessionId(), value.id(), value.startIndex(), value.count()));
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (JammarrSettings.enabled()) LegacySoundAccess.stopVanillaMusic(minecraft);
        SoundSystem current = LegacySoundAccess.soundSystem(minecraft);
        if (started && current != soundSystem) { requestRebuffer("sound engine reload"); return; }
        long localStart = clock.toLocalTime(manifest.startedAtEpochMs() + Math.max(0L, firstChunkStartMs));
        if (!started && !manifest.paused() && JammarrSettings.enabled() && decoder.format() != null
                && decoder.bufferedMillis() >= START_BUFFER_MS && firstChunkStartMs >= 0L && now >= localStart) {
            startSource(current, now);
        }
        if (started && soundSystem != null) {
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
            if (!manifest.paused() && !soundSystem.playing(SOURCE) && decoder.finished()
                    && window.complete() && now >= queuedUntilLocalMs) stop();
        }
        sendHealthIfNeeded(now);
    }

    private void startSource(SoundSystem system, long now) {
        if (system == null) { requestRebuffer("sound engine unavailable"); return; }
        soundSystem = system;
        soundSystem.rawDataStream(decoder.format(), true, SOURCE, 0.0F, 0.0F, 0.0F, 0, 0.0F);
        soundSystem.setLooping(SOURCE, false);
        soundSystem.setAttenuation(SOURCE, 0);
        sourceStartedLocalMs = now;
        sourceStartedPositionMs = Math.max(0L, firstChunkStartMs);
        queuedUntilLocalMs = now;
        started = true;
        // A live Paulscode source proves the previous recovery succeeded, so
        // only consecutive failures consume the retry allowance.
        recoveryAttempts = 0;
        if (!feedPcm(now) || soundSystem == null) return;
        soundSystem.play(SOURCE);
    }

    private boolean feedPcm(long now) {
        if (soundSystem == null) return false;
        queuedUntilLocalMs = Math.max(now, queuedUntilLocalMs);
        try {
            while (queuedUntilLocalMs - now < TARGET_SOUND_QUEUE_MS) {
                byte[] pcm = decoder.drain(PCM_FEED_BYTES);
                if (pcm == null) break;
                PcmGain.apply(pcm, JammarrSettings.volume()
                        * Minecraft.getMinecraft().gameSettings.getSoundLevel(SoundCategory.MUSIC));
                soundSystem.feedRawAudioData(SOURCE, pcm);
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
            LegacyNetwork.sendToServer(LegacyPacketTypes.MANIFEST_REQUEST, new StatePackets.ManifestRequest(true));
        }
    }

    void retry() {
        if (manifest == null || !JammarrSettings.enabled()) return;
        recoveryAttempts = 0; recoveryFailed = false; requestRebuffer("manual retry");
    }

    void audioEngineReloaded() {
        if (manifest != null) requestRebuffer("sound engine reload");
    }

    private void beginStreaming() {
        firstChunkStartMs = -1L;
        decoder = new LegacyStreamingMp3Decoder(manifest.firstChunk(), manifest.totalChunks());
        window = new ChunkWindowTracker(manifest.firstChunk(), manifest.totalChunks(), 8, 1_500L);
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
        recovering = false; recoveryFailed = false; lastHealthSentMs = 0L; lastHealthState = "";
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
        SoundSystem previous = soundSystem;
        soundSystem = null;
        if (previous != null) {
            try {
                previous.stop(SOURCE); previous.flush(SOURCE); previous.removeSource(SOURCE);
            } catch (RuntimeException unavailable) {
                Jammarr.LOGGER.warn("Jammarr legacy sound engine changed during cleanup", unavailable);
            }
        }
        if (decoder != null) { decoder.close(); decoder = null; }
        window = null; started = false; firstChunkStartMs = -1L;
        sourceStartedLocalMs = 0L; sourceStartedPositionMs = 0L; queuedUntilLocalMs = 0L;
    }
}
