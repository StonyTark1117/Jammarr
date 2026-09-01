package stonytark.jammarr.client;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import stonytark.jammarr.Jammarr;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class StreamingMp3Decoder implements AutoCloseable {
    static final long MAX_BUFFERED_MS = 12_000;
    private static final long PCM_WAIT_MS = 100;
    private final ChunkInputStream input;
    private final Queue<byte[]> pcm = new ConcurrentLinkedQueue<>();
    private final AtomicLong bufferedBytes = new AtomicLong();
    private final Object flowControl = new Object();
    private final Object pcmAvailable = new Object();
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

    StreamingMp3Decoder(int firstChunk, int totalChunks) {
        input = new ChunkInputStream(firstChunk, totalChunks);
        thread = new Thread(this::decode, "jammarr-mp3-decoder");
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
        AudioFormat value = format; if (value == null) return 0;
        return bufferedBytes.get() * 1000L / Math.max(1, (long)value.getSampleRate() * value.getChannels() * 2L);
    }
    long discardMillis(long requestedMillis) {
        AudioFormat value = format;
        if (value == null || requestedMillis <= 0) return 0;
        long bytesPerSecond = Math.max(1, (long)value.getSampleRate() * value.getChannels() * 2L);
        int frameBytes = value.getChannels() * 2;
        long requestedBytes = requestedMillis > Long.MAX_VALUE / bytesPerSecond
                ? Long.MAX_VALUE : requestedMillis * bytesPerSecond / 1_000L;
        requestedBytes -= requestedBytes % frameBytes;
        long discardedBytes = 0;
        while (discardedBytes < requestedBytes) {
            if (!ensureConsumerBuffer()) break;
            int available = consumerBuffer.length - consumerOffset;
            int count = (int)Math.min((long)available, requestedBytes - discardedBytes);
            count -= count % frameBytes;
            if (count == 0) break;
            consumerOffset += count;
            discardedBytes += count;
            bufferedBytes.addAndGet(-count);
            releaseConsumedBuffer();
        }
        if (discardedBytes > 0) {
            synchronized (flowControl) { flowControl.notifyAll(); }
        }
        return discardedBytes * 1_000L / bytesPerSecond;
    }
    byte[] poll() {
        byte[] value = takeConsumerBuffer();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PCM_WAIT_MS);
        while (value == null && !finished && !closed) {
            synchronized (pcmAvailable) {
                value = takeConsumerBuffer();
                if (value == null && !finished && !closed) {
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) break;
                    long waitMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                    try { pcmAvailable.wait(waitMillis); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    value = takeConsumerBuffer();
                }
            }
        }
        if (value != null) {
            bufferedBytes.addAndGet(-value.length);
            synchronized (flowControl) { flowControl.notifyAll(); }
        }
        return value;
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

    private byte[] takeConsumerBuffer() {
        if (!ensureConsumerBuffer()) return null;
        byte[] result;
        if (consumerOffset == 0) result = consumerBuffer;
        else {
            result = new byte[consumerBuffer.length - consumerOffset];
            System.arraycopy(consumerBuffer, consumerOffset, result, 0, result.length);
        }
        consumerBuffer = null;
        consumerOffset = 0;
        return result;
    }

    private void releaseConsumedBuffer() {
        if (consumerBuffer != null && consumerOffset == consumerBuffer.length) {
            consumerBuffer = null;
            consumerOffset = 0;
        }
    }

    private void decode() {
        Bitstream stream = new Bitstream(input);
        try {
            Decoder decoder = new Decoder(); Header header;
            while (!closed && (header = stream.readFrame()) != null) {
                synchronized (flowControl) {
                    while (!closed && bufferedMillis() >= MAX_BUFFERED_MS) flowControl.wait(250);
                }
                if (closed) break;
                SampleBuffer samples = (SampleBuffer)decoder.decodeFrame(header, stream);
                if (format == null) format = new AudioFormat(samples.getSampleFrequency(), 16, samples.getChannelCount(), true, false);
                int length = samples.getBufferLength();
                if (!producedPcm) {
                    if (length == 0) {
                        initialEmptySamples += header.version() == Header.MPEG1 ? 1_152L : 576L;
                        initialPcmDelayMs = initialEmptySamples * 1_000L / samples.getSampleFrequency();
                    } else producedPcm = true;
                }
                ByteBuffer bytes = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN);
                short[] values = samples.getBuffer(); for (int i = 0; i < length; i++) bytes.putShort(values[i]);
                byte[] result = bytes.array();
                pcm.add(result); bufferedBytes.addAndGet(result.length);
                synchronized (pcmAvailable) { pcmAvailable.notifyAll(); }
                stream.closeFrame();
            }
        } catch (Exception e) {
            if (!closed) {
                failure = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                Jammarr.LOGGER.warn("Jammarr MP3 decoder stopped", e);
            }
        } finally {
            finished = true;
            synchronized (pcmAvailable) { pcmAvailable.notifyAll(); }
            try { stream.close(); } catch (Exception ignored) {}
        }
    }
    @Override public void close() {
        closed = true; input.close();
        synchronized (flowControl) { flowControl.notifyAll(); }
        synchronized (pcmAvailable) { pcmAvailable.notifyAll(); }
        thread.interrupt(); pcm.clear(); consumerBuffer = null; consumerOffset = 0; bufferedBytes.set(0);
    }
}
