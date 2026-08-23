package stonytark.jammarr.server;

import de.sciss.jump3r.lowlevel.LameEncoder;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mp3CbrNormalizerTest {
    @Test void reencodesDecodableMp3AsExact160KbpsStereo() throws Exception {
        Path input = Files.createTempFile("jammarr-normalize-input-", ".mp3");
        Path output = Files.createTempFile("jammarr-normalize-output-", ".mp3");
        try {
            Files.write(input, encodeSine(128));
            Mp3CbrNormalizer.normalize(input, output, 160);
            Mp3FrameIndex.Info info = Mp3FrameIndex.inspect(Files.readAllBytes(output));
            assertTrue(info.constantBitrate());
            assertEquals(160, info.bitrateKbps());
            assertEquals(2, info.channels());
            assertTrue(info.durationMs() >= 900);
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    private static byte[] encodeSine(int bitrate) throws Exception {
        int rate = 44_100;
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, 2, 4, rate, false);
        LameEncoder encoder = new LameEncoder(format, bitrate, LameEncoder.CHANNEL_MODE_JOINT_STEREO,
                LameEncoder.QUALITY_HIGH, false);
        byte[] pcm = new byte[rate * 4];
        for (int frame = 0; frame < rate; frame++) {
            short sample = (short) (Math.sin(frame * 2 * Math.PI * 440 / rate) * 8_000);
            int offset = frame * 4;
            pcm[offset] = pcm[offset + 2] = (byte) sample;
            pcm[offset + 1] = pcm[offset + 3] = (byte) (sample >>> 8);
        }
        byte[] encoded = new byte[encoder.getMP3BufferSize()];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            for (int offset = 0; offset < pcm.length;) {
                int count = Math.min(encoder.getPCMBufferSize(), pcm.length - offset);
                count -= count % 4;
                int written = encoder.encodeBuffer(pcm, offset, count, encoded);
                output.write(encoded, 0, written);
                offset += count;
            }
            int finalBytes = encoder.encodeFinish(encoded);
            output.write(encoded, 0, finalBytes);
            return output.toByteArray();
        } finally {
            encoder.close();
        }
    }
}
