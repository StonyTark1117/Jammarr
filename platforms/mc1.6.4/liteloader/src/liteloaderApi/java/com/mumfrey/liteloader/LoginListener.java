package com.mumfrey.liteloader;

import net.minecraft.network.packet.NetHandler;
import net.minecraft.network.packet.Packet1Login;

public interface LoginListener extends LiteMod {
    void onLogin(NetHandler handler, Packet1Login packet);
}
