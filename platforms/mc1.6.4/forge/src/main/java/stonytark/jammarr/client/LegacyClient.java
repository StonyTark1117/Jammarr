package stonytark.jammarr.client;

import cpw.mods.fml.client.registry.KeyBindingRegistry;
import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.Player;
import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatMessageComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.sound.SoundLoadEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeSubscribe;
import org.lwjgl.input.Keyboard;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.protocol.ProtocolLimits;
import stonytark.jammarr.network.LegacyNetwork;

import java.util.EnumSet;

@SideOnly(Side.CLIENT)
public final class LegacyClient implements ITickHandler {
    private static final LegacyClient INSTANCE = new LegacyClient();
    private final KeyBinding open = new KeyBinding("key.jammarr.open", Keyboard.KEY_P);
    private boolean vanillaMusicSuppressed;
    private boolean connected;
    private boolean registered;

    public static synchronized void register() {
        if (INSTANCE.registered) return;
        awaitInitialSoundStartup();
        KeyBindingRegistry.registerKeyBinding(new RegisteredKey(INSTANCE.open));
        LegacyNetwork.setClientListener(LegacyClientState.INSTANCE);
        TickRegistry.registerTickHandler(INSTANCE, Side.CLIENT);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        INSTANCE.registered = true;
    }

    private static void awaitInitialSoundStartup() {
        long deadline = System.currentTimeMillis() + 10_000L;
        boolean waited = false;
        while (LegacySoundAccess.soundSystem(Minecraft.getMinecraft()) == null
                && System.currentTimeMillis() < deadline) {
            waited = true;
            try { Thread.sleep(25L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (waited) Jammarr.LOGGER.info(
                "Jammarr waited for the initial legacy sound loader before Forge's resource reload");
    }

    @Override public void tickStart(EnumSet<TickType> type, Object... tickData) {
        updateVanillaMusicSuppression();
    }

    @Override public void tickEnd(EnumSet<TickType> type, Object... tickData) {
        Minecraft minecraft = Minecraft.getMinecraft();
        boolean inWorld = minecraft.theWorld != null && minecraft.thePlayer != null;
        boolean channelActive = inWorld && NetworkRegistry.instance().isChannelActive(
                Jammarr.MOD_ID, (Player) minecraft.thePlayer);
        LegacyNetwork.clientTick(channelActive);
        updateVanillaMusicSuppression();
        if (open.isPressed() && inWorld) minecraft.displayGuiScreen(new LegacyScreen(LegacyClientState.INSTANCE));
        if (inWorld) LegacyClientState.INSTANCE.tick();
        else if (connected) LegacyClientState.INSTANCE.stop();
        connected = inWorld;
    }

    @Override public EnumSet<TickType> ticks() { return EnumSet.of(TickType.CLIENT); }
    @Override public String getLabel() { return "Jammarr client"; }

    private void updateVanillaMusicSuppression() {
        boolean suppress = LegacyClientState.INSTANCE.suppressVanillaMusic();
        try {
            if (suppress) LegacySoundAccess.suppressVanillaMusic(Minecraft.getMinecraft());
            else if (vanillaMusicSuppressed) LegacySoundAccess.restoreVanillaMusic(Minecraft.getMinecraft());
            vanillaMusicSuppressed = suppress;
        } catch (RuntimeException unavailable) {
            Jammarr.LOGGER.warn("Unable to update legacy vanilla-music suppression", unavailable);
        }
    }

    @ForgeSubscribe public void chat(ClientChatReceivedEvent event) {
        if (!ProtocolLimits.commandProbeEnabled()) return;
        String message = event.message;
        try {
            ChatMessageComponent component = ChatMessageComponent.createFromJson(message);
            if (component != null) message = component.toStringWithFormatting(false);
        } catch (RuntimeException ignored) {
            // A third-party server may still send the historical plain string.
        }
        Jammarr.LOGGER.info("Acceptance command response: {}", message);
        if (message.contains("JAMMARR_ACCEPTANCE_OPERATOR_READY")) {
            LegacyClientState.INSTANCE.operatorCommandProbe();
        }
    }

    @ForgeSubscribe public void soundLoaded(SoundLoadEvent event) {
        LegacyClientState.INSTANCE.audioEngineReloaded();
    }

    private static final class RegisteredKey extends KeyBindingRegistry.KeyHandler {
        private RegisteredKey(KeyBinding binding) { super(new KeyBinding[] { binding }); }
        @Override public void keyDown(EnumSet<TickType> types, KeyBinding binding, boolean tickEnd, boolean repeat) {}
        @Override public void keyUp(EnumSet<TickType> types, KeyBinding binding, boolean tickEnd) {}
        @Override public EnumSet<TickType> ticks() { return EnumSet.of(TickType.CLIENT); }
        @Override public String getLabel() { return "Jammarr key"; }
    }

    private LegacyClient() {}
}
