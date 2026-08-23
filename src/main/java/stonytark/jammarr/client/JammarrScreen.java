package stonytark.jammarr.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import stonytark.jammarr.config.JammarrConfig;
import stonytark.jammarr.network.JammarrPayloads;
import java.util.ArrayList;
import java.util.List;

public final class JammarrScreen extends Screen {
    private enum View {
        NOW("jammarr.screen.now_playing", null), SEARCH("jammarr.screen.search_tab", JammarrPayloads.BrowseKind.SEARCH),
        ARTISTS("jammarr.screen.artists", JammarrPayloads.BrowseKind.ARTISTS), ALBUMS("jammarr.screen.albums", JammarrPayloads.BrowseKind.ALBUMS),
        PLAYLISTS("jammarr.screen.playlists", JammarrPayloads.BrowseKind.PLAYLISTS), STATIONS("jammarr.screen.stations", null),
        ADVENTURE("jammarr.screen.adventure", JammarrPayloads.BrowseKind.SEARCH), QUEUE("jammarr.screen.queue", JammarrPayloads.BrowseKind.QUEUE);
        final String label; final JammarrPayloads.BrowseKind browseKind;
        View(String label, JammarrPayloads.BrowseKind browseKind) { this.label = label; this.browseKind = browseKind; }
    }

    private static final int PAGE_SIZE = 20;
    private final JammarrClientState state;
    private final List<JammarrPayloads.StationSeed> mixSeeds = new ArrayList<>();
    private final List<JammarrPayloads.StationSeed> adventureWaypoints = new ArrayList<>();
    private View view = View.NOW;
    private Button searchTab;
    private EditBox search;
    private boolean requestPending, queuePending;
    private String pendingQueueKey = "", pendingQuery = "", screenNotice = "", searchQuery = "";
    private JammarrPayloads.BrowseKind pendingKind;
    private int pendingPage, rowOffset;
    private long clearArmedUntil, startNowArmedUntil;

    public JammarrScreen(JammarrClientState state) {
        super(Component.translatable("jammarr.screen.title")); this.state = state;
        if (state.browse().kind() == JammarrPayloads.BrowseKind.SEARCH) searchQuery = state.browse().query();
    }

    @Override protected void init() {
        clearWidgets(); int panelWidth = Math.min(760, width - 16), left = (width - panelWidth) / 2;
        int tabWidth = Math.max(1, panelWidth / View.values().length), tabX = left;
        for (int i = 0; i < View.values().length; i++) {
            View candidate = View.values()[i]; int actualWidth = i == View.values().length - 1 ? left + panelWidth - tabX : tabWidth;
            addTab(tabX, 38, actualWidth, candidate); tabX += actualWidth;
        }
        int contentTop = 66;
        if (view == View.SEARCH || view == View.ADVENTURE) { addSearch(left, contentTop, panelWidth); contentTop += 27; }
        switch (view) {
            case NOW -> addNowPlaying(left, contentTop, panelWidth);
            case STATIONS -> addStations(left, contentTop, panelWidth);
            case ADVENTURE -> addAdventure(left, contentTop, panelWidth);
            default -> addResults(left, contentTop, panelWidth);
        }
        addBottomControls(left, panelWidth);
        if (view.browseKind != null && view != View.ADVENTURE) addPaging(left, panelWidth);
        if (search != null) setInitialFocus(search); else if (searchTab != null) setInitialFocus(searchTab);
    }

    private void addSearch(int left, int top, int panelWidth) {
        search = addRenderableWidget(new EditBox(font, left, top, panelWidth - 126, 20, Component.translatable("jammarr.screen.search")));
        search.setMaxLength(128); search.setHint(Component.translatable("jammarr.screen.search")); search.setValue(searchQuery); search.setResponder(value -> searchQuery = value);
        Button go = Button.builder(Component.translatable("jammarr.screen.go"), b -> request(0)).bounds(left + panelWidth - 122, top, 58, 20).build(); go.active = !requestPending; addRenderableWidget(go);
        Button clear = Button.builder(Component.translatable("jammarr.screen.clear"), b -> { searchQuery = ""; search.setValue(""); request(0); })
                .bounds(left + panelWidth - 60, top, 60, 20).build(); clear.active = !requestPending; addRenderableWidget(clear);
    }

