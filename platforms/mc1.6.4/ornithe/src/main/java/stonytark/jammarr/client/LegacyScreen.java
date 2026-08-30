package stonytark.jammarr.client;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.lwjgl.input.Keyboard;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.network.LegacyPacketTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Compact eight-tab Ornithe 1.6.4 screen with full shared-control flows. */
final class LegacyScreen extends Screen {
    private enum View {
        NOW("Now Playing", null), SEARCH("Search", ControlPackets.BrowseKind.SEARCH),
        ARTISTS("Artists", ControlPackets.BrowseKind.ARTISTS), ALBUMS("Albums", ControlPackets.BrowseKind.ALBUMS),
        PLAYLISTS("Playlists", ControlPackets.BrowseKind.PLAYLISTS), STATIONS("Stations", null),
        ADVENTURE("Adventure", ControlPackets.BrowseKind.SEARCH), QUEUE("Queue", ControlPackets.BrowseKind.QUEUE);
        private final String label;
        private final ControlPackets.BrowseKind browseKind;
        View(String label, ControlPackets.BrowseKind browseKind) { this.label = label; this.browseKind = browseKind; }
    }

    private static final int TAB_BASE = 10;
    private static final int GO = 50, PREVIOUS = 51, NEXT = 52, MUTE = 53, VOLUME_DOWN = 54,
            VOLUME_UP = 55, PAUSE = 56, SKIP = 57, CLEAR = 58, RETRY = 59;
    private static final int AUTOPLAY = 60, SHUFFLE = 61, MIX_START = 62, STATION_STOP = 63;
    private static final int ADVENTURE_PREVIEW = 70, ADVENTURE_START = 71,
            ADVENTURE_START_NOW = 72, ADVENTURE_CLEAR = 73;
    private static final int ADD_BASE = 100, RADIO_BASE = 200, MIX_BASE = 300, ADVENTURE_ADD_BASE = 400,
            REMOVE_BASE = 500, UP_BASE = 600, DOWN_BASE = 700, WAYPOINT_REMOVE_BASE = 800;
    private static final int MAX_VISIBLE_ROWS = 8;

    private final LegacyClientState state;
    private final List<StationModels.StationSeed> mixSeeds = new ArrayList<StationModels.StationSeed>();
    private final List<StationModels.StationSeed> waypoints = new ArrayList<StationModels.StationSeed>();
    private View view = View.NOW;
    private TextFieldWidget search;
    private LegacyTextFieldState searchEditState;
    private String searchQuery = "";
    private String notice = "";
    private boolean requestPending;
    private ControlPackets.BrowseKind pendingKind;
    private String pendingQuery = "";
    private int pendingPage;
    private long clearArmedUntil;
    private long startNowArmedUntil;

    LegacyScreen(LegacyClientState state) {
        this.state = state;
        if (state.browse().kind() == ControlPackets.BrowseKind.SEARCH) searchQuery = state.browse().query();
    }

    @Override public void init() {
        if (search != null) {
            searchEditState = LegacyTextFieldState.capture(search);
            searchQuery = searchEditState.text();
        }
        Keyboard.enableRepeatEvents(true);
        buttons.clear();
        int left = Math.max(8, (width - 760) / 2);
        int panel = Math.min(760, width - 16);
        int tabWidth = panel / View.values().length;
        for (int index = 0; index < View.values().length; index++) {
            View candidate = View.values()[index];
            ButtonWidget tab = button(TAB_BASE + index, left + tabWidth * index, 35,
                    index == View.values().length - 1 ? panel - tabWidth * index : tabWidth, 20, candidate.label);
            tab.active = candidate != view;
        }
        int top = 65;
        if (view == View.SEARCH || view == View.ADVENTURE) {
            search = new TextFieldWidget(textRenderer, left, top, panel - 66, 20);
            search.setMaxLength(128); search.setText(searchQuery);
            if (searchEditState != null) searchEditState.restore(search);
            button(GO, left + panel - 62, top, 62, 20, "Search");
            top += 26;
        } else search = null;
        if (view == View.STATIONS) addStationButtons(left, top, panel);
        else if (view == View.ADVENTURE) addAdventureButtons(left, top, panel);
        else if (view != View.NOW) addResultButtons(left, top, panel);
        addBottomButtons(left, panel);
    }

