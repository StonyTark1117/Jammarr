package stonytark.jammarr.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import stonytark.jammarr.compat.MinecraftCompat;
import stonytark.jammarr.core.platform.JammarrSettings;

/** Local settings exposed by NeoForge's Mod List before joining a server. */
public final class JammarrClientConfigScreen extends Screen {
    private final Screen parent;

    public JammarrClientConfigScreen(Screen parent) {
        super(stonytark.jammarr.compat.MinecraftCompat.translatable("jammarr.config.title"));
        this.parent = parent;
    }

    private void rebuildWidgets() {
        clearWidgets();
        init();
    }

    @Override protected void init() {
        int center = width / 2;
        addRenderableWidget(CompatButton.builder(listeningLabel(), button -> {
            JammarrSettings.enabled(!JammarrSettings.enabled());
            JammarrSettings.saveEnabled();
            JammarrClientState.INSTANCE.listeningChanged();
            rebuildWidgets();
        }).bounds(center - 100, height / 2 - 28, 200, 20).build());
        addRenderableWidget(new VolumeSlider(center - 100, height / 2, 200, 20));
        addRenderableWidget(CompatButton.builder(stonytark.jammarr.compat.MinecraftCompat.translatable("jammarr.config.done"), button -> onClose())
                .bounds(center - 100, height / 2 + 38, 200, 20).build());
    }

    @Override public void render(PoseStack graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        drawCenteredString(graphics, font, title, width / 2, height / 2 - 62, 0xFFFFFF);
        drawCenteredString(graphics, font, stonytark.jammarr.compat.MinecraftCompat.translatable("jammarr.config.local_only"), width / 2, height / 2 - 46, 0xA0D8FF);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private static Component listeningLabel() {
        return stonytark.jammarr.compat.MinecraftCompat.translatable("jammarr.config.listening", stonytark.jammarr.compat.MinecraftCompat.translatable(JammarrSettings.enabled() ? "jammarr.config.on" : "jammarr.config.off"));
    }

    private static final class VolumeSlider extends AbstractSliderButton {
        private VolumeSlider(int x, int y, int width, int height) {
            super(x, y, width, height, stonytark.jammarr.compat.MinecraftCompat.empty(), JammarrSettings.volume());
            updateMessage();
        }

        @Override protected void updateMessage() { setMessage(stonytark.jammarr.compat.MinecraftCompat.translatable("jammarr.screen.volume", Math.round(value * 100))); }
        @Override protected void applyValue() { JammarrSettings.volume(value); JammarrSettings.saveVolume(); }
    }
}
