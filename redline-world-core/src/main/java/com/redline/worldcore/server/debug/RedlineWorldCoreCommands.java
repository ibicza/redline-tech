package com.redline.worldcore.server.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.redline.worldcore.RedlineWorldCore;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.pos.Region3DPos;
import com.redline.worldcore.server.dimension.CubicTestDimensionService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Set;

public final class RedlineWorldCoreCommands {
    private static final CubicDimensionSettings DEFAULTS = CubicDimensionSettings.defaults();
    private static final CubicTestDimensionService CUBIC_TEST = new CubicTestDimensionService();

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
                .then(Commands.literal("cubic_test")
                        .then(Commands.literal("status")
                                .executes(context -> cubicTestStatus(context.getSource())))
                        .then(Commands.literal("enter")
                                .executes(context -> cubicTestEnter(context.getSource())))
                        .then(Commands.literal("virtual")
                                .then(Commands.literal("set")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .then(Commands.argument("block", StringArgumentType.word())
                                                                        .executes(context -> cubicVirtualSet(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                                IntegerArgumentType.getInteger(context, "z"),
                                                                                StringArgumentType.getString(context, "block")
                                                                        )))))))
                                .then(Commands.literal("get")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(context -> cubicVirtualGet(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "x"),
                                                                        IntegerArgumentType.getInteger(context, "y"),
                                                                        IntegerArgumentType.getInteger(context, "z")
                                                                ))))))
                                .then(Commands.literal("fill_cube")
                                        .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                        .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                                .then(Commands.argument("block", StringArgumentType.word())
                                                                        .executes(context -> cubicVirtualFillCube(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                                IntegerArgumentType.getInteger(context, "cubeZ"),
                                                                                StringArgumentType.getString(context, "block")
                                                                        )))))))
                                .then(Commands.literal("write_probe")
                                        .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                        .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                                .executes(context -> cubicVirtualWriteProbe(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cubeX"),
                                                                        IntegerArgumentType.getInteger(context, "cubeY"),
                                                                        IntegerArgumentType.getInteger(context, "cubeZ")
                                                                ))))))
                                .then(Commands.literal("verify_probe")
                                        .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                        .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                                .executes(context -> cubicVirtualVerifyProbe(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cubeX"),
                                                                        IntegerArgumentType.getInteger(context, "cubeY"),
                                                                        IntegerArgumentType.getInteger(context, "cubeZ")
                                                                ))))))
                                .then(Commands.literal("materialize")
                                        .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                        .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                                .executes(context -> cubicVirtualMaterialize(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cubeX"),
                                                                        IntegerArgumentType.getInteger(context, "cubeY"),
                                                                        IntegerArgumentType.getInteger(context, "cubeZ")
                                                                ))))))))
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

    private static int cubicTestStatus(CommandSourceStack source) {
        boolean registered = CUBIC_TEST.isRegistered(source.getServer());
        source.sendSuccess(() -> Component.literal("Redline cubic test dimension: " + CubicDimensionKeys.CUBIC_TEST_ID), false);
        source.sendSuccess(() -> Component.literal("Registered in this world: " + registered), false);
        source.sendSuccess(() -> Component.literal("Storage root: " + CUBIC_TEST.storageRoot(source.getServer())), false);
        source.sendSuccess(() -> Component.literal("Configured cubic Y: cubes "
                + CubicTestDimensionService.SETTINGS.minCubeY() + ".." + CubicTestDimensionService.SETTINGS.maxCubeY()
                + ", blocks " + CubicTestDimensionService.SETTINGS.minBlockY()
                + ".." + CubicTestDimensionService.SETTINGS.maxBlockY()), false);
        return registered ? 1 : 0;
    }

    private static int cubicTestEnter(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel target = CUBIC_TEST.level(source.getServer()).orElse(null);
            if (target == null) {
                source.sendFailure(Component.literal("Cubic test dimension is not registered in this world. Try creating/reloading a world after adding the mod data pack."));
                return 0;
            }
            player.teleportTo(target, 0.5, 80.0, 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
            source.sendSuccess(() -> Component.literal("Teleported to " + CubicDimensionKeys.CUBIC_TEST_ID), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Failed to enter cubic test dimension: " + exception.getMessage()));
            return 0;
        }
    }

    private static int cubicVirtualSet(CommandSourceStack source, int x, int y, int z, String blockName) {
        try {
            BlockState state = CubicTestDimensionService.parseMarkerBlock(blockName);
            CubicTestDimensionService.VirtualSetResult result = CUBIC_TEST.setVirtualBlock(source.getServer(), new BlockPos(x, y, z), state);
            source.sendSuccess(() -> Component.literal("Cubic virtual block saved: block="
                    + x + " " + y + " " + z
                    + ", cube=" + formatCube(result.cubePos())
                    + ", state=" + CubicTestDimensionService.blockStateName(result.state())), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to save cubic virtual block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int cubicVirtualGet(CommandSourceStack source, int x, int y, int z) {
        try {
            return CUBIC_TEST.getVirtualBlock(source.getServer(), new BlockPos(x, y, z))
                    .map(result -> {
                        source.sendSuccess(() -> Component.literal("Cubic virtual block loaded: block="
                                + x + " " + y + " " + z
                                + ", cube=" + formatCube(result.cubePos())
                                + ", state=" + CubicTestDimensionService.blockStateName(result.state())), false);
                        return 1;
                    })
                    .orElseGet(() -> {
                        source.sendFailure(Component.literal("No cubic virtual cube saved for block " + x + " " + y + " " + z));
                        return 0;
                    });
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to load cubic virtual block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int cubicVirtualFillCube(CommandSourceStack source, int cubeX, int cubeY, int cubeZ, String blockName) {
        try {
            BlockState state = CubicTestDimensionService.parseMarkerBlock(blockName);
            CubicTestDimensionService.FillCubeResult result = CUBIC_TEST.fillVirtualCube(
                    source.getServer(), new CubePos(cubeX, cubeY, cubeZ), state);
            source.sendSuccess(() -> Component.literal("Cubic virtual cube filled: cube="
                    + formatCube(result.cubePos())
                    + ", blocks=" + result.blockCount()
                    + ", state=" + CubicTestDimensionService.blockStateName(result.state())), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to fill cubic virtual cube: " + exception.getMessage()));
            return 0;
        }
    }

    private static int cubicVirtualWriteProbe(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        try {
            CubicTestDimensionService.ProbeResult result = CUBIC_TEST.writeProbeCube(source.getServer(), new CubePos(cubeX, cubeY, cubeZ));
            source.sendSuccess(() -> Component.literal("Cubic test probe cube written: cube=" + formatCube(result.cubePos())), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to write cubic test probe cube: " + exception.getMessage()));
            return 0;
        }
    }

    private static int cubicVirtualVerifyProbe(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        try {
            CubicTestDimensionService.ProbeResult result = CUBIC_TEST.verifyProbeCube(source.getServer(), new CubePos(cubeX, cubeY, cubeZ));
            source.sendSuccess(() -> Component.literal("Cubic test probe cube verified: cube=" + formatCube(result.cubePos())), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to verify cubic test probe cube: " + exception.getMessage()));
            return 0;
        }
    }

    private static int cubicVirtualMaterialize(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        try {
            CubicTestDimensionService.MaterializeResult result = CUBIC_TEST.materializeVirtualCube(
                    source.getServer(), source.getLevel(), new CubePos(cubeX, cubeY, cubeZ));
            source.sendSuccess(() -> Component.literal("Cubic virtual cube materialized into current vanilla level: cube="
                    + formatCube(result.cubePos())
                    + ", changedBlocks=" + result.changedBlocks()), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to materialize cubic virtual cube: " + exception.getMessage()));
            return 0;
        }
    }

    private static String formatCube(CubePos cubePos) {
        return cubePos.x() + " " + cubePos.y() + " " + cubePos.z();
    }

    private RedlineWorldCoreCommands() {
    }
}
