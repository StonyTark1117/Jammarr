package stonytark.jammarr;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import stonytark.jammarr.client.JammarrClient;
import stonytark.jammarr.config.JammarrConfig;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.server.JammarrCommands;
import stonytark.jammarr.server.JammarrServer;

@Mod(Jammarr.MODID)
public final class Jammarr {
    public static final String MODID = "jammarr";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Jammarr() {
        FMLJavaModLoadingContext context = FMLJavaModLoadingContext.get();
        JammarrSettings.installClient(JammarrConfig.clientValues());
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, JammarrConfig.CLIENT_SPEC);
        JammarrNetwork.register();
        JammarrServer.register();
        JammarrCommands.register();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> JammarrClient.register(context));
    }
}
