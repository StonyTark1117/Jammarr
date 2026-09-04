package stonytark.jammarr.client;

import com.mojang.blaze3d.audio.Channel;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTDeviceClock;
import org.lwjgl.openal.SOFTSourceLatency;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Samples the position that OpenAL reports as reaching the output device. */
final class OpenAlPlaybackClock {
    private static volatile Field sourceField;

    static long deviceLatencyMillis() {
        try {
            long context = ALC10.alcGetCurrentContext();
            if (context == 0) return -1;
            long device = ALC10.alcGetContextsDevice(context);
            if (device == 0 || !ALC10.alcIsExtensionPresent(device, "ALC_SOFT_device_clock")) return -1;
            long measured = OpenAlPlaybackMath.latencyMillis(SOFTDeviceClock.alcGetInteger64vSOFT(
                    device, SOFTDeviceClock.ALC_DEVICE_LATENCY_SOFT));
            return measured > 0 ? measured : -1;
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    /** Skips buffer-preparation time before any queued sample becomes audible. */
    static boolean alignBeforeStart(Channel channel, LegacyPcmAudioStream stream, long offsetMillis) {
        if (offsetMillis <= 0) return true;
        int source = source(channel);
        if (source <= 0 || !AL10.alIsSource(source)
                || AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_INITIAL) return false;
        PcmSubmissionTracker.Snapshot queue = stream.submissionSnapshot(
                AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED));
        long frames = Math.round(offsetMillis * (double) stream.getFormat().getSampleRate() / 1_000.0);
        if (queue == null || frames < 0 || frames >= queue.queuedFrames()
                || frames > Integer.MAX_VALUE) return false;
        // OpenAL applies an initial source offset on the next play call. Keep
        // the submission history intact: its position includes this offset.
        AL10.alSourcei(source, AL11.AL_SAMPLE_OFFSET, (int) frames);
        return AL10.alGetError() == AL10.AL_NO_ERROR;
    }

    static Position sample(Channel channel, LegacyPcmAudioStream stream) {
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
        long playedMillis = OpenAlPlaybackMath.playedMillis(
                queue.submittedFrames(), queue.queuedFrames(), offsetAndLatency[0],
                offsetAndLatency[1], stream.getFormat().getSampleRate());
        if (playedMillis < 0) return null;
        return new Position(playedMillis, OpenAlPlaybackMath.latencyMillis(offsetAndLatency[1]));
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
