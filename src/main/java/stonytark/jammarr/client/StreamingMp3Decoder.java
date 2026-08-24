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
import java.util.concurrent.atomic.AtomicLong;

final class StreamingMp3Decoder implements AutoCloseable {
    static final long MAX_BUFFERED_MS = 12_000;
    private final ChunkInputStream input;
    private final Queue<byte[]> pcm = new ConcurrentLinkedQueue<>();
    private final AtomicLong bufferedBytes = new AtomicLong();
    private final Object flowControl = new Object();
    private final Object pcmAvailable = new Object();
    private final Thread thread;
    private volatile AudioFormat format;
    private volatile boolean closed;
    private volatile boolean finished;
    private volatile String failure;

    StreamingMp3Decoder(int firstChunk, int totalChunks) {
        input = new ChunkInputStream(firstChunk, totalChunks);
        thread = new Thread(this::decode, "jammarr-mp3-decoder");
        thread.setDaemon(true);
        thread.start();
    }
    boolean offer(int index, byte[] bytes) { return input.offer(index, bytes); }
    AudioFormat format() { return format; }
    String failure() { return failure; }
    boolean finished() { return finished; }
    long bufferedMillis() {
        AudioFormat value = format; if (value == null) return 0;
        return bufferedBytes.get() * 1000L / Math.max(1, (long)value.getSampleRate() * value.getChannels() * 2L);
    }
    byte[] poll() {
        byte[] value = pcm.poll();
        while (value == null && !finished && !closed) {
            synchronized (pcmAvailable) {
                value = pcm.poll();
                if (value == null && !finished && !closed) {
                    try { pcmAvailable.wait(250); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    value = pcm.poll();
                }
            }
        }
        if (value != null) {
            bufferedBytes.addAndGet(-value.length);
            synchronized (flowControl) { flowControl.notifyAll(); }
        }
        return value;
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
                int length = samples.getBufferLength(); ByteBuffer bytes = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN);
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
        thread.interrupt(); pcm.clear(); bufferedBytes.set(0);
    }
}
