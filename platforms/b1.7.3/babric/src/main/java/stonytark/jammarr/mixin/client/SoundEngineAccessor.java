package stonytark.jammarr.mixin.client;

import net.minecraft.client.sound.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundManager.class)
public interface SoundEngineAccessor {
    @Accessor("soundSystem") static paulscode.sound.SoundSystem jammarr$backend() {
        throw new AssertionError();
    }
    @Accessor("timeUntilNextSong") void jammarr$timeUntilNextSong(int value);
}
