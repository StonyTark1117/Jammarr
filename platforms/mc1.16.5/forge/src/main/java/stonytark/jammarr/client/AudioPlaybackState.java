package stonytark.jammarr.client;

/** Local listener state; the server playback state does not describe this client's audio device. */
public enum AudioPlaybackState {
    DISABLED,
    NO_STREAM,
    BUFFERING,
    PLAYING,
    PAUSED,
    RECOVERING,
    ERROR
}