    private void addNowPlaying(int left, int top, int panelWidth) {
        JammarrPayloads.PlaybackState playing = state.playback();
        String title = playing.title().isBlank() ? Component.translatable("jammarr.screen.nothing_playing").getString() : playing.title();
        Button titleButton = disabled(trim(title, panelWidth - 20), left, top + 18, panelWidth); addRenderableWidget(titleButton);
        if (font.width(title) > panelWidth - 20) titleButton.setTooltip(Tooltip.create(Component.literal(title)));
        if (!playing.artist().isBlank()) addRenderableWidget(disabled(trim(playing.artist(), panelWidth - 20), left, top + 42, panelWidth));
        String source = playing.sourceName().isBlank() ? switch (playing.origin()) {
            case MANUAL -> "Manual request"; case STATION -> "Station"; case ADVENTURE -> "Sonic Adventure"; case NONE -> "";
        } : playing.sourceName();
        if (!source.isBlank()) addRenderableWidget(disabled("Source: " + source, left, top + 66, panelWidth));
        if (state.audioState() == AudioPlaybackState.ERROR)
            addRenderableWidget(Button.builder(Component.translatable("jammarr.screen.retry_audio"), b -> state.retryAudio()).bounds(left + panelWidth / 2 - 48, top + 92, 96, 20).build());
    }

    private void addStations(int left, int top, int panelWidth) {
        JammarrPayloads.StationState station = state.station();
        addRenderableWidget(disabled(capabilityLabel(station), left, top, panelWidth));
        addRenderableWidget(disabled(station.active() && station.stationType() != JammarrPayloads.StationType.SONIC_ADVENTURE
                ? "Active: " + station.name() : "No general station active", left, top + 23, panelWidth));
        if (!state.playback().operator()) return;
        Button autoplay = Button.builder(Component.literal("Autoplay: " + (station.autoplayEnabled() ? "On" : "Off")), b ->
                stationRequest(JammarrPayloads.StationAction.SET_AUTOPLAY, JammarrPayloads.StationType.AUTOPLAY, !station.autoplayEnabled(), List.of()))
                .bounds(left, top + 48, 110, 20).build(); addRenderableWidget(autoplay);
        addRenderableWidget(Button.builder(Component.translatable("jammarr.screen.library_shuffle"), b ->
                stationRequest(JammarrPayloads.StationAction.START, JammarrPayloads.StationType.LIBRARY_SHUFFLE, false, List.of()))
                .bounds(left + 116, top + 48, 126, 20).build());
        Button stop = Button.builder(Component.translatable("jammarr.screen.stop_station"), b ->
                stationRequest(JammarrPayloads.StationAction.STOP, JammarrPayloads.StationType.NONE, false, List.of()))
                .bounds(left + 248, top + 48, 100, 20).build(); stop.active = station.active(); addRenderableWidget(stop);

        addRenderableWidget(disabled("Sonic Mix seeds (2-5, one type)", left, top + 76, panelWidth));
        for (int i = 0; i < Math.min(5, mixSeeds.size()); i++) {
            int index = i, y = top + 99 + i * 22; JammarrPayloads.StationSeed seed = mixSeeds.get(i);
            addRenderableWidget(disabled((i + 1) + ". " + seed.title() + (seed.subtitle().isBlank() ? "" : " — " + seed.subtitle()), left, y, panelWidth - 34));
            addRenderableWidget(Button.builder(Component.literal("×"), b -> { mixSeeds.remove(index); rebuildWidgets(); }).bounds(left + panelWidth - 30, y, 30, 20).build());
        }
        int actionY = top + 99 + Math.min(5, mixSeeds.size()) * 22;
        Button start = Button.builder(Component.translatable("jammarr.screen.start"), b ->
                stationRequest(JammarrPayloads.StationAction.START, JammarrPayloads.StationType.SONIC_MIX, false, mixSeeds)).bounds(left, actionY, 78, 20).build();
        start.active = mixSeeds.size() >= 2; addRenderableWidget(start);
        Button startNow = Button.builder(Component.translatable("jammarr.screen.start_now"), b -> confirmStartNow(JammarrPayloads.StationType.SONIC_MIX, mixSeeds))
                .bounds(left + 84, actionY, 92, 20).build(); startNow.active = mixSeeds.size() >= 2; addRenderableWidget(startNow);
        addRenderableWidget(Button.builder(Component.translatable("jammarr.screen.clear_builder"), b -> { mixSeeds.clear(); rebuildWidgets(); })
                .bounds(left + 182, actionY, 102, 20).build());
        int previewY = actionY + 26;
        if (!station.preview().isEmpty()) {
            addRenderableWidget(disabled("Generated next:", left, previewY, panelWidth));
            for (int i = 0; i < station.preview().size(); i++) addRenderableWidget(disabled("  " + station.preview().get(i).title() + " — " + station.preview().get(i).artist(), left, previewY + 22 + i * 22, panelWidth));
        }
    }

