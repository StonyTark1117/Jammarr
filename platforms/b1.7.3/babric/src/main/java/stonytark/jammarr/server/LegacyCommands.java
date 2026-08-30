package stonytark.jammarr.server;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.Command;
import net.minecraft.server.command.CommandOutput;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.network.LegacyPacketTypes;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/** Beta 1.7.3 command interceptor matching the modern /jammarr tree. */
public final class LegacyCommands {
    private static final String USAGE =
            "/jammarr [status|pause|resume|skip|clear|reload|cache|diagnostics|station|autoplay|adventure]";

    public static boolean execute(MinecraftServer server, Command command) {
        String line = command.commandAndArgs == null ? "" : command.commandAndArgs.trim();
        if (line.startsWith("/")) line = line.substring(1);
        String[] tokens = line.isEmpty() ? new String[0] : line.split("\\s+");
        if (tokens.length == 0 || !"jammarr".equalsIgnoreCase(tokens[0])) return false;

        CommandOutput output = command.output;
        ServerPlayerEntity player = server.playerManager.getPlayer(output.getName());
        try {
            requireCapable(player);
            LegacyGlobalPlayer coordinator = coordinator();
            String[] arguments = Arrays.copyOfRange(tokens, 1, tokens.length);
            if (arguments.length == 0) {
                if (player == null) reply(output, coordinator.status());
                else BabricServerNetwork.sendToPlayer(player, LegacyPacketTypes.OPEN_SCREEN,
                        LegacyPacketTypes.OpenScreen.INSTANCE);
                return true;
            }
            String action = arguments[0].toLowerCase(Locale.ROOT);
            if ("status".equals(action)) reply(output, coordinator.status());
            else if ("reload".equals(action)) {
                requireOperator(server, player); coordinator.validatePlex(); reply(output, "Jammarr Plex validation started");
            } else if ("cache".equals(action)) {
                requireOperator(server, player); reply(output, "Jammarr cache: " + coordinator.cacheSize() / 1024L / 1024L + " MiB");
            } else if ("diagnostics".equals(action)) {
                requireOperator(server, player); reply(output, coordinator.diagnostics());
            } else if ("pause".equals(action) || "resume".equals(action)
                    || "skip".equals(action) || "clear".equals(action)) {
                requireOperator(server, player);
                coordinator.control(player, new ControlPackets.ControlRequest(
                        ControlPackets.ControlAction.valueOf(action.toUpperCase(Locale.ROOT)), -1, ""));
            } else if ("station".equals(action)) station(server, player, output, arguments, coordinator);
            else if ("autoplay".equals(action)) autoplay(server, player, arguments, coordinator);
            else if ("adventure".equals(action)) adventure(server, player, output, arguments, coordinator);
            else fail(USAGE);
        } catch (CommandFailure failure) {
            reply(output, failure.getMessage());
        }
        return true;
    }

    private static LegacyGlobalPlayer coordinator() {
        LegacyGlobalPlayer value = Jammarr.coordinator();
        if (value == null) fail("Jammarr is not ready");
        return value;
    }

    private static void station(MinecraftServer server, ServerPlayerEntity player, CommandOutput output,
                                String[] arguments, LegacyGlobalPlayer coordinator) {
        requireOperator(server, player);
        if (arguments.length < 2 || "status".equalsIgnoreCase(arguments[1])) {
            reply(output, coordinator.stationStatus()); return;
        }
        ControlPackets.StationAction action;
        StationModels.StationType type;
        if ("stop".equalsIgnoreCase(arguments[1])) {
            action = ControlPackets.StationAction.STOP; type = StationModels.StationType.NONE;
        } else if ("library-shuffle".equalsIgnoreCase(arguments[1])) {
            action = ControlPackets.StationAction.START; type = StationModels.StationType.LIBRARY_SHUFFLE;
        } else { fail("Usage: /jammarr station [status|stop|library-shuffle]"); return; }
        coordinator.station(player, new ControlPackets.StationRequest(action, type,
                false, coordinator.stationGeneration(), Collections.<StationModels.StationSeed>emptyList()));
    }

    private static void autoplay(MinecraftServer server, ServerPlayerEntity player, String[] arguments,
                                 LegacyGlobalPlayer coordinator) {
        requireOperator(server, player);
        if (arguments.length != 2 || !("on".equalsIgnoreCase(arguments[1]) || "off".equalsIgnoreCase(arguments[1]))) {
            fail("Usage: /jammarr autoplay <on|off>");
        }
        coordinator.station(player, new ControlPackets.StationRequest(
                ControlPackets.StationAction.SET_AUTOPLAY, StationModels.StationType.AUTOPLAY,
                "on".equalsIgnoreCase(arguments[1]), coordinator.stationGeneration(),
                Collections.<StationModels.StationSeed>emptyList()));
    }

    private static void adventure(MinecraftServer server, ServerPlayerEntity player, CommandOutput output,
                                  String[] arguments, LegacyGlobalPlayer coordinator) {
        requireOperator(server, player);
        if (arguments.length < 2 || "status".equalsIgnoreCase(arguments[1])) {
            reply(output, coordinator.stationStatus()); return;
        }
        if (!"stop".equalsIgnoreCase(arguments[1])) fail("Usage: /jammarr adventure [status|stop]");
        coordinator.station(player, new ControlPackets.StationRequest(
                ControlPackets.StationAction.STOP, StationModels.StationType.NONE, false,
                coordinator.stationGeneration(), Collections.<StationModels.StationSeed>emptyList()));
    }

    private static void requireOperator(MinecraftServer server, ServerPlayerEntity player) {
        if (player != null && !server.playerManager.isOperator(player.name)) fail("Operator permission is required");
    }

    private static void requireCapable(ServerPlayerEntity player) {
        if (player != null && !BabricServerNetwork.accepted(player)) {
            fail("Jammarr requires the client mod for player commands");
        }
    }

    private static void reply(CommandOutput output, String message) { output.sendMessage(message); }
    private static void fail(String message) { throw new CommandFailure(message); }

    private static final class CommandFailure extends RuntimeException {
        CommandFailure(String message) { super(message); }
    }

    private LegacyCommands() {}
}
