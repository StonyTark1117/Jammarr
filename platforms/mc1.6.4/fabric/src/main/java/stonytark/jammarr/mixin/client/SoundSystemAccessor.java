package stonytark.jammarr.mixin.client;

import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundSystem.class)
public interface SoundSystemAccessor {
    @Accessor("soundSystem") paulscode.sound.SoundSystem jammarr$backend();
    @Accessor("field_2267") void jammarr$timeUntilNextSong(int value);
}
