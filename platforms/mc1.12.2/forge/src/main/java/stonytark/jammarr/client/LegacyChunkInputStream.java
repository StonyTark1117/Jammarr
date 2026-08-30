package stonytark.jammarr.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

final class LegacyChunkInputStream extends InputStream {
    private static final int MAX_PENDING_CHUNKS = 32;
    private final int totalChunks;
    private final Map<Integer, byte[]> pending = new HashMap<Integer, byte[]>();
    private int index;
    private int offset;
    private boolean closed;

    LegacyChunkInputStream(int firstChunk, int totalChunks) {
        this.index = firstChunk;
        this.totalChunks = totalChunks;
    }

    synchronized boolean offer(int chunkIndex, byte[] bytes) {
        if (closed || bytes == null || chunkIndex < index) return false;
        if (pending.containsKey(chunkIndex)) return true;
        if (pending.size() >= MAX_PENDING_CHUNKS) {
            int farthest = chunkIndex;
            for (Integer value : pending.keySet()) farthest = Math.max(farthest, value);
            if (chunkIndex >= farthest) return false;
            pending.remove(farthest);
        }
        pending.put(chunkIndex, bytes);
        notifyAll();
        return true;
    }

    @Override public synchronized int read() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 0, 1);
        return count < 0 ? -1 : one[0] & 255;
    }

    @Override public synchronized int read(byte[] bytes, int destination, int length) throws IOException {
        while (!closed && index < totalChunks && !pending.containsKey(index)) {
            try { wait(1_000L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("MP3 decoder interrupted", interrupted);
            }
        }
        if (closed || index >= totalChunks) return -1;
        byte[] current = pending.get(index);
        int count = Math.min(length, current.length - offset);
        System.arraycopy(current, offset, bytes, destination, count);
        offset += count;
        if (offset == current.length) { pending.remove(index++); offset = 0; }
        return count;
    }

    @Override public synchronized void close() {
        closed = true; pending.clear(); notifyAll();
    }
}
