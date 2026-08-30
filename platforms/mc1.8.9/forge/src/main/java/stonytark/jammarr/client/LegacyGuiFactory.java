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
    @Override public Class<? extends GuiScreen> mainConfigGuiClass() { return LegacyClientConfigScreen.class; }
    @Override public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() { return Collections.emptySet(); }
    @Override public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) { return null; }
}
