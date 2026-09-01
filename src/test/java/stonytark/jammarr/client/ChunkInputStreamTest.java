package stonytark.jammarr.client;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ChunkInputStreamTest {
    @Test void reordersChunksAndBoundsPendingCompressedData() throws Exception {
        ChunkInputStream input = new ChunkInputStream(0, 40);
        for (int i = 39; i >= 0; i--) input.offer(i, new byte[]{(byte)i});
        assertEquals(32, input.pendingCount());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i < 8; i++) output.write(input.read());
        for (int i = 32; i < 40; i++) input.offer(i, new byte[]{(byte)i});
        for (int i = 8; i < 40; i++) output.write(input.read());
        byte[] values = output.toByteArray();
        for (int i = 0; i < 40; i++) assertEquals(i, values[i] & 255);
        assertEquals(-1, input.read());
    }

    @Test void consumedDuplicatesAreAcceptedForAtomicWindowRetries() throws Exception {
        ChunkInputStream input = new ChunkInputStream(32, 40);
        assertTrue(input.offer(32, new byte[]{32}));
        assertEquals(32, input.read());
        assertTrue(input.offer(32, new byte[]{32}));
        assertTrue(input.canAcceptWindow(8));
    }
}
