package stonytark.jammarr.server;

import stonytark.jammarr.core.server.Mp3CbrNormalizer;
import stonytark.jammarr.core.server.AudioCache;
import org.junit.jupiter.api.Test;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.network.ClientPayloadBridge;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DedicatedServerIsolationTest {
    @Test void commonAndServerClassesHaveNoClientOpenAlOrLwjglLinkage() throws Exception {
        List<Class<?>> classes = List.of(Jammarr.class, JammarrNetwork.class, JammarrPayloads.class, ClientPayloadBridge.class,
                JammarrServer.class, GlobalPlayer.class, JammarrCommands.class, PlexClient.class, AudioCache.class, Mp3CbrNormalizer.class);
        for (Class<?> type : classes) {
            assertDoesNotThrow(() -> Class.forName(type.getName(), false, type.getClassLoader()));
            String resource = "/" + type.getName().replace('.', '/') + ".class";
            try (InputStream input = type.getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                String constants = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(constants.contains("net/minecraft/client"), type + " links client Minecraft classes");
                assertFalse(constants.contains("com/mojang/blaze3d"), type + " links OpenAL integration");
                assertFalse(constants.contains("org/lwjgl"), type + " links LWJGL");
            }
        }
    }
}
