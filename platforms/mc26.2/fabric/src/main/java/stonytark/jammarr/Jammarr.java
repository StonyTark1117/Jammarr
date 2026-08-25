package stonytark.jammarr;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.server.JammarrCommands;
import stonytark.jammarr.server.JammarrServer;

import java.util.concurrent.atomic.AtomicBoolean;

public final class Jammarr implements ModInitializer {
    public static final String MODID = "jammarr";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    @Override public void onInitialize() {
        initializeOnce();
    }

    public static void bootstrapQuilt() {
        if (FabricLoader.getInstance().isModLoaded("quilt_loader")) initializeOnce();
    }

    private static void initializeOnce() {
        if (!INITIALIZED.compareAndSet(false, true)) return;
        JammarrNetwork.register();
        JammarrServer.register();
        JammarrCommands.register();
    }
}
