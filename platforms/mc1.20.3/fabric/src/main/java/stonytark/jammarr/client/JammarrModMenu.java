package stonytark.jammarr.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Optional Mod Menu bridge; Fabric loads this entry point only when Mod Menu is present. */
public final class JammarrModMenu implements ModMenuApi {
    @Override public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return JammarrClientConfigScreen::new;
    }
}
