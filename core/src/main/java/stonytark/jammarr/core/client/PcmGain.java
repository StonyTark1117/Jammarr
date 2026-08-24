package stonytark.jammarr.core.client;

/** In-place gain for signed 16-bit little-endian interleaved PCM. */
public final class PcmGain {
    public static void apply(byte[] pcm, double requestedGain) {
        if (pcm == null) throw new IllegalArgumentException("pcm");
        double gain = Math.max(0.0, Math.min(1.0, requestedGain));
        for (int index = 0; index + 1 < pcm.length; index += 2) {
            int encoded = (pcm[index] & 0xff) | (pcm[index + 1] << 8);
            short sample = (short) encoded;
            int scaled = (int) Math.round(sample * gain);
            pcm[index] = (byte) scaled;
            pcm[index + 1] = (byte) (scaled >>> 8);
        }
    }

    private PcmGain() {}
}
