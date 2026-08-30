package stonytark.jammarr;

import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import stonytark.jammarr.server.LegacyGlobalPlayer;

public final class Jammarr implements ModInitializer {
    public static final String MOD_ID = "jammarr";
    public static final String MOD_NAME = "Jammarr";
    public static final String VERSION = "1.1.0";
    public static final int PROTOCOL = 6;
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    private static LegacyGlobalPlayer coordinator;

    @Override public void onInitialize() {
        LOGGER.info("Initializing Jammarr {} for Babric/StationAPI Beta 1.7.3 protocol {}", VERSION, PROTOCOL);
    }

    public static LegacyGlobalPlayer coordinator() { return coordinator; }
    public static void coordinator(LegacyGlobalPlayer value) { coordinator = value; }
}