    private void addAdventure(int left, int top, int panelWidth) {
        JammarrPayloads.StationState station = state.station();
        addRenderableWidget(disabled(capabilityLabel(station), left, top, panelWidth));
        addRenderableWidget(disabled(station.stationType() == JammarrPayloads.StationType.SONIC_ADVENTURE ? "Active: " + station.name() : "Adventure waypoints (2-5 tracks)", left, top + 23, panelWidth));
        if (!state.playback().operator()) return;
        int listTop = top + 46;
        for (int i = 0; i < adventureWaypoints.size(); i++) {
            int index = i, y = listTop + i * 22; JammarrPayloads.StationSeed seed = adventureWaypoints.get(i);
            addRenderableWidget(disabled((i + 1) + ". " + seed.title() + (seed.subtitle().isBlank() ? "" : " — " + seed.subtitle()), left, y, panelWidth - 94));
            Button up = Button.builder(Component.literal("↑"), b -> moveWaypoint(index, -1)).bounds(left + panelWidth - 90, y, 28, 20).build(); up.active = i > 0; addRenderableWidget(up);
            Button down = Button.builder(Component.literal("↓"), b -> moveWaypoint(index, 1)).bounds(left + panelWidth - 60, y, 28, 20).build(); down.active = i + 1 < adventureWaypoints.size(); addRenderableWidget(down);
            addRenderableWidget(Button.builder(Component.literal("×"), b -> { adventureWaypoints.remove(index); rebuildWidgets(); }).bounds(left + panelWidth - 30, y, 30, 20).build());
        }
        int actionsY = listTop + adventureWaypoints.size() * 22;
        Button preview = Button.builder(Component.translatable("jammarr.screen.preview"), b -> stationRequest(JammarrPayloads.StationAction.PREVIEW_ADVENTURE,
                JammarrPayloads.StationType.SONIC_ADVENTURE, false, adventureWaypoints)).bounds(left, actionsY, 76, 20).build(); preview.active = adventureWaypoints.size() >= 2; addRenderableWidget(preview);
        Button start = Button.builder(Component.translatable("jammarr.screen.start"), b -> stationRequest(JammarrPayloads.StationAction.START,
                JammarrPayloads.StationType.SONIC_ADVENTURE, false, adventureWaypoints)).bounds(left + 82, actionsY, 76, 20).build(); start.active = adventureWaypoints.size() >= 2; addRenderableWidget(start);
        Button startNow = Button.builder(Component.translatable("jammarr.screen.start_now"), b -> confirmStartNow(JammarrPayloads.StationType.SONIC_ADVENTURE, adventureWaypoints))
                .bounds(left + 164, actionsY, 92, 20).build(); startNow.active = adventureWaypoints.size() >= 2; addRenderableWidget(startNow);
        addRenderableWidget(Button.builder(Component.translatable("jammarr.screen.clear_builder"), b -> { adventureWaypoints.clear(); rebuildWidgets(); })
                .bounds(left + 262, actionsY, 104, 20).build());
        int resultsTop = actionsY + 26;
        JammarrPayloads.AdventurePreview path = state.adventurePreview();
        if (!path.message().isBlank()) {
            addRenderableWidget(disabled(path.message(), left, resultsTop, panelWidth));
            for (int i = 0; i < Math.min(3, path.path().size()); i++) addRenderableWidget(disabled("  " + path.path().get(i).title() + " — " + path.path().get(i).artist(), left, resultsTop + 22 + i * 22, panelWidth));
            resultsTop += 92;
        }
        addAdventureSearchResults(left, resultsTop, panelWidth);
    }

