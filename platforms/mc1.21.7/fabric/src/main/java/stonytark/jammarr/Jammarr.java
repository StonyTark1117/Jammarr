package stonytark.jammarr;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.server.JammarrCommands;
import stonytark.jammarr.server.JammarrServer;

public final class Jammarr implements ModInitializer {
    public static final String MODID = "jammarr";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    @Override public void onInitialize() {
        JammarrNetwork.register();
        JammarrServer.register();
        JammarrCommands.register();
    }
}
