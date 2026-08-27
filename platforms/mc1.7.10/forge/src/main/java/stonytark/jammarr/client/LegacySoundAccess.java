package stonytark.jammarr.client;

import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.MusicTicker;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.SoundManager;
import paulscode.sound.SoundSystem;

import java.lang.reflect.Field;

/** Narrow reflection bridge to the 1.7.10 Paulscode backend. */
final class LegacySoundAccess {
    private static final Field SOUND_MANAGER = ReflectionHelper.findField(
            SoundHandler.class, "sndManager", "field_147694_f");
    private static final Field SOUND_SYSTEM = ReflectionHelper.findField(
            SoundManager.class, "sndSystem", "field_148620_e");
    private static final Field MUSIC_TICKER = ReflectionHelper.findField(
            Minecraft.class, "mcMusicTicker", "field_147126_aw");
    private static final Field CURRENT_MUSIC = ReflectionHelper.findField(
            MusicTicker.class, "field_147678_c");
    private static final Field MUSIC_DELAY = ReflectionHelper.findField(
            MusicTicker.class, "field_147676_d");

    static SoundSystem soundSystem(Minecraft minecraft) {
        try {
            SoundManager manager = (SoundManager) SOUND_MANAGER.get(minecraft.getSoundHandler());
            return (SoundSystem) SOUND_SYSTEM.get(manager);
        } catch (IllegalAccessException error) { throw new IllegalStateException("Unable to access Minecraft sound system", error); }
    }

    static void suppressVanillaMusic(Minecraft minecraft) {
        try {
            MusicTicker ticker = (MusicTicker) MUSIC_TICKER.get(minecraft);
            ISound current = (ISound) CURRENT_MUSIC.get(ticker);
            if (current != null) {
                try { minecraft.getSoundHandler().stopSound(current); }
                catch (LinkageError unavailable) {
                    // A failed LWJGL2 context cannot be called safely. Clearing
                    // the ticker handle prevents the next native playing() poll.
                }
                CURRENT_MUSIC.set(ticker, null);
            }
            MUSIC_DELAY.setInt(ticker, Integer.MAX_VALUE);
        } catch (IllegalAccessException error) { throw new IllegalStateException("Unable to suppress vanilla music", error); }
    }

    static void restoreVanillaMusic(Minecraft minecraft) {
        try {
            MusicTicker ticker = (MusicTicker) MUSIC_TICKER.get(minecraft);
            MUSIC_DELAY.setInt(ticker, 0);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Unable to restore vanilla music scheduling", error);
        }
    }

    private LegacySoundAccess() {}
}
