package stonytark.jammarr.core.protocol;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Bounded acceptance-only PCM trace retaining the newest complete segments. */
public final class RotatingPcmTrace implements Closeable {
    public static final int DEFAULT_SEGMENT_BYTES = 16 * 1024 * 1024;
    public static final int DEFAULT_RETAINED_SEGMENTS = 2;

    private final File directory;
    private final String prefix;
    private final int segmentBytes;
    private final int retainedSegments;
    private int segment;
    private int written;
    private OutputStream output;

    public static RotatingPcmTrace open(File directory, String prefix) throws IOException {
        return new RotatingPcmTrace(directory, prefix, DEFAULT_SEGMENT_BYTES,
                DEFAULT_RETAINED_SEGMENTS);
    }

    RotatingPcmTrace(File directory, String prefix, int segmentBytes,
                     int retainedSegments) throws IOException {
        if (directory == null || prefix == null || !prefix.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("PCM trace path is invalid");
        }
        if (segmentBytes < 1 || retainedSegments < 2) {
            throw new IllegalArgumentException("PCM trace bounds are invalid");
        }
        this.directory = directory;
        this.prefix = prefix;
        this.segmentBytes = segmentBytes;
        this.retainedSegments = retainedSegments;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create PCM trace directory " + directory);
        }
        openSegment();
    }

    public synchronized void write(byte[] pcm) throws IOException {
        write(pcm, 0, pcm.length);
    }

    public synchronized void write(byte[] pcm, int offset, int count) throws IOException {
        if (output == null) throw new IOException("PCM trace is closed");
        if (pcm == null || offset < 0 || count < 0 || offset + count > pcm.length) {
            throw new IndexOutOfBoundsException();
        }
        int remaining = count;
        int cursor = offset;
        while (remaining > 0) {
            if (written == segmentBytes) rotate();
            int part = Math.min(remaining, segmentBytes - written);
            output.write(pcm, cursor, part);
            written += part;
            cursor += part;
            remaining -= part;
        }
        output.flush();
    }

    public synchronized void flush() throws IOException {
        if (output == null) throw new IOException("PCM trace is closed");
        output.flush();
    }

    private void rotate() throws IOException {
        output.close();
        output = null;
        segment++;
        written = 0;
        File expired = segmentFile(segment - retainedSegments);
        if (expired.isFile() && !expired.delete()) {
            throw new IOException("Cannot remove expired PCM trace segment " + expired);
        }
        openSegment();
    }

    private void openSegment() throws IOException {
        output = new FileOutputStream(segmentFile(segment));
    }

    private File segmentFile(int value) {
        return new File(directory, prefix + "-" + String.format("%05d", value) + ".s16le");
    }

    @Override public synchronized void close() throws IOException {
        if (output == null) return;
        output.close();
        output = null;
    }
}
