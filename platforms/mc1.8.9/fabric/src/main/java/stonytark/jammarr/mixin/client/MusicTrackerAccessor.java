package stonytark.jammarr.mixin.client;

import net.minecraft.client.sound.MusicTracker;
import net.minecraft.client.sound.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MusicTracker.class)
public interface MusicTrackerAccessor {
    @Accessor("field_8173") SoundInstance jammarr$currentMusic();
    @Accessor("field_8173") void jammarr$currentMusic(SoundInstance value);
    @Accessor("timeUntilNextSong") void jammarr$timeUntilNextSong(int value);
}
