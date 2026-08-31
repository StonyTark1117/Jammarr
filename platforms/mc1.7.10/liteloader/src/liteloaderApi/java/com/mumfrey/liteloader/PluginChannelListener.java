package com.mumfrey.liteloader;

import java.util.List;

public interface PluginChannelListener extends JoinGameListener {
    List<String> getChannels();
    void onCustomPayload(String channel, int length, byte[] data);
}
