package stonytark.jammarr.client;

import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import java.util.Collections;
import java.util.Set;

@SideOnly(Side.CLIENT)
public final class LegacyGuiFactory implements IModGuiFactory {
    @Override public void initialize(Minecraft minecraft) {}
    @Override public boolean hasConfigGui() { return true; }
    @Override public GuiScreen createConfigGui(GuiScreen parentScreen) { return new LegacyClientConfigScreen(parentScreen); }
    @Override public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() { return Collections.emptySet(); }
}
