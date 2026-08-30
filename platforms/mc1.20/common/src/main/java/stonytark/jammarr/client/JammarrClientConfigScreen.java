package stonytark.jammarr.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import stonytark.jammarr.core.platform.JammarrSettings;

/** Local settings exposed by NeoForge's Mod List before joining a server. */
public final class JammarrClientConfigScreen extends Screen {
    private final Screen parent;

    public JammarrClientConfigScreen(Screen parent) {
        super(Component.translatable("jammarr.config.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        int center = width / 2;
        addRenderableWidget(Button.builder(listeningLabel(), button -> {
            JammarrSettings.enabled(!JammarrSettings.enabled());
            JammarrSettings.saveEnabled();
            JammarrClientState.INSTANCE.listeningChanged();
            rebuildWidgets();
        }).bounds(center - 100, height / 2 - 28, 200, 20).build());
        addRenderableWidget(new VolumeSlider(center - 100, height / 2, 200, 20));
        addRenderableWidget(Button.builder(Component.translatable("jammarr.config.done"), button -> onClose())
                .bounds(center - 100, height / 2 + 38, 200, 20).build());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 62, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("jammarr.config.local_only"), width / 2, height / 2 - 46, 0xA0D8FF);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private static Component listeningLabel() {
        return Component.translatable("jammarr.config.listening", Component.translatable(JammarrSettings.enabled() ? "jammarr.config.on" : "jammarr.config.off"));
    }

    private static final class VolumeSlider extends AbstractSliderButton {
        private VolumeSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), JammarrSettings.volume());
            updateMessage();
        }

        @Override protected void updateMessage() { setMessage(Component.translatable("jammarr.screen.volume", Math.round(value * 100))); }
        @Override protected void applyValue() { JammarrSettings.volume(value); JammarrSettings.saveVolume(); }
    }
}
