package com.mumfrey.liteloader;

import java.util.List;

public interface PluginChannelListener extends LoginListener {
    List<String> getChannels();
    void onCustomPayload(String channel, int length, byte[] data);
}
