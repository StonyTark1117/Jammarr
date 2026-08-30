package stonytark.jammarr.client;

/** Pure lookup kept separate from GuiScreen so hover-help coverage is unit-testable without LWJGL. */
final class LegacyUiTooltips {
    private LegacyUiTooltips() {}

    static String tooltip(int id) {
        if (id >= 10 && id <= 17) {
            switch (id - 10) {
                case 0: return "Show current shared playback and its source";
                case 1: return "Find tracks, albums, artists, and playlists in the selected music library";
                case 2: return "Browse artists in the selected music library";
                case 3: return "Browse albums in the selected music library";
                case 4: return "Browse playlists in the selected music library";
                case 5: return "Configure autoplay, Library Shuffle, and Sonic Mix";
                case 6: return "Build a Sonic path through two to five tracks; Plex Pass is required";
                case 7: return "View and reorder the shared playback queue";
                default: return null;
            }
        }
        switch (id) {
            case 50: return "Run this search";
            case 51: return "Show the previous results page";
            case 52: return "Show the next results page";
            case 53: return "Toggle Jammarr audio on this client only";
            case 54: return "Lower Jammarr volume on this client only";
            case 55: return "Raise Jammarr volume on this client only";
            case 56: return "Pause or resume shared playback for everyone";
            case 57: return "Skip the current track for everyone";
            case 58: return "Clear all shared playback after confirmation";
            case 59: return "Retry local audio playback after an error";
            case 60: return "Continue playback with Sonic or configured metadata fallback";
            case 61: return "Shuffle the selected music library; Plex Pass is not required";
            case 62: return "Start this Sonic Mix after queued manual requests";
            case 63: return "Stop the active station or autoplay source";
            case 70: return "Preview the generated Sonic path without changing playback";
            case 71: return "Start this Adventure after queued manual requests";
            case 72: return "Replace current shared playback with this Adventure";
            case 73: return "Remove all Adventure waypoints";
            default: break;
        }
        if (id >= 100 && id < 200) return "Add to the shared manual queue";
        if (id >= 200 && id < 300) return "Start radio; metadata fallback can work without Plex Pass";
        if (id >= 300 && id < 400) return "Add to Sonic Mix; metadata fallback can be enabled";
        if (id >= 400 && id < 500) return "Add Adventure waypoint; Sonic and Plex Pass are required";
        if (id >= 500 && id < 600) return "Remove this manual request";
        if (id >= 600 && id < 700) return "Move this manual request earlier";
        if (id >= 700 && id < 800) return "Move this manual request later";
        if (id >= 800 && id < 900) return "Remove this Adventure waypoint";
        return null;
    }
}