    private void addAdventureSearchResults(int left, int top, int panelWidth) {
        JammarrPayloads.BrowseResults results = state.browse();
        if (results.kind() != JammarrPayloads.BrowseKind.SEARCH || requestPending) return;
        int rows = Math.max(0, Math.min(4, (height - top - 54) / 22));
        for (int i = 0; i < rows && i < results.items().size(); i++) {
            JammarrPayloads.MediaItem item = results.items().get(i); if (item.kind() != JammarrPayloads.ItemKind.TRACK) continue;
            int y = top + i * 22; addRenderableWidget(disabled(item.title() + " — " + item.subtitle(), left, y, panelWidth - 40));
            Button add = Button.builder(Component.literal("A"), b -> addAdventure(item)).bounds(left + panelWidth - 36, y, 36, 20).build();
            add.setTooltip(Tooltip.create(Component.literal("Add as Adventure waypoint"))); add.active = adventureWaypoints.size() < 5; addRenderableWidget(add);
        }
    }

    private void addResults(int left, int contentTop, int panelWidth) {
        JammarrPayloads.BrowseResults results = state.browse(); if (view.browseKind == null || results.kind() != view.browseKind || requestPending) return;
        List<JammarrPayloads.MediaItem> items = results.items(); int rows = Math.max(1, (height - contentTop - 54) / 22);
        rowOffset = Math.max(0, Math.min(rowOffset, Math.max(0, items.size() - rows)));
        for (int row = 0; row < rows && row + rowOffset < items.size(); row++) {
            int localIndex = row + rowOffset, queueIndex = results.page() * PAGE_SIZE + localIndex, y = contentTop + row * 22;
            JammarrPayloads.MediaItem item = items.get(localIndex); JammarrPayloads.QueueEntry queueEntry = queueIndex < state.playback().queue().size() ? state.playback().queue().get(queueIndex) : null;
            String prefix = view == View.QUEUE ? (queueIndex == 0 ? "▶ " : (queueIndex + 1) + ". ") : "";
            String duration = item.durationMs() > 0 ? " (" + time(item.durationMs()) + ")" : "";
            String source = view == View.QUEUE && queueEntry != null && queueEntry.source() != JammarrPayloads.PlaybackOrigin.MANUAL ? " [" + queueEntry.source().name().toLowerCase() + "]" : "";
            String label = prefix + item.title() + (item.subtitle().isBlank() ? "" : " — " + item.subtitle()) + duration + source;
            int controlsWidth = controlsWidth(item, queueEntry); Button itemButton = disabled(trim(label, panelWidth - controlsWidth - 20), left, y, panelWidth - controlsWidth - 4);
            if (font.width(label) > panelWidth - controlsWidth - 20) itemButton.setTooltip(Tooltip.create(Component.literal(label))); addRenderableWidget(itemButton);
            if (view == View.QUEUE && queueEntry != null && queueEntry.editable() && state.playback().operator()) addQueueControls(left, panelWidth, y, queueIndex, queueEntry);
            else if (view != View.QUEUE) addBrowseControls(left, panelWidth, y, item);
        }
    }

