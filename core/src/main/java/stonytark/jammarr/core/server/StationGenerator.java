package stonytark.jammarr.core.server;

import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.StationModels.ItemKind;
import stonytark.jammarr.core.model.StationModels.MediaItem;
import stonytark.jammarr.core.model.StationModels.SonicCapability;
import stonytark.jammarr.core.model.StationModels.SonicResult;
import stonytark.jammarr.core.model.StationModels.StationDefinition;
import stonytark.jammarr.core.model.StationModels.StationSeed;
import stonytark.jammarr.core.model.StationModels.StationType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Platform-neutral station, autoplay, and Sonic Adventure decision engine. */
public final class StationGenerator {
    public static final int LOOKAHEAD_TARGET = 20;
    public static final int TRACK_HISTORY_LIMIT = 100;
    public static final int ARTIST_HISTORY_LIMIT = 5;
    private static final double NORMAL_DISTANCE = 0.25;
    private static final double WIDE_DISTANCE = 0.40;

    private final StationCatalog plex;

    public StationGenerator(StationCatalog plex) {
        if (plex == null) throw new IllegalArgumentException("plex");
        this.plex = plex;
    }

    public GeneratedBatch generate(StationDefinition definition, List<QueueTrack> history,
                                   SonicCapability capability, boolean allowMetadataFallback)
            throws IOException, InterruptedException {
        validate(definition);
        Set<String> excludedKeys = recentKeys(history, TRACK_HISTORY_LIMIT);
        Set<String> excludedArtists = recentArtists(history, ARTIST_HISTORY_LIMIT);
        if (definition.type() == StationType.LIBRARY_SHUFFLE) {
            List<QueueTrack> candidates = plex.randomTracks(LOOKAHEAD_TARGET * 2, excludedKeys);
            List<QueueTrack> selected = filtered(candidates, excludedKeys, excludedArtists, LOOKAHEAD_TARGET, false);
            if (selected.isEmpty()) selected = first(candidates, LOOKAHEAD_TARGET);
            return new GeneratedBatch(selected, false, "Library shuffle");
        }
        if (capability != SonicCapability.READY) {
            if (!allowMetadataFallback || definition.adventure()) {
                throw new PlexException(PlexException.Kind.INVALID_RESPONSE, capabilityMessage(capability));
            }
            return new GeneratedBatch(plex.metadataFallback(definition.seeds(), LOOKAHEAD_TARGET, excludedKeys), false,
                    "Metadata fallback is active because Plex sonic analysis is unavailable");
        }
        switch (definition.type()) {
            case AUTOPLAY:
                return tracksFromRankings(autoplaySeeds(history), excludedKeys, excludedArtists, "Sonic autoplay");
            case TRACK_RADIO:
                return trackRadio(requireSeed(definition, ItemKind.TRACK), excludedKeys, excludedArtists);
            case ARTIST_RADIO:
                return artistRadio(definition.seeds().get(0), excludedKeys, excludedArtists);
            case ALBUM_RADIO:
                return albumRadio(definition.seeds().get(0), excludedKeys);
            case SONIC_MIX:
                return sonicMix(definition, excludedKeys, excludedArtists);
            case SONIC_ADVENTURE:
                return adventure(definition);
            default:
                throw new IllegalStateException("Inactive station cannot generate tracks");
        }
    }

