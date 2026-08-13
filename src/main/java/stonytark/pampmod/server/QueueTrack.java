package stonytark.pampmod.server;

import net.minecraft.nbt.CompoundTag;
import stonytark.pampmod.network.PampPayloads;

public record QueueTrack(String key, String title, String artist, String album, long durationMs) {
    public PampPayloads.QueueEntry networkEntry() { return new PampPayloads.QueueEntry(key, title, artist, durationMs); }

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