    private int controlsWidth(JammarrPayloads.MediaItem item, JammarrPayloads.QueueEntry queueEntry) {
        if (view == View.QUEUE) return queueEntry != null && queueEntry.editable() && state.playback().operator() ? 90 : 4;
        if (!state.playback().operator() || item.kind() == JammarrPayloads.ItemKind.PLAYLIST) return 40;
        return item.kind() == JammarrPayloads.ItemKind.TRACK ? 124 : 94;
    }
    private void addQueueControls(int left, int panelWidth, int y, int index, JammarrPayloads.QueueEntry entry) {
        int x = left + panelWidth - 88; Button up = Button.builder(Component.literal("↑"), b -> control(JammarrPayloads.ControlAction.MOVE_UP, index, entry.key())).bounds(x, y, 28, 20).build();
        Button down = Button.builder(Component.literal("↓"), b -> control(JammarrPayloads.ControlAction.MOVE_DOWN, index, entry.key())).bounds(x + 30, y, 28, 20).build();
        up.active = index > 0 && state.playback().queue().get(index - 1).editable(); down.active = index + 1 < state.playback().queue().size() && state.playback().queue().get(index + 1).editable();
        addRenderableWidget(up); addRenderableWidget(down); addRenderableWidget(Button.builder(Component.literal("×"), b -> control(JammarrPayloads.ControlAction.REMOVE, index, entry.key())).bounds(x + 60, y, 28, 20).build());
    }
    private void addBrowseControls(int left, int panelWidth, int y, JammarrPayloads.MediaItem item) {
        int button = 28, gap = 2, actions = state.playback().operator() && item.kind() != JammarrPayloads.ItemKind.PLAYLIST ? (item.kind() == JammarrPayloads.ItemKind.TRACK ? 4 : 3) : 1;
        int x = left + panelWidth - actions * (button + gap);
        addAction("+", "Add to manual queue", x, y, b -> activate(item)); x += button + gap;
        if (!state.playback().operator() || item.kind() == JammarrPayloads.ItemKind.PLAYLIST) return;
        addAction("R", "Start radio after manual requests", x, y, b -> startRadio(item)); x += button + gap;
        addAction("M", "Add to Sonic Mix", x, y, b -> addMix(item)); x += button + gap;
        if (item.kind() == JammarrPayloads.ItemKind.TRACK) addAction("A", "Add as Adventure waypoint", x, y, b -> addAdventure(item));
    }
    private void addAction(String label, String tooltip, int x, int y, Button.OnPress press) {
        Button value = Button.builder(Component.literal(label), press).bounds(x, y, 28, 20).build(); value.setTooltip(Tooltip.create(Component.literal(tooltip))); addRenderableWidget(value);
    }

