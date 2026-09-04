package stonytark.jammarr.client;

import net.minecraft.client.sounds.SoundManager;

/** Keeps the channel-pool diagnostic behind the version-specific Minecraft API. */
final class SoundChannelDiagnostics {
    private SoundChannelDiagnostics() {}

    static String describe(SoundManager manager) {
        return manager.getChannelDebugString();
    }
}
