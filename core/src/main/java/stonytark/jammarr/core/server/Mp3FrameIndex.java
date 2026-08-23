package stonytark.jammarr.core.server;

import stonytark.jammarr.core.network.Hashing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class Mp3FrameIndex {
    public static final int MAX_CHUNK_BYTES = 16_000;
    private static final int[][] BITRATES = {
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}
    };

    public static final class Chunk {
        private final int index;
        private final long startMs;
        private final String sha256;
        private final byte[] data;

        public Chunk(int index, long startMs, String sha256, byte[] data) {
            this.index = index;
            this.startMs = startMs;
            this.sha256 = sha256;
            this.data = data;
        }

        public int index() { return index; }
        public long startMs() { return startMs; }
        public String sha256() { return sha256; }
        public byte[] data() { return data; }
    }

    public static final class Info {
        private final int frameCount;
        private final int sampleRate;
        private final int channels;
        private final int bitrateKbps;
        private final int minimumBitrateKbps;
        private final int maximumBitrateKbps;
        private final boolean constantBitrate;
        private final long durationMs;

        public Info(int frameCount, int sampleRate, int channels, int bitrateKbps, int minimumBitrateKbps,
                    int maximumBitrateKbps, boolean constantBitrate, long durationMs) {
            this.frameCount = frameCount;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitrateKbps = bitrateKbps;
            this.minimumBitrateKbps = minimumBitrateKbps;
            this.maximumBitrateKbps = maximumBitrateKbps;
            this.constantBitrate = constantBitrate;
            this.durationMs = durationMs;
        }

        public int frameCount() { return frameCount; }
        public int sampleRate() { return sampleRate; }
        public int channels() { return channels; }
        public int bitrateKbps() { return bitrateKbps; }
        public int minimumBitrateKbps() { return minimumBitrateKbps; }
        public int maximumBitrateKbps() { return maximumBitrateKbps; }
        public boolean constantBitrate() { return constantBitrate; }
        public long durationMs() { return durationMs; }
    }

    public static List<Chunk> split(byte[] bytes) {
        List<Frame> frames = scan(bytes);
        if (frames.isEmpty()) throw new IllegalArgumentException("Plex response is not a supported Layer III MP3 stream");
        List<Chunk> chunks = new ArrayList<Chunk>();
        int first = 0;
        while (first < frames.size()) {
            Frame start = frames.get(first);
            int endOffset = start.offset;
            int cursor = first;
            while (cursor < frames.size()) {
                Frame frame = frames.get(cursor);
                if (frame.offset + frame.length - start.offset > MAX_CHUNK_BYTES && cursor > first) break;
                endOffset = frame.offset + frame.length;
                cursor++;
            }
            byte[] data = Arrays.copyOfRange(bytes, start.offset, endOffset);
            chunks.add(new Chunk(chunks.size(), start.startMs, Hashing.sha256(data), data));
            first = cursor;
        }
        return Collections.unmodifiableList(new ArrayList<Chunk>(chunks));
    }

    public static int chunkAt(List<Chunk> chunks, long positionMs) {
        int low = 0, high = chunks.size() - 1, answer = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (chunks.get(mid).startMs <= positionMs) { answer = mid; low = mid + 1; }
            else high = mid - 1;
        }
        return answer;
    }

    public static Info inspect(byte[] bytes) {
        List<Frame> frames = scan(bytes);
        if (frames.isEmpty()) throw new IllegalArgumentException("Plex response is not a supported Layer III MP3 stream");
        Frame first = frames.get(0), last = frames.get(frames.size() - 1);
        int audioEnd = last.offset + last.length;
        int trailing = bytes.length - audioEnd;
        if (trailing != 0 && !(trailing == 128 && bytes[audioEnd] == 'T' && bytes[audioEnd + 1] == 'A' && bytes[audioEnd + 2] == 'G')) {
            throw new IllegalArgumentException("MP3 stream contains data outside its contiguous frame sequence");
        }
        boolean constant = true;
        int minimumBitrate = first.bitrateKbps;
        int maximumBitrate = first.bitrateKbps;
        for (Frame frame : frames) {
            constant &= frame.bitrateKbps == first.bitrateKbps;
            minimumBitrate = Math.min(minimumBitrate, frame.bitrateKbps);
            maximumBitrate = Math.max(maximumBitrate, frame.bitrateKbps);
            if (frame.sampleRate != first.sampleRate || frame.channels != first.channels) {
                throw new IllegalArgumentException("MP3 stream changes sample format mid-track");
            }
        }
        return new Info(frames.size(), first.sampleRate, first.channels, first.bitrateKbps, minimumBitrate,
                maximumBitrate, constant, last.startMs + last.durationMs);
    }

    private static List<Frame> scan(byte[] data) {
        int offset = id3Size(data); long samples = 0; List<Frame> frames = new ArrayList<Frame>();
        while (offset + 4 <= data.length) {
            int h = ((data[offset] & 255) << 24) | ((data[offset + 1] & 255) << 16) | ((data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
            if ((h & 0xFFE00000) != 0xFFE00000) { if (!frames.isEmpty()) break; offset++; continue; }
            int versionBits = (h >>> 19) & 3, layerBits = (h >>> 17) & 3, bitrateIndex = (h >>> 12) & 15, rateIndex = (h >>> 10) & 3;
            if (versionBits == 1 || layerBits != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) { if (!frames.isEmpty()) break; offset++; continue; }
            boolean mpeg1 = versionBits == 3;
            int[] rates = versionBits == 3 ? new int[]{44100, 48000, 32000} : versionBits == 2 ? new int[]{22050, 24000, 16000} : new int[]{11025, 12000, 8000};
            int sampleRate = rates[rateIndex], bitrate = BITRATES[mpeg1 ? 0 : 1][bitrateIndex] * 1000, padding = (h >>> 9) & 1;
            int length = (mpeg1 ? 144 : 72) * bitrate / sampleRate + padding;
            if (length < 24 || offset + length > data.length) break;
            long startMs = samples * 1000L / sampleRate;
            int frameSamples = mpeg1 ? 1152 : 576;
            frames.add(new Frame(offset, length, startMs, frameSamples * 1000L / sampleRate, bitrate / 1000, sampleRate, ((h >>> 6) & 3) == 3 ? 1 : 2));
            samples += frameSamples;
            offset += length;
        }
        return frames;
    }

    private static int id3Size(byte[] data) {
        if (data.length < 10 || data[0] != 'I' || data[1] != 'D' || data[2] != '3') return 0;
        return Math.min(data.length, 10 + ((data[6] & 127) << 21) + ((data[7] & 127) << 14) + ((data[8] & 127) << 7) + (data[9] & 127));
    }

    private static final class Frame {
        private final int offset;
        private final int length;
        private final long startMs;
        private final long durationMs;
        private final int bitrateKbps;
        private final int sampleRate;
        private final int channels;

        private Frame(int offset, int length, long startMs, long durationMs, int bitrateKbps, int sampleRate, int channels) {
            this.offset = offset;
            this.length = length;
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.bitrateKbps = bitrateKbps;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    private Mp3FrameIndex() {}
}