    private void addBottomControls(int left, int panelWidth) {
        int bottom = height - 27;
        addRenderableWidget(Button.builder(Component.translatable(JammarrConfig.ENABLED.get() ? "jammarr.screen.mute" : "jammarr.screen.unmute"), b -> {
            JammarrConfig.ENABLED.set(!JammarrConfig.ENABLED.get()); JammarrConfig.ENABLED.save(); state.listeningChanged(); rebuildWidgets();
        }).bounds(left, bottom, 68, 20).build()); addRenderableWidget(new VolumeSlider(left + 74, bottom, 130, 20));
        if (state.playback().operator()) {
            addRenderableWidget(Button.builder(Component.translatable(state.playback().paused() ? "jammarr.screen.resume" : "jammarr.screen.pause"), b ->
                    control(state.playback().paused() ? JammarrPayloads.ControlAction.RESUME : JammarrPayloads.ControlAction.PAUSE, -1)).bounds(left + 210, bottom, 72, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("jammarr.screen.skip"), b -> control(JammarrPayloads.ControlAction.SKIP, -1)).bounds(left + 288, bottom, 58, 20).build());
            boolean armed = System.currentTimeMillis() < clearArmedUntil;
            addRenderableWidget(Button.builder(Component.translatable(armed ? "jammarr.screen.confirm" : "jammarr.screen.clear"), b -> {
                if (System.currentTimeMillis() < clearArmedUntil) { clearArmedUntil = 0; control(JammarrPayloads.ControlAction.CLEAR, -1); }
                else { clearArmedUntil = System.currentTimeMillis() + 5_000; screenNotice = Component.translatable("jammarr.screen.confirm_clear_notice").getString(); rebuildWidgets(); }
            }).bounds(left + 352, bottom, 72, 20).build());
        }
    }
    private void addPaging(int left, int panelWidth) {
        JammarrPayloads.BrowseResults results = state.browse(); if (view.browseKind == null || results.kind() != view.browseKind) return; int bottom = height - 27;
        if (results.page() > 0) { Button previous = Button.builder(Component.literal("<"), b -> request(results.page() - 1)).bounds(left + panelWidth - 70, bottom, 32, 20).build(); previous.active = !requestPending; addRenderableWidget(previous); }
        if (results.hasMore()) { Button next = Button.builder(Component.literal(">"), b -> request(results.page() + 1)).bounds(left + panelWidth - 34, bottom, 32, 20).build(); next.active = !requestPending; addRenderableWidget(next); }
    }

    private void addTab(int x, int y, int width, View candidate) {
        Button button = Button.builder(Component.translatable(candidate.label), b -> {
            view = candidate; rowOffset = 0; requestPending = false; queuePending = false; pendingQueueKey = ""; screenNotice = ""; state.clearNotice();
            if (candidate.browseKind != null && candidate != View.ADVENTURE) request(0); rebuildWidgets();
        }).bounds(x, y, width, 20).build(); if (candidate == View.SEARCH) searchTab = button; button.active = view != candidate; addRenderableWidget(button);
    }
    private void activate(JammarrPayloads.MediaItem item) {
        if (queuePending) return; queuePending = true; pendingQueueKey = item.key(); screenNotice = Component.translatable("jammarr.screen.queuing").getString();
        PacketDistributor.sendToServer(new JammarrPayloads.QueueRequest(item.kind(), item.key())); rebuildWidgets();
    }
    private void startRadio(JammarrPayloads.MediaItem item) {
        JammarrPayloads.StationType type = switch (item.kind()) { case TRACK -> JammarrPayloads.StationType.TRACK_RADIO; case ARTIST -> JammarrPayloads.StationType.ARTIST_RADIO; case ALBUM -> JammarrPayloads.StationType.ALBUM_RADIO; case PLAYLIST -> JammarrPayloads.StationType.NONE; };
        if (type != JammarrPayloads.StationType.NONE) stationRequest(JammarrPayloads.StationAction.START, type, false, List.of(seed(item)));
    }
    private void addMix(JammarrPayloads.MediaItem item) {
        if (item.kind() == JammarrPayloads.ItemKind.PLAYLIST) return;
        if (!mixSeeds.isEmpty() && mixSeeds.getFirst().kind() != item.kind()) { screenNotice = "Sonic Mix seeds must all be the same type"; rebuildWidgets(); return; }
        if (mixSeeds.size() >= 5 || mixSeeds.stream().anyMatch(seed -> seed.key().equals(item.key()))) return;
        mixSeeds.add(seed(item)); screenNotice = "Added to Sonic Mix builder"; rebuildWidgets();
    }
    private void addAdventure(JammarrPayloads.MediaItem item) {
        if (item.kind() != JammarrPayloads.ItemKind.TRACK || adventureWaypoints.size() >= 5 || adventureWaypoints.stream().anyMatch(seed -> seed.key().equals(item.key()))) return;
        adventureWaypoints.add(seed(item)); screenNotice = "Added Adventure waypoint"; rebuildWidgets();
    }
    private void moveWaypoint(int index, int delta) { int target = index + delta; if (target < 0 || target >= adventureWaypoints.size()) return; JammarrPayloads.StationSeed value = adventureWaypoints.remove(index); adventureWaypoints.add(target, value); rebuildWidgets(); }
    private static JammarrPayloads.StationSeed seed(JammarrPayloads.MediaItem item) { return new JammarrPayloads.StationSeed(item.kind(), item.key(), item.title(), item.subtitle()); }
    private void stationRequest(JammarrPayloads.StationAction action, JammarrPayloads.StationType type, boolean enabled, List<JammarrPayloads.StationSeed> seeds) {
        PacketDistributor.sendToServer(new JammarrPayloads.StationRequest(action, type, enabled, state.station().generation(), List.copyOf(seeds))); screenNotice = "Updating shared playback source…";
    }
    private void confirmStartNow(JammarrPayloads.StationType type, List<JammarrPayloads.StationSeed> seeds) {
        if (System.currentTimeMillis() < startNowArmedUntil) { startNowArmedUntil = 0; stationRequest(JammarrPayloads.StationAction.START_NOW, type, false, seeds); }
        else { startNowArmedUntil = System.currentTimeMillis() + 5_000; screenNotice = "Press Start Now again to clear manual requests and replace current playback"; rebuildWidgets(); }
    }
    private void control(JammarrPayloads.ControlAction action, int index) { control(action, index, ""); }
    private void control(JammarrPayloads.ControlAction action, int index, String expectedKey) { PacketDistributor.sendToServer(new JammarrPayloads.ControlRequest(action, index, expectedKey)); }

