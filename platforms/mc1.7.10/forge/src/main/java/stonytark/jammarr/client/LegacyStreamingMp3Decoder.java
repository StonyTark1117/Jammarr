package stonytark.jammarr.client;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import stonytark.jammarr.Jammarr;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

final class LegacyStreamingMp3Decoder implements AutoCloseable {
    static final long MAX_BUFFERED_MS = 12_000L;
    private final LegacyChunkInputStream input;
    private final Queue<byte[]> pcm = new ConcurrentLinkedQueue<byte[]>();
    private final AtomicLong bufferedBytes = new AtomicLong();
    private final Object flowControl = new Object();
    private final Thread thread;
    private volatile AudioFormat format;
    private volatile boolean closed;
    private volatile boolean finished;
    private volatile String failure;

    LegacyStreamingMp3Decoder(int firstChunk, int totalChunks) {
        input = new LegacyChunkInputStream(firstChunk, totalChunks);
        thread = new Thread(new Runnable() { @Override public void run() { decode(); } }, "jammarr-legacy-mp3-decoder");
        thread.setDaemon(true);
        thread.start();
    }

    boolean offer(int index, byte[] bytes) { return input.offer(index, bytes); }
    AudioFormat format() { return format; }
    String failure() { return failure; }
    boolean finished() { return finished; }

    long bufferedMillis() {
        AudioFormat value = format;
        if (value == null) return 0L;
        return bufferedBytes.get() * 1_000L
                / Math.max(1L, (long) value.getSampleRate() * value.getChannels() * 2L);
    }

    long discardMillis(long requestedMillis) {
        AudioFormat value = format;
        if (value == null || requestedMillis <= 0L) return 0L;
        long bytesPerSecond = Math.max(1L,
                (long) value.getSampleRate() * value.getChannels() * 2L);
        long requestedBytes = requestedMillis * bytesPerSecond / 1_000L;
        long discardedBytes = 0L;
        byte[] next;
        while ((next = pcm.peek()) != null && discardedBytes + next.length <= requestedBytes) {
            if (pcm.poll() != next) continue;
            discardedBytes += next.length;
            bufferedBytes.addAndGet(-next.length);
        }
        if (discardedBytes > 0L) {
            synchronized (flowControl) { flowControl.notifyAll(); }
        }
        return discardedBytes * 1_000L / bytesPerSecond;
    }

    byte[] drain(int maximumBytes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(maximumBytes);
        while (output.size() < maximumBytes) {
            byte[] value = pcm.poll();
            if (value == null) break;
            output.write(value, 0, value.length);
            bufferedBytes.addAndGet(-value.length);
        }
        if (output.size() == 0) return null;
        synchronized (flowControl) { flowControl.notifyAll(); }
        return output.toByteArray();
    }

    long durationMs(byte[] bytes) {
        AudioFormat value = format;
        if (value == null || bytes == null) return 0L;
        return bytes.length * 1_000L
                / Math.max(1L, (long) value.getSampleRate() * value.getChannels() * 2L);
    }

    private void decode() {
        Bitstream stream = new Bitstream(input);
        try {
            Decoder decoder = new Decoder();
            Header header;
            while (!closed && (header = stream.readFrame()) != null) {
                synchronized (flowControl) {
                    while (!closed && bufferedMillis() >= MAX_BUFFERED_MS) flowControl.wait(250L);
                }
                if (closed) break;
                SampleBuffer samples = (SampleBuffer) decoder.decodeFrame(header, stream);
                if (format == null) format = new AudioFormat(samples.getSampleFrequency(), 16,
                        samples.getChannelCount(), true, false);
                int length = samples.getBufferLength();
                ByteBuffer bytes = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN);
                short[] values = samples.getBuffer();
                for (int index = 0; index < length; index++) bytes.putShort(values[index]);
                byte[] result = bytes.array();
                pcm.add(result); bufferedBytes.addAndGet(result.length);
                stream.closeFrame();
            }
        } catch (Exception error) {
            if (!closed) {
                failure = error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : ": " + error.getMessage());
                Jammarr.LOGGER.warn("Jammarr legacy MP3 decoder stopped", error);
            }
        } finally {
            finished = true;
            try { stream.close(); } catch (Exception ignored) {}
        }
    }

    @Override public void close() {
        closed = true; input.close();
        synchronized (flowControl) { flowControl.notifyAll(); }
        thread.interrupt(); pcm.clear(); bufferedBytes.set(0L);
    }
}
