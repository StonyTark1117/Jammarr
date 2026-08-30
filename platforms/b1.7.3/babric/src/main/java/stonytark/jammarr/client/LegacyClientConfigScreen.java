package stonytark.jammarr.client;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;
import stonytark.jammarr.core.platform.JammarrSettings;

public final class LegacyClientConfigScreen extends Screen {
    private final Screen parent;
    public LegacyClientConfigScreen(Screen parent) { this.parent = parent; }

    @Override public void init() {
        buttons.clear();
        buttons.add(new ButtonWidget(1, width / 2 - 100, height / 2 - 24, 200, 20,
                "Listening: " + (JammarrSettings.enabled() ? "On" : "Off")));
        buttons.add(new ButtonWidget(2, width / 2 - 100, height / 2, 64, 20, "Volume -"));
        buttons.add(new ButtonWidget(3, width / 2 + 36, height / 2, 64, 20, "Volume +"));
        buttons.add(new ButtonWidget(0, width / 2 - 100, height / 2 + 36, 200, 20, "Done"));
    }

    @Override protected void buttonClicked(ButtonWidget button) {
        if (button.id == 0) { minecraft.setScreen(parent); return; }
        if (button.id == 1) {
            JammarrSettings.enabled(!JammarrSettings.enabled()); JammarrSettings.saveEnabled();
            LegacyClientState.INSTANCE.listeningChanged();
        } else if (button.id == 2 || button.id == 3) {
            JammarrSettings.volume(JammarrSettings.volume() + (button.id == 3 ? 0.1 : -0.1));
            JammarrSettings.saveVolume();
        }
        init();
    }

    @Override public void render(int mouseX, int mouseY, float tickDelta) {
        renderBackground();
        drawCenteredTextWithShadow(textRenderer, "Jammarr Client Settings", width / 2, height / 2 - 66, 0xFFFFFF);
        drawCenteredTextWithShadow(textRenderer, "These settings affect only this Minecraft client", width / 2, height / 2 - 50, 0xA0D8FF);
        drawCenteredTextWithShadow(textRenderer, "Volume " + Math.round(JammarrSettings.volume() * 100.0) + "%", width / 2, height / 2 + 6, 0xCFCFCF);
        super.render(mouseX, mouseY, tickDelta);
    }
}
