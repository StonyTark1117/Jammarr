package stonytark.jammarr.core.server;

import java.io.IOException;

public final class PlexException extends IOException {
    public enum Kind { CONFIGURATION, AUTHENTICATION, NOT_FOUND, OFFLINE, INVALID_RESPONSE, TRANSCODE }
    private final Kind kind;

    public PlexException(Kind kind, String message) { super(message); this.kind = kind; }
    public PlexException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
    public Kind kind() { return kind; }
}
