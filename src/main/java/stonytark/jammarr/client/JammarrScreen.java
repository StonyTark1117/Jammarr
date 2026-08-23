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

import java.util.List;

public final class JammarrScreen extends Screen {
    private enum View {
        NOW("jammarr.screen.now_playing", null), SEARCH("jammarr.screen.search_tab", JammarrPayloads.BrowseKind.SEARCH), ARTISTS("jammarr.screen.artists", JammarrPayloads.BrowseKind.ARTISTS),
        ALBUMS("jammarr.screen.albums", JammarrPayloads.BrowseKind.ALBUMS), PLAYLISTS("jammarr.screen.playlists", JammarrPayloads.BrowseKind.PLAYLISTS), QUEUE("jammarr.screen.queue", JammarrPayloads.BrowseKind.QUEUE);
        final String label; final JammarrPayloads.BrowseKind browseKind;
        View(String label, JammarrPayloads.BrowseKind browseKind) { this.label = label; this.browseKind = browseKind; }
    }

    private static final int PAGE_SIZE = 20;
    private final JammarrClientState state;
    private View view = View.NOW;
    private Button searchTab;
    private EditBox search;
    private boolean requestPending;
    private boolean queuePending;
    private String pendingQueueKey = "";
    private JammarrPayloads.BrowseKind pendingKind;
    private String pendingQuery = "";
    private int pendingPage;
    private long clearArmedUntil;
    private String screenNotice = "";
    private String searchQuery = "";
    private int rowOffset;

    public JammarrScreen(JammarrClientState state) {
        super(Component.translatable("jammarr.screen.title"));
        this.state = state;
        if (state.browse().kind() == JammarrPayloads.BrowseKind.SEARCH) searchQuery = state.browse().query();
    }

    @Override protected void init() {
        clearWidgets();
        int panelWidth = Math.min(720, width - 24), left = (width - panelWidth) / 2;
        int tabWidth = Math.max(56, panelWidth / View.values().length);
        int tabX = left;
        for (int i = 0; i < View.values().length; i++) {
            View candidate = View.values()[i];
            int actualWidth = i == View.values().length - 1 ? left + panelWidth - tabX : tabWidth;
            addTab(tabX, 38, actualWidth, candidate);
            tabX += actualWidth;
        }

        int contentTop = 66;
        if (view == View.SEARCH) {
            int actionWidth = 126;
            search = addRenderableWidget(new EditBox(font, left, contentTop, panelWidth - actionWidth, 20, Component.translatable("jammarr.screen.search")));
            search.setMaxLength(128); search.setHint(Component.translatable("jammarr.screen.search"));
            search.setValue(searchQuery);
            search.setResponder(value -> searchQuery = value);
            Button go = Button.builder(Component.translatable("jammarr.screen.go"), b -> request(0)).bounds(left + panelWidth - 122, contentTop, 58, 20).build();
            go.active = !requestPending;
            addRenderableWidget(go);
            Button clearSearch = Button.builder(Component.translatable("jammarr.screen.clear"), b -> {
                searchQuery = "";
                search.setValue("");
                request(0);
            }).bounds(left + panelWidth - 60, contentTop, 60, 20).build();
            clearSearch.active = !requestPending;
            addRenderableWidget(clearSearch);
            contentTop += 27;
        }

        if (view == View.NOW) addNowPlaying(left, contentTop, panelWidth);
        else addResults(left, contentTop, panelWidth);

        int bottom = height - 27;
        addRenderableWidget(Button.builder(Component.translatable(JammarrConfig.ENABLED.get() ? "jammarr.screen.mute" : "jammarr.screen.unmute"), b -> {
            JammarrConfig.ENABLED.set(!JammarrConfig.ENABLED.get()); JammarrConfig.ENABLED.save(); state.listeningChanged(); rebuildWidgets();
        }).bounds(left, bottom, 68, 20).build());
        addRenderableWidget(new VolumeSlider(left + 74, bottom, 130, 20));
        if (state.playback().operator()) {
            addRenderableWidget(Button.builder(Component.translatable(state.playback().paused() ? "jammarr.screen.resume" : "jammarr.screen.pause"), b -> control(state.playback().paused() ? JammarrPayloads.ControlAction.RESUME : JammarrPayloads.ControlAction.PAUSE, -1)).bounds(left + 210, bottom, 72, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("jammarr.screen.skip"), b -> control(JammarrPayloads.ControlAction.SKIP, -1)).bounds(left + 288, bottom, 58, 20).build());
            boolean armed = System.currentTimeMillis() < clearArmedUntil;
            addRenderableWidget(Button.builder(Component.translatable(armed ? "jammarr.screen.confirm" : "jammarr.screen.clear"), b -> {
                if (System.currentTimeMillis() < clearArmedUntil) {
                    clearArmedUntil = 0;
                    control(JammarrPayloads.ControlAction.CLEAR, -1);
                } else {
                    clearArmedUntil = System.currentTimeMillis() + 5_000;
                    screenNotice = Component.translatable("jammarr.screen.confirm_clear_notice").getString();
                    rebuildWidgets();
                }
            }).bounds(left + 352, bottom, 72, 20).build());
        }
        JammarrPayloads.BrowseResults results = state.browse();
        if (view.browseKind != null && results.kind() == view.browseKind) {
            if (results.page() > 0) {
                Button previous = Button.builder(Component.literal("<"), b -> request(results.page() - 1)).bounds(left + panelWidth - 70, bottom, 32, 20).build();
                previous.active = !requestPending;
                addRenderableWidget(previous);
            }
            if (results.hasMore()) {
                Button next = Button.builder(Component.literal(">"), b -> request(results.page() + 1)).bounds(left + panelWidth - 34, bottom, 32, 20).build();
                next.active = !requestPending;
                addRenderableWidget(next);
            }
        }
        setInitialFocus(view == View.SEARCH ? search : searchTab);
    }

