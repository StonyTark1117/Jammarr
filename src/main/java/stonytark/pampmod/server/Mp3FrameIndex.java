package stonytark.pampmod.server;

import stonytark.pampmod.network.Hashing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Mp3FrameIndex {
    public static final int MAX_CHUNK_BYTES = 16_000;
    private static final int[][] BITRATES = {
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}
    };

    public record Chunk(int index, long startMs, String sha256, byte[] data) {}
    public record Info(int frameCount, int sampleRate, int channels, int bitrateKbps, int minimumBitrateKbps,
                       int maximumBitrateKbps, boolean constantBitrate, long durationMs) {}

    public static List<Chunk> split(byte[] bytes) {
        List<Frame> frames = scan(bytes);
        if (frames.isEmpty()) throw new IllegalArgumentException("Plex response is not a supported Layer III MP3 stream");
        List<Chunk> chunks = new ArrayList<>();
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
        return List.copyOf(chunks);
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
        Frame first = frames.getFirst(), last = frames.getLast();
        int audioEnd = last.offset + last.length;
        int trailing = bytes.length - audioEnd;
        if (trailing != 0 && !(trailing == 128 && bytes[audioEnd] == 'T' && bytes[audioEnd + 1] == 'A' && bytes[audioEnd + 2] == 'G')) {
            throw new IllegalArgumentException("MP3 stream contains data outside its contiguous frame sequence");
        }
        boolean constant = frames.stream().allMatch(frame -> frame.bitrateKbps == first.bitrateKbps);
        int minimumBitrate = frames.stream().mapToInt(Frame::bitrateKbps).min().orElse(first.bitrateKbps);
        int maximumBitrate = frames.stream().mapToInt(Frame::bitrateKbps).max().orElse(first.bitrateKbps);
        boolean consistentFormat = frames.stream().allMatch(frame -> frame.sampleRate == first.sampleRate && frame.channels == first.channels);
        if (!consistentFormat) throw new IllegalArgumentException("MP3 stream changes sample format mid-track");
        return new Info(frames.size(), first.sampleRate, first.channels, first.bitrateKbps, minimumBitrate, maximumBitrate, constant, last.startMs + last.durationMs);
    }

    private static List<Frame> scan(byte[] data) {
        int offset = id3Size(data); long samples = 0; List<Frame> frames = new ArrayList<>();
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

    private record Frame(int offset, int length, long startMs, long durationMs, int bitrateKbps, int sampleRate, int channels) {}
    private Mp3FrameIndex() {}
}
