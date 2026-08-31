package com.mumfrey.liteloader.core;

public abstract class ClientPluginChannels extends PluginChannels<Object> {
    public static boolean sendMessage(String channel, byte[] data, ChannelPolicy policy) { return false; }
}
