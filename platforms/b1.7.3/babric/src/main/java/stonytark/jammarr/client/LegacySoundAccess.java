package stonytark.jammarr.client;

import net.minecraft.client.Minecraft;
import stonytark.jammarr.mixin.client.SoundEngineAccessor;

/** Mapped accessor bridge to Beta 1.7.3's existing Paulscode/OpenAL backend. */
final class LegacySoundAccess {
    static paulscode.sound.SoundSystem soundSystem(Minecraft minecraft) {
        return minecraft == null || minecraft.soundManager == null
                ? null : SoundEngineAccessor.jammarr$backend();
    }

    static void suppressVanillaMusic(Minecraft minecraft) {
        paulscode.sound.SoundSystem backend = soundSystem(minecraft);
        if (backend != null && backend.playing("BgMusic")) backend.stop("BgMusic");
        SoundEngineAccessor accessor = (SoundEngineAccessor) minecraft.soundManager;
        accessor.jammarr$timeUntilNextSong(Integer.MAX_VALUE);
    }

    static void restoreVanillaMusic(Minecraft minecraft) {
        ((SoundEngineAccessor) minecraft.soundManager).jammarr$timeUntilNextSong(0);
    }

    private LegacySoundAccess() {}
}
