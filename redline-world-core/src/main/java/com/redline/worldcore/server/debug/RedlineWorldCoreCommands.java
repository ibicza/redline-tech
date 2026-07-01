package com.redline.worldcore.server.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.redline.worldcore.RedlineWorldCore;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.pos.Region3DPos;
import com.redline.worldcore.api.ticket.CubeTicket;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
import com.redline.worldcore.api.ticket.CubeTicketShape;
import com.redline.worldcore.api.ticket.CubeTicketType;
import com.redline.worldcore.server.cube.CubeHolder;
import com.redline.worldcore.server.cube.CubeLoadingSnapshot;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.dimension.CubicTestDimensionService;
import com.redline.worldcore.server.ticket.CubeTicketDebugFormatter;
import com.redline.worldcore.server.ticket.CubeTicketManager;
import com.redline.worldcore.server.ticket.CubeTicketSnapshot;
import com.redline.worldcore.server.ticket.PlayerCubeTicketUpdater;
import com.redline.worldcore.server.ticket.WorldCoreTickets;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class RedlineWorldCoreCommands {
    private static final CubicDimensionSettings DEFAULTS = CubicDimensionSettings.defaults();
    private static final CubicTestDimensionService CUBIC_TEST = new CubicTestDimensionService();
    private static final CubeTicketManager TICKETS = WorldCoreTickets.MANAGER;

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rwc")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("cube")
                        .executes(context -> cube(context.getSource()))
                        .then(Commands.literal("pos")
                                .executes(context -> cube(context.getSource()))))
                .then(Commands.literal("selftest")
                        .executes(context -> selfTest(context.getSource())))
                .then(Commands.literal("storage")
                        .then(Commands.literal("selftest")
                                .executes(context -> storageSelfTest(context.getSource()))))
                .then(Commands.literal("cubes")
                        .then(Commands.literal("status")
                                .executes(context -> cubesStatus(context.getSource())))
                        .then(Commands.literal("list")
                                .executes(context -> cubesList(context.getSource())))
                        .then(Commands.literal("inspect")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .executes(context -> cubesInspect(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                IntegerArgumentType.getInteger(context, "cubeZ")
                                                        ))))))
                        .then(Commands.literal("save_all")
                                .executes(context -> cubesSaveAll(context.getSource())))
                        .then(Commands.literal("unload_all")
                                .executes(context -> cubesUnloadAll(context.getSource()))))
                .then(Commands.literal("tickets")
                        .then(Commands.literal("status")
                                .executes(context -> ticketsStatus(context.getSource())))
                        .then(Commands.literal("list")
                                .executes(context -> ticketsList(context.getSource())))
                        .then(Commands.literal("clear")
                                .executes(context -> ticketsClear(context.getSource())))
                        .then(Commands.literal("player_probe")
                                .executes(context -> ticketsPlayerProbe(context.getSource())))
                        .then(Commands.literal("level_at")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .executes(context -> ticketsLevelAt(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                IntegerArgumentType.getInteger(context, "cubeZ")
                                                        ))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("uuid", StringArgumentType.word())
                                        .executes(context -> ticketsRemove(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "uuid")
                                        ))))
                        .then(Commands.literal("add")
                                .then(Commands.literal("single")
                                        .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                        .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                                .then(Commands.argument("level", StringArgumentType.word())
                                                                        .executes(context -> ticketsAddSingle(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                                IntegerArgumentType.getInteger(context, "cubeZ"),
                                                                                StringArgumentType.getString(context, "level")
                                                                        )))))))
                                .then(Commands.literal("cuboid")
                                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                                .then(Commands.argument("y1", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("y2", IntegerArgumentType.integer())
                                                                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                                        .then(Commands.argument("level", StringArgumentType.word())
                                                                                                .executes(context -> ticketsAddCuboid(
                                                                                                        context.getSource(),
                                                                                                        IntegerArgumentType.getInteger(context, "x1"),
                                                                                                        IntegerArgumentType.getInteger(context, "y1"),
                                                                                                        IntegerArgumentType.getInteger(context, "z1"),
                                                                                                        IntegerArgumentType.getInteger(context, "x2"),
                                                                                                        IntegerArgumentType.getInteger(context, "y2"),
                                                                                                        IntegerArgumentType.getInteger(context, "z2"),
                                                                                                        StringArgumentType.getString(context, "level")
                                                                                                ))))))))))
                                .then(Commands.literal("column")
                                        .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .then(Commands.argument("minCubeY", IntegerArgumentType.integer())
                                                                .then(Commands.argument("maxCubeY", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("level", StringArgumentType.word())
                                                                                .executes(context -> ticketsAddColumn(
                                                                                        context.getSource(),
                                                                                        IntegerArgumentType.getInteger(context, "cubeX"),
                                                                                        IntegerArgumentType.getInteger(context, "cubeZ"),
                                                                                        IntegerArgumentType.getInteger(context, "minCubeY"),
                                                                                        IntegerArgumentType.getInteger(context, "maxCubeY"),
                                                                                        StringArgumentType.getString(context, "level")
                                                                                ))))))))))
                .then(Commands.literal("cubic_test")
                        .then(Commands.literal("status")
                                .executes(context -> cubicTestStatus(context.getSource())))
                        .then(Commands.literal("enter")
                                .executes(context -> cubicTestEnter(context.getSource())))
                        .then(Commands.literal("leave")
                                .executes(context -> cubicTestLeave(context.getSource())))
                        .then(Commands.literal("check_height")
                                .executes(context -> cubicTestCheckHeight(context.getSource())))
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


    private static int cubicTestLeave(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel overworld = source.getServer().overworld();
            player.teleportTo(overworld, 0.5, 100.0, 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
            source.sendSuccess(() -> Component.literal("Teleported back to minecraft:overworld"), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Failed to leave cubic test dimension: " + exception.getMessage()));
            return 0;
        }
    }

    private static int cubicTestCheckHeight(CommandSourceStack source) {
        ServerLevel current = source.getLevel();
        ServerLevel cubic = CUBIC_TEST.level(source.getServer()).orElse(null);

        source.sendSuccess(() -> Component.literal("Current dimension: " + current.dimension().identifier()), false);
        source.sendSuccess(() -> Component.literal("Current vanilla build Y: "
                + current.getMinY() + ".." + (current.getMaxY() - 1)
                + " height=" + current.getHeight()), false);

        if (cubic == null) {
            source.sendFailure(Component.literal("Cubic test dimension is not registered in this world."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Cubic test dimension: " + cubic.dimension().identifier()), false);
        source.sendSuccess(() -> Component.literal("Cubic test vanilla shell Y: "
                + cubic.getMinY() + ".." + (cubic.getMaxY() - 1)
                + " height=" + cubic.getHeight()), false);
        source.sendSuccess(() -> Component.literal("Cubic test generator: "
                + cubic.getChunkSource().getGenerator().getClass().getName()), false);
        source.sendSuccess(() -> Component.literal("Configured virtual cube storage Y: cubes "
                + CubicTestDimensionService.SETTINGS.minCubeY() + ".." + CubicTestDimensionService.SETTINGS.maxCubeY()
                + ", blocks " + CubicTestDimensionService.SETTINGS.minBlockY()
                + ".." + CubicTestDimensionService.SETTINGS.maxBlockY()), false);
        source.sendSuccess(() -> Component.literal("Vanilla legal dimension_type Y range in 26.2: "
                + DimensionType.MIN_Y + ".." + DimensionType.MAX_Y
                + " maxHeight=" + DimensionType.Y_SIZE), false);
        return 1;
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



    private static int cubesStatus(CommandSourceStack source) {
        ServerCubeCache cache = cubeCache(source);
        CubeLoadingSnapshot snapshot = cache.snapshot();
        source.sendSuccess(() -> Component.literal("Cube cache: loaded=" + snapshot.loadedCubes()
                + ", pending=" + snapshot.pendingLoads()
                + ", requestedLastTick=" + snapshot.requestedCubes()), false);
        source.sendSuccess(() -> Component.literal("Last tick: queued=" + snapshot.queuedLastTick()
                + ", loaded=" + snapshot.loadedLastTick()
                + ", unloaded=" + snapshot.unloadedLastTick()
                + ", requestLimitHit=" + snapshot.requestLimitHitLastTick()), false);
        source.sendSuccess(() -> Component.literal("Totals: loaded=" + snapshot.totalLoaded()
                + ", unloaded=" + snapshot.totalUnloaded()
                + ", saved=" + snapshot.totalSaved()), false);
        source.sendSuccess(() -> Component.literal("By ticket level: " + snapshot.byTicketLevel()), false);
        source.sendSuccess(() -> Component.literal("By cube status: " + snapshot.byCubeStatus()), false);
        source.sendSuccess(() -> Component.literal("By holder state: " + snapshot.byHolderState()), false);
        source.sendSuccess(() -> Component.literal("Storage root: " + cache.storageRoot()), false);
        return snapshot.loadedCubes();
    }

    private static int cubesList(CommandSourceStack source) {
        List<CubeHolder> holders = cubeCache(source).sortedHolders();
        if (holders.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Cube cache: no loaded holders"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Cube cache holders, showing " + Math.min(holders.size(), 25) + " of " + holders.size()), false);
        for (int i = 0; i < Math.min(holders.size(), 25); i++) {
            CubeHolder holder = holders.get(i);
            source.sendSuccess(() -> Component.literal(formatHolder(holder)), false);
        }
        if (holders.size() > 25) {
            source.sendSuccess(() -> Component.literal("More holders hidden. Use /rwc cubes inspect <cubeX> <cubeY> <cubeZ> for details."), false);
        }
        return holders.size();
    }

    private static int cubesInspect(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
        List<CubeTicket> covering = TICKETS.ticketsCovering(cubePos);
        source.sendSuccess(() -> Component.literal("Cube inspect: " + formatCube(cubePos)
                + " region=" + cubePos.regionPos().fileName()
                + " entry=" + Region3DPos.localIndex(cubePos)), false);
        source.sendSuccess(() -> Component.literal("Tickets covering: " + covering.size()
                + ", strongest=" + TICKETS.strongestLevelFor(cubePos).orElse(CubeTicketLevel.UNLOADED)), false);
        for (CubeTicket ticket : covering.stream().limit(8).toList()) {
            source.sendSuccess(() -> Component.literal("ticket " + CubeTicketDebugFormatter.formatTicket(ticket)), false);
        }
        return cubeCache(source).holder(cubePos)
                .map(holder -> {
                    source.sendSuccess(() -> Component.literal("Holder loaded: " + formatHolder(holder)), false);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendSuccess(() -> Component.literal("Holder is not loaded in M6 cache yet."), false);
                    return 0;
                });
    }

    private static int cubesSaveAll(CommandSourceStack source) {
        int saved = cubeCache(source).saveAllLoaded();
        source.sendSuccess(() -> Component.literal("Cube cache saved loaded holders to Region3D: saved=" + saved), false);
        return saved;
    }

    private static int cubesUnloadAll(CommandSourceStack source) {
        int unloaded = cubeCache(source).unloadAllLoaded(true);
        source.sendSuccess(() -> Component.literal("Cube cache unloaded all holders: unloaded=" + unloaded + ". Active tickets may reload them next tick."), false);
        return unloaded;
    }

    private static ServerCubeCache cubeCache(CommandSourceStack source) {
        return WorldCoreCubeLoading.cubicTestForServer(source.getServer());
    }

    private static String formatHolder(CubeHolder holder) {
        return "cube=" + formatCube(holder.cubePos())
                + " level=" + holder.ticketLevel()
                + " status=" + holder.cube().status()
                + " state=" + holder.state()
                + " dirty=" + holder.dirty()
                + " loadedAt=" + holder.loadedGameTime()
                + " lastRequired=" + holder.lastRequiredGameTime();
    }

    private static int ticketsStatus(CommandSourceStack source) {
        CubeTicketSnapshot snapshot = TICKETS.snapshot();
        source.sendSuccess(() -> Component.literal("Cube tickets: total=" + snapshot.totalTickets()
                + ", permanent=" + snapshot.permanentTickets()
                + ", temporary=" + snapshot.temporaryTickets()), false);
        source.sendSuccess(() -> Component.literal("Covered cube requests: " + snapshot.coveredCubeRequests()), false);
        source.sendSuccess(() -> Component.literal("By type: " + snapshot.byType()), false);
        source.sendSuccess(() -> Component.literal("By level: " + snapshot.byLevel()), false);
        return snapshot.totalTickets();
    }

    private static int ticketsList(CommandSourceStack source) {
        List<CubeTicket> tickets = TICKETS.sortedTickets();
        if (tickets.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Cube tickets: none"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Cube tickets list, showing " + Math.min(tickets.size(), 25) + " of " + tickets.size()), false);
        for (int i = 0; i < Math.min(tickets.size(), 25); i++) {
            CubeTicket ticket = tickets.get(i);
            source.sendSuccess(() -> Component.literal(CubeTicketDebugFormatter.formatTicket(ticket)), false);
        }
        if (tickets.size() > 25) {
            source.sendSuccess(() -> Component.literal("More tickets hidden. Use /rwc tickets clear if this is only debug noise."), false);
        }
        return tickets.size();
    }

    private static int ticketsClear(CommandSourceStack source) {
        int removed = TICKETS.clear();
        source.sendSuccess(() -> Component.literal("Cube tickets cleared: removed=" + removed), false);
        return removed;
    }

    private static int ticketsRemove(CommandSourceStack source, String uuidText) {
        try {
            UUID id = UUID.fromString(uuidText);
            boolean removed = TICKETS.remove(id);
            if (removed) {
                source.sendSuccess(() -> Component.literal("Cube ticket removed: " + id), false);
                return 1;
            }
            source.sendFailure(Component.literal("Cube ticket not found: " + id));
            return 0;
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("Invalid UUID: " + uuidText));
            return 0;
        }
    }

    private static int ticketsPlayerProbe(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            if (!PlayerCubeTicketUpdater.isCubicTestPlayer(player)) {
                boolean removed = PlayerCubeTicketUpdater.removePlayerTicket(player);
                source.sendSuccess(() -> Component.literal("Player is outside cubic_test; player cube ticket removed=" + removed), false);
                return removed ? 1 : 0;
            }

            CubeTicket ticket = PlayerCubeTicketUpdater.createPlayerTicket(player);
            TICKETS.add(ticket);
            source.sendSuccess(() -> Component.literal("Player cube ticket updated: " + CubeTicketDebugFormatter.formatTicket(ticket)), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Failed to create player cube ticket: " + exception.getMessage()));
            return 0;
        }
    }

    private static int ticketsLevelAt(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
        List<CubeTicket> covering = TICKETS.ticketsCovering(cubePos);
        if (covering.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No cube tickets cover cube " + formatCube(cubePos)), false);
            return 0;
        }
        CubeTicketLevel strongest = TICKETS.strongestLevelFor(cubePos).orElse(CubeTicketLevel.UNLOADED);
        source.sendSuccess(() -> Component.literal("Strongest level for cube " + formatCube(cubePos) + ": " + strongest
                + ", coveringTickets=" + covering.size()), false);
        for (CubeTicket ticket : covering.stream().limit(10).toList()) {
            source.sendSuccess(() -> Component.literal(CubeTicketDebugFormatter.formatTicket(ticket)), false);
        }
        return covering.size();
    }

    private static int ticketsAddSingle(CommandSourceStack source, int cubeX, int cubeY, int cubeZ, String levelName) {
        try {
            CubeTicket ticket = CubeTicket.permanent(
                    CubeTicketType.DEBUG,
                    parseTicketLevel(levelName),
                    CubeTicketShape.single(new CubePos(cubeX, cubeY, cubeZ)),
                    "debug:single-command"
            );
            TICKETS.add(ticket);
            source.sendSuccess(() -> Component.literal("Cube ticket added: " + CubeTicketDebugFormatter.formatTicket(ticket)), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to add single cube ticket: " + exception.getMessage()));
            return 0;
        }
    }

    private static int ticketsAddCuboid(CommandSourceStack source, int x1, int y1, int z1, int x2, int y2, int z2, String levelName) {
        try {
            CubeTicketShape shape = CubeTicketShape.cuboid(
                    new CubePos(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2)),
                    new CubePos(Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2))
            );
            CubeTicket ticket = CubeTicket.permanent(CubeTicketType.DEBUG, parseTicketLevel(levelName), shape, "debug:cuboid-command");
            TICKETS.add(ticket);
            source.sendSuccess(() -> Component.literal("Cube ticket added: " + CubeTicketDebugFormatter.formatTicket(ticket)), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to add cuboid cube ticket: " + exception.getMessage()));
            return 0;
        }
    }

    private static int ticketsAddColumn(CommandSourceStack source, int cubeX, int cubeZ, int minCubeY, int maxCubeY, String levelName) {
        try {
            CubeTicketShape shape = CubeTicketShape.verticalColumnRange(cubeX, cubeZ, minCubeY, maxCubeY);
            CubeTicket ticket = CubeTicket.permanent(CubeTicketType.DEBUG, parseTicketLevel(levelName), shape, "debug:column-command");
            TICKETS.add(ticket);
            source.sendSuccess(() -> Component.literal("Cube ticket added: " + CubeTicketDebugFormatter.formatTicket(ticket)), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to add vertical column cube ticket: " + exception.getMessage()));
            return 0;
        }
    }

    private static CubeTicketLevel parseTicketLevel(String levelName) {
        try {
            return CubeTicketLevel.valueOf(levelName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown ticket level '" + levelName + "'. Valid levels: "
                    + String.join(", ", java.util.Arrays.stream(CubeTicketLevel.values()).map(Enum::name).toList()));
        }
    }

    private static String formatCube(CubePos cubePos) {
        return cubePos.x() + " " + cubePos.y() + " " + cubePos.z();
    }

    private RedlineWorldCoreCommands() {
    }
}
