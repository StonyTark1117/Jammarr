package stonytark.jammarr.mixin.client;

import net.minecraft.client.sound.MusicManager;
import net.minecraft.client.sound.instance.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MusicManager.class)
public interface MusicTrackerAccessor {
    @Accessor("currentMusic") SoundInstance jammarr$currentMusic();
    @Accessor("currentMusic") void jammarr$currentMusic(SoundInstance value);
    @Accessor("timeUntilNextSong") void jammarr$timeUntilNextSong(int value);
}
