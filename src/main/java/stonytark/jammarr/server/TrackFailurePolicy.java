package stonytark.jammarr.server;

public final class TrackFailurePolicy {
    public enum Action { WAIT_FOR_PLEX, SKIP_TRACK }
    public static Action action(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        if (root instanceof PlexException plex && (plex.kind() == PlexException.Kind.AUTHENTICATION
                || plex.kind() == PlexException.Kind.CONFIGURATION || plex.kind() == PlexException.Kind.OFFLINE)) return Action.WAIT_FOR_PLEX;
        return Action.SKIP_TRACK;
    }
    private TrackFailurePolicy() {}
}