    private void addStationButtons(int left, int top, int panel) {
        if (!state.playback().operator()) return;
        button(AUTOPLAY, left, top + 46, 110, 20,
                "Autoplay: " + (state.station().autoplayEnabled() ? "On" : "Off"));
        button(SHUFFLE, left + 116, top + 46, 120, 20, "Library Shuffle");
        ButtonWidget mix = button(MIX_START, left + 242, top + 46, 100, 20, "Start Mix");
        mix.active = mixSeeds.size() >= 2;
        button(STATION_STOP, left + 348, top + 46, 100, 20, "Stop Station");
    }

    private void addAdventureButtons(int left, int top, int panel) {
        if (!state.playback().operator()) return;
        for (int index = 0; index < waypoints.size(); index++) {
            button(WAYPOINT_REMOVE_BASE + index, left + panel - 28, top + 14 + index * 20, 26, 18, "X");
        }
        int actions = top + Math.min(5, waypoints.size()) * 20 + 22;
        ButtonWidget preview = button(ADVENTURE_PREVIEW, left, actions, 76, 20, "Preview");
        ButtonWidget start = button(ADVENTURE_START, left + 82, actions, 76, 20, "Start");
        ButtonWidget startNow = button(ADVENTURE_START_NOW, left + 164, actions, 94, 20, "Start Now");
        preview.active = start.active = startNow.active = waypoints.size() >= 2;
        button(ADVENTURE_CLEAR, left + 264, actions, 76, 20, "Clear");
        int searchTop = actions + 26;
        addResultButtons(left, searchTop, panel);
    }

    private void addResultButtons(int left, int top, int panel) {
        ControlPackets.BrowseResults results = state.browse();
        if (view.browseKind == null || results.kind() != view.browseKind || requestPending) return;
        int rows = Math.min(MAX_VISIBLE_ROWS, results.items().size());
        for (int row = 0; row < rows; row++) {
            int y = top + row * 22;
            StationModels.MediaItem item = results.items().get(row);
            if (view == View.QUEUE) {
                int queueIndex = results.page() * 20 + row;
                if (queueIndex < state.playback().queue().size()) {
                    StatePackets.QueueEntry entry = state.playback().queue().get(queueIndex);
                    if (entry.editable() && state.playback().operator()) {
                        ButtonWidget up = button(UP_BASE + row, left + panel - 84, y, 26, 20, "^");
                        ButtonWidget down = button(DOWN_BASE + row, left + panel - 56, y, 26, 20, "v");
                        button(REMOVE_BASE + row, left + panel - 28, y, 26, 20, "X");
                        up.active = queueIndex > 0 && state.playback().queue().get(queueIndex - 1).editable();
                        down.active = queueIndex + 1 < state.playback().queue().size()
                                && state.playback().queue().get(queueIndex + 1).editable();
                    }
                }
            } else {
                int x = left + panel - 28;
                button(ADD_BASE + row, x, y, 26, 20, "+");
                if (state.playback().operator() && item.kind() != StationModels.ItemKind.PLAYLIST) {
                    x -= 28; button(RADIO_BASE + row, x, y, 26, 20, "R");
                    x -= 28; button(MIX_BASE + row, x, y, 26, 20, "M");
                    if (item.kind() == StationModels.ItemKind.TRACK) {
                        x -= 28; button(ADVENTURE_ADD_BASE + row, x, y, 26, 20, "A");
                    }
                }
            }
        }
        if (results.page() > 0) button(PREVIOUS, left + panel - 70, height - 27, 32, 20, "<");
        if (results.hasMore()) button(NEXT, left + panel - 34, height - 27, 32, 20, ">");
    }

