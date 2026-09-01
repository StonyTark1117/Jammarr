package stonytark.jammarr.client;

import net.minecraft.client.sounds.AudioStream;
import javax.sound.sampled.AudioFormat;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.core.protocol.AudioTimingTrace;
import stonytark.jammarr.core.protocol.RotatingPcmTrace;

final class PcmAudioStream implements AudioStream {
    private final StreamingMp3Decoder decoder;
    private byte[] remainder;
    private int remainderOffset;
    private int acceptanceReads;
    private RotatingPcmTrace acceptancePcmTrace;

    PcmAudioStream(StreamingMp3Decoder decoder) {
        this.decoder = decoder;
        acceptancePcmTrace = openAcceptancePcmTrace();
    }
    @Override public AudioFormat getFormat() { return decoder.format(); }
    @Override public ByteBuffer read(int requested) {
        ByteBuffer output = ByteBuffer.allocateDirect(requested); boolean wrote = false;
        while (output.hasRemaining()) {
            if (remainder == null) { remainder = decoder.poll(); remainderOffset = 0; if (remainder == null) break; }
            int count = Math.min(output.remaining(), remainder.length - remainderOffset);
            output.put(remainder, remainderOffset, count);
            traceAcceptancePcm(remainder, remainderOffset, count);
            remainderOffset += count; wrote = true;
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

    private static RotatingPcmTrace openAcceptancePcmTrace() {
        if (!ProtocolLimits.audioProbeEnabled()) return null;
        String traceDirectory = System.getProperty("jammarr.acceptance.pcmTraceDir", "");
        if (traceDirectory.isEmpty()) return null;
        File directory = new File(traceDirectory);
        if (!directory.isDirectory() && !directory.mkdirs()) return null;
        try {
            return RotatingPcmTrace.open(directory, "pcm-feed-" + System.nanoTime());
        } catch (IOException error) {
            Jammarr.LOGGER.warn("Unable to open the acceptance PCM trace", error);
            return null;
        }
    }

    private void traceAcceptancePcm(byte[] pcm, int offset, int count) {
        if (acceptancePcmTrace == null) return;
        try {
            acceptancePcmTrace.write(pcm, offset, count);
            acceptancePcmTrace.flush();
        } catch (IOException error) {
            Jammarr.LOGGER.warn("Unable to write the acceptance PCM trace", error);
            closeAcceptancePcmTrace();
        }
    }

    private void closeAcceptancePcmTrace() {
        if (acceptancePcmTrace == null) return;
        try {
            acceptancePcmTrace.close();
        } catch (IOException ignored) {
        }
        acceptancePcmTrace = null;
    }

    @Override public void close() {
        closeAcceptancePcmTrace();
        decoder.close();
    }
}
