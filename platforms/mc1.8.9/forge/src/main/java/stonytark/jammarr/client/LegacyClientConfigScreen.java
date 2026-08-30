package stonytark.jammarr.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import stonytark.jammarr.core.platform.JammarrSettings;

public final class LegacyClientConfigScreen extends GuiScreen {
    private final GuiScreen parent;
    public LegacyClientConfigScreen(GuiScreen parent) { this.parent = parent; }

    @Override public void initGui() {
        buttonList.clear();
        buttonList.add(new GuiButton(1, width / 2 - 100, height / 2 - 24, 200, 20,
                "Listening: " + (JammarrSettings.enabled() ? "On" : "Off")));
        buttonList.add(new GuiButton(2, width / 2 - 100, height / 2, 64, 20, "Volume -"));
        buttonList.add(new GuiButton(3, width / 2 + 36, height / 2, 64, 20, "Volume +"));
        buttonList.add(new GuiButton(0, width / 2 - 100, height / 2 + 36, 200, 20, "Done"));
    }

    @Override protected void actionPerformed(GuiButton button) {
        if (button.id == 0) { mc.displayGuiScreen(parent); return; }
        if (button.id == 1) {
            JammarrSettings.enabled(!JammarrSettings.enabled()); JammarrSettings.saveEnabled();
            LegacyClientState.INSTANCE.listeningChanged();
        } else if (button.id == 2 || button.id == 3) {
            JammarrSettings.volume(JammarrSettings.volume() + (button.id == 3 ? 0.1 : -0.1));
            JammarrSettings.saveVolume();
        }
        initGui();
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Jammarr Client Settings", width / 2, height / 2 - 66, 0xFFFFFF);
        drawCenteredString(fontRendererObj, "These settings affect only this Minecraft client", width / 2, height / 2 - 50, 0xA0D8FF);
        drawCenteredString(fontRendererObj, "Volume " + Math.round(JammarrSettings.volume() * 100.0) + "%", width / 2, height / 2 + 6, 0xCFCFCF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
