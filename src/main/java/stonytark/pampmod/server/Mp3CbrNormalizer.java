package stonytark.pampmod.server;

import de.sciss.jump3r.lowlevel.LameEncoder;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Converts a Plex MP3 response into the transport's exact CBR stereo format. */
final class Mp3CbrNormalizer {
    private Mp3CbrNormalizer() {}

    static void normalize(Path input, Path output, int bitrateKbps) throws IOException {
        try (var raw = new BufferedInputStream(Files.newInputStream(input));
             var encoded = new BufferedOutputStream(Files.newOutputStream(output,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            Bitstream bitstream = new Bitstream(raw);
            Decoder decoder = new Decoder();
            LameEncoder encoder = null;
            byte[] pcm = null;
            byte[] mp3 = null;
            int sampleRate = -1;
            int decodedFrames = 0;
            try {
                Header header;
                while ((header = bitstream.readFrame()) != null) {
                    SampleBuffer samples = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    int frameRate = samples.getSampleFrequency();
                    int channels = samples.getChannelCount();
                    if (channels != 1 && channels != 2) throw new IOException("Plex MP3 has an unsupported channel count");
                    if (encoder == null) {
                        sampleRate = frameRate;
                        AudioFormat source = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                                sampleRate, 16, 2, 4, sampleRate, false);
                        encoder = new LameEncoder(source, bitrateKbps,
                                LameEncoder.CHANNEL_MODE_JOINT_STEREO, LameEncoder.QUALITY_HIGH, false);
                        pcm = new byte[encoder.getPCMBufferSize()];
                        mp3 = new byte[encoder.getMP3BufferSize()];
                    } else if (frameRate != sampleRate) {
                        throw new IOException("Plex MP3 changed sample rate mid-stream");
                    }
                    short[] decoded = samples.getBuffer();
                    int decodedLength = samples.getBufferLength();
                    int frames = decodedLength / channels;
                    int needed = frames * 4;
                    if (needed > pcm.length) pcm = new byte[needed];
                    if (channels == 2) {
                        for (int i = 0, p = 0; i < decodedLength; i++) p = writeLittleEndian(pcm, p, decoded[i]);
                    } else {
                        for (int i = 0, p = 0; i < decodedLength; i++) {
                            p = writeLittleEndian(pcm, p, decoded[i]);
                            p = writeLittleEndian(pcm, p, decoded[i]);
                        }
                    }
                    writeEncoded(encoder, pcm, needed, mp3, encoded);
                    bitstream.closeFrame();
                    decodedFrames++;
                }
                if (encoder == null) throw new IOException("Plex returned an MP3 with no decodable frames");
                int finalBytes = encoder.encodeFinish(mp3);
                if (finalBytes > 0) encoded.write(mp3, 0, finalBytes);
            } catch (javazoom.jl.decoder.JavaLayerException | RuntimeException decodeFailure) {
                throw new IOException("Unable to normalize Plex MP3 audio after " + decodedFrames + " frames", decodeFailure);
            } finally {
                if (encoder != null) encoder.close();
                try { bitstream.close(); }
                catch (javazoom.jl.decoder.BitstreamException closeFailure) {
                    throw new IOException("Unable to close Plex MP3 stream", closeFailure);
                }
            }
        }
    }

    private static int writeLittleEndian(byte[] output, int offset, short sample) {
        output[offset] = (byte) sample;
        output[offset + 1] = (byte) (sample >>> 8);
        return offset + 2;
    }

    private static void writeEncoded(LameEncoder encoder, byte[] pcm, int length, byte[] mp3, OutputStream output) throws IOException {
        int offset = 0;
        int alignment = 4;
        while (offset < length) {
            int count = Math.min(encoder.getPCMBufferSize(), length - offset);
            count -= count % alignment;
            if (count == 0) throw new IOException("Unaligned PCM returned by MP3 decoder");
            int encoded = encoder.encodeBuffer(pcm, offset, count, mp3);
            if (encoded > 0) output.write(mp3, 0, encoded);
            offset += count;
        }
    }
}
