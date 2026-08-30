package stonytark.jammarr.server;

import net.legacyfabric.fabric.api.registry.CommandRegistrationCallback;
import net.minecraft.command.AbstractCommand;
import net.minecraft.command.CommandException;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;
import stonytark.jammarr.Jammarr;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.network.LegacyPacketTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Legacy Fabric 1.8.9 command surface matching the modern /jammarr tree. */
public final class LegacyCommands extends AbstractCommand {
    public static void register() {
        CommandRegistrationCallback.EVENT.register(registry -> registry.register(new LegacyCommands()));
    }

    @Override public String getCommandName() { return "jammarr"; }
    @Override public String getUsageTranslationKey(CommandSource source) {
        return "/jammarr [status|pause|resume|skip|clear|reload|cache|diagnostics|station|autoplay|adventure]";
    }
    @Override public int getPermissionLevel() { return 0; }
    @Override public boolean isAccessible(CommandSource source) { return true; }

    @Override public void execute(CommandSource source, String[] arguments) throws CommandException {
        requireCapable(source);
        LegacyGlobalPlayer coordinator = coordinator();
        if (arguments.length == 0) {
            LegacyNetwork.sendToPlayer(getAsPlayer(source), LegacyPacketTypes.OPEN_SCREEN,
                    LegacyPacketTypes.OpenScreen.INSTANCE);
            return;
        }
        String action = arguments[0].toLowerCase(Locale.ROOT);
        if ("status".equals(action)) { reply(source, coordinator.status()); return; }
        if ("reload".equals(action)) { requireOperator(source); coordinator.validatePlex(); reply(source, "Jammarr Plex validation started"); return; }
        if ("cache".equals(action)) { requireOperator(source); reply(source, "Jammarr cache: " + coordinator.cacheSize() / 1024L / 1024L + " MiB"); return; }
        if ("diagnostics".equals(action)) { requireOperator(source); reply(source, coordinator.diagnostics()); return; }
        if ("pause".equals(action) || "resume".equals(action) || "skip".equals(action) || "clear".equals(action)) {
            requireOperator(source);
            coordinator.control(getAsPlayer(source), new ControlPackets.ControlRequest(
                    ControlPackets.ControlAction.valueOf(action.toUpperCase(Locale.ROOT)), -1, ""));
            return;
        }
        if ("station".equals(action)) { station(source, arguments, coordinator); return; }
        if ("autoplay".equals(action)) { autoplay(source, arguments, coordinator); return; }
        if ("adventure".equals(action)) { adventure(source, arguments, coordinator); return; }
        throw new CommandException(getUsageTranslationKey(source));
    }

    private static LegacyGlobalPlayer coordinator() throws CommandException {
        LegacyGlobalPlayer value = Jammarr.coordinator();
        if (value == null) throw new CommandException("Jammarr is not ready");
        return value;
    }

    private static void station(CommandSource source, String[] arguments,
                                LegacyGlobalPlayer coordinator) throws CommandException {
        requireOperator(source);
        if (arguments.length < 2 || "status".equalsIgnoreCase(arguments[1])) {
            reply(source, coordinator.stationStatus()); return;
        }
        ControlPackets.StationAction action;
        StationModels.StationType type;
        if ("stop".equalsIgnoreCase(arguments[1])) {
            action = ControlPackets.StationAction.STOP; type = StationModels.StationType.NONE;
        } else if ("library-shuffle".equalsIgnoreCase(arguments[1])) {
            action = ControlPackets.StationAction.START; type = StationModels.StationType.LIBRARY_SHUFFLE;
        } else throw new CommandException("Usage: /jammarr station [status|stop|library-shuffle]");
        coordinator.station(getAsPlayer(source), new ControlPackets.StationRequest(action, type,
                false, coordinator.stationGeneration(), Collections.<StationModels.StationSeed>emptyList()));
    }

    private static void autoplay(CommandSource source, String[] arguments,
                                 LegacyGlobalPlayer coordinator) throws CommandException {
        requireOperator(source);
        if (arguments.length != 2 || !("on".equalsIgnoreCase(arguments[1]) || "off".equalsIgnoreCase(arguments[1]))) {
            throw new CommandException("Usage: /jammarr autoplay <on|off>");
        }
        coordinator.station(getAsPlayer(source), new ControlPackets.StationRequest(
                ControlPackets.StationAction.SET_AUTOPLAY, StationModels.StationType.AUTOPLAY,
                "on".equalsIgnoreCase(arguments[1]), coordinator.stationGeneration(),
                Collections.<StationModels.StationSeed>emptyList()));
    }

    private static void adventure(CommandSource source, String[] arguments,
                                  LegacyGlobalPlayer coordinator) throws CommandException {
        requireOperator(source);
        if (arguments.length < 2 || "status".equalsIgnoreCase(arguments[1])) {
            reply(source, coordinator.stationStatus()); return;
        }
        if (!"stop".equalsIgnoreCase(arguments[1])) {
            throw new CommandException("Usage: /jammarr adventure [status|stop]");
        }
        coordinator.station(getAsPlayer(source), new ControlPackets.StationRequest(
                ControlPackets.StationAction.STOP, StationModels.StationType.NONE, false,
                coordinator.stationGeneration(), Collections.<StationModels.StationSeed>emptyList()));
    }

    private static void requireOperator(CommandSource source) throws CommandException {
        if (!source.canUseCommand(JammarrSettings.operatorPermissionLevel(), "jammarr")) {
            throw new CommandException("Operator permission is required");
        }
    }

    private static void requireCapable(CommandSource source) throws CommandException {
        if (source instanceof ServerPlayerEntity && !LegacyNetwork.accepted((ServerPlayerEntity) source)) {
            throw new CommandException("Jammarr requires the client mod for player commands");
        }
    }

    private static void reply(CommandSource source, String message) {
        source.sendMessage(new LiteralText(message));
    }

    @Override public List<String> getAutoCompleteHints(CommandSource source, String[] arguments, BlockPos pos) {
        if (arguments.length == 1) return matching(arguments[0], "status", "pause", "resume", "skip",
                "clear", "reload", "cache", "diagnostics", "station", "autoplay", "adventure");
        if (arguments.length == 2 && "station".equalsIgnoreCase(arguments[0])) {
            return matching(arguments[1], "status", "stop", "library-shuffle");
        }
        if (arguments.length == 2 && "autoplay".equalsIgnoreCase(arguments[0])) {
            return matching(arguments[1], "on", "off");
        }
        if (arguments.length == 2 && "adventure".equalsIgnoreCase(arguments[0])) {
            return matching(arguments[1], "status", "stop");
        }
        return Collections.emptyList();
    }

    private static List<String> matching(String prefix, String... values) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<String>();
        for (String value : values) if (value.startsWith(normalized)) result.add(value);
        return result;
    }
}
