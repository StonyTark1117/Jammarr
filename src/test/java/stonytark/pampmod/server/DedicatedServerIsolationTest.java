package stonytark.pampmod.server;

import org.junit.jupiter.api.Test;
import stonytark.pampmod.Pampmod;
import stonytark.pampmod.network.ClientPayloadBridge;
import stonytark.pampmod.network.PampNetwork;
import stonytark.pampmod.network.PampPayloads;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DedicatedServerIsolationTest {
    @Test void commonAndServerClassesHaveNoClientOpenAlOrLwjglLinkage() throws Exception {
        List<Class<?>> classes = List.of(Pampmod.class, PampNetwork.class, PampPayloads.class, ClientPayloadBridge.class,
                PampServer.class, GlobalPlayer.class, PampCommands.class, PlexClient.class, AudioCache.class, Mp3CbrNormalizer.class);
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