    private void request(int page) {
        if (view.browseKind == null || requestPending) return; if (search != null) searchQuery = search.getValue(); searchQuery = searchQuery.trim();
        if ((view == View.SEARCH || view == View.ADVENTURE) && searchQuery.length() < 2) {
            requestPending = false; screenNotice = Component.translatable("jammarr.screen.short_query").getString(); state.clearBrowse(view.browseKind, searchQuery); rebuildWidgets(); return;
        }
        String requestQuery = browseQuery(view.browseKind, searchQuery);
        requestPending = true; pendingKind = view.browseKind; pendingQuery = requestQuery; pendingPage = page;
        PacketDistributor.sendToServer(new JammarrPayloads.BrowseRequest(view.browseKind, requestQuery, page)); rebuildWidgets();
    }
    static String browseQuery(JammarrPayloads.BrowseKind kind, String searchQuery) {
        return kind == JammarrPayloads.BrowseKind.SEARCH ? searchQuery.trim() : "";
    }
    void resultsChanged() { JammarrPayloads.BrowseResults result = state.browse(); if (requestPending && result.kind() == pendingKind && result.page() == pendingPage && result.query().equals(pendingQuery)) requestPending = false; if (minecraft != null) rebuildWidgets(); }
    void requestFailed() { requestPending = false; queuePending = false; pendingQueueKey = ""; if (minecraft != null) rebuildWidgets(); }
    void playbackChanged() { if (!queuePending || state.playback().queue().stream().noneMatch(entry -> entry.key().equals(pendingQueueKey) && entry.source() == JammarrPayloads.PlaybackOrigin.MANUAL)) return; queuePending = false; pendingQueueKey = ""; screenNotice = Component.translatable("jammarr.screen.queued").getString(); rebuildWidgets(); }
    void queueChanged() { if (view == View.QUEUE && minecraft != null) rebuildWidgets(); }
    void stationChanged() { if (minecraft != null && (view == View.STATIONS || view == View.ADVENTURE || view == View.NOW || view == View.QUEUE)) rebuildWidgets(); }
    void adventurePreviewChanged() { if (minecraft != null && view == View.ADVENTURE) rebuildWidgets(); }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) { if (key == 257 && search != null && search.isFocused()) { request(0); return true; } return super.keyPressed(key, scanCode, modifiers); }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) { if (scrollY != 0 && view.browseKind != null && state.browse().kind() == view.browseKind) { rowOffset = Math.max(0, rowOffset + (scrollY < 0 ? 1 : -1)); rebuildWidgets(); return true; } return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY); }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick); super.render(graphics, mouseX, mouseY, partialTick); graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        JammarrPayloads.PlaybackState playing = state.playback(); String now = statusLabel(playing) + (playing.title().isBlank() ? "" : ": " + playing.title() + (playing.artist().isBlank() ? "" : " — " + playing.artist())) + "  " + time(playing.positionMs()) + "/" + time(playing.durationMs());
        graphics.drawCenteredString(font, trim(now, width - 20), width / 2, 25, statusColor(playing.status()));
        String notice = screenNotice.isBlank() ? (state.notice().isBlank() ? playing.statusMessage() : state.notice()) : screenNotice;
        if (!notice.isBlank()) graphics.drawCenteredString(font, trim(notice, width - 24), width / 2, height - 40, 0xFFB36B);
        if (view == View.NOW) graphics.drawCenteredString(font, "Audio: " + state.audioStatus(), width / 2, 187, state.audioState() == AudioPlaybackState.ERROR ? 0xFF7777 : 0xA0D8FF);
        if (requestPending) graphics.drawCenteredString(font, Component.translatable("jammarr.screen.searching"), width / 2, height / 2, 0xA0D8FF);
        else if (queuePending) graphics.drawCenteredString(font, Component.translatable("jammarr.screen.queuing"), width / 2, height / 2, 0xA0D8FF);
        else if (playing.status() == JammarrPayloads.PlaybackStatus.PLEX_OFFLINE && notice.isBlank()) graphics.drawCenteredString(font, Component.translatable("jammarr.screen.plex_unavailable"), width / 2, height / 2, 0xFF7777);
    }

    private Button disabled(String value, int x, int y, int width) { Button button = Button.builder(Component.literal(trim(value, width - 12)), b -> {}).bounds(x, y, width, 20).build(); button.active = false; return button; }
    private static String capabilityLabel(JammarrPayloads.StationState station) { return "Sonic: " + station.capability().name().replace('_', ' ').toLowerCase() + " — " + station.capabilityMessage(); }
    private static String statusLabel(JammarrPayloads.PlaybackState state) { return Component.translatable(switch (state.status()) { case IDLE -> "jammarr.status.idle"; case PREPARING -> "jammarr.status.preparing"; case PLAYING -> "jammarr.status.playing"; case PAUSED -> "jammarr.status.paused"; case PLEX_OFFLINE -> "jammarr.status.plex_offline"; }).getString(); }
    private static int statusColor(JammarrPayloads.PlaybackStatus status) { return status == JammarrPayloads.PlaybackStatus.PLEX_OFFLINE ? 0xFF7777 : status == JammarrPayloads.PlaybackStatus.PREPARING ? 0xFFD37A : 0xCFCFCF; }
    private String trim(String value, int pixels) { return font.width(value) <= pixels ? value : font.plainSubstrByWidth(value, Math.max(0, pixels - font.width("…"))) + "…"; }
    private static String time(long ms) { long seconds = Math.max(0, ms / 1000); return "%d:%02d".formatted(seconds / 60, seconds % 60); }
    @Override public boolean isPauseScreen() { return false; }

    private final class VolumeSlider extends AbstractSliderButton {
        private VolumeSlider(int x, int y, int width, int height) { super(x, y, width, height, Component.empty(), JammarrConfig.VOLUME.get()); updateMessage(); }
        @Override protected void updateMessage() { setMessage(Component.translatable("jammarr.screen.volume", Math.round(value * 100))); }
        @Override protected void applyValue() { JammarrConfig.VOLUME.set(value); JammarrConfig.VOLUME.save(); }
    }
}
