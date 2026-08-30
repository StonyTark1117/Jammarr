package stonytark.jammarr.client;

import net.minecraft.client.gui.widget.TextFieldWidget;

/** Preserves an in-progress text edit when the legacy screen rebuilds its controls. */
final class LegacyTextFieldState {
    private final String text;
    private final boolean focused;

    private LegacyTextFieldState(String text, boolean focused) {
        this.text = text;
        this.focused = focused;
    }

    static LegacyTextFieldState capture(TextFieldWidget field) {
        return new LegacyTextFieldState(field.getText(), field.focused);
    }

    String text() {
        return text;
    }

    void restore(TextFieldWidget field) {
        field.setText(text);
        field.setFocused(focused);
    }
}