    private void addNowPlaying(int left, int top, int panelWidth) {
        JammarrPayloads.PlaybackState playing = state.playback();
        String title = playing.title().isBlank() ? Component.translatable("jammarr.screen.nothing_playing").getString() : playing.title();
        String artist = playing.artist().isBlank() ? "" : playing.artist();
        Button titleButton = Button.builder(Component.literal(trim(title, panelWidth - 20)), b -> {}).bounds(left, top + 18, panelWidth, 20).build();
        titleButton.active = false;
        if (font.width(title) > panelWidth - 20) titleButton.setTooltip(Tooltip.create(Component.literal(title)));
        addRenderableWidget(titleButton);
        if (!artist.isBlank()) addRenderableWidget(Button.builder(Component.literal(trim(artist, panelWidth - 20)), b -> {}).bounds(left, top + 42, panelWidth, 20).build()).active = false;
        if (state.audioState() == AudioPlaybackState.ERROR) {
            addRenderableWidget(Button.builder(Component.translatable("jammarr.screen.retry_audio"), b -> state.retryAudio()).bounds(left + panelWidth / 2 - 48, top + 66, 96, 20).build());
        }
    }

    private void addResults(int left, int contentTop, int panelWidth) {
        JammarrPayloads.BrowseResults results = state.browse();
        if (view.browseKind == null || results.kind() != view.browseKind) return;
        if (requestPending) return;
        List<JammarrPayloads.MediaItem> items = results.items();
        int rows = Math.max(1, (height - contentTop - 54) / 22);
        rowOffset = Math.max(0, Math.min(rowOffset, Math.max(0, items.size() - rows)));
        for (int row = 0; row < rows && row + rowOffset < items.size(); row++) {
            int localIndex = row + rowOffset;
            int queueIndex = results.page() * PAGE_SIZE + localIndex;
            JammarrPayloads.MediaItem item = items.get(localIndex);
            int y = contentTop + row * 22;
            String prefix = view == View.QUEUE ? (queueIndex == 0 ? "▶ " : (queueIndex + 1) + ". ") : "";
            String duration = item.durationMs() > 0 ? " (" + time(item.durationMs()) + ")" : "";
            String label = prefix + item.title() + (item.subtitle().isBlank() ? "" : " — " + item.subtitle()) + duration;
            int controlsWidth = view == View.QUEUE && state.playback().operator() ? 90 : 52;
            Button itemButton = Button.builder(Component.literal(trim(label, panelWidth - controlsWidth - 20)), b -> activate(item))
                    .bounds(left, y, panelWidth - controlsWidth - 4, 20).build();
            if (view == View.QUEUE) itemButton.active = false;
            if (font.width(label) > panelWidth - controlsWidth - 20) itemButton.setTooltip(Tooltip.create(Component.literal(label)));
            addRenderableWidget(itemButton);
            if (view == View.QUEUE && state.playback().operator()) {
                int x = left + panelWidth - 88;
                Button up = Button.builder(Component.literal("↑"), b -> control(JammarrPayloads.ControlAction.MOVE_UP, queueIndex, item.key())).bounds(x, y, 28, 20).build();
                Button down = Button.builder(Component.literal("↓"), b -> control(JammarrPayloads.ControlAction.MOVE_DOWN, queueIndex, item.key())).bounds(x + 30, y, 28, 20).build();
                up.active = queueIndex > 1; down.active = queueIndex > 0 && queueIndex < state.playback().queue().size() - 1;
                addRenderableWidget(up); addRenderableWidget(down);
                addRenderableWidget(Button.builder(Component.literal("×"), b -> control(JammarrPayloads.ControlAction.REMOVE, queueIndex, item.key())).bounds(x + 60, y, 28, 20).build());
            } else if (view != View.QUEUE && !queuePending) {
                addRenderableWidget(Button.builder(Component.literal("+"), b -> activate(item)).bounds(left + panelWidth - 48, y, 48, 20).build());
            }
        }
    }

