package stonytark.jammarr.core.server;

import stonytark.jammarr.core.network.Hashing;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AudioAsset {
    private final Path path; private final String sha256; private final List<Mp3FrameIndex.Chunk> chunks;
    private final long size; private final long durationMs;
    public AudioAsset(Path path, String sha256, List<Mp3FrameIndex.Chunk> chunks, long size, long durationMs) {
        this.path = path; this.sha256 = sha256;
        this.chunks = Collections.unmodifiableList(new ArrayList<Mp3FrameIndex.Chunk>(chunks));
        this.size = size; this.durationMs = durationMs;
    }
    public Path path() { return path; }
    public String sha256() { return sha256; }
    public List<Mp3FrameIndex.Chunk> chunks() { return chunks; }
    public long size() { return size; }
    public long durationMs() { return durationMs; }

    public List<Mp3FrameIndex.Chunk> readChunks(int first, int count) throws IOException {
        if (first < 0 || count < 0 || first > chunks.size() - count) {
            throw new IllegalArgumentException("chunk range");
        }
        List<Mp3FrameIndex.Chunk> result = new ArrayList<Mp3FrameIndex.Chunk>(count);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            for (int index = first; index < first + count; index++) {
                Mp3FrameIndex.Chunk descriptor = chunks.get(index);
                if (!descriptor.fileBacked()) {
                    result.add(descriptor);
                    continue;
                }
                byte[] data = new byte[descriptor.length()];
                file.seek(descriptor.offset());
                file.readFully(data);
                if (!Hashing.matchesSha256(data, descriptor.sha256())) {
                    throw new IOException("Cached audio chunk failed its integrity check");
                }
                result.add(new Mp3FrameIndex.Chunk(descriptor.index(), descriptor.startMs(),
                        descriptor.sha256(), data));
            }
        }
        return Collections.unmodifiableList(result);
    }
}
