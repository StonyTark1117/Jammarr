package stonytark.jammarr.client;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Tracks the PCM buffers handed to Minecraft's streaming OpenAL queue. */
final class PcmSubmissionTracker {
    private static final int MAX_RETAINED_BUFFERS = 32;
    private final ArrayDeque<Long> recentBufferFrames = new ArrayDeque<>();
    private long submittedFrames;
    private boolean reliable = true;

    synchronized void submitted(int bytes, int frameSize) {
        if (bytes <= 0 || frameSize <= 0 || bytes % frameSize != 0) {
            reliable = false;
            return;
        }
        long frames = bytes / frameSize;
        submittedFrames += frames;
        recentBufferFrames.addLast(frames);
        while (recentBufferFrames.size() > MAX_RETAINED_BUFFERS) {
            recentBufferFrames.removeFirst();
        }
    }

    synchronized Snapshot snapshot(int queuedBuffers) {
        if (!reliable || queuedBuffers < 0 || queuedBuffers > recentBufferFrames.size()) return null;
        long queuedFrames = 0;
        int remaining = queuedBuffers;
        Iterator<Long> newestFirst = recentBufferFrames.descendingIterator();
        while (remaining-- > 0 && newestFirst.hasNext()) queuedFrames += newestFirst.next();
        return new Snapshot(submittedFrames, queuedFrames);
    }

    static final class Snapshot {
        private final long submittedFrames;
        private final long queuedFrames;

        Snapshot(long submittedFrames, long queuedFrames) {
            this.submittedFrames = submittedFrames;
            this.queuedFrames = queuedFrames;
        }

        long submittedFrames() { return submittedFrames; }
        long queuedFrames() { return queuedFrames; }
    }
}
