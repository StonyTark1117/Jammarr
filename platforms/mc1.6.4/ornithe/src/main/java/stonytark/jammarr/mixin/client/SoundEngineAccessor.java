package stonytark.jammarr.mixin.client;

import net.minecraft.client.sound.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("system") paulscode.sound.SoundSystem jammarr$backend();
    @Accessor("musicCooldown") void jammarr$timeUntilNextSong(int value);
}
