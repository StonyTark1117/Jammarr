package stonytark.jammarr.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sound.MusicManager;
import net.minecraft.client.sound.instance.SoundInstance;
import net.minecraft.client.sound.system.SoundEngine;
import net.minecraft.client.sound.system.SoundManager;
import stonytark.jammarr.mixin.client.MusicTrackerAccessor;
import stonytark.jammarr.mixin.client.SoundManagerAccessor;

import java.lang.reflect.Field;

/** Mapped accessor bridge to Minecraft 1.8.9's existing Paulscode/OpenAL backend. */
final class LegacySoundAccess {
    private static volatile Field backendField;

    static paulscode.sound.SoundSystem soundSystem(Minecraft minecraft) {
        SoundManager manager = minecraft.getSoundManager();
        if (manager == null) return null;
        SoundEngine engine = ((SoundManagerAccessor) manager).jammarr$soundEngine();
        if (engine == null) return null;
        try {
            Field field = backendField;
            if (field == null) {
                for (Field candidate : SoundEngine.class.getDeclaredFields()) {
                    if (paulscode.sound.SoundSystem.class.isAssignableFrom(candidate.getType())) {
                        candidate.setAccessible(true);
                        field = candidate;
                        backendField = candidate;
                        break;
                    }
                }
            }
            if (field == null) throw new IllegalStateException("Minecraft sound backend field was not found");
            return (paulscode.sound.SoundSystem) field.get(engine);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Unable to access Minecraft sound backend", error);
        }
    }

    static void suppressVanillaMusic(Minecraft minecraft) {
        MusicManager ticker = minecraft.getMusicManager();
        MusicTrackerAccessor accessor = (MusicTrackerAccessor) ticker;
        SoundInstance current = accessor.jammarr$currentMusic();
        if (current != null) {
            try { minecraft.getSoundManager().stop(current); }
            catch (LinkageError unavailable) {
                // Clearing the ticker handle avoids another native playing()
                // call if the shared legacy context is temporarily unavailable.
            }
            accessor.jammarr$currentMusic(null);
        }
        accessor.jammarr$timeUntilNextSong(Integer.MAX_VALUE);
    }

    static void restoreVanillaMusic(Minecraft minecraft) {
        ((MusicTrackerAccessor) minecraft.getMusicManager()).jammarr$timeUntilNextSong(0);
    }

    private LegacySoundAccess() {}
}
