package stonytark.jammarr.client;

import net.minecraft.client.gui.widget.TextFieldWidget;

/** Preserves an in-progress text edit when the legacy screen rebuilds its controls. */
final class LegacyTextFieldState {
    private final String text;
    private final int cursor;
    private final int selection;
    private final boolean focused;

    private LegacyTextFieldState(String text, int cursor, int selection, boolean focused) {
        this.text = text;
        this.cursor = cursor;
        this.selection = selection;
        this.focused = focused;
    }

    static LegacyTextFieldState capture(TextFieldWidget field) {
        return new LegacyTextFieldState(field.getText(), field.getCursor(),
                field.getSelectionEnd(), field.isFocused());
    }

    String text() {
        return text;
    }

    void restore(TextFieldWidget field) {
        field.setText(text);
        field.setCursor(cursor);
        field.setSelectionEnd(selection);
        field.setFocused(focused);
    }
}
