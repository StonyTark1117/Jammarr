package stonytark.jammarr;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Small placeholder-format bridge around the pre-Log4j FML logger. */
public final class LegacyLogger {
    private final Logger delegate;

    public LegacyLogger(Logger delegate) {
        if (delegate == null) throw new IllegalArgumentException("delegate");
        this.delegate = delegate;
    }

    public void info(String message, Object... arguments) {
        delegate.info(format(message, arguments));
    }

    public void warn(String message, Object... arguments) {
        log(Level.WARNING, message, arguments);
    }

    public void error(String message, Object... arguments) {
        log(Level.SEVERE, message, arguments);
    }

    private void log(Level level, String message, Object[] arguments) {
        Throwable error = arguments != null && arguments.length > 0
                && arguments[arguments.length - 1] instanceof Throwable
                ? (Throwable) arguments[arguments.length - 1] : null;
        String rendered = format(message, arguments);
        if (error == null) delegate.log(level, rendered);
        else delegate.log(level, rendered, error);
    }

    static String format(String message, Object... arguments) {
        String rendered = message == null ? "" : message;
        if (arguments == null) return rendered;
        for (Object argument : arguments) {
            if (argument instanceof Throwable) continue;
            int marker = rendered.indexOf("{}");
            if (marker < 0) break;
            rendered = rendered.substring(0, marker) + String.valueOf(argument)
                    + rendered.substring(marker + 2);
        }
        return rendered;
    }
}
