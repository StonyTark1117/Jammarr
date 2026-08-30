package stonytark.jammarr.client;

import net.minecraft.client.Minecraft;
import stonytark.jammarr.mixin.client.SoundEngineAccessor;

/** Mapped accessor bridge to Minecraft 1.6.4's existing Paulscode/OpenAL backend. */
final class LegacySoundAccess {
    static paulscode.sound.SoundSystem soundSystem(Minecraft minecraft) {
        return minecraft == null || minecraft.soundEngine == null
                ? null : ((SoundEngineAccessor) minecraft.soundEngine).jammarr$backend();
    }

    static void suppressVanillaMusic(Minecraft minecraft) {
        paulscode.sound.SoundSystem backend = soundSystem(minecraft);
        if (backend != null && backend.playing("BgMusic")) backend.stop("BgMusic");
        SoundEngineAccessor accessor = (SoundEngineAccessor) minecraft.soundEngine;
        accessor.jammarr$timeUntilNextSong(Integer.MAX_VALUE);
    }

    static void restoreVanillaMusic(Minecraft minecraft) {
        ((SoundEngineAccessor) minecraft.soundEngine).jammarr$timeUntilNextSong(0);
    }

    private LegacySoundAccess() {}
}
