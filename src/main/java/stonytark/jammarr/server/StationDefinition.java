package stonytark.jammarr.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import stonytark.jammarr.network.JammarrPayloads;

import java.util.ArrayList;
import java.util.List;

public record StationDefinition(JammarrPayloads.StationType type, String name,
                                List<JammarrPayloads.StationSeed> seeds, long generation) {
    public StationDefinition {
        type = type == null ? JammarrPayloads.StationType.NONE : type;
        name = name == null ? "" : name;
        seeds = seeds == null ? List.of() : List.copyOf(seeds.stream().limit(5).toList());
        generation = Math.max(0, generation);
    }

    public static StationDefinition none(long generation) {
        return new StationDefinition(JammarrPayloads.StationType.NONE, "", List.of(), generation);
    }

    public boolean active() { return type != JammarrPayloads.StationType.NONE; }
    public boolean adventure() { return type == JammarrPayloads.StationType.SONIC_ADVENTURE; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", type.name()); tag.putString("name", name); tag.putLong("generation", generation);
        ListTag seedTags = new ListTag();
        for (JammarrPayloads.StationSeed seed : seeds) {
            CompoundTag value = new CompoundTag();
            value.putString("kind", seed.kind().name()); value.putString("key", seed.key());
            value.putString("title", seed.title()); value.putString("subtitle", seed.subtitle()); seedTags.add(value);
        }
        tag.put("seeds", seedTags);
        return tag;
    }

    public static StationDefinition load(CompoundTag tag) {
        JammarrPayloads.StationType type;
        try { type = JammarrPayloads.StationType.valueOf(tag.getString("type")); }
        catch (IllegalArgumentException invalid) { type = JammarrPayloads.StationType.NONE; }
        List<JammarrPayloads.StationSeed> seeds = new ArrayList<>();
        ListTag values = tag.getList("seeds", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(5, values.size()); i++) {
            CompoundTag value = values.getCompound(i);
            try {
                seeds.add(new JammarrPayloads.StationSeed(JammarrPayloads.ItemKind.valueOf(value.getString("kind")),
                        value.getString("key"), value.getString("title"), value.getString("subtitle")));
            } catch (IllegalArgumentException ignored) {}
        }
        return new StationDefinition(type, tag.getString("name"), seeds, tag.getLong("generation"));
    }
}
