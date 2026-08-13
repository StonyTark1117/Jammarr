package stonytark.pampmod.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

public final class PampSavedData extends SavedData {
    public static final Factory<PampSavedData> FACTORY = new Factory<>(PampSavedData::new, PampSavedData::load);
    private final List<QueueTrack> queue = new ArrayList<>();
    private long checkpointMs;
    private boolean paused;

    public List<QueueTrack> queue() { return queue; }
    public long checkpointMs() { return checkpointMs; }
    public boolean paused() { return paused; }
    public void update(long checkpointMs, boolean paused) { this.checkpointMs = Math.max(0, checkpointMs); this.paused = paused; setDirty(); }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag(); queue.forEach(track -> list.add(track.save()));
        tag.put("queue", list); tag.putLong("checkpointMs", checkpointMs); tag.putBoolean("paused", paused); return tag;
    }

    static PampSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PampSavedData data = new PampSavedData();
        ListTag list = tag.getList("queue", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) data.queue.add(QueueTrack.load(list.getCompound(i)));
        data.checkpointMs = tag.getLong("checkpointMs"); data.paused = tag.getBoolean("paused"); return data;
    }
}
