package stonytark.pampmod.server;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import stonytark.pampmod.config.PampConfig;
import stonytark.pampmod.network.PampPayloads;

public final class PampCommands {
    @SubscribeEvent public void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("pamp")
                .executes(c -> { PacketDistributor.sendToPlayer(c.getSource().getPlayerOrException(), new PampPayloads.OpenScreen()); return 1; })
                .then(Commands.literal("status").executes(c -> { GlobalPlayer p = PampServer.instance().player(); c.getSource().sendSuccess(() -> Component.literal(p == null ? "PAmpMod is unavailable" : p.status()), false); return 1; }));
        root.then(operator("pause", PampPayloads.ControlAction.PAUSE));
        root.then(operator("resume", PampPayloads.ControlAction.RESUME));
        root.then(operator("skip", PampPayloads.ControlAction.SKIP));
        root.then(operator("clear", PampPayloads.ControlAction.CLEAR));
        root.then(Commands.literal("reload").requires(s -> s.hasPermission(PampConfig.OP_PERMISSION.get())).executes(c -> { GlobalPlayer p = PampServer.instance().player(); if (p != null) p.validatePlex(); c.getSource().sendSuccess(() -> Component.literal("PAmpMod Plex validation started"), false); return 1; }));
        root.then(Commands.literal("cache").requires(s -> s.hasPermission(PampConfig.OP_PERMISSION.get())).executes(c -> { GlobalPlayer p = PampServer.instance().player(); long bytes = p == null ? 0 : p.cacheSize(); c.getSource().sendSuccess(() -> Component.literal("PAmpMod cache: " + bytes / 1024 / 1024 + " MiB"), false); return 1; }));
        root.then(Commands.literal("diagnostics").requires(s -> s.hasPermission(PampConfig.OP_PERMISSION.get())).executes(c -> { GlobalPlayer p = PampServer.instance().player(); c.getSource().sendSuccess(() -> Component.literal(p == null ? "PAmpMod is unavailable" : p.diagnostics()), false); return 1; }));
        event.getDispatcher().register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> operator(String name, PampPayloads.ControlAction action) {
        return Commands.literal(name).requires(s -> s.hasPermission(PampConfig.OP_PERMISSION.get())).executes(c -> {
            PampServer.instance().control(c.getSource().getPlayerOrException(), new PampPayloads.ControlRequest(action, -1)); return 1;
        });
    }
}
