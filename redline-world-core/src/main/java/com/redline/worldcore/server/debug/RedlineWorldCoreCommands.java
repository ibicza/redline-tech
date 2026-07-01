package com.redline.worldcore.server.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.redline.worldcore.RedlineWorldCore;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.pos.Region3DPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class RedlineWorldCoreCommands {
    private static final CubicDimensionSettings DEFAULTS = CubicDimensionSettings.defaults();

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rwc")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("cube")
                        .executes(context -> cube(context.getSource())))
                .then(Commands.literal("selftest")
                        .executes(context -> selfTest(context.getSource())))
                .then(Commands.literal("storage")
                        .then(Commands.literal("selftest")
                                .executes(context -> storageSelfTest(context.getSource()))))
        );
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Redline World Core loaded: " + RedlineWorldCore.MOD_ID), false);
        source.sendSuccess(() -> Component.literal("Default cube Y range: "
                + DEFAULTS.minCubeY() + ".." + DEFAULTS.maxCubeY()
                + " blocks " + DEFAULTS.minBlockY() + ".." + DEFAULTS.maxBlockY()), false);
        source.sendSuccess(() -> Component.literal("Default load distances: horizontal="
                + DEFAULTS.horizontalLoadDistance() + ", vertical=" + DEFAULTS.verticalLoadDistance()), false);
        return 1;
    }

    private static int cube(CommandSourceStack source) {
        Vec3 pos = source.getPosition();
        CubePos cubePos = CubePos.fromBlock(Mth.floor(pos.x), Mth.floor(pos.y), Mth.floor(pos.z));
        Region3DPos regionPos = cubePos.regionPos();

        source.sendSuccess(() -> Component.literal("Block: "
                + Mth.floor(pos.x) + " " + Mth.floor(pos.y) + " " + Mth.floor(pos.z)), false);
        source.sendSuccess(() -> Component.literal("CubePos: "
                + cubePos.x() + " " + cubePos.y() + " " + cubePos.z()), false);
        source.sendSuccess(() -> Component.literal("Cube blocks: "
                + cubePos.minBlockX() + ".." + cubePos.maxBlockX() + " / "
                + cubePos.minBlockY() + ".." + cubePos.maxBlockY() + " / "
                + cubePos.minBlockZ() + ".." + cubePos.maxBlockZ()), false);
        source.sendSuccess(() -> Component.literal("Region3D: "
                + regionPos.x() + " " + regionPos.y() + " " + regionPos.z()
                + " file=" + regionPos.fileName()
                + " entry=" + Region3DPos.localIndex(cubePos)), false);
        return 1;
    }

    private static int selfTest(CommandSourceStack source) {
        try {
            CubeMathSelfTest.runOrThrow();
            source.sendSuccess(() -> Component.literal("Redline World Core math self-test passed."), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Redline World Core math self-test failed: " + exception.getMessage()));
            return 0;
        }
    }

    private static int storageSelfTest(CommandSourceStack source) {
        try {
            CubeStorageSelfTest.Result result = CubeStorageSelfTest.runOrThrow();
            source.sendSuccess(() -> Component.literal("Redline World Core Region3D storage self-test passed. "
                    + "written=" + result.writtenCubes()
                    + ", survivingAfterRemove=" + result.survivingCubesAfterRemove()), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Redline World Core Region3D storage self-test failed: " + exception.getMessage()));
            return 0;
        }
    }

    private RedlineWorldCoreCommands() {
    }
}
