package stonytark.jammarr.client;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Single-owner OpenAL queue for legacy PCM; Minecraft retains ownership of the context and device. */
final class LegacyOpenAlStream implements AutoCloseable {
    private final int source;
    private final int alFormat;
    private final int sampleRate;
    private final Deque<Integer> reusable = new ArrayDeque<Integer>();
    private final List<Integer> buffers = new ArrayList<Integer>();
    private boolean closed;

    LegacyOpenAlStream(AudioFormat format) {
        if (format == null || format.getSampleSizeInBits() != 16
                || (format.getChannels() != 1 && format.getChannels() != 2)) {
            throw new IllegalArgumentException("Unsupported legacy PCM format");
        }
        this.alFormat = format.getChannels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        this.sampleRate = Math.round(format.getSampleRate());
        this.source = AL10.alGenSources();
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0.0F);
        AL10.alSourcef(source, AL10.AL_GAIN, 0.0F);
    }

    void feed(byte[] pcm) {
        if (closed || pcm == null || pcm.length == 0) return;
        reclaimProcessed();
        Integer reusableBuffer = reusable.pollFirst();
        int buffer = reusableBuffer == null ? AL10.alGenBuffers() : reusableBuffer.intValue();
        if (reusableBuffer == null) buffers.add(buffer);
        ByteBuffer data = BufferUtils.createByteBuffer(pcm.length);
        data.put(pcm).flip();
        AL10.alBufferData(buffer, alFormat, data, sampleRate);
        AL10.alSourceQueueBuffers(source, buffer);
    }

    void play() { if (!closed) AL10.alSourcePlay(source); }
    void pause() { if (!closed) AL10.alSourcePause(source); }
    void gain(float value) { if (!closed) AL10.alSourcef(source, AL10.AL_GAIN, value); }
    boolean playing() { return !closed && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING; }

    String acceptanceDiagnostics() {
        if (closed) return "closed";
        return "state=" + AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE)
                + " queued=" + AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED)
                + " processed=" + AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)
                + " error=" + AL10.alGetError();
    }

    private void reclaimProcessed() {
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        while (processed-- > 0) reusable.addLast(AL10.alSourceUnqueueBuffers(source));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        AL10.alSourceStop(source);
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        while (queued-- > 0) {
            try { AL10.alSourceUnqueueBuffers(source); }
            catch (RuntimeException ignored) { break; }
        }
        for (Integer buffer : buffers) {
            if (AL10.alIsBuffer(buffer.intValue())) AL10.alDeleteBuffers(buffer.intValue());
        }
        if (AL10.alIsSource(source)) AL10.alDeleteSources(source);
        buffers.clear();
        reusable.clear();
    }
}
