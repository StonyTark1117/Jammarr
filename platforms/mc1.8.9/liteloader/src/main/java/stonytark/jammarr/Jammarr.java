package stonytark.jammarr;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Shared identity for the client-only LiteLoader companion. */
public final class Jammarr {
    public static final String MOD_ID = "jammarr";
    public static final String MOD_NAME = "Jammarr";
    public static final String VERSION = "1.1.0";
    public static final int PROTOCOL = 6;
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    private Jammarr() {}
}
