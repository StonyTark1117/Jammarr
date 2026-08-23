package stonytark.jammarr.server;

import net.minecraft.nbt.CompoundTag;
import stonytark.jammarr.network.JammarrPayloads;

public record QueueTrack(String key, String title, String artist, String album, long durationMs) {
    public JammarrPayloads.QueueEntry networkEntry() { return new JammarrPayloads.QueueEntry(key, title, artist, durationMs); }
    public JammarrPayloads.QueueEntry networkEntry(JammarrPayloads.PlaybackOrigin source, boolean editable) {
        return new JammarrPayloads.QueueEntry(key, title, artist, durationMs, source, editable);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("key", key); tag.putString("title", title); tag.putString("artist", artist);
        tag.putString("album", album); tag.putLong("duration", durationMs);
        return tag;
    }

    public static QueueTrack load(CompoundTag tag) {
        return new QueueTrack(tag.getString("key"), tag.getString("title"), tag.getString("artist"), tag.getString("album"), tag.getLong("duration"));
    }
}
