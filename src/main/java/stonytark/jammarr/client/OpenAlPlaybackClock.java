package stonytark.jammarr.client;

import com.mojang.blaze3d.audio.Channel;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTDeviceClock;
import org.lwjgl.openal.SOFTSourceLatency;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Samples the position that OpenAL reports as reaching the output device. */
final class OpenAlPlaybackClock {
    private static final double FIXED_32_SCALE = 4_294_967_296.0;
    private static volatile Field sourceField;

    /** Returns the current output device latency, or -1 when the backend cannot expose it. */
    static long deviceLatencyMillis() {
        try {
            long context = ALC10.alcGetCurrentContext();
            if (context == 0) return -1;
            long device = ALC10.alcGetContextsDevice(context);
            if (device == 0 || !ALC10.alcIsExtensionPresent(device, "ALC_SOFT_device_clock")) return -1;
            long measured = latencyMillis(SOFTDeviceClock.alcGetInteger64vSOFT(
                    device, SOFTDeviceClock.ALC_DEVICE_LATENCY_SOFT));
            // OpenAL Soft can report zero before the first source wakes the
            // output device. Do not replace a learned source latency with it.
            return measured > 0 ? measured : -1;
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    static Position sample(Channel channel, PcmAudioStream stream) {
        if (channel == null || stream == null || !AL.getCapabilities().AL_SOFT_source_latency) return null;
        int source = source(channel);
        if (source <= 0 || !AL10.alIsSource(source)) return null;
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        if (state != AL10.AL_PLAYING && state != AL10.AL_PAUSED) return null;
        int queuedBuffers = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        PcmSubmissionTracker.Snapshot queue = stream.submissionSnapshot(queuedBuffers);
        if (queue == null) return null;

        long[] offsetAndLatency = new long[2];
        SOFTSourceLatency.alGetSourcei64vSOFT(
                source, SOFTSourceLatency.AL_SAMPLE_OFFSET_LATENCY_SOFT, offsetAndLatency);
        float sampleRate = stream.getFormat().getSampleRate();
        long playedMillis = calculatePlayedMillis(
                queue.submittedFrames(), queue.queuedFrames(), offsetAndLatency[0],
                offsetAndLatency[1], sampleRate);
        if (playedMillis < 0) return null;
        return new Position(playedMillis, latencyMillis(offsetAndLatency[1]));
    }

    static long latencyMillis(long latencyNanos) {
        if (latencyNanos < 0) return -1;
        return Math.round(latencyNanos / 1_000_000.0);
    }

    static long calculatePlayedMillis(long submittedFrames, long queuedFrames,
                                      long sampleOffsetFixed, long latencyNanos,
                                      float sampleRate) {
        if (submittedFrames < 0 || queuedFrames < 0 || queuedFrames > submittedFrames
                || sampleOffsetFixed < 0 || latencyNanos < 0
                || !Float.isFinite(sampleRate) || sampleRate <= 0) return -1;
        double offsetFrames = sampleOffsetFixed / FIXED_32_SCALE;
        if (offsetFrames > queuedFrames) return -1;
        double latencyFrames = latencyNanos * (double) sampleRate / 1_000_000_000.0;
        double heardFrames = submittedFrames - queuedFrames + offsetFrames - latencyFrames;
        return Math.max(0, Math.round(heardFrames * 1_000.0 / sampleRate));
    }

    private static int source(Channel channel) {
        Field cached = sourceField;
        if (cached != null) {
            int value = read(cached, channel);
            if (value > 0 && AL10.alIsSource(value)) return value;
        }
        for (Class<?> type = channel.getClass(); type != null; type = type.getSuperclass()) {
            for (Field candidate : type.getDeclaredFields()) {
                int modifiers = candidate.getModifiers();
                if (candidate.getType() != int.class || Modifier.isStatic(modifiers)
                        || !Modifier.isFinal(modifiers)) continue;
                try {
                    candidate.setAccessible(true);
                    int value = candidate.getInt(channel);
                    if (value > 0 && AL10.alIsSource(value)) {
                        sourceField = candidate;
                        return value;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return -1;
    }

    private static int read(Field field, Channel channel) {
        try {
            return field.getInt(channel);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            sourceField = null;
            return -1;
        }
    }

    static final class Position {
        private final long playedMillis;
        private final long deviceLatencyMillis;

        Position(long playedMillis, long deviceLatencyMillis) {
            this.playedMillis = playedMillis;
            this.deviceLatencyMillis = deviceLatencyMillis;
        }

        long playedMillis() { return playedMillis; }
        long deviceLatencyMillis() { return deviceLatencyMillis; }
    }

    private OpenAlPlaybackClock() {}
}