    private void addBottomButtons(int left, int panel) {
        button(MUTE, left, height - 27, 68, 20, JammarrSettings.enabled() ? "Mute" : "Unmute");
        button(VOLUME_DOWN, left + 72, height - 27, 28, 20, "-");
        button(VOLUME_UP, left + 102, height - 27, 28, 20, "+");
        if ("ERROR".equals(state.audioState())) button(RETRY, left + 134, height - 27, 70, 20, "Retry Audio");
        if (state.playback().operator()) {
            button(PAUSE, left + 210, height - 27, 72, 20, state.playback().paused() ? "Resume" : "Pause");
            button(SKIP, left + 288, height - 27, 58, 20, "Skip");
            button(CLEAR, left + 352, height - 27, 72, 20,
                    System.currentTimeMillis() < clearArmedUntil ? "Confirm" : "Clear");
        }
    }

    private ButtonWidget button(int id, int x, int y, int width, int height, String label) {
        ButtonWidget value = new ButtonWidget(id, x, y, width, height, label);
        buttons.add(value);
        return value;
    }

    @Override protected void buttonClicked(ButtonWidget button) {
        if (!button.active) return;
        if (button.id >= TAB_BASE && button.id < TAB_BASE + View.values().length) {
            view = View.values()[button.id - TAB_BASE]; requestPending = false; notice = ""; state.clearNotice();
            if (view.browseKind != null) request(0); else init();
            return;
        }
        if (button.id == GO) { request(0); return; }
        if (button.id == PREVIOUS) { request(state.browse().page() - 1); return; }
        if (button.id == NEXT) { request(state.browse().page() + 1); return; }
        if (button.id == MUTE) {
            JammarrSettings.enabled(!JammarrSettings.enabled()); JammarrSettings.saveEnabled();
            state.listeningChanged(); init(); return;
        }
        if (button.id == VOLUME_DOWN || button.id == VOLUME_UP) {
            double delta = button.id == VOLUME_UP ? 0.1 : -0.1;
            JammarrSettings.volume(JammarrSettings.volume() + delta); JammarrSettings.saveVolume(); init(); return;
        }
        if (button.id == RETRY) { state.retryAudio(); init(); return; }
        if (button.id == PAUSE) {
            control(state.playback().paused() ? ControlPackets.ControlAction.RESUME : ControlPackets.ControlAction.PAUSE, -1, ""); return;
        }
        if (button.id == SKIP) { control(ControlPackets.ControlAction.SKIP, -1, ""); return; }
        if (button.id == CLEAR) {
            if (System.currentTimeMillis() < clearArmedUntil) {
                clearArmedUntil = 0L; control(ControlPackets.ControlAction.CLEAR, -1, "");
            } else { clearArmedUntil = System.currentTimeMillis() + 5_000L; notice = "Press Clear again to confirm"; init(); }
            return;
        }
        if (button.id == AUTOPLAY) {
            station(ControlPackets.StationAction.SET_AUTOPLAY, StationModels.StationType.AUTOPLAY,
                    !state.station().autoplayEnabled(), Collections.<StationModels.StationSeed>emptyList()); return;
        }
        if (button.id == SHUFFLE) {
            station(ControlPackets.StationAction.START, StationModels.StationType.LIBRARY_SHUFFLE,
                    false, Collections.<StationModels.StationSeed>emptyList()); return;
        }
        if (button.id == MIX_START) {
            station(ControlPackets.StationAction.START, StationModels.StationType.SONIC_MIX, false, mixSeeds); return;
        }
        if (button.id == STATION_STOP) {
            station(ControlPackets.StationAction.STOP, StationModels.StationType.NONE,
                    false, Collections.<StationModels.StationSeed>emptyList()); return;
        }
        if (button.id == ADVENTURE_PREVIEW) {
            station(ControlPackets.StationAction.PREVIEW_ADVENTURE,
                    StationModels.StationType.SONIC_ADVENTURE, false, waypoints); return;
        }
        if (button.id == ADVENTURE_START) {
            station(ControlPackets.StationAction.START, StationModels.StationType.SONIC_ADVENTURE, false, waypoints); return;
        }
        if (button.id == ADVENTURE_START_NOW) {
            if (System.currentTimeMillis() < startNowArmedUntil) {
                startNowArmedUntil = 0L;
                station(ControlPackets.StationAction.START_NOW, StationModels.StationType.SONIC_ADVENTURE, false, waypoints);
            } else {
                startNowArmedUntil = System.currentTimeMillis() + 5_000L;
                notice = "Press Start Now again to replace current playback"; init();
            }
            return;
        }
        if (button.id == ADVENTURE_CLEAR) { waypoints.clear(); init(); return; }
        handleRowAction(button.id);
    }

