package stonytark.jammarr.client;

import net.minecraft.client.MinecraftClient;
import stonytark.jammarr.mixin.client.SoundSystemAccessor;

/** Mapped accessor bridge to Minecraft 1.6.4's existing Paulscode/OpenAL backend. */
final class LegacySoundAccess {
    static paulscode.sound.SoundSystem soundSystem(MinecraftClient minecraft) {
        return minecraft == null || minecraft.field_3759 == null
                ? null : ((SoundSystemAccessor) minecraft.field_3759).jammarr$backend();
    }

    static void suppressVanillaMusic(MinecraftClient minecraft) {
        paulscode.sound.SoundSystem backend = soundSystem(minecraft);
        if (backend != null && backend.playing("BgMusic")) backend.stop("BgMusic");
        ((SoundSystemAccessor) minecraft.field_3759).jammarr$timeUntilNextSong(Integer.MAX_VALUE);
    }

    static void restoreVanillaMusic(MinecraftClient minecraft) {
        ((SoundSystemAccessor) minecraft.field_3759).jammarr$timeUntilNextSong(0);
    }

    private LegacySoundAccess() {}
}
