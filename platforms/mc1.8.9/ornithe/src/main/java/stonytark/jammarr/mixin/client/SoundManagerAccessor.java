package stonytark.jammarr.mixin.client;

import net.minecraft.client.sound.system.SoundEngine;
import net.minecraft.client.sound.system.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundManager.class)
public interface SoundManagerAccessor {
    @Accessor("engine") SoundEngine jammarr$soundEngine();
}
