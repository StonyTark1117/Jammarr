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
    private byte[] consumerBuffer;
    private int consumerOffset;
    private volatile AudioFormat format;
    private volatile long initialPcmDelayMs;
    private volatile boolean closed;
    private volatile boolean finished;
    private volatile String failure;
    private long initialEmptySamples;
    private boolean producedPcm;

    LegacyStreamingMp3Decoder(int firstChunk, int totalChunks) {
        input = new LegacyChunkInputStream(firstChunk, totalChunks);
        thread = new Thread(new Runnable() { @Override public void run() { decode(); } }, "jammarr-legacy-mp3-decoder");
        thread.setDaemon(true);
        thread.start();
    }

    boolean offer(int index, byte[] bytes) { return input.offer(index, bytes); }
    boolean canAcceptWindow(int count) { return input.canAcceptWindow(count); }
    AudioFormat format() { return format; }
    String failure() { return failure; }
    boolean finished() { return finished; }
    long initialPcmDelayMillis() { return initialPcmDelayMs; }

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
        int frameBytes = value.getChannels() * 2;
        long requestedBytes = requestedMillis > Long.MAX_VALUE / bytesPerSecond
                ? Long.MAX_VALUE : requestedMillis * bytesPerSecond / 1_000L;
        requestedBytes -= requestedBytes % frameBytes;
        long discardedBytes = 0L;
        while (discardedBytes < requestedBytes) {
            if (!ensureConsumerBuffer()) break;
            int available = consumerBuffer.length - consumerOffset;
            int count = (int) Math.min((long) available, requestedBytes - discardedBytes);
            count -= count % frameBytes;
            if (count == 0) break;
            consumerOffset += count;
            discardedBytes += count;
            bufferedBytes.addAndGet(-count);
            releaseConsumedBuffer();
        }
        if (discardedBytes > 0L) {
            synchronized (flowControl) { flowControl.notifyAll(); }
        }
        return discardedBytes * 1_000L / bytesPerSecond;
    }

    byte[] drain(int maximumBytes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(maximumBytes);
        while (output.size() < maximumBytes) {
            if (!ensureConsumerBuffer()) break;
            int count = Math.min(maximumBytes - output.size(), consumerBuffer.length - consumerOffset);
            output.write(consumerBuffer, consumerOffset, count);
            consumerOffset += count;
            bufferedBytes.addAndGet(-count);
            releaseConsumedBuffer();
        }
        if (output.size() == 0) return null;
        synchronized (flowControl) { flowControl.notifyAll(); }
        return output.toByteArray();
    }

    private boolean ensureConsumerBuffer() {
        if (consumerBuffer != null && consumerOffset < consumerBuffer.length) return true;
        do {
            consumerBuffer = pcm.poll();
            consumerOffset = 0;
            if (consumerBuffer == null) return false;
        } while (consumerBuffer.length == 0);
        return true;
    }

    private void releaseConsumedBuffer() {
        if (consumerBuffer != null && consumerOffset == consumerBuffer.length) {
            consumerBuffer = null;
            consumerOffset = 0;
        }
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
                if (!producedPcm) {
                    if (length == 0) {
                        initialEmptySamples += header.version() == Header.MPEG1 ? 1_152L : 576L;
                        initialPcmDelayMs = initialEmptySamples * 1_000L / samples.getSampleFrequency();
                    } else producedPcm = true;
                }
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
        thread.interrupt(); pcm.clear(); consumerBuffer = null; consumerOffset = 0; bufferedBytes.set(0L);
    }
}