    private void handleRowAction(int id) {
        int row = id % 100;
        if (id >= WAYPOINT_REMOVE_BASE && id < WAYPOINT_REMOVE_BASE + 100) {
            if (row < waypoints.size()) waypoints.remove(row);
            init();
            return;
        }
        if (row < 0 || row >= state.browse().items().size()) return;
        StationModels.MediaItem item = state.browse().items().get(row);
        if (id >= ADD_BASE && id < RADIO_BASE) {
            LegacyNetwork.sendToServer(LegacyPacketTypes.QUEUE_REQUEST,
                    new ControlPackets.QueueRequest(item.kind(), item.key())); notice = "Queuing...";
        } else if (id >= RADIO_BASE && id < MIX_BASE) {
            StationModels.StationType type = item.kind() == StationModels.ItemKind.TRACK
                    ? StationModels.StationType.TRACK_RADIO : item.kind() == StationModels.ItemKind.ARTIST
                    ? StationModels.StationType.ARTIST_RADIO : item.kind() == StationModels.ItemKind.ALBUM
                    ? StationModels.StationType.ALBUM_RADIO : StationModels.StationType.NONE;
            if (type != StationModels.StationType.NONE) station(ControlPackets.StationAction.START, type,
                    false, Collections.singletonList(seed(item)));
        } else if (id >= MIX_BASE && id < ADVENTURE_ADD_BASE) {
            if ((mixSeeds.isEmpty() || mixSeeds.get(0).kind() == item.kind()) && mixSeeds.size() < 5
                    && !contains(mixSeeds, item.key())) mixSeeds.add(seed(item));
        } else if (id >= ADVENTURE_ADD_BASE && id < REMOVE_BASE) {
            if (item.kind() == StationModels.ItemKind.TRACK && waypoints.size() < 5 && !contains(waypoints, item.key())) {
                waypoints.add(seed(item));
            }
        } else if (id >= REMOVE_BASE && id < UP_BASE) {
            queueControl(ControlPackets.ControlAction.REMOVE, row);
        } else if (id >= UP_BASE && id < DOWN_BASE) {
            queueControl(ControlPackets.ControlAction.MOVE_UP, row);
        } else if (id >= DOWN_BASE && id < WAYPOINT_REMOVE_BASE) {
            queueControl(ControlPackets.ControlAction.MOVE_DOWN, row);
        }
        init();
    }

    private void queueControl(ControlPackets.ControlAction action, int row) {
        int index = state.browse().page() * 20 + row;
        if (index < state.playback().queue().size()) {
            StatePackets.QueueEntry entry = state.playback().queue().get(index);
            control(action, index, entry.key());
        }
    }

    private void request(int page) {
        if (view.browseKind == null || requestPending) return;
        if (search != null) searchQuery = search.getText().trim();
        if (view.browseKind == ControlPackets.BrowseKind.QUEUE) {
            requestPending = false; state.showQueuePage(page); init(); return;
        }
        if ((view == View.SEARCH || view == View.ADVENTURE) && searchQuery.length() < 2) {
            requestPending = false; notice = "Enter at least two characters";
            state.clearBrowse(ControlPackets.BrowseKind.SEARCH, searchQuery); init(); return;
        }
        pendingKind = view.browseKind;
        pendingQuery = pendingKind == ControlPackets.BrowseKind.SEARCH ? searchQuery : "";
        pendingPage = Math.max(0, page);
        requestPending = true;
        LegacyNetwork.sendToServer(LegacyPacketTypes.BROWSE_REQUEST,
                new ControlPackets.BrowseRequest(pendingKind, pendingQuery, pendingPage));
        init();
    }