    private void addTab(int x, int y, int width, View candidate) {
        Button button = Button.builder(Component.translatable(candidate.label), b -> {
            view = candidate; rowOffset = 0; requestPending = false; queuePending = false; pendingQueueKey = ""; screenNotice = ""; state.clearNotice(); if (candidate.browseKind != null) request(0); rebuildWidgets();
        }).bounds(x, y, width, 20).build();
        if (candidate == View.SEARCH) searchTab = button;
        button.active = view != candidate; addRenderableWidget(button);
    }
    private void activate(JammarrPayloads.MediaItem item) {
        if (queuePending) return;
        queuePending = true;
        pendingQueueKey = item.key();
        screenNotice = Component.translatable("jammarr.screen.queuing").getString();
        PacketDistributor.sendToServer(new JammarrPayloads.QueueRequest(item.kind(), item.key()));
        rebuildWidgets();
    }
    private void control(JammarrPayloads.ControlAction action, int index) { control(action, index, ""); }
    private void control(JammarrPayloads.ControlAction action, int index, String expectedKey) { PacketDistributor.sendToServer(new JammarrPayloads.ControlRequest(action, index, expectedKey)); }
    private void request(int page) {
        if (view.browseKind == null) return;
        if (requestPending) return;
        if (search != null) searchQuery = search.getValue();
        searchQuery = searchQuery.trim();
        if (view == View.SEARCH && searchQuery.length() < 2) {
            requestPending = false;
            screenNotice = Component.translatable("jammarr.screen.short_query").getString();
            state.clearBrowse(view.browseKind, searchQuery);
            rebuildWidgets();
            return;
        }
        requestPending = true;
        pendingKind = view.browseKind;
        pendingQuery = searchQuery;
        pendingPage = page;
        PacketDistributor.sendToServer(new JammarrPayloads.BrowseRequest(view.browseKind, searchQuery, page));
        rebuildWidgets();
    }
    void resultsChanged() {
        JammarrPayloads.BrowseResults result = state.browse();
        if (requestPending && result.kind() == pendingKind && result.page() == pendingPage && result.query().equals(pendingQuery)) requestPending = false;
        if (minecraft != null) rebuildWidgets();
    }
    void requestFailed() { requestPending = false; queuePending = false; pendingQueueKey = ""; if (minecraft != null) rebuildWidgets(); }

    void playbackChanged() {
        if (!queuePending || state.playback().queue().stream().noneMatch(entry -> entry.key().equals(pendingQueueKey))) return;
        queuePending = false;
        pendingQueueKey = "";
        screenNotice = Component.translatable("jammarr.screen.queued").getString();
        rebuildWidgets();
    }

    void queueChanged() {
        if (view == View.QUEUE && minecraft != null) {
            rebuildWidgets();
        }
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == 257 && search != null && search.isFocused()) { request(0); return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && view.browseKind != null && state.browse().kind() == view.browseKind) { rowOffset = Math.max(0, rowOffset + (scrollY < 0 ? 1 : -1)); rebuildWidgets(); return true; }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick); super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        JammarrPayloads.PlaybackState playing = state.playback();
        String now = statusLabel(playing) + (playing.title().isBlank() ? "" : ": " + playing.title() + (playing.artist().isBlank() ? "" : " — " + playing.artist())) + "  " + time(playing.positionMs()) + "/" + time(playing.durationMs());
        graphics.drawCenteredString(font, trim(now, width - 20), width / 2, 25, statusColor(playing.status()));
        String notice = screenNotice.isBlank() ? (state.notice().isBlank() ? playing.statusMessage() : state.notice()) : screenNotice;
        if (!notice.isBlank()) graphics.drawCenteredString(font, trim(notice, width - 24), width / 2, height - 40, 0xFFB36B);
        if (view == View.NOW) graphics.drawCenteredString(font, "Audio: " + state.audioStatus(), width / 2, 145, state.audioState() == AudioPlaybackState.ERROR ? 0xFF7777 : 0xA0D8FF);
        if (requestPending) graphics.drawCenteredString(font, Component.translatable(view == View.SEARCH ? "jammarr.screen.searching" : "jammarr.screen.loading_page"), width / 2, height / 2, 0xA0D8FF);
        else if (queuePending) graphics.drawCenteredString(font, Component.translatable("jammarr.screen.queuing"), width / 2, height / 2, 0xA0D8FF);
        else if (playing.status() == JammarrPayloads.PlaybackStatus.PLEX_OFFLINE && notice.isBlank()) graphics.drawCenteredString(font, Component.translatable("jammarr.screen.plex_unavailable"), width / 2, height / 2, 0xFF7777);
        else if (view.browseKind != null && state.browse().kind() == view.browseKind && state.browse().items().isEmpty()) graphics.drawCenteredString(font, Component.translatable("jammarr.screen.empty"), width / 2, height / 2, 0x909090);
    }
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
