package stonytark.pampmod.server;

import stonytark.pampmod.Pampmod;
import stonytark.pampmod.network.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Set;

public final class AudioCache {
    private final Path directory;
    private final long maxBytes;

    public AudioCache(Path directory, long maxBytes) throws IOException {
        this.directory = directory; this.maxBytes = maxBytes; Files.createDirectories(directory);
    }

    public Path target(String key, int bitrate) { return directory.resolve(safeName(key) + "-" + bitrate + ".mp3"); }

    public AudioAsset load(Path path) throws IOException {
        return load(path, -1);
    }

    public AudioAsset load(Path path, int expectedBitrateKbps) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        try {
            Mp3FrameIndex.Info info = Mp3FrameIndex.inspect(bytes);
            if (!info.constantBitrate() || info.channels() != 2 || expectedBitrateKbps > 0 && info.bitrateKbps() != expectedBitrateKbps) {
                throw new IllegalArgumentException("Plex audio must be the configured constant-bitrate stereo MP3 format");
            }
            return new AudioAsset(path, Hashing.sha256(bytes), Mp3FrameIndex.split(bytes), bytes.length, info.durationMs());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Cached Plex audio is not a valid Layer III MP3", invalid);
        }
    }

    public AudioAsset install(Path temporary, Path target, Set<Path> pinned) throws IOException {
        return install(temporary, target, pinned, -1);
    }

    public AudioAsset install(Path temporary, Path target, Set<Path> pinned, int expectedBitrateKbps) throws IOException {
        AudioAsset validated = load(temporary, expectedBitrateKbps);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        AudioAsset result = new AudioAsset(target, validated.sha256(), validated.chunks(), validated.size(), validated.durationMs());
        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        trim(pinned); return result;
    }

    public long size() {
        try (var files = Files.list(directory)) { return files.filter(AudioCache::isCacheFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum(); }
        catch (IOException e) { return 0; }
    }

    public void trim(Set<Path> pinned) {
        try {
            long size = size();
            if (size <= maxBytes) return;
            try (var files = Files.list(directory)) {
                for (Path path : files.filter(AudioCache::isCacheFile).filter(p -> !pinned.contains(p))
                        .sorted(Comparator.comparingLong(p -> { try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return Long.MIN_VALUE; } })).toList()) {
                    long removed = Files.size(path); Files.deleteIfExists(path); size -= removed;
                    if (size <= maxBytes) break;
                }
            }
        } catch (IOException e) { Pampmod.LOGGER.warn("Unable to trim PAmpMod audio cache", e); }
    }

    private static boolean isCacheFile(Path path) { return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".mp3"); }

    private static String safeName(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 24); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