    private void control(ControlPackets.ControlAction action, int index, String expectedKey) {
        LegacyNetwork.sendToServer(LegacyPacketTypes.CONTROL_REQUEST,
                new ControlPackets.ControlRequest(action, index, expectedKey));
    }

    private void station(ControlPackets.StationAction action, StationModels.StationType type,
                         boolean enabled, List<StationModels.StationSeed> seeds) {
        LegacyNetwork.sendToServer(LegacyPacketTypes.STATION_REQUEST,
                new ControlPackets.StationRequest(action, type, enabled, state.station().generation(),
                        new ArrayList<StationModels.StationSeed>(seeds)));
        notice = "Updating shared playback source...";
    }

    private static StationModels.StationSeed seed(StationModels.MediaItem item) {
        return new StationModels.StationSeed(item.kind(), item.key(), item.title(), item.subtitle());
    }
    private static boolean contains(List<StationModels.StationSeed> seeds, String key) {
        for (StationModels.StationSeed seed : seeds) if (seed.key().equals(key)) return true;
        return false;
    }

    void resultsChanged() {
        ControlPackets.BrowseResults result = state.browse();
        if (requestPending && result.kind() == pendingKind && result.page() == pendingPage
                && result.query().equals(pendingQuery)) requestPending = false;
        init();
    }
    void requestFailed() { requestPending = false; init(); }
    void stateChanged() { init(); }

