package stonytark.jammarr.core.platform;

public interface CoreLogger {
    void info(String message);
    void warn(String message, Throwable error);

    CoreLogger NO_OP = new CoreLogger() {
        @Override public void info(String message) {}
        @Override public void warn(String message, Throwable error) {}
    };
}
