package stonytark.jammarr.core.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class PcmGainTest {
    @Test void scalesSignedLittleEndianSamplesInPlace() {
        byte[] pcm = new byte[] { 0x10, 0x27, (byte) 0xf0, (byte) 0xd8, (byte) 0xff, 0x7f, 0x00, (byte) 0x80 };
        PcmGain.apply(pcm, 0.2);
        assertArrayEquals(new byte[] { (byte) 0xd0, 0x07, 0x30, (byte) 0xf8, (byte) 0x99, 0x19, 0x66, (byte) 0xe6 }, pcm);
    }

    @Test void clampsGainToTheSupportedRange() {
        byte[] muted = new byte[] { 0x34, 0x12 };
        PcmGain.apply(muted, -1.0);
        assertArrayEquals(new byte[] { 0, 0 }, muted);

        byte[] unchanged = new byte[] { 0x34, 0x12 };
        PcmGain.apply(unchanged, 2.0);
        assertArrayEquals(new byte[] { 0x34, 0x12 }, unchanged);
    }
}
