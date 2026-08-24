package stonytark.jammarr.core.server;

import java.nio.file.Path;

/** Platform-neutral path contract for server-owned transient media storage. */
public interface ServerStorage {
    Path cacheDirectory();
}
