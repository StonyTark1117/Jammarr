package stonytark.jammarr.client;

import net.minecraft.client.sounds.AudioStream;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.core.protocol.AudioTimingTrace;

final class LegacyPcmAudioStream implements AudioStream {
    private final LegacyStreamingMp3Decoder decoder;
    private byte[] remainder;
    private int remainderOffset;
    private int acceptanceReads;

    LegacyPcmAudioStream(LegacyStreamingMp3Decoder decoder) { this.decoder = decoder; }
    @Override public AudioFormat getFormat() { return decoder.format(); }
    @Override public ByteBuffer read(int requested) {
        ByteBuffer output = ByteBuffer.allocateDirect(requested); boolean wrote = false;
        while (output.hasRemaining()) {
            if (remainder == null) { remainder = decoder.drain(output.remaining()); remainderOffset = 0; if (remainder == null) break; }
            int count = Math.min(output.remaining(), remainder.length - remainderOffset);
            output.put(remainder, remainderOffset, count); remainderOffset += count; wrote = true;
            if (remainderOffset == remainder.length) remainder = null;
        }
        if (!wrote) {
            // Yield the sound executor after a bounded decoder wait. Minecraft
            // skips a null streaming buffer and tries again on a later channel
            // update; an indefinite wait here would also block volume, stop,
            // reload, and recovery commands queued to that executor.
            return null;
        }
        output.flip();
        if (acceptanceReads == 0) AudioTimingTrace.record("pcm_drained", "bytes", output.remaining(),
                "bufferedMs", decoder.bufferedMillis());
        if (ProtocolLimits.audioProbeEnabled() && acceptanceReads++ < 12) {
            Jammarr.LOGGER.info("Acceptance PCM read: requested={} returned={} finished={} bufferedMs={}",
                    requested, output.remaining(), decoder.finished(), decoder.bufferedMillis());
        }
        return output;
    }
    @Override public void close() { decoder.close(); }
}
