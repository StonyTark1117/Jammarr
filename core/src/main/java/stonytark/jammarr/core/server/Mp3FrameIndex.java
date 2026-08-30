package stonytark.jammarr.core.server;

import stonytark.jammarr.core.network.Hashing;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
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
        private final int offset;
        private final int length;

        public Chunk(int index, long startMs, String sha256, byte[] data) {
            this.index = index;
            this.startMs = startMs;
            this.sha256 = sha256;
            this.data = data;
            this.offset = -1;
            this.length = data == null ? 0 : data.length;
        }

        private Chunk(int index, long startMs, String sha256, int offset, int length) {
            this.index = index; this.startMs = startMs; this.sha256 = sha256;
            this.data = null; this.offset = offset; this.length = length;
        }

        public int index() { return index; }
        public long startMs() { return startMs; }
        public String sha256() { return sha256; }
        public byte[] data() {
            if (data == null) throw new IllegalStateException("file-backed chunk must be read through AudioAsset");
            return data;
        }
        public int offset() { return offset; }
        public int length() { return length; }
        public boolean fileBacked() { return data == null; }
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

    public static final class FileIndex {
        private final Info info;
        private final List<Chunk> chunks;
        private final String sha256;
        private final long size;

        private FileIndex(Info info, List<Chunk> chunks, String sha256, long size) {
            this.info = info;
            this.chunks = Collections.unmodifiableList(new ArrayList<Chunk>(chunks));
            this.sha256 = sha256;
            this.size = size;
        }

        public Info info() { return info; }
        public List<Chunk> chunks() { return chunks; }
        public String sha256() { return sha256; }
        public long size() { return size; }
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

    public static FileIndex index(Path path) throws IOException {
        List<Frame> frames = scan(path);
        if (frames.isEmpty()) throw new IllegalArgumentException("Plex response is not a supported Layer III MP3 stream");
        long size = java.nio.file.Files.size(path);
        validateTrailing(path, frames, size);
        Info info = describe(frames);
        List<Chunk> chunks = new ArrayList<Chunk>();
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
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
                int length = endOffset - start.offset;
                byte[] data = new byte[length];
                file.seek(start.offset);
                file.readFully(data);
                chunks.add(new Chunk(chunks.size(), start.startMs, Hashing.sha256(data), start.offset, length));
                first = cursor;
            }
        }
        return new FileIndex(info, chunks, Hashing.sha256(path), size);
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
        return describe(frames);
    }

    public static Info inspect(Path path) throws IOException { return index(path).info(); }

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

    private static List<Frame> scan(Path path) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long fileLength = file.length();
            if (fileLength > Integer.MAX_VALUE) throw new IllegalArgumentException("MP3 stream is too large to index");
            int length = (int) fileLength;
            byte[] prefix = new byte[Math.min(10, length)];
            file.readFully(prefix);
            int offset = id3Size(prefix, length);
            long samples = 0L;
            List<Frame> frames = new ArrayList<Frame>();
            while (offset + 4 <= length) {
                file.seek(offset);
                int h = file.readInt();
                Header header = header(h);
                if (header == null) {
                    if (!frames.isEmpty()) break;
                    offset++;
                    continue;
                }
                if (offset + header.length > length) break;
                long startMs = samples * 1000L / header.sampleRate;
                frames.add(new Frame(offset, header.length, startMs, header.durationMs,
                        header.bitrateKbps, header.sampleRate, header.channels));
                samples += header.frameSamples;
                offset += header.length;
            }
            return frames;
        }
    }

    private static Info describe(List<Frame> frames) {
        Frame first = frames.get(0), last = frames.get(frames.size() - 1);
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
        return new Info(frames.size(), first.sampleRate, first.channels, first.bitrateKbps,
                minimumBitrate, maximumBitrate, constant, last.startMs + last.durationMs);
    }

    private static void validateTrailing(Path path, List<Frame> frames, long size) throws IOException {
        Frame last = frames.get(frames.size() - 1);
        long audioEnd = (long) last.offset + last.length;
        long trailing = size - audioEnd;
        if (trailing == 0L) return;
        if (trailing == 128L) {
            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                file.seek(audioEnd);
                if (file.read() == 'T' && file.read() == 'A' && file.read() == 'G') return;
            }
        }
        throw new IllegalArgumentException("MP3 stream contains data outside its contiguous frame sequence");
    }

    private static Header header(int h) {
        if ((h & 0xFFE00000) != 0xFFE00000) return null;
        int versionBits = (h >>> 19) & 3, layerBits = (h >>> 17) & 3;
        int bitrateIndex = (h >>> 12) & 15, rateIndex = (h >>> 10) & 3;
        if (versionBits == 1 || layerBits != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null;
        boolean mpeg1 = versionBits == 3;
        int[] rates = versionBits == 3 ? new int[]{44100, 48000, 32000}
                : versionBits == 2 ? new int[]{22050, 24000, 16000} : new int[]{11025, 12000, 8000};
        int sampleRate = rates[rateIndex];
        int bitrate = BITRATES[mpeg1 ? 0 : 1][bitrateIndex] * 1000;
        int frameSamples = mpeg1 ? 1152 : 576;
        int length = (mpeg1 ? 144 : 72) * bitrate / sampleRate + ((h >>> 9) & 1);
        if (length < 24) return null;
        return new Header(length, frameSamples, frameSamples * 1000L / sampleRate,
                bitrate / 1000, sampleRate, ((h >>> 6) & 3) == 3 ? 1 : 2);
    }

    private static int id3Size(byte[] data) {
        if (data.length < 10 || data[0] != 'I' || data[1] != 'D' || data[2] != '3') return 0;
        return Math.min(data.length, 10 + ((data[6] & 127) << 21) + ((data[7] & 127) << 14) + ((data[8] & 127) << 7) + (data[9] & 127));
    }

    private static int id3Size(byte[] prefix, int fileLength) {
        if (prefix.length < 10 || prefix[0] != 'I' || prefix[1] != 'D' || prefix[2] != '3') return 0;
        return Math.min(fileLength, 10 + ((prefix[6] & 127) << 21) + ((prefix[7] & 127) << 14)
                + ((prefix[8] & 127) << 7) + (prefix[9] & 127));
    }

    private static final class Header {
        private final int length, frameSamples, bitrateKbps, sampleRate, channels;
        private final long durationMs;
        private Header(int length, int frameSamples, long durationMs,
                       int bitrateKbps, int sampleRate, int channels) {
            this.length = length; this.frameSamples = frameSamples; this.durationMs = durationMs;
            this.bitrateKbps = bitrateKbps; this.sampleRate = sampleRate; this.channels = channels;
        }
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
