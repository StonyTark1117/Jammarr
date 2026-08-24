package stonytark.jammarr.core.server;

import stonytark.jammarr.core.model.QueueTrack;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.protocol.ControlPackets;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Testable coordinator-facing subset of Plex operations. */
public interface PlexGateway extends StationCatalog {
    void validate() throws IOException, InterruptedException;
    PlexService.SonicStatus sonicStatus() throws IOException, InterruptedException;
    PlexService.Page browse(ControlPackets.BrowseKind kind, String query, int page, int pageSize)
            throws IOException, InterruptedException;
    List<QueueTrack> expand(StationModels.ItemKind kind, String key, int limit)
            throws IOException, InterruptedException;
    void transcode(QueueTrack track, Path output, int bitrate) throws IOException, InterruptedException;
}
