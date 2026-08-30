package stonytark.jammarr.client;

import net.minecraft.client.gui.GuiTextField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyTextFieldStateTest {
    @Test void preservesUnsubmittedTextFocusCursorAndSelectionAcrossARebuild() {
        GuiTextField original = field();
        original.setText("still typing a search");
        original.setCursorPosition(5);
        original.setSelectionPos(12);
        original.setFocused(true);

        LegacyTextFieldState state = LegacyTextFieldState.capture(original);
        GuiTextField rebuilt = field();
        state.restore(rebuilt);

        assertEquals("still typing a search", rebuilt.getText());
        assertEquals(5, rebuilt.getCursorPosition());
        assertEquals(12, rebuilt.getSelectionEnd());
        assertTrue(rebuilt.isFocused());
    }

    private static GuiTextField field() {
        GuiTextField field = new GuiTextField(0, null, 0, 0, 160, 20);
        field.setMaxStringLength(128);
        return field;
    }
}
