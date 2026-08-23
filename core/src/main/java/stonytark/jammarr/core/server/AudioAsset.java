package stonytark.jammarr.core.server;

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
}
