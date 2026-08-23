package stonytark.jammarr.server;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import stonytark.jammarr.config.JammarrConfig;
import stonytark.jammarr.network.JammarrPayloads;

public final class JammarrCommands {
    @SubscribeEvent public void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("jammarr")
                .executes(c -> { PacketDistributor.sendToPlayer(c.getSource().getPlayerOrException(), new JammarrPayloads.OpenScreen()); return 1; })
                .then(Commands.literal("status").executes(c -> { GlobalPlayer p = JammarrServer.instance().player(); c.getSource().sendSuccess(() -> Component.literal(p == null ? "Jammarr is unavailable" : p.status()), false); return 1; }));
        root.then(operator("pause", JammarrPayloads.ControlAction.PAUSE));
        root.then(operator("resume", JammarrPayloads.ControlAction.RESUME));
        root.then(operator("skip", JammarrPayloads.ControlAction.SKIP));
        root.then(operator("clear", JammarrPayloads.ControlAction.CLEAR));
        root.then(Commands.literal("reload").requires(s -> s.hasPermission(JammarrConfig.OP_PERMISSION.get())).executes(c -> { GlobalPlayer p = JammarrServer.instance().player(); if (p != null) p.validatePlex(); c.getSource().sendSuccess(() -> Component.literal("Jammarr Plex validation started"), false); return 1; }));
        root.then(Commands.literal("cache").requires(s -> s.hasPermission(JammarrConfig.OP_PERMISSION.get())).executes(c -> { GlobalPlayer p = JammarrServer.instance().player(); long bytes = p == null ? 0 : p.cacheSize(); c.getSource().sendSuccess(() -> Component.literal("Jammarr cache: " + bytes / 1024 / 1024 + " MiB"), false); return 1; }));
        root.then(Commands.literal("diagnostics").requires(s -> s.hasPermission(JammarrConfig.OP_PERMISSION.get())).executes(c -> { GlobalPlayer p = JammarrServer.instance().player(); c.getSource().sendSuccess(() -> Component.literal(p == null ? "Jammarr is unavailable" : p.diagnostics()), false); return 1; }));
        event.getDispatcher().register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> operator(String name, JammarrPayloads.ControlAction action) {
        return Commands.literal(name).requires(s -> s.hasPermission(JammarrConfig.OP_PERMISSION.get())).executes(c -> {
            JammarrServer.instance().control(c.getSource().getPlayerOrException(), new JammarrPayloads.ControlRequest(action, -1)); return 1;
        });
    }
}
