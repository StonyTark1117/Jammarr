package stonytark.jammarr.core.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioCacheTest {
    @TempDir Path directory;

    @Test void evictsLeastRecentlyUsedWhilePinningCurrentAndNext() throws Exception {
        AudioCache cache = new AudioCache(directory, 900);
        Path first = cache.target("first", 128), second = cache.target("second", 128), third = cache.target("third", 128);
        cache.install(temp("one"), first, Collections.singleton(first));
        Thread.sleep(5); cache.install(temp("two"), second, new HashSet<Path>(Arrays.asList(first, second)));
        assertTrue(Files.exists(first)); assertTrue(Files.exists(second));
        Thread.sleep(5); cache.install(temp("three"), third, new HashSet<Path>(Arrays.asList(second, third)));
        assertFalse(Files.exists(first)); assertTrue(Files.exists(second)); assertTrue(Files.exists(third));
    }

    @Test void validatesBeforeAtomicReplacement() throws Exception {
        AudioCache cache = new AudioCache(directory, 10_000); Path target = cache.target("track", 128);
        cache.install(temp("valid"), target, Collections.singleton(target)); byte[] original = Files.readAllBytes(target);
        Path invalid = directory.resolve("bad.part"); Files.write(invalid, "not mp3".getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> cache.install(invalid, target, Collections.singleton(target)));
        assertArrayEquals(original, Files.readAllBytes(target));
    }

    @Test void rejectsMonoButAcceptsVariableBitrateStereoAudio() throws Exception {
        AudioCache cache = new AudioCache(directory, 10_000); byte[] mono = stream(); mono[3] = (byte)0xC0;
        Path file = directory.resolve("mono.mp3"); Files.write(file, mono);
        assertThrows(java.io.IOException.class, () -> cache.load(file));
        Path variableFile = directory.resolve("variable.mp3"); Files.write(variableFile, variableStream());
        assertDoesNotThrow(() -> cache.load(variableFile, 160));
    }

    @Test void acceptsAValidCbrCacheEntryAtAnotherBitrate() throws Exception {
        AudioCache cache = new AudioCache(directory, 10_000);
        Path file = directory.resolve("wrong-bitrate.mp3"); Files.write(file, stream());
        assertDoesNotThrow(() -> cache.load(file, 160));
    }

    @Test void exposesCacheValidationAndInstallCounters() throws Exception {
        AudioCache cache = new AudioCache(directory, 10_000); Path target = cache.target("stats", 160);
        cache.install(temp("stats"), target, Collections.singleton(target)); cache.load(target, 160);
        AudioCache.CacheStats stats = cache.stats();
        assertEquals(2, stats.loads()); assertEquals(0, stats.misses()); assertEquals(1, stats.installs()); assertEquals(0, stats.invalidEntries());
    }

    @Test void versionsCacheTargetsAndRemovesOldOrPartialEntriesOnStartup() throws Exception {
        Files.write(directory.resolve("legacy-128.mp3"), stream());
        Files.write(directory.resolve("interrupted.part"), "partial".getBytes(StandardCharsets.UTF_8));
        AudioCache cache = new AudioCache(directory, 10_000);
        assertFalse(Files.exists(directory.resolve("legacy-128.mp3"))); assertFalse(Files.exists(directory.resolve("interrupted.part")));
        assertTrue(cache.target("versioned", 160).getFileName().toString().endsWith("-v" + AudioCache.CACHE_FORMAT_VERSION + ".mp3"));
    }

    @Test void keepsOnlyFrameOffsetsInMemoryAndReadsBoundedVerifiedWindows() throws Exception {
        AudioCache cache = new AudioCache(directory, 1_000_000);
        byte[] bytes = repeatedStream(100);
        Path file = directory.resolve("indexed-v" + AudioCache.CACHE_FORMAT_VERSION + ".mp3");
        Files.write(file, bytes);

        AudioAsset asset = cache.load(file, 128);
        assertTrue(asset.chunks().size() > 1);
        for (Mp3FrameIndex.Chunk chunk : asset.chunks()) {
            assertTrue(chunk.fileBacked());
            assertTrue(chunk.length() <= Mp3FrameIndex.MAX_CHUNK_BYTES);
        }
        java.io.ByteArrayOutputStream restored = new java.io.ByteArrayOutputStream();
        for (Mp3FrameIndex.Chunk chunk : asset.readChunks(0, asset.chunks().size())) {
            restored.write(chunk.data());
        }
        assertArrayEquals(bytes, restored.toByteArray());

        bytes[asset.chunks().get(0).offset() + 10] ^= 1;
        Files.write(file, bytes);
        assertThrows(java.io.IOException.class, () -> asset.readChunks(0, 1));
    }

    private Path temp(String name) throws Exception { Path path = directory.resolve(name + ".part"); Files.write(path, stream()); return path; }
    private static byte[] stream() {
        byte[] frame = new byte[417]; frame[0] = (byte)0xff; frame[1] = (byte)0xfb; frame[2] = (byte)0x90; frame[3] = 0;
        byte[] bytes = new byte[frame.length * 2]; System.arraycopy(frame, 0, bytes, 0, frame.length); System.arraycopy(frame, 0, bytes, frame.length, frame.length); return bytes;
    }
    private static byte[] variableStream() {
        byte[] bytes = new byte[417 + 365]; bytes[0] = (byte)0xff; bytes[1] = (byte)0xfb; bytes[2] = (byte)0x90;
        bytes[417] = (byte)0xff; bytes[418] = (byte)0xfb; bytes[419] = (byte)0x80; return bytes;
    }
    private static byte[] repeatedStream(int frames) {
        byte[] frame = stream();
        frame = Arrays.copyOf(frame, frame.length / 2);
        byte[] bytes = new byte[frame.length * frames];
        for (int index = 0; index < frames; index++) {
            System.arraycopy(frame, 0, bytes, index * frame.length, frame.length);
        }
        return bytes;
    }
}
