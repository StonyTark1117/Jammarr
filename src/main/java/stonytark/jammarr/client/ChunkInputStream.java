package stonytark.jammarr.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

final class ChunkInputStream extends InputStream {
    private static final int MAX_PENDING_CHUNKS = 32;
    private final int totalChunks;
    private final Map<Integer, byte[]> pending = new HashMap<>();
    private int index;
    private int offset;
    private boolean closed;

    ChunkInputStream(int firstChunk, int totalChunks) { this.index = firstChunk; this.totalChunks = totalChunks; }
    synchronized boolean offer(int chunkIndex, byte[] bytes) {
        if (closed) return false;
        // A bounded atomic retry can resend chunks the decoder has already
        // consumed. Treat those as accepted duplicates so the tracker can
        // acknowledge the complete retried window to the server.
        if (chunkIndex < index) return true;
        if (pending.containsKey(chunkIndex)) return true;
        if (pending.size() >= MAX_PENDING_CHUNKS) {
            int farthest = pending.keySet().stream().mapToInt(Integer::intValue).max().orElse(chunkIndex);
            if (chunkIndex >= farthest) return false;
            pending.remove(farthest);
        }
        pending.put(chunkIndex, bytes); notifyAll(); return true;
    }
    synchronized boolean canAcceptWindow(int count) {
        return !closed && count > 0 && pending.size() <= MAX_PENDING_CHUNKS - count;
    }

    @Override public synchronized int read() throws IOException {
        byte[] one = new byte[1]; int count = read(one, 0, 1); return count < 0 ? -1 : one[0] & 255;
    }
    @Override public synchronized int read(byte[] bytes, int destination, int length) throws IOException {
        while (!closed && index < totalChunks && !pending.containsKey(index)) {
            try { wait(1_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("MP3 decoder interrupted", e); }
        }
        if (closed || index >= totalChunks) return -1;
        byte[] current = pending.get(index); int count = Math.min(length, current.length - offset);
        System.arraycopy(current, offset, bytes, destination, count); offset += count;
        if (offset == current.length) { pending.remove(index++); offset = 0; }
        return count;
    }
    @Override public synchronized void close() { closed = true; pending.clear(); notifyAll(); }
    synchronized int pendingCount() { return pending.size(); }
}
