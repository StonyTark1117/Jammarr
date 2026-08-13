package stonytark.pampmod;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import stonytark.pampmod.config.PampConfig;
import stonytark.pampmod.network.PampNetwork;
import stonytark.pampmod.server.PampCommands;
import stonytark.pampmod.server.PampServer;
import org.slf4j.Logger;

@Mod(Pampmod.MODID)
public final class Pampmod {
    public static final String MODID = "pampmod";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Pampmod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, PampConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, PampConfig.CLIENT_SPEC);
        modBus.addListener(PampNetwork::register);
        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.register(new PampServer());
        gameBus.register(new PampCommands());
    }
}
