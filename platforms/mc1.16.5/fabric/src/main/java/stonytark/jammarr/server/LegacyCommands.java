package stonytark.jammarr.server;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.core.protocol.StatePackets;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.network.LegacyPacketTypes;

import java.util.Collections;

public final class LegacyCommands {
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("jammarr")
                    .requires(LegacyCommands::capableOrConsole)
                    .executes(context -> open(context.getSource()))
                    .then(Commands.literal("status").executes(context -> reply(context.getSource(), player() == null
                            ? "Jammarr is unavailable" : player().status())));
            root.then(control("resume", ControlPackets.ControlAction.RESUME));
            root.then(control("pause", ControlPackets.ControlAction.PAUSE));
            root.then(control("skip", ControlPackets.ControlAction.SKIP));
            root.then(Commands.literal("reload").requires(LegacyCommands::operator).executes(context -> {
                if (player() != null) player().validatePlex();
                return reply(context.getSource(), "Jammarr Plex validation started");
            }));
            root.then(Commands.literal("cache").requires(LegacyCommands::operator).executes(context -> reply(
                    context.getSource(), "Jammarr cache: " + (player() == null ? 0 : player().cacheSize() / 1024 / 1024) + " MiB")));
            root.then(Commands.literal("diagnostics").requires(LegacyCommands::operator).executes(context -> reply(
                    context.getSource(), player() == null ? "Jammarr is unavailable" : player().diagnostics())));
            root.then(Commands.literal("station").requires(LegacyCommands::operator)
                    .then(Commands.literal("status").executes(context -> reply(context.getSource(), player() == null
                            ? "Jammarr is unavailable" : player().stationStatus())))
                    .then(Commands.literal("stop").executes(context -> station(context.getSource(),
                            ControlPackets.StationAction.STOP, StationModels.StationType.NONE, false)))
                    .then(Commands.literal("library-shuffle").executes(context -> station(context.getSource(),
                            ControlPackets.StationAction.START, StationModels.StationType.LIBRARY_SHUFFLE, false))));
            root.then(Commands.literal("autoplay").requires(LegacyCommands::operator)
                    .then(Commands.literal("on").executes(context -> station(context.getSource(),
                            ControlPackets.StationAction.SET_AUTOPLAY, StationModels.StationType.AUTOPLAY, true)))
                    .then(Commands.literal("off").executes(context -> station(context.getSource(),
                            ControlPackets.StationAction.SET_AUTOPLAY, StationModels.StationType.AUTOPLAY, false))));
            root.then(Commands.literal("adventure").requires(LegacyCommands::operator)
                    .then(Commands.literal("status").executes(context -> reply(context.getSource(), player() == null
                            ? "Jammarr is unavailable" : player().stationStatus())))
                    .then(Commands.literal("stop").executes(context -> station(context.getSource(),
                            ControlPackets.StationAction.STOP, StationModels.StationType.NONE, false))));
            dispatcher.register(root);
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> control(String name, ControlPackets.ControlAction action) {
        return Commands.literal(name).requires(LegacyCommands::operator).executes(context -> {
            ServerPlayer sender = context.getSource().getPlayerOrException();
            if (!LegacyNetwork.accepted(sender)) return reply(context.getSource(), "Jammarr is not active for this client");
            if (player() != null) player().control(sender, new ControlPackets.ControlRequest(action, -1, ""));
            return 1;
        });
    }

    private static int open(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer sender = source.getPlayerOrException();
        if (!LegacyNetwork.accepted(sender)) return reply(source, "Jammarr is not active for this client");
        LegacyNetwork.sendToPlayer(sender, LegacyPacketTypes.OPEN_SCREEN, LegacyPacketTypes.OpenScreen.INSTANCE);
        return 1;
    }

    private static int station(CommandSourceStack source, ControlPackets.StationAction action,
                               StationModels.StationType type, boolean enabled) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer sender = source.getPlayerOrException();
        if (!LegacyNetwork.accepted(sender)) return reply(source, "Jammarr is not active for this client");
        if (player() != null) player().station(sender, new ControlPackets.StationRequest(
                action, type, enabled, player().stationGeneration(), Collections.<StationModels.StationSeed>emptyList()));
        return 1;
    }

    private static boolean capableOrConsole(CommandSourceStack source) {
        try { return LegacyNetwork.accepted(source.getPlayerOrException()); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) { return true; }
    }
    private static boolean operator(CommandSourceStack source) { return source.hasPermission(JammarrSettings.operatorPermissionLevel()); }
    private static int reply(CommandSourceStack source, String message) { source.sendSuccess(new TextComponent(message), false); return 1; }
    private static LegacyGlobalPlayer player() { return Jammarr.coordinator(); }
    private LegacyCommands() {}
}
