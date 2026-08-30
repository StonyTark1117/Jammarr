package stonytark.jammarr.client;

import de.sciss.jump3r.lowlevel.LameEncoder;
import org.junit.jupiter.api.Test;
import stonytark.jammarr.core.server.Mp3FrameIndex;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LegacyStreamingMp3DecoderTest {
    @Test void lateStartupCanTrimInsideADecodedMp3FrameAndDrainBoundedBlocks() throws Exception {
        List<Mp3FrameIndex.Chunk> chunks = Mp3FrameIndex.split(encodeTestTrack());
        verifyTrim(chunks, 0);
        verifyTrim(chunks, 1);
    }

    private static void verifyTrim(List<Mp3FrameIndex.Chunk> chunks, int firstChunk) throws Exception {
        LegacyStreamingMp3Decoder decoder = new LegacyStreamingMp3Decoder(firstChunk, chunks.size());
        try {
            for (int index = firstChunk; index < chunks.size(); index++) {
                Mp3FrameIndex.Chunk chunk = chunks.get(index);
                assertTrue(decoder.offer(chunk.index(), chunk.data()));
            }
            awaitBuffered(decoder, 500L);
            awaitFinished(decoder);
            if (firstChunk > 0) assertTrue(decoder.initialPcmDelayMillis() > 0L,
                    "a mid-stream Layer III decoder must expose its bit-reservoir warm-up delay");

            long before = decoder.bufferedMillis();
            long discarded = decoder.discardMillis(137L);
            long after = decoder.bufferedMillis();
            byte[] drained = decoder.drain(32 * 1024);

            assertTrue(Math.abs(discarded - 137L) <= 1L,
                    "startup trimming must not be quantized to a whole 26 ms MP3 frame");
            assertTrue(Math.abs((before - after) - discarded) <= 1L);
            assertNotNull(drained);
            assertTrue(drained.length <= 32 * 1024, "legacy OpenAL feed blocks must remain bounded");
        } finally {
            decoder.close();
        }
    }

    private static byte[] encodeTestTrack() throws Exception {
        int rate = 44_100;
        AudioFormat source = new AudioFormat(rate, 16, 2, true, false);
        LameEncoder encoder = new LameEncoder(source, 160, LameEncoder.CHANNEL_MODE_STEREO,
                LameEncoder.QUALITY_HIGHEST, false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] pcm = new byte[rate * 2 * 2 * 2];
        ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < rate * 2; frame++) {
            short sample = (short) (Math.sin(frame * Math.PI * 2 * 440 / rate) * 8_000);
            samples.putShort(sample).putShort(sample);
        }
        byte[] encoded = new byte[encoder.getMP3BufferSize()];
        try {
            for (int offset = 0; offset < pcm.length;) {
                int count = Math.min(encoder.getPCMBufferSize(), pcm.length - offset);
                int written = encoder.encodeBuffer(pcm, offset, count, encoded);
                if (written > 0) output.write(encoded, 0, written);
                offset += count;
            }
            int written = encoder.encodeFinish(encoded);
            if (written > 0) output.write(encoded, 0, written);
            return output.toByteArray();
        } finally {
            encoder.close();
        }
    }

    private static void awaitBuffered(LegacyStreamingMp3Decoder decoder, long milliseconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while ((decoder.format() == null || decoder.bufferedMillis() < milliseconds)
                && System.nanoTime() < deadline) Thread.sleep(10L);
        assertTrue(decoder.format() != null && decoder.bufferedMillis() >= milliseconds,
                "legacy MP3 decoder did not produce PCM in time");
    }

    private static void awaitFinished(LegacyStreamingMp3Decoder decoder) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!decoder.finished() && System.nanoTime() < deadline) Thread.sleep(10L);
        assertTrue(decoder.finished(), "legacy MP3 decoder did not finish the test stream");
    }
}
