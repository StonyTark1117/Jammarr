package stonytark.jammarr;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import stonytark.jammarr.config.JammarrConfig;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.server.JammarrCommands;
import stonytark.jammarr.server.JammarrServer;
import org.slf4j.Logger;

@Mod(Jammarr.MODID)
public final class Jammarr {
    public static final String MODID = "jammarr";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Jammarr(IEventBus modBus, ModContainer container) {
        JammarrSettings.installServer(JammarrConfig.serverValues());
        JammarrSettings.installClient(JammarrConfig.clientValues());
        container.registerConfig(ModConfig.Type.SERVER, JammarrConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, JammarrConfig.CLIENT_SPEC);
        modBus.addListener(JammarrNetwork::register);
        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.register(new JammarrServer());
        gameBus.register(new JammarrCommands());
    }
}
