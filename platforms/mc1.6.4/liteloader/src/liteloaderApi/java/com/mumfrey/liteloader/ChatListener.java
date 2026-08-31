package com.mumfrey.liteloader;

import net.minecraft.util.ChatMessageComponent;

public interface ChatListener extends LiteMod {
    void onChat(ChatMessageComponent message, String unformattedMessage);
}
