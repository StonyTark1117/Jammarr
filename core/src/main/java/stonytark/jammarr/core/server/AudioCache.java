package stonytark.jammarr.core.server;

import stonytark.jammarr.core.network.Hashing;
import stonytark.jammarr.core.platform.CoreLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class AudioCache {
    public static final int CACHE_FORMAT_VERSION = 1;
    private final Path directory;
    private final long maxBytes;
    private final CoreLogger logger;
    private final AtomicLong loads = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong invalidEntries = new AtomicLong();
    private final AtomicLong installs = new AtomicLong();

    public AudioCache(Path directory, long maxBytes) throws IOException { this(directory, maxBytes, CoreLogger.NO_OP); }
    public AudioCache(Path directory, long maxBytes, CoreLogger logger) throws IOException {
        this.directory = directory; this.maxBytes = maxBytes; this.logger = logger == null ? CoreLogger.NO_OP : logger;
        Files.createDirectories(directory); removeStaleFormats();
    }

    public Path target(String key, int bitrate) { return directory.resolve(safeName(key) + "-" + bitrate + "-v" + CACHE_FORMAT_VERSION + ".mp3"); }
    public AudioAsset load(Path path) throws IOException { return load(path, -1); }

    public AudioAsset load(Path path, int expectedBitrateKbps) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        try {
            Mp3FrameIndex.Info info = Mp3FrameIndex.inspect(bytes);
            if (info.channels() != 2) throw new IllegalArgumentException("Plex audio must be stereo MP3 format");
            loads.incrementAndGet();
            return new AudioAsset(path, Hashing.sha256(bytes), Mp3FrameIndex.split(bytes), bytes.length, info.durationMs());
        } catch (IllegalArgumentException invalid) {
            invalidEntries.incrementAndGet();
            throw new IOException("Cached Plex audio is not a valid Layer III MP3", invalid);
        }
    }

    public AudioAsset install(Path temporary, Path target, Set<Path> pinned) throws IOException { return install(temporary, target, pinned, -1); }
    public AudioAsset install(Path temporary, Path target, Set<Path> pinned, int expectedBitrateKbps) throws IOException {
        AudioAsset validated = load(temporary, expectedBitrateKbps);
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        AudioAsset result = new AudioAsset(target, validated.sha256(), validated.chunks(), validated.size(), validated.durationMs());
        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        installs.incrementAndGet(); trim(pinned); return result;
    }

    public long size() {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(AudioCache::isCacheFile).mapToLong(path -> {
                try { return Files.size(path); } catch (IOException ignored) { return 0; }
            }).sum();
        } catch (IOException ignored) { return 0; }
    }

    public void trim(Set<Path> pinned) {
        try {
            long size = size(); if (size <= maxBytes) return;
            List<Path> candidates;
            try (Stream<Path> files = Files.list(directory)) {
                candidates = files.filter(AudioCache::isCacheFile).filter(path -> !pinned.contains(path)).collect(Collectors.toList());
            }
            Collections.sort(candidates, new Comparator<Path>() {
                @Override public int compare(Path first, Path second) { return Long.compare(modified(first), modified(second)); }
            });
            for (Path path : candidates) {
                long removed = Files.size(path); Files.deleteIfExists(path); size -= removed;
                if (size <= maxBytes) break;
            }
        } catch (IOException error) { logger.warn("Unable to trim Jammarr audio cache", error); }
    }

    public void recordMiss() { misses.incrementAndGet(); }
    public CacheStats stats() { return new CacheStats(loads.get(), misses.get(), invalidEntries.get(), installs.get()); }

    public static final class CacheStats {
        private final long loads; private final long misses; private final long invalidEntries; private final long installs;
        public CacheStats(long loads, long misses, long invalidEntries, long installs) {
            this.loads = loads; this.misses = misses; this.invalidEntries = invalidEntries; this.installs = installs;
        }
        public long loads() { return loads; }
        public long misses() { return misses; }
        public long invalidEntries() { return invalidEntries; }
        public long installs() { return installs; }
    }

    private static boolean isCacheFile(Path path) { return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".mp3"); }
    private void removeStaleFormats() throws IOException {
        String suffix = "-v" + CACHE_FORMAT_VERSION + ".mp3";
        List<Path> stale = new ArrayList<Path>();
        try (Stream<Path> files = Files.list(directory)) {
            stale.addAll(files.filter(path -> isCacheFile(path) || path.getFileName().toString().endsWith(".part"))
                    .filter(path -> !path.getFileName().toString().endsWith(suffix)).collect(Collectors.toList()));
        }
        for (Path path : stale) { Files.deleteIfExists(path); logger.info("Removed stale Jammarr cache entry " + path.getFileName()); }
    }
    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException ignored) { return Long.MIN_VALUE; }
    }
    private static String safeName(String key) { return Hashing.sha256(key.getBytes(StandardCharsets.UTF_8)).substring(0, 24); }
}
