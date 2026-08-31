package com.mumfrey.liteloader;

import net.minecraft.util.IChatComponent;

public interface ChatListener extends LiteMod {
    void onChat(IChatComponent message, String unformattedMessage);
}
