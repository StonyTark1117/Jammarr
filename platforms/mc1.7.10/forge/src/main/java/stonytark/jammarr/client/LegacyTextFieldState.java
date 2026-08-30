package stonytark.jammarr.client;

import net.minecraft.client.gui.GuiTextField;

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

    static LegacyTextFieldState capture(GuiTextField field) {
        return new LegacyTextFieldState(field.getText(), field.getCursorPosition(),
                field.getSelectionEnd(), field.isFocused());
    }

    String text() {
        return text;
    }

    void restore(GuiTextField field) {
        field.setText(text);
        field.setCursorPosition(cursor);
        field.setSelectionPos(selection);
        field.setFocused(focused);
    }
}
