package stonytark.jammarr.client;

import net.minecraft.client.gui.widget.TextFieldWidget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyTextFieldStateTest {
    @Test void preservesUnsubmittedTextAndFocusAcrossARebuild() {
        TextFieldWidget original = field();
        original.setText("still typing a search");
        original.setFocused(true);

        LegacyTextFieldState state = LegacyTextFieldState.capture(original);
        TextFieldWidget rebuilt = field();
        state.restore(rebuilt);

        assertEquals("still typing a search", rebuilt.getText());
        assertTrue(rebuilt.focused);
    }

    private static TextFieldWidget field() {
        TextFieldWidget field = new TextFieldWidget(null, null, 0, 0, 160, 20, "");
        field.setMaxLength(128);
        return field;
    }
}
