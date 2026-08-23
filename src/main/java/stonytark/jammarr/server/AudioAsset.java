package stonytark.jammarr.server;

import java.nio.file.Path;
import java.util.List;

public record AudioAsset(Path path, String sha256, List<Mp3FrameIndex.Chunk> chunks, long size, long durationMs) {}
