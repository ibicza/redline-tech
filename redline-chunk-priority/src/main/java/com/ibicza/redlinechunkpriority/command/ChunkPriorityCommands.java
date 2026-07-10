package com.ibicza.redlinechunkpriority.command;

import com.ibicza.redlinechunkpriority.config.ChunkPriorityConfig;
import com.ibicza.redlinechunkpriority.core.ChunkPriorityManager;
import com.ibicza.redlinechunkpriority.core.ChunkPriorityStats;
import com.ibicza.redlinechunkpriority.core.ChunkPriorityTarget;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class ChunkPriorityCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("rcp")
                        .then(Commands.literal("status").executes(ChunkPriorityCommands::status))
                        .then(Commands.literal("toggle").executes(ChunkPriorityCommands::toggle))
                        .then(Commands.literal("on").executes(context -> setEnabled(context, true)))
                        .then(Commands.literal("off").executes(context -> setEnabled(context, false)))
                        .then(Commands.literal("dump").executes(ChunkPriorityCommands::dump))
        );
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        ChunkPriorityStats stats = ChunkPriorityManager.lastStats();
        context.getSource().sendSuccess(() -> Component.literal(
                "RCP runtime=" + stats.runtimeEnabled()
                        + ", config=" + ChunkPriorityConfig.ENABLED.get()
                        + ", players=" + stats.players()
                        + ", planned=" + stats.planned()
                        + ", requested=" + stats.requested()
                        + ", skippedLoaded=" + stats.skippedLoaded()
                        + ", capped=" + stats.capped()
                        + ", gameTime=" + stats.gameTime()
        ), false);

        if (!stats.sample().isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("RCP sample: " + joinTargets(stats.sample(), 10)), false);
        }
        return stats.requested();
    }

    private static int toggle(CommandContext<CommandSourceStack> context) {
        boolean enabled = ChunkPriorityManager.toggleRuntimeEnabled();
        context.getSource().sendSuccess(() -> Component.literal("RCP runtime enabled = " + enabled), true);
        return enabled ? 1 : 0;
    }

    private static int setEnabled(CommandContext<CommandSourceStack> context, boolean enabled) {
        ChunkPriorityManager.setRuntimeEnabled(enabled);
        context.getSource().sendSuccess(() -> Component.literal("RCP runtime enabled = " + enabled), true);
        return enabled ? 1 : 0;
    }

    private static int dump(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("/rcp dump must be executed by a player."));
            return 0;
        }

        List<ChunkPriorityTarget> targets = ChunkPriorityManager.preview(player);
        int limit = Math.min(targets.size(), ChunkPriorityConfig.DEBUG_LOG_COUNT.get());
        context.getSource().sendSuccess(() -> Component.literal("RCP planned " + targets.size() + " chunks. Showing " + limit + ":"), false);
        for (int i = 0; i < limit; i++) {
            ChunkPriorityTarget target = targets.get(i);
            context.getSource().sendSuccess(() -> Component.literal("  " + target.shortText()), false);
        }
        return targets.size();
    }

    private static String joinTargets(List<ChunkPriorityTarget> targets, int max) {
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(targets.size(), max);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                builder.append(" | ");
            }
            builder.append(targets.get(i).shortText());
        }
        return builder.toString();
    }

    private ChunkPriorityCommands() {
    }
}
