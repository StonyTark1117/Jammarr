package stonytark.jammarr.server;

import net.minecraft.nbt.CompoundTag;
import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.network.JammarrPayloads;

final class QueueTrackCodec {
    static JammarrPayloads.QueueEntry networkEntry(QueueTrack track) {
        return networkEntry(track, JammarrPayloads.PlaybackOrigin.MANUAL, true);
    }

    static JammarrPayloads.QueueEntry networkEntry(QueueTrack track, JammarrPayloads.PlaybackOrigin source, boolean editable) {
        return new JammarrPayloads.QueueEntry(track.key(), track.title(), track.artist(), track.durationMs(), source, editable);
    }

    static CompoundTag save(QueueTrack track) {
        CompoundTag tag = new CompoundTag();
        tag.putString("key", track.key()); tag.putString("title", track.title()); tag.putString("artist", track.artist());
        tag.putString("album", track.album()); tag.putLong("duration", track.durationMs());
        return tag;
    }

    static QueueTrack load(CompoundTag tag) {
        return new QueueTrack(tag.getStringOr("key", ""), tag.getStringOr("title", ""), tag.getStringOr("artist", ""), tag.getStringOr("album", ""), tag.getLongOr("duration", 0L));
    }

    private QueueTrackCodec() {}
}
