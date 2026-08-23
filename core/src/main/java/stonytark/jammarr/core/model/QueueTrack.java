package stonytark.jammarr.core.model;

import java.util.Objects;

public final class QueueTrack {
    private final String key;
    private final String title;
    private final String artist;
    private final String album;
    private final long durationMs;

    public QueueTrack(String key, String title, String artist, String album, long durationMs) {
        this.key = key == null ? "" : key;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.durationMs = Math.max(0, durationMs);
    }

    public String key() { return key; }
    public String title() { return title; }
    public String artist() { return artist; }
    public String album() { return album; }
    public long durationMs() { return durationMs; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof QueueTrack)) return false;
        QueueTrack value = (QueueTrack) other;
        return durationMs == value.durationMs && key.equals(value.key) && title.equals(value.title)
                && artist.equals(value.artist) && album.equals(value.album);
    }

    @Override public int hashCode() { return Objects.hash(key, title, artist, album, durationMs); }
    @Override public String toString() { return "QueueTrack{" + key + ", " + title + "}"; }
}