    private GeneratedBatch tracksFromRankings(List<StationSeed> seeds, Set<String> excludedKeys,
                                               Set<String> excludedArtists, String message)
            throws IOException, InterruptedException {
        List<List<QueueTrack>> rankings = new ArrayList<List<QueueTrack>>();
        for (StationSeed seed : seeds) {
            if (plex.hasSonicAnalysis(seed.key())) rankings.add(plex.nearestTracks(seed.key(), 50, NORMAL_DISTANCE));
        }
        if (rankings.isEmpty()) throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                "Plex sonic analysis is missing for every selected track seed");
        List<QueueTrack> selected = StationSelection.reciprocalRankFusion(rankings, excludedKeys, excludedArtists, LOOKAHEAD_TARGET);
        if (selected.isEmpty()) {
            rankings.clear();
            for (StationSeed seed : seeds) {
                if (plex.hasSonicAnalysis(seed.key())) rankings.add(plex.nearestTracks(seed.key(), 75, WIDE_DISTANCE));
            }
            selected = StationSelection.reciprocalRankFusion(rankings, excludedKeys, excludedArtists, LOOKAHEAD_TARGET);
            if (selected.isEmpty()) selected = StationSelection.reciprocalRankFusion(rankings, excludedKeys,
                    Collections.<String>emptySet(), LOOKAHEAD_TARGET);
        }
        if (selected.isEmpty()) throw noCandidates();
        return new GeneratedBatch(selected, false, message);
    }

    private GeneratedBatch trackRadio(StationSeed seed, Set<String> excludedKeys, Set<String> excludedArtists)
            throws IOException, InterruptedException {
        List<QueueTrack> nativeRadio = nativeRadio(seed, excludedKeys, excludedArtists);
        if (!nativeRadio.isEmpty()) return new GeneratedBatch(nativeRadio, false, "Track Radio (Plex native station)");
        return tracksFromRankings(Collections.singletonList(seed), excludedKeys, excludedArtists, "Track Radio");
    }

    private GeneratedBatch artistRadio(StationSeed seed, Set<String> excludedKeys, Set<String> excludedArtists)
            throws IOException, InterruptedException {
        List<QueueTrack> nativeRadio = nativeRadio(seed, excludedKeys, excludedArtists);
        if (!nativeRadio.isEmpty()) return new GeneratedBatch(nativeRadio, false, "Artist Radio (Plex native station)");
        List<QueueTrack> tracks = artistCandidates(seed, excludedKeys, excludedArtists, 30, NORMAL_DISTANCE);
        if (tracks.isEmpty()) tracks = artistCandidates(seed, excludedKeys, excludedArtists, 50, WIDE_DISTANCE);
        if (tracks.isEmpty()) tracks = artistCandidates(seed, excludedKeys, Collections.<String>emptySet(), 50, WIDE_DISTANCE);
        if (tracks.isEmpty()) throw noCandidates();
        return new GeneratedBatch(tracks, false, "Artist Radio");
    }

    private List<QueueTrack> artistCandidates(StationSeed seed, Set<String> excludedKeys,
                                              Set<String> excludedArtists, int limit, double distance)
            throws IOException, InterruptedException {
        List<SonicResult> artists = plex.nearest(ItemKind.ARTIST, seed.key(), limit, distance);
        List<QueueTrack> tracks = new ArrayList<QueueTrack>();
        for (SonicResult artist : artists) {
            List<QueueTrack> candidates = plex.expand(ItemKind.ARTIST, artist.item().key(), 20);
            for (QueueTrack candidate : candidates) {
                if (!excludedKeys.contains(candidate.key()) && !excludedArtists.contains(candidate.artist())) {
                    tracks.add(candidate);
                    break;
                }
            }
            if (tracks.size() >= LOOKAHEAD_TARGET) break;
        }
        return immutable(tracks);
    }

    private GeneratedBatch albumRadio(StationSeed seed, Set<String> excludedKeys)
            throws IOException, InterruptedException {
        List<QueueTrack> nativeRadio = nativeRadio(seed, excludedKeys, Collections.<String>emptySet());
        if (!nativeRadio.isEmpty()) return new GeneratedBatch(nativeRadio, false, "Album Radio (Plex native station)");
        List<SonicResult> nearby = plex.nearest(ItemKind.ALBUM, seed.key(), 15, NORMAL_DISTANCE);
        List<QueueTrack> tracks = albumCandidates(seed, nearby, excludedKeys);
        if (tracks.isEmpty()) {
            nearby = plex.nearest(ItemKind.ALBUM, seed.key(), 25, WIDE_DISTANCE);
            tracks = albumCandidates(seed, nearby, excludedKeys);
        }
        if (tracks.isEmpty()) throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                "Plex sonic analysis is missing or found no unrepeated albums for the selected album");
        return new GeneratedBatch(tracks, false, "Album Radio");
    }

    private List<QueueTrack> albumCandidates(StationSeed seed, List<SonicResult> nearby, Set<String> excludedKeys)
            throws IOException, InterruptedException {
        if (nearby.isEmpty()) return Collections.emptyList();
        List<SonicResult> albums = new ArrayList<SonicResult>();
        albums.add(new SonicResult(new MediaItem(ItemKind.ALBUM, seed.key(), seed.title(), seed.subtitle(), 0), 0));
        albums.addAll(nearby);
        List<QueueTrack> tracks = new ArrayList<QueueTrack>();
        for (SonicResult album : albums) {
            for (QueueTrack track : plex.expand(ItemKind.ALBUM, album.item().key(), 100)) {
                if (!excludedKeys.contains(track.key())) tracks.add(track);
            }
            if (tracks.size() >= LOOKAHEAD_TARGET) break;
        }
        return immutable(tracks);
    }

    private GeneratedBatch sonicMix(StationDefinition definition, Set<String> excludedKeys, Set<String> excludedArtists)
            throws IOException, InterruptedException {
        ItemKind kind = definition.seeds().get(0).kind();
        if (kind == ItemKind.TRACK) return tracksFromRankings(definition.seeds(), excludedKeys, excludedArtists, "Sonic Mix");
        List<List<QueueTrack>> rankings = nonTrackMixRankings(definition, kind, NORMAL_DISTANCE);
        if (rankings.isEmpty()) throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                "Plex sonic analysis is missing for every selected seed");
        Set<String> artists = kind == ItemKind.ARTIST ? excludedArtists : Collections.<String>emptySet();
        List<QueueTrack> selected = StationSelection.reciprocalRankFusion(rankings, excludedKeys, artists, LOOKAHEAD_TARGET);
        if (selected.isEmpty()) {
            rankings = nonTrackMixRankings(definition, kind, WIDE_DISTANCE);
            selected = StationSelection.reciprocalRankFusion(rankings, excludedKeys, artists, LOOKAHEAD_TARGET);
            if (selected.isEmpty() && kind == ItemKind.ARTIST) selected = StationSelection.reciprocalRankFusion(
                    rankings, excludedKeys, Collections.<String>emptySet(), LOOKAHEAD_TARGET);
        }
        if (selected.isEmpty()) throw noCandidates();
        return new GeneratedBatch(selected, false, "Sonic Mix");
    }

    private List<List<QueueTrack>> nonTrackMixRankings(StationDefinition definition, ItemKind kind, double distance)
            throws IOException, InterruptedException {
        List<List<QueueTrack>> rankings = new ArrayList<List<QueueTrack>>();
        for (StationSeed seed : definition.seeds()) {
            List<QueueTrack> ranking = new ArrayList<QueueTrack>();
            for (SonicResult result : plex.nearest(kind, seed.key(), distance == NORMAL_DISTANCE ? 25 : 50, distance)) {
                List<QueueTrack> expanded = plex.expand(kind, result.item().key(), kind == ItemKind.ALBUM ? 100 : 10);
                if (kind == ItemKind.ALBUM) ranking.addAll(expanded);
                else if (!expanded.isEmpty()) ranking.add(expanded.get(0));
            }
            if (!ranking.isEmpty()) rankings.add(immutable(ranking));
        }
        return rankings;
    }

    private GeneratedBatch adventure(StationDefinition definition) throws IOException, InterruptedException {
        List<List<QueueTrack>> segments = new ArrayList<List<QueueTrack>>();
        for (int i = 0; i < definition.seeds().size(); i++) {
            if (!plex.hasSonicAnalysis(definition.seeds().get(i).key())) throw new PlexException(
                    PlexException.Kind.INVALID_RESPONSE, "Adventure waypoint " + (i + 1) + " has no Plex sonic analysis");
        }
        for (int i = 0; i + 1 < definition.seeds().size(); i++) {
            List<QueueTrack> segment = plex.sonicPath(definition.seeds().get(i).key(), definition.seeds().get(i + 1).key(), 100);
            if (segment.size() < 2) throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                    "Plex could not build the sonic path between waypoint " + (i + 1) + " and " + (i + 2));
            segments.add(segment);
        }
        List<QueueTrack> path = StationSelection.deduplicatePath(segments, 100);
        if (path.size() < 2) throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                "Plex returned an empty Sonic Adventure");
        return new GeneratedBatch(path, true, "Sonic Adventure");
    }

    private List<QueueTrack> nativeRadio(StationSeed seed, Set<String> excludedKeys, Set<String> excludedArtists)
            throws IOException, InterruptedException {
        return filtered(plex.nativeRadioTracks(seed, LOOKAHEAD_TARGET * 2), excludedKeys, excludedArtists,
                LOOKAHEAD_TARGET, true);
    }

    private static List<StationSeed> autoplaySeeds(List<QueueTrack> history) throws PlexException {
        List<StationSeed> seeds = new ArrayList<StationSeed>();
        int start = Math.max(0, history.size() - 5);
        for (int i = start; i < history.size(); i++) {
            QueueTrack track = history.get(i);
            seeds.add(new StationSeed(ItemKind.TRACK, track.key(), track.title(), track.artist()));
        }
        if (seeds.isEmpty()) throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                "Sonic autoplay needs at least one previously played track");
        return immutableSeeds(seeds);
    }

    private static StationSeed requireSeed(StationDefinition definition, ItemKind kind) throws PlexException {
        if (definition.seeds().isEmpty() || definition.seeds().get(0).kind() != kind) throw new PlexException(
                PlexException.Kind.INVALID_RESPONSE, "This station has an invalid seed");
        return definition.seeds().get(0);
    }

    public static void validate(StationDefinition definition) throws PlexException {
        if (definition == null) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Station is missing");
        int count = definition.seeds().size();
        switch (definition.type()) {
            case NONE:
            case AUTOPLAY:
            case LIBRARY_SHUFFLE:
                if (count != 0) throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                        "This station does not accept seeds");
                break;
            case TRACK_RADIO:
                requireExact(definition, 1, ItemKind.TRACK);
                break;
            case ARTIST_RADIO:
                requireExact(definition, 1, ItemKind.ARTIST);
                break;
            case ALBUM_RADIO:
                requireExact(definition, 1, ItemKind.ALBUM);
                break;
            case SONIC_MIX:
                if (count < 2 || count > 5) throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                        "Sonic Mix requires 2 to 5 seeds");
                ItemKind kind = definition.seeds().get(0).kind();
                if (kind == ItemKind.PLAYLIST || anyDifferentKind(definition.seeds(), kind)) throw new PlexException(
                        PlexException.Kind.INVALID_RESPONSE,
                        "Sonic Mix seeds must all be tracks, artists, or albums of one type");
                break;
            case SONIC_ADVENTURE:
                if (count < 2 || count > 5 || anyDifferentKind(definition.seeds(), ItemKind.TRACK)) throw new PlexException(
                        PlexException.Kind.INVALID_RESPONSE, "Sonic Adventure requires 2 to 5 track waypoints");
                break;
            default:
                throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Unknown station type");
        }
        for (StationSeed seed : definition.seeds()) {
            if (seed.key().trim().isEmpty()) throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                    "Station seeds must have Plex keys");
        }
    }

    private static void requireExact(StationDefinition definition, int count, ItemKind kind) throws PlexException {
        if (definition.seeds().size() != count || anyDifferentKind(definition.seeds(), kind)) throw new PlexException(
                PlexException.Kind.INVALID_RESPONSE,
                definition.type() + " requires one " + kind.name().toLowerCase(java.util.Locale.ROOT) + " seed");
    }

    private static boolean anyDifferentKind(List<StationSeed> seeds, ItemKind kind) {
        for (StationSeed seed : seeds) if (seed.kind() != kind) return true;
        return false;
    }

    private static Set<String> recentKeys(List<QueueTrack> history, int limit) {
        Set<String> values = new HashSet<String>();
        for (int i = Math.max(0, history.size() - limit); i < history.size(); i++) values.add(history.get(i).key());
        return values;
    }

    private static Set<String> recentArtists(List<QueueTrack> history, int limit) {
        Set<String> values = new HashSet<String>();
        for (int i = Math.max(0, history.size() - limit); i < history.size(); i++) values.add(history.get(i).artist());
        return values;
    }

    private static List<QueueTrack> filtered(List<QueueTrack> candidates, Set<String> excludedKeys,
                                             Set<String> excludedArtists, int limit, boolean checkKeys) {
        List<QueueTrack> selected = new ArrayList<QueueTrack>();
        for (QueueTrack track : candidates) {
            if ((!checkKeys || !excludedKeys.contains(track.key())) && !excludedArtists.contains(track.artist())) selected.add(track);
            if (selected.size() >= limit) break;
        }
        return immutable(selected);
    }

    private static List<QueueTrack> first(List<QueueTrack> values, int limit) {
        return immutable(new ArrayList<QueueTrack>(values.subList(0, Math.min(values.size(), limit))));
    }

    private static List<QueueTrack> immutable(List<QueueTrack> tracks) {
        return Collections.unmodifiableList(new ArrayList<QueueTrack>(tracks));
    }

    private static List<StationSeed> immutableSeeds(List<StationSeed> seeds) {
        return Collections.unmodifiableList(new ArrayList<StationSeed>(seeds));
    }

    private static PlexException noCandidates() {
        return new PlexException(PlexException.Kind.INVALID_RESPONSE,
                "Plex sonic matching found no unrepeated tracks, even at the wider distance");
    }

    private static String capabilityMessage(SonicCapability capability) {
        if (capability == null) return "Plex sonic capability validation is still in progress";
        switch (capability) {
            case NO_PLEX_PASS: return "Plex Pass is required for sonic stations";
            case ANALYSIS_INCOMPLETE: return "Plex sonic analysis is disabled, incomplete, or missing for this library or seed";
            case UNSUPPORTED: return "This Plex Media Server does not support the required sonic operation";
            case PLEX_OFFLINE: return "Plex is offline; station generation will resume after reconnection";
            case CHECKING: return "Plex sonic capability validation is still in progress";
            case READY: return "Plex sonic analysis is ready";
            default: return "Plex sonic capability is unavailable";
        }
    }

    public static final class GeneratedBatch {
        private final List<QueueTrack> tracks;
        private final boolean adventurePath;
        private final String message;

        public GeneratedBatch(List<QueueTrack> tracks, boolean adventurePath, String message) {
            this.tracks = immutable(tracks == null ? Collections.<QueueTrack>emptyList() : tracks);
            this.adventurePath = adventurePath;
            this.message = message == null ? "" : message;
        }

        public List<QueueTrack> tracks() { return tracks; }
        public boolean adventurePath() { return adventurePath; }
        public String message() { return message; }
    }
}
