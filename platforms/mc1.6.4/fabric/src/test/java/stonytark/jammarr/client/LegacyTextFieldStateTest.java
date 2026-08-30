package stonytark.jammarr.client;

import net.minecraft.client.gui.widget.TextFieldWidget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyTextFieldStateTest {
    @Test void preservesUnsubmittedTextFocusCursorAndSelectionAcrossARebuild() {
        TextFieldWidget original = field();
        original.setText("still typing a search");
        original.setCursor(5);
        original.setSelectionEnd(12);
        original.setFocused(true);

        LegacyTextFieldState state = LegacyTextFieldState.capture(original);
        TextFieldWidget rebuilt = field();
        state.restore(rebuilt);

        assertEquals("still typing a search", rebuilt.getText());
        assertEquals(5, rebuilt.getCursor());
        assertEquals(12, rebuilt.getSelectionEnd());
        assertTrue(rebuilt.isFocused());
    }

    private static TextFieldWidget field() {
        TextFieldWidget field = new TextFieldWidget(null, 0, 0, 160, 20);
        field.setMaxLength(128);
        return field;
    }
}
