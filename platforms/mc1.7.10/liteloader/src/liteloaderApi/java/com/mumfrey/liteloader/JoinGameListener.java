package com.mumfrey.liteloader;

import net.minecraft.network.INetHandler;
import net.minecraft.network.play.server.S01PacketJoinGame;

public interface JoinGameListener extends LiteMod {
    void onJoinGame(INetHandler handler, S01PacketJoinGame packet);
}
