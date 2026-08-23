package stonytark.jammarr.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class AudioCacheTest {
    @TempDir Path directory;

    @Test void evictsLeastRecentlyUsedWhilePinningCurrentAndNext() throws Exception {
        AudioCache cache = new AudioCache(directory, 900);
        Path first = cache.target("first", 128), second = cache.target("second", 128), third = cache.target("third", 128);
        cache.install(temp("one"), first, Set.of(first));
        Thread.sleep(5); cache.install(temp("two"), second, Set.of(first, second));
        assertTrue(Files.exists(first)); assertTrue(Files.exists(second)); // pinned assets may temporarily exceed the cap
        Thread.sleep(5); cache.install(temp("three"), third, Set.of(second, third));
        assertFalse(Files.exists(first)); assertTrue(Files.exists(second)); assertTrue(Files.exists(third));
    }

    @Test void validatesBeforeAtomicReplacement() throws Exception {
        AudioCache cache = new AudioCache(directory, 10_000); Path target = cache.target("track", 128);
        cache.install(temp("valid"), target, Set.of(target)); byte[] original = Files.readAllBytes(target);
        Path invalid = directory.resolve("bad.part"); Files.writeString(invalid, "not mp3");
        assertThrows(java.io.IOException.class, () -> cache.install(invalid, target, Set.of(target)));
        assertArrayEquals(original, Files.readAllBytes(target));
    }

    @Test void rejectsMonoButAcceptsVariableBitrateStereoAudio() throws Exception {
        AudioCache cache = new AudioCache(directory, 10_000); byte[] mono = stream(); mono[3] = (byte)0xC0;
        Path file = directory.resolve("mono.mp3"); Files.write(file, mono);
        assertThrows(java.io.IOException.class, () -> cache.load(file));
        byte[] variable = variableStream();
        Path variableFile = directory.resolve("variable.mp3"); Files.write(variableFile, variable);
        assertDoesNotThrow(() -> cache.load(variableFile, 160));
    }

    @Test void acceptsAValidCbrCacheEntryAtAnotherBitrate() throws Exception {
        AudioCache cache = new AudioCache(directory, 10_000);
        Path file = directory.resolve("wrong-bitrate.mp3"); Files.write(file, stream());
        assertDoesNotThrow(() -> cache.load(file, 160));
    }

    private Path temp(String name) throws Exception { Path path = directory.resolve(name + ".part"); Files.write(path, stream()); return path; }
    private static byte[] stream() {
        byte[] frame = new byte[417]; frame[0] = (byte)0xff; frame[1] = (byte)0xfb; frame[2] = (byte)0x90; frame[3] = 0;
        byte[] bytes = new byte[frame.length * 2]; System.arraycopy(frame, 0, bytes, 0, frame.length); System.arraycopy(frame, 0, bytes, frame.length, frame.length); return bytes;
    }
    private static byte[] variableStream() {
        byte[] bytes = new byte[417 + 365];
        bytes[0] = (byte)0xff; bytes[1] = (byte)0xfb; bytes[2] = (byte)0x90;
        bytes[417] = (byte)0xff; bytes[418] = (byte)0xfb; bytes[419] = (byte)0x80;
        return bytes;
    }
}
