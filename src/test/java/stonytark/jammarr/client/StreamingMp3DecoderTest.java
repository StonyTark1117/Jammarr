package stonytark.jammarr.client;

import stonytark.jammarr.core.server.Mp3FrameIndex;
import de.sciss.jump3r.lowlevel.LameEncoder;
import org.junit.jupiter.api.Test;
import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class StreamingMp3DecoderTest {
    @Test
    void pcmStreamYieldsTheSoundExecutorWhenLiveDecoderHasNoPcm() throws Exception {
        StreamingMp3Decoder decoder = new StreamingMp3Decoder(0, 1);
        try {
            PcmAudioStream stream = new PcmAudioStream(decoder);
            CompletableFuture<ByteBuffer> pending = CompletableFuture.supplyAsync(() -> stream.read(256));
            assertNull(pending.get(1, TimeUnit.SECONDS),
                    "a temporary network gap must not monopolize Minecraft's sound executor");
        } finally {
            decoder.close();
        }
    }

    @Test
    void decodesTheServerChunkFormatIntoPcm() throws Exception {
        var chunks = Mp3FrameIndex.split(encodeTestTrack());
        assertTrue(chunks.size() > 1);

        StreamingMp3Decoder decoder = new StreamingMp3Decoder(0, chunks.size());
        try {
            Mp3FrameIndex.Chunk first = chunks.getFirst();
            assertTrue(decoder.offer(first.index(), first.data()));
            await(() -> decoder.format() != null);
            for (Mp3FrameIndex.Chunk chunk : chunks.subList(1, chunks.size())) assertTrue(decoder.offer(chunk.index(), chunk.data()));
            await(() -> decoder.format() != null && decoder.bufferedMillis() > 0);

            assertNotNull(decoder.format());
            assertEquals(2, decoder.format().getChannels());
            assertTrue(decoder.bufferedMillis() > 0);
            byte[] pcm = decoder.poll();
            assertNotNull(pcm);
            assertTrue(pcm.length > 0);
        } finally {
            decoder.close();
        }
    }

    private static byte[] encodeTestTrack() throws Exception {
        AudioFormat source = new AudioFormat(44_100, 16, 2, true, false);
        LameEncoder encoder = new LameEncoder(source, 160, LameEncoder.CHANNEL_MODE_STEREO,
                LameEncoder.QUALITY_HIGHEST, false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] pcm = new byte[44_100 * 2 * 2 * 2];
        ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < 44_100 * 2; frame++) {
            short sample = (short)(Math.sin(frame * Math.PI * 2 * 440 / 44_100) * 8_000);
            samples.putShort(sample).putShort(sample);
        }
        byte[] encoded = new byte[encoder.getMP3BufferSize()];
        for (int offset = 0; offset < pcm.length;) {
            int count = Math.min(encoder.getPCMBufferSize(), pcm.length - offset);
            int written = encoder.encodeBuffer(pcm, offset, count, encoded);
            if (written > 0) output.write(encoded, 0, written);
            offset += count;
        }
        int written = encoder.encodeFinish(encoded);
        if (written > 0) output.write(encoded, 0, written);
        encoder.close();
        return output.toByteArray();
    }

    private static void await(Check check) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!check.value() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(check.value(), "MP3 decoder did not produce PCM in time");
    }

    private interface Check { boolean value(); }
}
