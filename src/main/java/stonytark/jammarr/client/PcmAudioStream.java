package stonytark.jammarr.client;

import net.minecraft.client.sounds.AudioStream;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.ProtocolLimits;

final class PcmAudioStream implements AudioStream {
    private final StreamingMp3Decoder decoder;
    private byte[] remainder;
    private int remainderOffset;
    private int acceptanceReads;

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
            // StreamingMp3Decoder.poll blocks until PCM arrives, recovery
            // closes it, or the stream really finishes. An empty OpenAL
            // buffer is treated like end-of-stream by some sound engines.
            return null;
        }
        output.flip();
        if (ProtocolLimits.audioProbeEnabled() && acceptanceReads++ < 12) {
            Jammarr.LOGGER.info("Acceptance PCM read: requested={} returned={} finished={} bufferedMs={}",
                    requested, output.remaining(), decoder.finished(), decoder.bufferedMillis());
        }
        return output;
    }
    @Override public void close() { decoder.close(); }
}
