package stonytark.jammarr.server;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.BlockPos;
import stonytark.jammarr.core.model.StationModels;
import stonytark.jammarr.core.platform.JammarrSettings;
import stonytark.jammarr.core.protocol.ControlPackets;
import stonytark.jammarr.network.LegacyNetwork;
import stonytark.jammarr.network.LegacyPacketTypes;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Forge 1.8.9 ICommand surface matching the modern /jammarr command tree. */
public final class LegacyCommands extends CommandBase {
    private final LegacyGlobalPlayer coordinator;

    public LegacyCommands(LegacyGlobalPlayer coordinator) { this.coordinator = coordinator; }

    @Override public String getCommandName() { return "jammarr"; }
    @Override public String getCommandUsage(ICommandSender sender) {
        return "/jammarr [status|pause|resume|skip|clear|reload|cache|diagnostics|station|autoplay|adventure]";
    }
    @Override public int getRequiredPermissionLevel() { return 0; }

    /**
     * Vanilla 1.8.9 denies every non-whitelisted slash command to non-ops even
     * when its declared permission level is zero. Keep the root available so
     * public screen/status actions work, then enforce operator-only subcommands
     * in {@link #requireOperator(ICommandSender)}.
     */
    @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

    @Override
    public void processCommand(ICommandSender sender, String[] arguments) throws CommandException {
        requireCapable(sender);
        if (arguments.length == 0) {
            EntityPlayerMP player = getCommandSenderAsPlayer(sender);
            LegacyNetwork.sendToPlayer(player, LegacyPacketTypes.OPEN_SCREEN, LegacyPacketTypes.OpenScreen.INSTANCE);
            return;
        }
        String action = arguments[0].toLowerCase(java.util.Locale.ROOT);
        if ("status".equals(action)) { reply(sender, coordinator.status()); return; }
        if ("reload".equals(action)) { requireOperator(sender); coordinator.validatePlex(); reply(sender, "Jammarr Plex validation started"); return; }
        if ("cache".equals(action)) { requireOperator(sender); reply(sender, "Jammarr cache: " + coordinator.cacheSize() / 1024L / 1024L + " MiB"); return; }
        if ("diagnostics".equals(action)) { requireOperator(sender); reply(sender, coordinator.diagnostics()); return; }
        if ("pause".equals(action) || "resume".equals(action) || "skip".equals(action) || "clear".equals(action)) {
            requireOperator(sender);
            ControlPackets.ControlAction control = ControlPackets.ControlAction.valueOf(action.toUpperCase(java.util.Locale.ROOT));
            coordinator.control(getCommandSenderAsPlayer(sender), new ControlPackets.ControlRequest(control, -1, ""));
            return;
        }
        if ("station".equals(action)) { station(sender, arguments); return; }
        if ("autoplay".equals(action)) { autoplay(sender, arguments); return; }
        if ("adventure".equals(action)) { adventure(sender, arguments); return; }
        throw new CommandException(getCommandUsage(sender));
    }

    private void station(ICommandSender sender, String[] arguments) throws CommandException {
        requireOperator(sender);
        if (arguments.length < 2 || "status".equalsIgnoreCase(arguments[1])) {
            reply(sender, coordinator.stationStatus()); return;
        }
        ControlPackets.StationAction action;
        StationModels.StationType type;
        if ("stop".equalsIgnoreCase(arguments[1])) {
            action = ControlPackets.StationAction.STOP; type = StationModels.StationType.NONE;
        } else if ("library-shuffle".equalsIgnoreCase(arguments[1])) {
            action = ControlPackets.StationAction.START; type = StationModels.StationType.LIBRARY_SHUFFLE;
        } else throw new CommandException("Usage: /jammarr station [status|stop|library-shuffle]");
        coordinator.station(getCommandSenderAsPlayer(sender), new ControlPackets.StationRequest(action, type,
                false, coordinator.stationGeneration(), Collections.<StationModels.StationSeed>emptyList()));
    }

    private void autoplay(ICommandSender sender, String[] arguments) throws CommandException {
        requireOperator(sender);
        if (arguments.length != 2 || !("on".equalsIgnoreCase(arguments[1]) || "off".equalsIgnoreCase(arguments[1]))) {
            throw new CommandException("Usage: /jammarr autoplay <on|off>");
        }
        boolean enabled = "on".equalsIgnoreCase(arguments[1]);
        coordinator.station(getCommandSenderAsPlayer(sender), new ControlPackets.StationRequest(
                ControlPackets.StationAction.SET_AUTOPLAY, StationModels.StationType.AUTOPLAY,
                enabled, coordinator.stationGeneration(), Collections.<StationModels.StationSeed>emptyList()));
    }

    private void adventure(ICommandSender sender, String[] arguments) throws CommandException {
        requireOperator(sender);
        if (arguments.length < 2 || "status".equalsIgnoreCase(arguments[1])) {
            reply(sender, coordinator.stationStatus()); return;
        }
        if (!"stop".equalsIgnoreCase(arguments[1])) {
            throw new CommandException("Usage: /jammarr adventure [status|stop]");
        }
        coordinator.station(getCommandSenderAsPlayer(sender), new ControlPackets.StationRequest(
                ControlPackets.StationAction.STOP, StationModels.StationType.NONE, false,
                coordinator.stationGeneration(), Collections.<StationModels.StationSeed>emptyList()));
    }

    private static void requireOperator(ICommandSender sender) throws CommandException {
        if (!sender.canCommandSenderUseCommand(JammarrSettings.operatorPermissionLevel(), "jammarr")) {
            throw new CommandException("Operator permission is required");
        }
    }

    private static void requireCapable(ICommandSender sender) throws CommandException {
        if (sender instanceof EntityPlayerMP && !LegacyNetwork.accepted((EntityPlayerMP) sender)) {
            throw new CommandException("Jammarr requires the client mod for player commands");
        }
    }

    private static void reply(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(message));
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] arguments, BlockPos targetPos) {
        if (arguments.length == 1) return getListOfStringsMatchingLastWord(arguments,
                "status", "pause", "resume", "skip", "clear", "reload", "cache", "diagnostics",
                "station", "autoplay", "adventure");
        if (arguments.length == 2 && "station".equalsIgnoreCase(arguments[0])) {
            return getListOfStringsMatchingLastWord(arguments, "status", "stop", "library-shuffle");
        }
        if (arguments.length == 2 && "autoplay".equalsIgnoreCase(arguments[0])) {
            return getListOfStringsMatchingLastWord(arguments, "on", "off");
        }
        if (arguments.length == 2 && "adventure".equalsIgnoreCase(arguments[0])) {
            return getListOfStringsMatchingLastWord(arguments, "status", "stop");
        }
        return null;
    }
}
