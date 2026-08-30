package stonytark.jammarr.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundManager;
import paulscode.sound.SoundSystem;

import java.lang.reflect.Field;

/** Narrow reflection bridge to Minecraft 1.6.4's single Paulscode backend. */
final class LegacySoundAccess {
    private static final Field MUSIC_DELAY = field(SoundManager.class, "ticksBeforeMusic", "field_77383_i");

    static SoundSystem soundSystem(Minecraft minecraft) {
        return minecraft == null || minecraft.sndManager == null ? null : minecraft.sndManager.sndSystem;
    }

    static void suppressVanillaMusic(Minecraft minecraft) {
        SoundManager manager = minecraft == null ? null : minecraft.sndManager;
        if (manager == null) return;
        SoundSystem system = manager.sndSystem;
        if (system != null) {
            try { system.stop("BgMusic"); }
            catch (LinkageError unavailable) {
                // A failed LWJGL2 context cannot safely accept another native call.
            }
        }
        setDelay(manager, Integer.MAX_VALUE);
    }

    static void restoreVanillaMusic(Minecraft minecraft) {
        if (minecraft != null && minecraft.sndManager != null) setDelay(minecraft.sndManager, 0);
    }

    private static void setDelay(SoundManager manager, int value) {
        try { MUSIC_DELAY.setInt(manager, value); }
        catch (IllegalAccessException error) {
            throw new IllegalStateException("Unable to update vanilla music scheduling", error);
        }
    }

    private static Field field(Class<?> owner, String... names) {
        for (String name : names) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new IllegalStateException("Unable to find legacy sound field on " + owner.getName());
    }

    private LegacySoundAccess() {}
}
