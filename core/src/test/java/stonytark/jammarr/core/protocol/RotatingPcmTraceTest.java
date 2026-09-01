package stonytark.jammarr.core.protocol;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RotatingPcmTraceTest {
    @Test
    void retainsOnlyNewestTwoChronologicalSegments() throws Exception {
        Path directory = Files.createTempDirectory("jammarr-pcm-ring");
        try {
            RotatingPcmTrace trace = new RotatingPcmTrace(
                    directory.toFile(), "pcm-feed-42", 8, 2);
            trace.write(new byte[] {0, 1, 2, 3, 4, 5});
            trace.write(new byte[] {6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17});
            trace.close();

            assertFalse(Files.exists(directory.resolve("pcm-feed-42-00000.s16le")));
            assertArrayEquals(
                    new byte[] {8, 9, 10, 11, 12, 13, 14, 15},
                    Files.readAllBytes(directory.resolve("pcm-feed-42-00001.s16le")));
            assertArrayEquals(
                    new byte[] {16, 17},
                    Files.readAllBytes(directory.resolve("pcm-feed-42-00002.s16le")));
        } finally {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted((left, right) -> right.compareTo(left))
                        .forEach(path -> path.toFile().delete());
            }
        }
    }
}
