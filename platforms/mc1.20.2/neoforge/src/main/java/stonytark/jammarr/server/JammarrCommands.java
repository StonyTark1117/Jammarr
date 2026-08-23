package stonytark.jammarr.server;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.network.JammarrNetwork;
import stonytark.jammarr.network.JammarrPayloads;

import java.util.List;

public final class JammarrCommands {
    private static final JammarrCommands INSTANCE = new JammarrCommands();
    public static void register() { NeoForge.EVENT_BUS.register(INSTANCE); }

    @SubscribeEvent public void commands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("jammarr")
                .executes(c -> { JammarrNetwork.sendToPlayer(c.getSource().getPlayerOrException(), new JammarrPayloads.OpenScreen()); return 1; })
                .then(Commands.literal("status").executes(c -> { GlobalPlayer p = JammarrServer.instance().player(); c.getSource().sendSuccess(() -> Component.literal(p == null ? "Jammarr is unavailable" : p.status()), false); return 1; }));
        root.then(operator("pause", JammarrPayloads.ControlAction.PAUSE));
        root.then(operator("resume", JammarrPayloads.ControlAction.RESUME));
        root.then(operator("skip", JammarrPayloads.ControlAction.SKIP));
        root.then(operator("clear", JammarrPayloads.ControlAction.CLEAR));
        root.then(Commands.literal("reload").requires(s -> s.hasPermission(JammarrSettings.operatorPermissionLevel())).executes(c -> { GlobalPlayer p = JammarrServer.instance().player(); if (p != null) p.validatePlex(); c.getSource().sendSuccess(() -> Component.literal("Jammarr Plex validation started"), false); return 1; }));
        root.then(Commands.literal("cache").requires(s -> s.hasPermission(JammarrSettings.operatorPermissionLevel())).executes(c -> { GlobalPlayer p = JammarrServer.instance().player(); long bytes = p == null ? 0 : p.cacheSize(); c.getSource().sendSuccess(() -> Component.literal("Jammarr cache: " + bytes / 1024 / 1024 + " MiB"), false); return 1; }));
        root.then(Commands.literal("diagnostics").requires(s -> s.hasPermission(JammarrSettings.operatorPermissionLevel())).executes(c -> { GlobalPlayer p = JammarrServer.instance().player(); c.getSource().sendSuccess(() -> Component.literal(p == null ? "Jammarr is unavailable" : p.diagnostics()), false); return 1; }));
        root.then(Commands.literal("station").requires(s -> s.hasPermission(JammarrSettings.operatorPermissionLevel()))
                .then(Commands.literal("status").executes(c -> { GlobalPlayer p = JammarrServer.instance().player(); c.getSource().sendSuccess(() -> Component.literal(p == null ? "Jammarr is unavailable" : p.stationStatus()), false); return 1; }))
                .then(Commands.literal("stop").executes(c -> station(c.getSource(), JammarrPayloads.StationAction.STOP, JammarrPayloads.StationType.NONE, false)))
                .then(Commands.literal("library-shuffle").executes(c -> station(c.getSource(), JammarrPayloads.StationAction.START, JammarrPayloads.StationType.LIBRARY_SHUFFLE, false))));
        root.then(Commands.literal("autoplay").requires(s -> s.hasPermission(JammarrSettings.operatorPermissionLevel()))
                .then(Commands.literal("on").executes(c -> station(c.getSource(), JammarrPayloads.StationAction.SET_AUTOPLAY, JammarrPayloads.StationType.AUTOPLAY, true)))
                .then(Commands.literal("off").executes(c -> station(c.getSource(), JammarrPayloads.StationAction.SET_AUTOPLAY, JammarrPayloads.StationType.AUTOPLAY, false))));
        root.then(Commands.literal("adventure").requires(s -> s.hasPermission(JammarrSettings.operatorPermissionLevel()))
                .then(Commands.literal("status").executes(c -> { GlobalPlayer p = JammarrServer.instance().player(); c.getSource().sendSuccess(() -> Component.literal(p == null ? "Jammarr is unavailable" : p.stationStatus()), false); return 1; }))
                .then(Commands.literal("stop").executes(c -> station(c.getSource(), JammarrPayloads.StationAction.STOP, JammarrPayloads.StationType.NONE, false))));
        event.getDispatcher().register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> operator(String name, JammarrPayloads.ControlAction action) {
        return Commands.literal(name).requires(s -> s.hasPermission(JammarrSettings.operatorPermissionLevel())).executes(c -> {
            JammarrServer.instance().control(c.getSource().getPlayerOrException(), new JammarrPayloads.ControlRequest(action, -1)); return 1;
        });
    }

    private static int station(CommandSourceStack source, JammarrPayloads.StationAction action,
                               JammarrPayloads.StationType type, boolean enabled)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        GlobalPlayer player = JammarrServer.instance().player();
        if (player == null) { source.sendFailure(Component.literal("Jammarr is unavailable")); return 0; }
        player.station(source.getPlayerOrException(), new JammarrPayloads.StationRequest(
                action, type, enabled, player.stationGeneration(), List.of()));
        return 1;
    }
}
