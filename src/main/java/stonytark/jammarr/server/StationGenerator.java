package stonytark.jammarr.server;

import stonytark.jammarr.network.JammarrPayloads;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StationGenerator {
    static final int LOOKAHEAD_TARGET = 20;
    static final int TRACK_HISTORY_LIMIT = 100;
    static final int ARTIST_HISTORY_LIMIT = 5;
    private static final double NORMAL_DISTANCE = 0.25;
    private static final double WIDE_DISTANCE = 0.40;

    private final StationCatalog plex;

    public StationGenerator(StationCatalog plex) { this.plex = plex; }

    public GeneratedBatch generate(StationDefinition definition, List<QueueTrack> history,
                                   JammarrPayloads.SonicCapability capability, boolean allowMetadataFallback)
            throws IOException, InterruptedException {
        validate(definition);
        Set<String> excludedKeys = recentKeys(history, TRACK_HISTORY_LIMIT);
        Set<String> excludedArtists = recentArtists(history, ARTIST_HISTORY_LIMIT);
        if (definition.type() == JammarrPayloads.StationType.LIBRARY_SHUFFLE) {
            List<QueueTrack> candidates = plex.randomTracks(LOOKAHEAD_TARGET * 2, excludedKeys);
            List<QueueTrack> selected = candidates.stream().filter(track -> !excludedArtists.contains(track.artist())).limit(LOOKAHEAD_TARGET).toList();
            if (selected.isEmpty()) selected = candidates.stream().limit(LOOKAHEAD_TARGET).toList();
            return new GeneratedBatch(selected, false, "Library shuffle");
        }
        if (capability != JammarrPayloads.SonicCapability.READY) {
            if (!allowMetadataFallback || definition.adventure()) {
                throw new PlexException(PlexException.Kind.INVALID_RESPONSE, capabilityMessage(capability));
            }
            return new GeneratedBatch(plex.metadataFallback(definition.seeds(), LOOKAHEAD_TARGET, excludedKeys), false,
                    "Metadata fallback is active because Plex sonic analysis is unavailable");
        }
        return switch (definition.type()) {
            case AUTOPLAY -> tracksFromRankings(autoplaySeeds(history), excludedKeys, excludedArtists, "Sonic autoplay");
            case TRACK_RADIO -> trackRadio(requireSeed(definition, JammarrPayloads.ItemKind.TRACK), excludedKeys, excludedArtists);
            case ARTIST_RADIO -> artistRadio(definition.seeds().getFirst(), excludedKeys, excludedArtists);
            case ALBUM_RADIO -> albumRadio(definition.seeds().getFirst(), excludedKeys);
            case SONIC_MIX -> sonicMix(definition, excludedKeys, excludedArtists);
            case SONIC_ADVENTURE -> adventure(definition);
            case NONE, LIBRARY_SHUFFLE -> throw new IllegalStateException("Inactive station cannot generate tracks");
        };
    }

    private GeneratedBatch tracksFromRankings(List<JammarrPayloads.StationSeed> seeds, Set<String> excludedKeys,
                                               Set<String> excludedArtists, String message) throws IOException, InterruptedException {
        List<List<QueueTrack>> rankings = new ArrayList<>();
        for (JammarrPayloads.StationSeed seed : seeds) {
            if (!plex.hasSonicAnalysis(seed.key())) continue;
            rankings.add(plex.nearestTracks(seed.key(), 50, NORMAL_DISTANCE));
        }
        if (rankings.isEmpty()) throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                "Plex sonic analysis is missing for every selected track seed");
        List<QueueTrack> selected = StationSelection.reciprocalRankFusion(rankings, excludedKeys, excludedArtists, LOOKAHEAD_TARGET);
        if (selected.isEmpty()) {
            rankings.clear();
            for (JammarrPayloads.StationSeed seed : seeds) if (plex.hasSonicAnalysis(seed.key())) rankings.add(plex.nearestTracks(seed.key(), 75, WIDE_DISTANCE));
            selected = StationSelection.reciprocalRankFusion(rankings, excludedKeys, excludedArtists, LOOKAHEAD_TARGET);
            if (selected.isEmpty()) selected = StationSelection.reciprocalRankFusion(rankings, excludedKeys, Set.of(), LOOKAHEAD_TARGET);
        }
        if (selected.isEmpty()) throw noCandidates();
        return new GeneratedBatch(selected, false, message);
    }

    private GeneratedBatch trackRadio(JammarrPayloads.StationSeed seed, Set<String> excludedKeys, Set<String> excludedArtists)
            throws IOException, InterruptedException {
        List<QueueTrack> nativeRadio = nativeRadio(seed, excludedKeys, excludedArtists);
        if (!nativeRadio.isEmpty()) return new GeneratedBatch(nativeRadio, false, "Track Radio (Plex native station)");
        return tracksFromRankings(List.of(seed), excludedKeys, excludedArtists, "Track Radio");
    }

    private GeneratedBatch artistRadio(JammarrPayloads.StationSeed seed, Set<String> excludedKeys, Set<String> excludedArtists) throws IOException, InterruptedException {
        List<QueueTrack> nativeRadio = nativeRadio(seed, excludedKeys, excludedArtists);
        if (!nativeRadio.isEmpty()) return new GeneratedBatch(nativeRadio, false, "Artist Radio (Plex native station)");
        List<QueueTrack> tracks = artistCandidates(seed, excludedKeys, excludedArtists, 30, NORMAL_DISTANCE);
        if (tracks.isEmpty()) tracks = artistCandidates(seed, excludedKeys, excludedArtists, 50, WIDE_DISTANCE);
        if (tracks.isEmpty()) tracks = artistCandidates(seed, excludedKeys, Set.of(), 50, WIDE_DISTANCE);
        if (tracks.isEmpty()) throw noCandidates();
        return new GeneratedBatch(tracks, false, "Artist Radio");
    }

    private List<QueueTrack> artistCandidates(JammarrPayloads.StationSeed seed, Set<String> excludedKeys,
                                              Set<String> excludedArtists, int limit, double distance)
            throws IOException, InterruptedException {
        List<PlexClient.SonicResult> artists = plex.nearest(JammarrPayloads.ItemKind.ARTIST, seed.key(), limit, distance);
        List<QueueTrack> tracks = new ArrayList<>();
        for (PlexClient.SonicResult artist : artists) {
            List<QueueTrack> candidates = plex.expand(JammarrPayloads.ItemKind.ARTIST, artist.item().key(), 20);
            QueueTrack chosen = candidates.stream().filter(track -> !excludedKeys.contains(track.key()) && !excludedArtists.contains(track.artist())).findFirst().orElse(null);
            if (chosen != null) tracks.add(chosen);
            if (tracks.size() >= LOOKAHEAD_TARGET) break;
        }
        return List.copyOf(tracks);
    }

    private GeneratedBatch albumRadio(JammarrPayloads.StationSeed seed, Set<String> excludedKeys) throws IOException, InterruptedException {
        List<QueueTrack> nativeRadio = nativeRadio(seed, excludedKeys, Set.of());
        if (!nativeRadio.isEmpty()) return new GeneratedBatch(nativeRadio, false, "Album Radio (Plex native station)");
        List<PlexClient.SonicResult> nearby = plex.nearest(JammarrPayloads.ItemKind.ALBUM, seed.key(), 15, NORMAL_DISTANCE);
        List<QueueTrack> tracks = albumCandidates(seed, nearby, excludedKeys);
        if (tracks.isEmpty()) {
            nearby = plex.nearest(JammarrPayloads.ItemKind.ALBUM, seed.key(), 25, WIDE_DISTANCE);
            tracks = albumCandidates(seed, nearby, excludedKeys);
        }
        if (tracks.isEmpty()) throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                "Plex sonic analysis is missing or found no unrepeated albums for the selected album");
        return new GeneratedBatch(tracks, false, "Album Radio");
    }

    private List<QueueTrack> albumCandidates(JammarrPayloads.StationSeed seed, List<PlexClient.SonicResult> nearby,
                                             Set<String> excludedKeys) throws IOException, InterruptedException {
        if (nearby.isEmpty()) return List.of();
        List<PlexClient.SonicResult> albums = new ArrayList<>();
        albums.add(new PlexClient.SonicResult(new JammarrPayloads.MediaItem(JammarrPayloads.ItemKind.ALBUM,
                seed.key(), seed.title(), seed.subtitle(), 0), 0));
        albums.addAll(nearby);
        List<QueueTrack> tracks = new ArrayList<>();
        for (PlexClient.SonicResult album : albums) {
            for (QueueTrack track : plex.expand(JammarrPayloads.ItemKind.ALBUM, album.item().key(), 100)) {
                if (!excludedKeys.contains(track.key())) tracks.add(track);
            }
            if (tracks.size() >= LOOKAHEAD_TARGET) break;
        }
        return List.copyOf(tracks);
    }

    private GeneratedBatch sonicMix(StationDefinition definition, Set<String> excludedKeys, Set<String> excludedArtists)
            throws IOException, InterruptedException {
        JammarrPayloads.ItemKind kind = definition.seeds().getFirst().kind();
        if (kind == JammarrPayloads.ItemKind.TRACK) return tracksFromRankings(definition.seeds(), excludedKeys, excludedArtists, "Sonic Mix");
        List<List<QueueTrack>> rankings = nonTrackMixRankings(definition, kind, NORMAL_DISTANCE);
        if (rankings.isEmpty()) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex sonic analysis is missing for every selected seed");
        List<QueueTrack> selected = StationSelection.reciprocalRankFusion(rankings, excludedKeys,
                kind == JammarrPayloads.ItemKind.ARTIST ? excludedArtists : Set.of(), LOOKAHEAD_TARGET);
        if (selected.isEmpty()) {
            rankings = nonTrackMixRankings(definition, kind, WIDE_DISTANCE);
            selected = StationSelection.reciprocalRankFusion(rankings, excludedKeys,
                    kind == JammarrPayloads.ItemKind.ARTIST ? excludedArtists : Set.of(), LOOKAHEAD_TARGET);
            if (selected.isEmpty() && kind == JammarrPayloads.ItemKind.ARTIST)
                selected = StationSelection.reciprocalRankFusion(rankings, excludedKeys, Set.of(), LOOKAHEAD_TARGET);
        }
        if (selected.isEmpty()) throw noCandidates();
        return new GeneratedBatch(selected, false, "Sonic Mix");
    }

    private List<List<QueueTrack>> nonTrackMixRankings(StationDefinition definition, JammarrPayloads.ItemKind kind, double distance)
            throws IOException, InterruptedException {
        List<List<QueueTrack>> rankings = new ArrayList<>();
        for (JammarrPayloads.StationSeed seed : definition.seeds()) {
            List<QueueTrack> ranking = new ArrayList<>();
            for (PlexClient.SonicResult result : plex.nearest(kind, seed.key(), distance == NORMAL_DISTANCE ? 25 : 50, distance)) {
                List<QueueTrack> expanded = plex.expand(kind, result.item().key(), kind == JammarrPayloads.ItemKind.ALBUM ? 100 : 10);
                if (kind == JammarrPayloads.ItemKind.ALBUM) ranking.addAll(expanded);
                else expanded.stream().findFirst().ifPresent(ranking::add);
            }
            if (!ranking.isEmpty()) rankings.add(List.copyOf(ranking));
        }
        return rankings;
    }

    private GeneratedBatch adventure(StationDefinition definition) throws IOException, InterruptedException {
        List<List<QueueTrack>> segments = new ArrayList<>();
        for (int i = 0; i < definition.seeds().size(); i++) {
            if (!plex.hasSonicAnalysis(definition.seeds().get(i).key())) {
                throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                        "Adventure waypoint " + (i + 1) + " has no Plex sonic analysis");
            }
        }
        for (int i = 0; i + 1 < definition.seeds().size(); i++) {
            List<QueueTrack> segment = plex.sonicPath(definition.seeds().get(i).key(), definition.seeds().get(i + 1).key(), 100);
            if (segment.size() < 2) throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                    "Plex could not build the sonic path between waypoint " + (i + 1) + " and " + (i + 2));
            segments.add(segment);
        }
        List<QueueTrack> path = StationSelection.deduplicatePath(segments, 100);
        if (path.size() < 2) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex returned an empty Sonic Adventure");
        return new GeneratedBatch(path, true, "Sonic Adventure");
    }

    private List<QueueTrack> nativeRadio(JammarrPayloads.StationSeed seed, Set<String> excludedKeys, Set<String> excludedArtists)
            throws IOException, InterruptedException {
        return plex.nativeRadioTracks(seed, LOOKAHEAD_TARGET * 2).stream()
                .filter(track -> !excludedKeys.contains(track.key()) && !excludedArtists.contains(track.artist()))
                .limit(LOOKAHEAD_TARGET).toList();
    }

    private List<JammarrPayloads.StationSeed> autoplaySeeds(List<QueueTrack> history) throws PlexException {
        List<JammarrPayloads.StationSeed> seeds = history.stream().skip(Math.max(0, history.size() - 5L))
                .map(track -> new JammarrPayloads.StationSeed(JammarrPayloads.ItemKind.TRACK, track.key(), track.title(), track.artist())).toList();
        if (seeds.isEmpty()) throw new PlexException(PlexException.Kind.INVALID_RESPONSE,
                "Sonic autoplay needs at least one previously played track");
        return seeds;
    }

    private static JammarrPayloads.StationSeed requireSeed(StationDefinition definition, JammarrPayloads.ItemKind kind) throws PlexException {
        if (definition.seeds().isEmpty() || definition.seeds().getFirst().kind() != kind)
            throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "This station has an invalid seed");
        return definition.seeds().getFirst();
    }

    public static void validate(StationDefinition definition) throws PlexException {
        int count = definition.seeds().size();
        switch (definition.type()) {
            case NONE, AUTOPLAY, LIBRARY_SHUFFLE -> {
                if (count != 0) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "This station does not accept seeds");
            }
            case TRACK_RADIO -> requireExact(definition, 1, JammarrPayloads.ItemKind.TRACK);
            case ARTIST_RADIO -> requireExact(definition, 1, JammarrPayloads.ItemKind.ARTIST);
            case ALBUM_RADIO -> requireExact(definition, 1, JammarrPayloads.ItemKind.ALBUM);
            case SONIC_MIX -> {
                if (count < 2 || count > 5) throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Sonic Mix requires 2 to 5 seeds");
                JammarrPayloads.ItemKind kind = definition.seeds().getFirst().kind();
                if (kind == JammarrPayloads.ItemKind.PLAYLIST || definition.seeds().stream().anyMatch(seed -> seed.kind() != kind))
                    throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Sonic Mix seeds must all be tracks, artists, or albums of one type");
            }
            case SONIC_ADVENTURE -> {
                if (count < 2 || count > 5 || definition.seeds().stream().anyMatch(seed -> seed.kind() != JammarrPayloads.ItemKind.TRACK))
                    throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Sonic Adventure requires 2 to 5 track waypoints");
            }
        }
        if (definition.seeds().stream().anyMatch(seed -> seed.key().isBlank()))
            throw new PlexException(PlexException.Kind.INVALID_RESPONSE, "Station seeds must have Plex keys");
    }

    private static void requireExact(StationDefinition definition, int count, JammarrPayloads.ItemKind kind) throws PlexException {
        if (definition.seeds().size() != count || definition.seeds().stream().anyMatch(seed -> seed.kind() != kind))
            throw new PlexException(PlexException.Kind.INVALID_RESPONSE, definition.type() + " requires one " + kind.name().toLowerCase() + " seed");
    }

    private static Set<String> recentKeys(List<QueueTrack> history, int limit) {
        Set<String> values = new HashSet<>(); history.stream().skip(Math.max(0, history.size() - (long)limit)).forEach(track -> values.add(track.key())); return values;
    }
    private static Set<String> recentArtists(List<QueueTrack> history, int limit) {
        Set<String> values = new HashSet<>(); history.stream().skip(Math.max(0, history.size() - (long)limit)).forEach(track -> values.add(track.artist())); return values;
    }
    private static PlexException noCandidates() { return new PlexException(PlexException.Kind.INVALID_RESPONSE, "Plex sonic matching found no unrepeated tracks, even at the wider distance"); }
    private static String capabilityMessage(JammarrPayloads.SonicCapability capability) {
        return switch (capability) {
            case NO_PLEX_PASS -> "Plex Pass is required for sonic stations";
            case ANALYSIS_INCOMPLETE -> "Plex sonic analysis is disabled, incomplete, or missing for this library or seed";
            case UNSUPPORTED -> "This Plex Media Server does not support the required sonic operation";
            case PLEX_OFFLINE -> "Plex is offline; station generation will resume after reconnection";
            case CHECKING -> "Plex sonic capability validation is still in progress";
            case READY -> "Plex sonic analysis is ready";
        };
    }

    public record GeneratedBatch(List<QueueTrack> tracks, boolean adventurePath, String message) {
        public GeneratedBatch { tracks = List.copyOf(tracks); }
    }
}
