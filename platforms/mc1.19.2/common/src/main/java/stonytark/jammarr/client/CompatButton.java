package stonytark.jammarr.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Restores the small builder surface introduced after Minecraft 1.19.2. */
final class CompatButton {
    private CompatButton() {}

    static Builder builder(Component label, Button.OnPress press) {
        return new Builder(label, press);
    }

    static final class Builder {
        private final Component label;
        private final Button.OnPress press;
        private int x;
        private int y;
        private int width;
        private int height;

        private Builder(Component label, Button.OnPress press) {
            this.label = label;
            this.press = press;
        }

        Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        Button build() {
            return new Button(x, y, width, height, label, press);
        }
    }
}
