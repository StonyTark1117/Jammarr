package stonytark.jammarr.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.MusicTicker;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.SoundManager;
import paulscode.sound.SoundSystem;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Narrow reflection bridge which does not depend on Forge's ReflectionHelper. */
final class LegacySoundAccess {
    /*
     * LiteLoader runs the release artifact directly against obfuscated Minecraft.
     * String field names are therefore unsafe: Forge's ReflectionHelper remaps SRG
     * names, but a standalone LiteLoader client has no equivalent remapper. These
     * fields are unique by type in their owners in 1.8.9, so resolve their stable
     * descriptors instead.
     */
    private static final Field SOUND_MANAGER = fieldByType(SoundHandler.class, SoundManager.class);
    private static final Field SOUND_SYSTEM = fieldByType(SoundManager.class, SoundSystem.class);
    private static final Field MUSIC_TICKER = fieldByType(Minecraft.class, MusicTicker.class);
    private static final Field CURRENT_MUSIC = fieldByType(MusicTicker.class, ISound.class);
    private static final Field MUSIC_DELAY = fieldByType(MusicTicker.class, Integer.TYPE);

    static SoundSystem soundSystem(Minecraft minecraft) {
        try {
            SoundManager manager = (SoundManager) SOUND_MANAGER.get(minecraft.getSoundHandler());
            return (SoundSystem) SOUND_SYSTEM.get(manager);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Unable to access Minecraft sound system", error);
        }
    }

    static void suppressVanillaMusic(Minecraft minecraft) {
        try {
            MusicTicker ticker = (MusicTicker) MUSIC_TICKER.get(minecraft);
            ISound current = (ISound) CURRENT_MUSIC.get(ticker);
            if (current != null) {
                try { minecraft.getSoundHandler().stopSound(current); }
                catch (LinkageError unavailable) { /* Never call a failed LWJGL2 context again. */ }
                CURRENT_MUSIC.set(ticker, null);
            }
            MUSIC_DELAY.setInt(ticker, Integer.MAX_VALUE);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Unable to suppress vanilla music", error);
        }
    }

    static void restoreVanillaMusic(Minecraft minecraft) {
        try {
            MusicTicker ticker = (MusicTicker) MUSIC_TICKER.get(minecraft);
            MUSIC_DELAY.setInt(ticker, 0);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Unable to restore vanilla music scheduling", error);
        }
    }

    private static Field fieldByType(Class<?> owner, Class<?> expectedType) {
        Field match = null;
        for (Field candidate : owner.getDeclaredFields()) {
            if (Modifier.isStatic(candidate.getModifiers())
                    || !expectedType.isAssignableFrom(candidate.getType())) {
                continue;
            }
            if (match != null) {
                throw new IllegalStateException("Multiple " + expectedType.getName()
                        + " fields found on " + owner.getName());
            }
            match = candidate;
        }
        if (match != null) {
            match.setAccessible(true);
            return match;
        }
        throw new IllegalStateException("Unable to find " + expectedType.getName()
                + " field on " + owner.getName());
    }

    private LegacySoundAccess() {}
}
