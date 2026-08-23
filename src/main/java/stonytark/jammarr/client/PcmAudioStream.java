package stonytark.jammarr.client;

import net.minecraft.client.sounds.AudioStream;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

final class PcmAudioStream implements AudioStream {
    private final StreamingMp3Decoder decoder;
    private byte[] remainder;
    private int remainderOffset;

    PcmAudioStream(StreamingMp3Decoder decoder) { this.decoder = decoder; }
    @Override public AudioFormat getFormat() { return decoder.format(); }
    @Override public ByteBuffer read(int requested) {
        ByteBuffer output = ByteBuffer.allocateDirect(requested); boolean wrote = false;
        while (output.hasRemaining()) {
            if (remainder == null) { remainder = decoder.poll(); remainderOffset = 0; if (remainder == null) break; }
            int count = Math.min(output.remaining(), remainder.length - remainderOffset);
            output.put(remainder, remainderOffset, count); remainderOffset += count; wrote = true;
            if (remainderOffset == remainder.length) remainder = null;
        }
        if (!wrote) {
            // AudioStream treats null as end-of-stream. Keep the OpenAL
            // source alive while the network/decoder catches up; the player
            // will request a rebuffer if the decoder actually fails.
            if (!decoder.finished()) return output;
            return null;
        }
        output.flip(); return output;
    }
    @Override public void close() { decoder.close(); }
}
