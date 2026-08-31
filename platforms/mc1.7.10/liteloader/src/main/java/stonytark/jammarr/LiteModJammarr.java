package stonytark.jammarr;

import com.mumfrey.liteloader.ChatListener;
import com.mumfrey.liteloader.PluginChannelListener;
import com.mumfrey.liteloader.Tickable;
import net.minecraft.client.Minecraft;
import net.minecraft.network.INetHandler;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.util.IChatComponent;
import stonytark.jammarr.client.LegacyClient;
import stonytark.jammarr.config.LegacyConfig;
import stonytark.jammarr.network.LegacyNetwork;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** LiteLoader 1.7.10 lifecycle and plugin-channel adapter for Forge-backed Jammarr servers. */
public final class LiteModJammarr implements Tickable, PluginChannelListener, ChatListener {
    @Override public String getName() { return Jammarr.MOD_NAME; }
    @Override public String getVersion() { return Jammarr.VERSION; }

    @Override public void init(File configPath) {
        try {
            LegacyConfig.installClient(configPath);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load canonical Jammarr client configuration", error);
        }
        LegacyNetwork.register();
        LegacyClient.register();
        Jammarr.LOGGER.info("Initializing Jammarr {} client companion for LiteLoader 1.7.10 protocol {}",
                Jammarr.VERSION, Jammarr.PROTOCOL);
    }

    @Override public void upgradeSettings(String version, File configPath, File oldConfigPath) {}
    @Override public List<String> getChannels() { return Collections.singletonList(Jammarr.MOD_ID); }

    @Override public void onJoinGame(INetHandler handler, S01PacketJoinGame packet) {
        LegacyNetwork.connected();
        LegacyClient.joined();
    }

    @Override public void onCustomPayload(String channel, int length, byte[] data) {
        LegacyNetwork.receive(channel, length, data);
    }

    @Override public void onTick(Minecraft minecraft, float partialTicks, boolean inGame, boolean clock) {
        LegacyClient.tick(minecraft, inGame);
    }

    @Override public void onChat(IChatComponent message, String unformattedMessage) {
        LegacyClient.chat(unformattedMessage);
    }
}
