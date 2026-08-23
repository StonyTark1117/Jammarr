package stonytark.jammarr.core.server;

import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.network.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mp3FrameIndexTest {
    @Test void splitsOnlyOnFrameBoundariesAndFindsTime() {
        byte[] frame = frame(); byte[] stream = new byte[frame.length * 100];
        for (int i = 0; i < 100; i++) System.arraycopy(frame, 0, stream, i * frame.length, frame.length);
        List<Mp3FrameIndex.Chunk> chunks = Mp3FrameIndex.split(stream);
        assertTrue(chunks.size() >= 3);
        for (Mp3FrameIndex.Chunk chunk : chunks) {
            assertTrue(chunk.data().length <= Mp3FrameIndex.MAX_CHUNK_BYTES);
            assertTrue(Hashing.matchesSha256(chunk.data(), chunk.sha256()));
        }
        assertArrayEquals(new byte[]{(byte)0xff, (byte)0xfb, (byte)0x90, 0}, Arrays.copyOf(chunks.get(1).data(), 4));
        for (int i = 1; i < chunks.size(); i++) assertTrue(chunks.get(i).startMs() > chunks.get(i - 1).startMs());
        int selected = Mp3FrameIndex.chunkAt(chunks, chunks.get(1).startMs() + 1);
        assertEquals(1, selected);
    }

    @Test void ignoresId3v2Prefix() {
        byte[] frame = frame(); byte[] stream = new byte[20 + frame.length];
        stream[0] = 'I'; stream[1] = 'D'; stream[2] = '3'; stream[9] = 10;
        System.arraycopy(frame, 0, stream, 20, frame.length);
        assertEquals(frame.length, Mp3FrameIndex.split(stream).get(0).data().length);
    }

    @Test void rejectsNonMp3Responses() {
        assertThrows(IllegalArgumentException.class, () -> Mp3FrameIndex.split("not audio".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void identifiesConstantBitrateStereoAndExpectedBandwidth() {
        byte[] frame = frame(); byte[] stream = new byte[frame.length * 100];
        for (int i = 0; i < 100; i++) System.arraycopy(frame, 0, stream, i * frame.length, frame.length);
        Mp3FrameIndex.Info info = Mp3FrameIndex.inspect(stream);
        assertTrue(info.constantBitrate()); assertEquals(2, info.channels()); assertEquals(128, info.bitrateKbps());
        double bytesPerSecond = stream.length / (info.durationMs() / 1000.0);
        assertTrue(bytesPerSecond > 15_000 && bytesPerSecond < 17_000, "128 kbps should use about 16 KB/s");
    }

    @Test void default160KbpsTransportUsesAbout20KilobytesPerListenerSecond() {
        byte[] frame = frame160(); byte[] stream = new byte[frame.length * 1_000];
        for (int i = 0; i < 1_000; i++) System.arraycopy(frame, 0, stream, i * frame.length, frame.length);
        Mp3FrameIndex.Info info = Mp3FrameIndex.inspect(stream);
        double perListener = stream.length / (info.durationMs() / 1000.0);
        assertEquals(160, info.bitrateKbps());
        assertTrue(perListener > 19_500 && perListener < 20_500, "160 kbps should use about 20 KB/s");
        assertTrue(perListener * 32 < 656_000, "32-listener payload soak must remain linear with modest framing overhead headroom");
    }

    private static byte[] frame() {
        byte[] frame = new byte[417]; frame[0] = (byte)0xff; frame[1] = (byte)0xfb; frame[2] = (byte)0x90; frame[3] = 0; return frame;
    }

    private static byte[] frame160() {
        byte[] frame = new byte[522]; frame[0] = (byte)0xff; frame[1] = (byte)0xfb; frame[2] = (byte)0xa0; frame[3] = 0; return frame;
    }
}
