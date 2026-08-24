package stonytark.jammarr.core.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Polls a sequence-tagged command file used only by real-client acceptance.
 * A command is formatted as {@code sequence|operation}; changing the sequence
 * allows the harness to issue the same operation more than once.
 */
public final class AcceptanceControlFile {
    private String lastValue = "";

    public String poll() {
        String configured = ProtocolLimits.audioControlFile();
        if (configured.isEmpty()) return "";
        Path path = Paths.get(configured);
        if (!Files.isRegularFile(path)) return "";
        final String value;
        try {
            value = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return "";
        }
        if (value.isEmpty() || value.equals(lastValue)) return "";
        lastValue = value;
        int separator = value.indexOf('|');
        return separator < 0 ? value : value.substring(separator + 1).trim();
    }

    public void reset() { lastValue = ""; }
}
