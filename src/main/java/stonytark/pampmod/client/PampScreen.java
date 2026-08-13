package stonytark.pampmod.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import stonytark.pampmod.config.PampConfig;
import stonytark.pampmod.network.PampPayloads;

import java.util.List;

public final class PampScreen extends Screen {
    private enum View {
        NOW("Now Playing", null), SEARCH("Search", PampPayloads.BrowseKind.SEARCH), ARTISTS("Artists", PampPayloads.BrowseKind.ARTISTS),
        ALBUMS("Albums", PampPayloads.BrowseKind.ALBUMS), PLAYLISTS("Playlists", PampPayloads.BrowseKind.PLAYLISTS), QUEUE("Queue", PampPayloads.BrowseKind.QUEUE);
        final String label; final PampPayloads.BrowseKind browseKind;
        View(String label, PampPayloads.BrowseKind browseKind) { this.label = label; this.browseKind = browseKind; }
    }

    private static final int PAGE_SIZE = 20;
    private final PampClientState state;
    private View view = View.NOW;
    private EditBox search;
    private int rowOffset;

    public PampScreen(PampClientState state) { super(Component.translatable("pampmod.screen.title")); this.state = state; }

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
            search = addRenderableWidget(new EditBox(font, left, contentTop, panelWidth - 72, 20, Component.translatable("pampmod.screen.search")));
            search.setMaxLength(128); search.setHint(Component.translatable("pampmod.screen.search"));
            if (state.browse().kind() == PampPayloads.BrowseKind.SEARCH) search.setValue(state.browse().query());
            addRenderableWidget(Button.builder(Component.literal("Go"), b -> request(0)).bounds(left + panelWidth - 66, contentTop, 66, 20).build());
            contentTop += 27;
        }

        if (view == View.NOW) addNowPlaying(left, contentTop, panelWidth);
        else addResults(left, contentTop, panelWidth);

        int bottom = height - 27;
        addRenderableWidget(Button.builder(Component.literal(PampConfig.ENABLED.get() ? "Mute" : "Listen"), b -> {
            PampConfig.ENABLED.set(!PampConfig.ENABLED.get()); PampConfig.ENABLED.save(); state.listeningChanged(); rebuildWidgets();
        }).bounds(left, bottom, 68, 20).build());
        addRenderableWidget(new VolumeSlider(left + 74, bottom, 130, 20));
        if (state.playback().operator()) {
            addRenderableWidget(Button.builder(Component.literal(state.playback().paused() ? "Resume" : "Pause"), b -> control(state.playback().paused() ? PampPayloads.ControlAction.RESUME : PampPayloads.ControlAction.PAUSE, -1)).bounds(left + 210, bottom, 72, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Skip"), b -> control(PampPayloads.ControlAction.SKIP, -1)).bounds(left + 288, bottom, 58, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Clear"), b -> control(PampPayloads.ControlAction.CLEAR, -1)).bounds(left + 352, bottom, 58, 20).build());
        }
        PampPayloads.BrowseResults results = state.browse();
        if (view.browseKind != null && results.kind() == view.browseKind) {
            if (results.page() > 0) addRenderableWidget(Button.builder(Component.literal("<"), b -> request(results.page() - 1)).bounds(left + panelWidth - 70, bottom, 32, 20).build());
            if (results.hasMore()) addRenderableWidget(Button.builder(Component.literal(">"), b -> request(results.page() + 1)).bounds(left + panelWidth - 34, bottom, 32, 20).build());
        }
    }

    private void addNowPlaying(int left, int top, int panelWidth) {
        PampPayloads.PlaybackState playing = state.playback();
        String title = playing.title().isBlank() ? "Nothing playing" : playing.title();
        String artist = playing.artist().isBlank() ? "" : playing.artist();
        addRenderableWidget(Button.builder(Component.literal(trim(title, panelWidth - 20)), b -> {}).bounds(left, top + 18, panelWidth, 20).build()).active = false;
        if (!artist.isBlank()) addRenderableWidget(Button.builder(Component.literal(trim(artist, panelWidth - 20)), b -> {}).bounds(left, top + 42, panelWidth, 20).build()).active = false;
    }

    private void addResults(int left, int contentTop, int panelWidth) {
        PampPayloads.BrowseResults results = state.browse();
        if (view.browseKind == null || results.kind() != view.browseKind) return;
        List<PampPayloads.MediaItem> items = results.items();
        int rows = Math.max(1, (height - contentTop - 54) / 22);
        rowOffset = Math.max(0, Math.min(rowOffset, Math.max(0, items.size() - rows)));
        for (int row = 0; row < rows && row + rowOffset < items.size(); row++) {
            int localIndex = row + rowOffset;
            int queueIndex = results.page() * PAGE_SIZE + localIndex;
            PampPayloads.MediaItem item = items.get(localIndex);
            int y = contentTop + row * 22;
            String label = item.title() + (item.subtitle().isBlank() ? "" : " — " + item.subtitle());
            int controlsWidth = view == View.QUEUE && state.playback().operator() ? 90 : 52;
            Button itemButton = Button.builder(Component.literal(trim(label, panelWidth - controlsWidth - 20)), b -> activate(item))
                    .bounds(left, y, panelWidth - controlsWidth - 4, 20).build();
            if (view == View.QUEUE) itemButton.active = false;
            addRenderableWidget(itemButton);
            if (view == View.QUEUE && state.playback().operator()) {
                int x = left + panelWidth - 88;
                Button up = Button.builder(Component.literal("↑"), b -> control(PampPayloads.ControlAction.MOVE_UP, queueIndex)).bounds(x, y, 28, 20).build();
                Button down = Button.builder(Component.literal("↓"), b -> control(PampPayloads.ControlAction.MOVE_DOWN, queueIndex)).bounds(x + 30, y, 28, 20).build();
                up.active = queueIndex > 1; down.active = queueIndex > 0 && queueIndex < state.playback().queue().size() - 1;
                addRenderableWidget(up); addRenderableWidget(down);
                addRenderableWidget(Button.builder(Component.literal("×"), b -> control(PampPayloads.ControlAction.REMOVE, queueIndex)).bounds(x + 60, y, 28, 20).build());
            } else if (view != View.QUEUE) {
                addRenderableWidget(Button.builder(Component.literal("+"), b -> activate(item)).bounds(left + panelWidth - 48, y, 48, 20).build());
            }
        }
    }

    private void addTab(int x, int y, int width, View candidate) {
        Button button = Button.builder(Component.literal(candidate.label), b -> {
            view = candidate; rowOffset = 0; state.clearNotice(); if (candidate.browseKind != null) request(0); rebuildWidgets();
        }).bounds(x, y, width, 20).build();
        button.active = view != candidate; addRenderableWidget(button);
    }
    private void activate(PampPayloads.MediaItem item) { PacketDistributor.sendToServer(new PampPayloads.QueueRequest(item.kind(), item.key())); }
    private void control(PampPayloads.ControlAction action, int index) { PacketDistributor.sendToServer(new PampPayloads.ControlRequest(action, index)); }
    private void request(int page) { if (view.browseKind != null) PacketDistributor.sendToServer(new PampPayloads.BrowseRequest(view.browseKind, search == null ? "" : search.getValue(), page)); }
    void resultsChanged() { if (minecraft != null) rebuildWidgets(); }

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
        PampPayloads.PlaybackState playing = state.playback();
        String now = statusLabel(playing) + (playing.title().isBlank() ? "" : ": " + playing.title() + (playing.artist().isBlank() ? "" : " — " + playing.artist())) + "  " + time(playing.positionMs()) + "/" + time(playing.durationMs());
        graphics.drawCenteredString(font, trim(now, width - 20), width / 2, 25, statusColor(playing.status()));
        String notice = state.notice().isBlank() ? playing.statusMessage() : state.notice();
        if (!notice.isBlank()) graphics.drawCenteredString(font, trim(notice, width - 24), width / 2, height - 40, 0xFFB36B);
        if (view == View.NOW) graphics.drawCenteredString(font, state.audioStatus(), width / 2, 145, 0xA0D8FF);
        if (view.browseKind != null && state.browse().kind() == view.browseKind && state.browse().items().isEmpty()) graphics.drawCenteredString(font, Component.translatable("pampmod.screen.empty"), width / 2, height / 2, 0x909090);
    }
    private static String statusLabel(PampPayloads.PlaybackState state) { return switch (state.status()) { case IDLE -> "Idle"; case PREPARING -> "Preparing"; case PLAYING -> "Now playing"; case PAUSED -> "Paused"; case PLEX_OFFLINE -> "Plex offline"; }; }
    private static int statusColor(PampPayloads.PlaybackStatus status) { return status == PampPayloads.PlaybackStatus.PLEX_OFFLINE ? 0xFF7777 : status == PampPayloads.PlaybackStatus.PREPARING ? 0xFFD37A : 0xCFCFCF; }
    private String trim(String value, int pixels) { return font.width(value) <= pixels ? value : font.plainSubstrByWidth(value, Math.max(0, pixels - font.width("…"))) + "…"; }
    private static String time(long ms) { long seconds = Math.max(0, ms / 1000); return "%d:%02d".formatted(seconds / 60, seconds % 60); }
    @Override public boolean isPauseScreen() { return false; }

    private final class VolumeSlider extends AbstractSliderButton {
        private VolumeSlider(int x, int y, int width, int height) { super(x, y, width, height, Component.empty(), PampConfig.VOLUME.get()); updateMessage(); }
        @Override protected void updateMessage() { setMessage(Component.literal("Volume " + Math.round(value * 100) + "%")); }
        @Override protected void applyValue() { PampConfig.VOLUME.set(value); PampConfig.VOLUME.save(); }
    }
}