    @Override protected void keyPressed(char character, int key) {
        if (search != null && search.keyPressed(character, key)) return;
        if (key == Keyboard.KEY_RETURN && search != null) { request(0); return; }
        super.keyPressed(character, key);
    }
    @Override protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        if (search != null) search.mouseClicked(x, y, button);
    }
    @Override public void tick() { if (search != null) search.tick(); }
    @Override public void removed() { Keyboard.enableRepeatEvents(false); }
    @Override public boolean shouldPauseGame() { return false; }

    @Override public void render(int mouseX, int mouseY, float tickDelta) {
        renderBackground();
        drawCenteredString(textRenderer, "Jammarr", width / 2, 12, 0xFFFFFF);
        StatePackets.PlaybackState playback = state.playback();
        String now = playback.status().name() + (empty(playback.title()) ? "" : ": " + playback.title()
                + (empty(playback.artist()) ? "" : " - " + playback.artist()))
                + "  " + time(playback.positionMs()) + "/" + time(playback.durationMs());
        drawCenteredString(textRenderer, trim(now, width - 20), width / 2, 24,
                playback.status() == StatePackets.PlaybackStatus.PLEX_OFFLINE ? 0xFF7777 : 0xCFCFCF);
        int left = Math.max(8, (width - 760) / 2), panel = Math.min(760, width - 16), top = 65;
        if (search != null) { search.render(); top += 26; }
        if (view == View.NOW) drawNow(left, top, panel);
        else if (view == View.STATIONS) drawStations(left, top, panel);
        else if (view == View.ADVENTURE) drawAdventure(left, top, panel);
        else drawResults(left, top, panel);
        String shownNotice = empty(notice) ? (empty(state.notice()) ? playback.statusMessage() : state.notice()) : notice;
        if (!empty(shownNotice)) drawCenteredString(textRenderer, trim(shownNotice, width - 20), width / 2, height - 40, 0xFFB36B);
        if (requestPending) drawCenteredString(textRenderer, "Searching...", width / 2, height / 2, 0xA0D8FF);
        if (view.browseKind != null && view != View.QUEUE) {
            drawCenteredString(textRenderer, "+ Queue   R Radio   M Mix   A Adventure", width / 2, 56, 0x9FAFCF);
        }
        textRenderer.draw("Volume " + Math.round(JammarrSettings.volume() * 100.0) + "%", left + 134, height - 21, 0xCFCFCF);
        super.render(mouseX, mouseY, tickDelta);
        for (Object item : buttons) {
            ButtonWidget button = (ButtonWidget)item;
            String tooltip = tooltip(button.id);
            if (tooltip != null && button.isHovered()) {
                renderTooltip(tooltip, mouseX, mouseY);
                break;
            }
        }
    }

    private void renderTooltip(String text, int mouseX, int mouseY) {
        int tooltipWidth = textRenderer.getWidth(text);
        int x = Math.min(mouseX + 12, width - tooltipWidth - 8);
        int y = Math.min(mouseY + 12, height - 20);
        fillGradient(x - 3, y - 4, x + tooltipWidth + 3, y + 12, 0xF0100010, 0xF0100010);
        textRenderer.draw(text, x, y, 0xFFFFFF);
    }

    private void drawNow(int left, int top, int panel) {
        StatePackets.PlaybackState value = state.playback();
        drawCenteredString(textRenderer, empty(value.title()) ? "Nothing playing" : value.title(), width / 2, top + 12, 0xFFFFFF);
        if (!empty(value.artist())) drawCenteredString(textRenderer, value.artist(), width / 2, top + 28, 0xCFCFCF);
        if (!empty(value.sourceName())) drawCenteredString(textRenderer, "Source: " + value.sourceName(), width / 2, top + 44, 0xA0D8FF);
        drawCenteredString(textRenderer, "Audio: " + state.audioStatus(), width / 2, top + 66,
                "ERROR".equals(state.audioState()) ? 0xFF7777 : 0xA0D8FF);
    }

    private void drawStations(int left, int top, int panel) {
        StatePackets.StationState value = state.station();
        textRenderer.draw("Sonic: " + value.capability().name().toLowerCase() + " - "
                + value.capabilityMessage(), left, top, 0xA0D8FF);
        textRenderer.draw(value.active() && value.stationType() != StationModels.StationType.SONIC_ADVENTURE
                ? "Active: " + value.name() : "No general station active", left, top + 16, 0xCFCFCF);
        textRenderer.draw("Mix seeds: " + mixSeeds.size() + "/5", left, top + 28, 0xCFCFCF);
        int y = top + 72;
        for (StatePackets.QueueEntry entry : value.preview()) {
            textRenderer.draw(trim(entry.title() + " - " + entry.artist(), panel), left, y, 0xBFCFFF); y += 14;
        }
    }

    private void drawAdventure(int left, int top, int panel) {
        textRenderer.draw("Sonic Adventure waypoints (2-5)", left, top, 0xA0D8FF);
        for (int index = 0; index < waypoints.size(); index++) {
            StationModels.StationSeed seed = waypoints.get(index);
            textRenderer.draw((index + 1) + ". " + trim(seed.title() + " - " + seed.subtitle(), panel - 30), left, top + 18 + index * 20, 0xCFCFCF);
        }
        int resultsTop = top + Math.min(5, waypoints.size()) * 20 + 70;
        if (!empty(state.adventure().message())) {
            textRenderer.draw(state.adventure().message(), left, resultsTop, 0xBFCFFF); resultsTop += 14;
        }
        drawResults(left, resultsTop, panel);
    }

    private void drawResults(int left, int top, int panel) {
        ControlPackets.BrowseResults results = state.browse();
        if (view.browseKind == null || results.kind() != view.browseKind || requestPending) return;
        for (int row = 0; row < Math.min(MAX_VISIBLE_ROWS, results.items().size()); row++) {
            StationModels.MediaItem item = results.items().get(row);
            int queueIndex = results.page() * 20 + row;
            String prefix = view == View.QUEUE ? (queueIndex == 0 ? "> " : (queueIndex + 1) + ". ") : "";
            textRenderer.draw(trim(prefix + item.title()
                    + (empty(item.subtitle()) ? "" : " - " + item.subtitle()), panel - 120),
                    left, top + row * 22 + 6, 0xE0E0E0);
        }
    }

    private String trim(String value, int pixels) { return textRenderer.trim(value, Math.max(10, pixels)); }
    static String tooltip(int id) { return LegacyUiTooltips.tooltip(id); }
    private static boolean empty(String value) { return value == null || value.isEmpty(); }
    private static String time(long millis) {
        long seconds = Math.max(0L, millis / 1_000L);
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }
}
