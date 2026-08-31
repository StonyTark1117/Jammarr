package com.mumfrey.liteloader;

import net.minecraft.client.Minecraft;

public interface Tickable extends LiteMod {
    void onTick(Minecraft minecraft, float partialTicks, boolean inGame, boolean clock);
}
