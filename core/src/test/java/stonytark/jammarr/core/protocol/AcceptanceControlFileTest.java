package stonytark.jammarr.core.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AcceptanceControlFileTest {
    @TempDir Path temporary;

    @AfterEach void clearProperties() {
        System.clearProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY);
        System.clearProperty(ProtocolLimits.ACCEPTANCE_AUDIO_PROBE_PROPERTY);
        System.clearProperty(ProtocolLimits.ACCEPTANCE_AUDIO_CONTROL_FILE_PROPERTY);
    }

    @Test void sequenceTagsAllowRepeatedCommandsWithoutReplayingUnchangedContent() throws Exception {
        Path control = temporary.resolve("control.txt");
        System.setProperty(ProtocolLimits.ACCEPTANCE_ENABLED_PROPERTY, "true");
        System.setProperty(ProtocolLimits.ACCEPTANCE_AUDIO_PROBE_PROPERTY, "true");
        System.setProperty(ProtocolLimits.ACCEPTANCE_AUDIO_CONTROL_FILE_PROPERTY, control.toString());
        AcceptanceControlFile reader = new AcceptanceControlFile();

        Files.write(control, "1|pause\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("pause", reader.poll());
        assertEquals("", reader.poll());
        Files.write(control, "2|pause\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("pause", reader.poll());
        reader.reset();
        assertEquals("pause", reader.poll());
    }
}
