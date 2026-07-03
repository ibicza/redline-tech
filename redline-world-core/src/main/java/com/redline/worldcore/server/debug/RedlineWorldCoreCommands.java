package com.redline.worldcore.server.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.redline.worldcore.RedlineWorldCore;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.cube.CubeScheduledTickData;
import com.redline.worldcore.api.cube.CubeScheduledTickKind;
import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.server.generation.CubeGenerationDebug;
import com.redline.worldcore.server.generation.CubeGenerationProfiler;
import com.redline.worldcore.server.generation.CubeGenerationSummary;
import com.redline.worldcore.api.pos.ColumnPos;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.pos.Region3DPos;
import com.redline.worldcore.api.ticket.CubeTicket;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
import com.redline.worldcore.api.ticket.CubeTicketShape;
import com.redline.worldcore.api.ticket.CubeTicketType;
import com.redline.worldcore.server.cube.CubeHolder;
import com.redline.worldcore.server.cube.CubeLoadingSnapshot;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.compat.CubicClientSyncBridge;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.cube.access.CubeMutationContext;
import com.redline.worldcore.server.cube.access.CubeMutationResult;
import com.redline.worldcore.server.cube.access.CubeMutationSnapshot;
import com.redline.worldcore.server.cube.blockentity.CubeBlockEntityRef;
import com.redline.worldcore.server.cube.blockentity.CubeBlockEntitySnapshot;
import com.redline.worldcore.server.cube.dirty.CubeDirtySnapshot;
import com.redline.worldcore.server.cube.ownership.CubeOwnershipValidationSnapshot;
import com.redline.worldcore.server.cube.ownership.CubeOwnershipValidator;
import com.redline.worldcore.server.cube.tick.CubeScheduledTickSnapshot;
import com.redline.worldcore.server.dimension.CubicTestDimensionService;
import com.redline.worldcore.server.entity.EntityCubeTracker;
import com.redline.worldcore.server.entity.EntityRef;
import com.redline.worldcore.server.entity.EntityTrackingSnapshot;
import com.redline.worldcore.server.generation.CubeGenerationHasher;
import com.redline.worldcore.server.lighting.ColumnSkyIndex;
import com.redline.worldcore.server.lighting.CubeLightDebug;
import com.redline.worldcore.server.lighting.SkyLightLayer;
import com.redline.worldcore.server.lighting.SkyLightSummary;
import com.redline.worldcore.server.lighting.SkyLightTransferData;
import com.redline.worldcore.server.lighting.StaticBlockLightLayer;
import com.redline.worldcore.server.lighting.StaticLightSummary;
import com.redline.worldcore.server.pregen.AfkPregenController;
import com.redline.worldcore.server.pregen.AfkPregenSnapshot;
import com.redline.worldcore.server.pregen.ColumnVisitEntry;
import com.redline.worldcore.server.pregen.ColumnVisitSnapshot;
import com.redline.worldcore.server.pregen.ColumnVisitTracker;
import com.redline.worldcore.server.pregen.CubePregenBudget;
import com.redline.worldcore.server.pregen.CubePregenJob;
import com.redline.worldcore.server.pregen.CubePregenManager;
import com.redline.worldcore.server.pregen.CubePregenSnapshot;
import com.redline.worldcore.server.pregen.VerticalBackfillDaemon;
import com.redline.worldcore.server.pregen.VerticalBackfillSnapshot;
import com.redline.worldcore.server.ticket.CubeTicketDebugFormatter;
import com.redline.worldcore.server.ticket.CubeTicketManager;
import com.redline.worldcore.server.ticket.CubeTicketSnapshot;
import com.redline.worldcore.server.ticket.PlayerCubeTicketUpdater;
import com.redline.worldcore.server.ticket.WorldCoreTickets;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
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
                .then(Commands.literal("entity")
                        .then(Commands.literal("status")
                                .executes(context -> entityStatus(context.getSource())))
                        .then(Commands.literal("dump")
                                .then(Commands.literal("current")
                                        .executes(context -> entityDumpCurrent(context.getSource())))
                                .then(Commands.literal("busiest")
                                        .executes(context -> entityDumpBusiest(context.getSource()))))
                        .then(Commands.literal("reset")
                                .executes(context -> entityReset(context.getSource()))))
                .then(Commands.literal("selftest")
                        .executes(context -> selfTest(context.getSource())))
                .then(Commands.literal("storage")
                        .then(Commands.literal("selftest")
                                .executes(context -> storageSelfTest(context.getSource()))))
                .then(Commands.literal("client_sync")
                        .then(Commands.literal("status")
                                .executes(context -> clientSyncStatus(context.getSource())))
                        .then(Commands.literal("reset")
                                .executes(context -> clientSyncReset(context.getSource())))
                        .then(Commands.literal("counters")
                                .then(Commands.literal("reset")
                                        .executes(context -> clientSyncResetCounters(context.getSource()))))
                        .then(Commands.literal("overlay")
                                .then(Commands.literal("hidden")
                                        .executes(context -> clientSyncOverlay(context.getSource(), CubicClientSyncBridge.OVERLAY_HIDDEN)))
                                .then(Commands.literal("compact")
                                        .executes(context -> clientSyncOverlay(context.getSource(), CubicClientSyncBridge.OVERLAY_COMPACT)))
                                .then(Commands.literal("full")
                                        .executes(context -> clientSyncOverlay(context.getSource(), CubicClientSyncBridge.OVERLAY_FULL))))
                        .then(Commands.literal("configure")
                                .then(Commands.argument("horizontalRadius", IntegerArgumentType.integer(0, 10))
                                        .then(Commands.argument("verticalRadius", IntegerArgumentType.integer(0, 4))
                                                .then(Commands.argument("maxPerTick", IntegerArgumentType.integer(1, 16))
                                                        .then(Commands.argument("syncIntervalTicks", IntegerArgumentType.integer(1, 100))
                                                                .executes(context -> clientSyncConfigure(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "horizontalRadius"),
                                                                        IntegerArgumentType.getInteger(context, "verticalRadius"),
                                                                        IntegerArgumentType.getInteger(context, "maxPerTick"),
                                                                        IntegerArgumentType.getInteger(context, "syncIntervalTicks")
                                                                )))))))
                        .then(Commands.literal("configure_default")
                                .executes(context -> clientSyncConfigureDefault(context.getSource())))
                        .then(Commands.literal("persistence_check")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .executes(context -> clientSyncPersistenceCheck(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                IntegerArgumentType.getInteger(context, "cubeZ")
                                                        )))))))
                .then(Commands.literal("light")
                        .then(Commands.literal("status")
                                .executes(context -> lightStatus(context.getSource())))
                        .then(Commands.literal("summary")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .executes(context -> lightSummary(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                IntegerArgumentType.getInteger(context, "cubeZ")
                                                        ))))))
                        .then(Commands.literal("block")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .then(Commands.argument("localX", IntegerArgumentType.integer(0, 15))
                                                                .then(Commands.argument("localY", IntegerArgumentType.integer(0, 15))
                                                                        .then(Commands.argument("localZ", IntegerArgumentType.integer(0, 15))
                                                                                .executes(context -> lightBlock(
                                                                                        context.getSource(),
                                                                                        IntegerArgumentType.getInteger(context, "cubeX"),
                                                                                        IntegerArgumentType.getInteger(context, "cubeY"),
                                                                                        IntegerArgumentType.getInteger(context, "cubeZ"),
                                                                                        IntegerArgumentType.getInteger(context, "localX"),
                                                                                        IntegerArgumentType.getInteger(context, "localY"),
                                                                                        IntegerArgumentType.getInteger(context, "localZ")
                                                                                )))))))))
                        .then(Commands.literal("rebuild")
                                .then(Commands.literal("cube")
                                        .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                        .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                                .executes(context -> lightRebuildCube(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cubeX"),
                                                                        IntegerArgumentType.getInteger(context, "cubeY"),
                                                                        IntegerArgumentType.getInteger(context, "cubeZ")
                                                                ))))))
                                .then(Commands.literal("column")
                                        .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .then(Commands.argument("minCubeY", IntegerArgumentType.integer())
                                                                .then(Commands.argument("maxCubeY", IntegerArgumentType.integer())
                                                                        .executes(context -> lightRebuildColumn(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                                IntegerArgumentType.getInteger(context, "cubeZ"),
                                                                                IntegerArgumentType.getInteger(context, "minCubeY"),
                                                                                IntegerArgumentType.getInteger(context, "maxCubeY")
                                                                        )))))))))
                .then(Commands.literal("skylight")
                        .then(Commands.literal("status")
                                .executes(context -> skylightStatus(context.getSource())))
                        .then(Commands.literal("summary")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .executes(context -> skylightSummary(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                IntegerArgumentType.getInteger(context, "cubeZ")
                                                        ))))))
                        .then(Commands.literal("block")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .then(Commands.argument("localX", IntegerArgumentType.integer(0, 15))
                                                                .then(Commands.argument("localY", IntegerArgumentType.integer(0, 15))
                                                                        .then(Commands.argument("localZ", IntegerArgumentType.integer(0, 15))
                                                                                .executes(context -> skylightBlock(
                                                                                        context.getSource(),
                                                                                        IntegerArgumentType.getInteger(context, "cubeX"),
                                                                                        IntegerArgumentType.getInteger(context, "cubeY"),
                                                                                        IntegerArgumentType.getInteger(context, "cubeZ"),
                                                                                        IntegerArgumentType.getInteger(context, "localX"),
                                                                                        IntegerArgumentType.getInteger(context, "localY"),
                                                                                        IntegerArgumentType.getInteger(context, "localZ")
                                                                                )))))))))
                        .then(Commands.literal("column")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                .executes(context -> skylightColumn(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "cubeX"),
                                                        IntegerArgumentType.getInteger(context, "cubeZ")
                                                )))))
                        .then(Commands.literal("rebuild")
                                .then(Commands.literal("cube")
                                        .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                        .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                                .executes(context -> skylightRebuildCube(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cubeX"),
                                                                        IntegerArgumentType.getInteger(context, "cubeY"),
                                                                        IntegerArgumentType.getInteger(context, "cubeZ")
                                                                ))))))
                                .then(Commands.literal("column")
                                        .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .then(Commands.argument("minCubeY", IntegerArgumentType.integer())
                                                                .then(Commands.argument("maxCubeY", IntegerArgumentType.integer())
                                                                        .executes(context -> skylightRebuildColumn(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                                IntegerArgumentType.getInteger(context, "cubeZ"),
                                                                                IntegerArgumentType.getInteger(context, "minCubeY"),
                                                                                IntegerArgumentType.getInteger(context, "maxCubeY")
                                                                        )))))))))
                .then(Commands.literal("gen")
                        .then(Commands.literal("summary")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .executes(context -> genSummary(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                IntegerArgumentType.getInteger(context, "cubeZ")
                                                        ))))))
                        .then(Commands.literal("block")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .then(Commands.argument("localX", IntegerArgumentType.integer(0, 15))
                                                                .then(Commands.argument("localY", IntegerArgumentType.integer(0, 15))
                                                                        .then(Commands.argument("localZ", IntegerArgumentType.integer(0, 15))
                                                                                .executes(context -> genBlock(
                                                                                        context.getSource(),
                                                                                        IntegerArgumentType.getInteger(context, "cubeX"),
                                                                                        IntegerArgumentType.getInteger(context, "cubeY"),
                                                                                        IntegerArgumentType.getInteger(context, "cubeZ"),
                                                                                        IntegerArgumentType.getInteger(context, "localX"),
                                                                                        IntegerArgumentType.getInteger(context, "localY"),
                                                                                        IntegerArgumentType.getInteger(context, "localZ")
                                                                                )))))))))
                        .then(Commands.literal("column")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                .then(Commands.argument("minCubeY", IntegerArgumentType.integer())
                                                        .then(Commands.argument("maxCubeY", IntegerArgumentType.integer())
                                                                .executes(context -> genColumn(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "cubeX"),
                                                                        IntegerArgumentType.getInteger(context, "cubeZ"),
                                                                        IntegerArgumentType.getInteger(context, "minCubeY"),
                                                                        IntegerArgumentType.getInteger(context, "maxCubeY")
                                                                )))))))
                        .then(Commands.literal("verify")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .executes(context -> genVerify(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                IntegerArgumentType.getInteger(context, "cubeZ")
                                                        ))))))
                        .then(Commands.literal("verify_loaded")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .executes(context -> genVerifyLoaded(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                IntegerArgumentType.getInteger(context, "cubeZ")
                                                        ))))))
                        .then(Commands.literal("benchmark")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 12))
                                        .executes(context -> genBenchmark(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "radius")
                                        )))))
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
                .then(Commands.literal("cube_access")
                        .then(Commands.literal("status")
                                .executes(context -> cubeAccessStatus(context.getSource())))
                        .then(Commands.literal("reset_counters")
                                .executes(context -> cubeAccessResetCounters(context.getSource())))
                        .then(Commands.literal("get")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(context -> cubeAccessGet(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "z"),
                                                                false
                                                        ))))))
                        .then(Commands.literal("get_generated")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(context -> cubeAccessGet(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "z"),
                                                                true
                                                        ))))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("block", StringArgumentType.word())
                                                                .executes(context -> cubeAccessSet(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "x"),
                                                                        IntegerArgumentType.getInteger(context, "y"),
                                                                        IntegerArgumentType.getInteger(context, "z"),
                                                                        StringArgumentType.getString(context, "block")
                                                                ))))))))
                .then(Commands.literal("block_entities")
                        .then(Commands.literal("status")
                                .executes(context -> blockEntitiesStatus(context.getSource())))
                        .then(Commands.literal("get")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(context -> blockEntitiesGet(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "z")
                                                        ))))))
                        .then(Commands.literal("capture")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(context -> blockEntitiesCapture(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "z")
                                                        ))))))
                        .then(Commands.literal("dump_cube")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .executes(context -> blockEntitiesDumpCube(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                IntegerArgumentType.getInteger(context, "cubeZ")
                                                        )))))))
                .then(Commands.literal("scheduled_ticks")
                        .then(Commands.literal("status")
                                .executes(context -> scheduledTicksStatus(context.getSource())))
                        .then(Commands.literal("dump_cube")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .executes(context -> scheduledTicksDumpCube(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                IntegerArgumentType.getInteger(context, "cubeZ")
                                                        ))))))
                        .then(Commands.literal("clear_cube")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .executes(context -> scheduledTicksClearCube(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                IntegerArgumentType.getInteger(context, "cubeZ")
                                                        ))))))
                        .then(Commands.literal("add_block")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("target", StringArgumentType.word())
                                                                .then(Commands.argument("delayTicks", IntegerArgumentType.integer(0, 72000))
                                                                        .executes(context -> scheduledTicksAdd(
                                                                                context.getSource(),
                                                                                CubeScheduledTickKind.BLOCK,
                                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                                IntegerArgumentType.getInteger(context, "z"),
                                                                                StringArgumentType.getString(context, "target"),
                                                                                IntegerArgumentType.getInteger(context, "delayTicks")
                                                                        ))))))))
                        .then(Commands.literal("add_fluid")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("target", StringArgumentType.word())
                                                                .then(Commands.argument("delayTicks", IntegerArgumentType.integer(0, 72000))
                                                                        .executes(context -> scheduledTicksAdd(
                                                                                context.getSource(),
                                                                                CubeScheduledTickKind.FLUID,
                                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                                IntegerArgumentType.getInteger(context, "z"),
                                                                                StringArgumentType.getString(context, "target"),
                                                                                IntegerArgumentType.getInteger(context, "delayTicks")
                                                                        )))))))))
                .then(Commands.literal("ownership")
                        .then(Commands.literal("status")
                                .executes(context -> ownershipStatus(context.getSource())))
                        .then(Commands.literal("auto_test")
                                .executes(context -> CubeOwnershipAutoTest.start(context.getSource())))
                        .then(Commands.literal("auto_test_cancel")
                                .executes(context -> CubeOwnershipAutoTest.cancel(context.getSource())))
                        .then(Commands.literal("validate_current")
                                .executes(context -> ownershipValidateCurrent(context.getSource())))
                        .then(Commands.literal("validate")
                                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                                        .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                                                .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                                                        .executes(context -> ownershipValidate(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "cubeX"),
                                                                IntegerArgumentType.getInteger(context, "cubeY"),
                                                                IntegerArgumentType.getInteger(context, "cubeZ")
                                                        )))))))
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
                .then(Commands.literal("pregen")
                        .then(Commands.literal("status")
                                .executes(context -> pregenStatus(context.getSource())))
                        .then(Commands.literal("pause")
                                .executes(context -> pregenPause(context.getSource())))
                        .then(Commands.literal("resume")
                                .executes(context -> pregenResume(context.getSource())))
                        .then(Commands.literal("stop")
                                .executes(context -> pregenStop(context.getSource())))
                        .then(Commands.literal("clearqueue")
                                .executes(context -> pregenClearQueue(context.getSource())))
                        .then(Commands.literal("configure")
                                .then(Commands.argument("maxCubesPerTick", IntegerArgumentType.integer(1, CubePregenBudget.MAX_CUBES_PER_TICK_LIMIT))
                                        .then(Commands.argument("maxMillisPerTick", IntegerArgumentType.integer(1, CubePregenBudget.MAX_MILLIS_PER_TICK_LIMIT))
                                                .executes(context -> pregenConfigure(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "maxCubesPerTick"),
                                                        IntegerArgumentType.getInteger(context, "maxMillisPerTick")
                                                )))))
                        .then(Commands.literal("throttle")
                                .then(Commands.literal("status")
                                        .executes(context -> pregenThrottleStatus(context.getSource())))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("maxSkippedPerTick", IntegerArgumentType.integer(1, CubePregenBudget.MAX_SKIPPED_CUBES_PER_TICK_LIMIT))
                                                .then(Commands.argument("maxGeneratedPerSecond", IntegerArgumentType.integer(1, CubePregenBudget.MAX_GENERATED_CUBES_PER_SECOND_LIMIT))
                                                        .then(Commands.argument("expensiveCubeMs", IntegerArgumentType.integer(1, CubePregenBudget.MAX_EXPENSIVE_CUBE_MILLIS_LIMIT))
                                                                .then(Commands.argument("cooldownTicks", IntegerArgumentType.integer(0, CubePregenBudget.MAX_COOLDOWN_AFTER_EXPENSIVE_TICKS_LIMIT))
                                                                        .executes(context -> pregenThrottleSet(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "maxSkippedPerTick"),
                                                                                IntegerArgumentType.getInteger(context, "maxGeneratedPerSecond"),
                                                                                IntegerArgumentType.getInteger(context, "expensiveCubeMs"),
                                                                                IntegerArgumentType.getInteger(context, "cooldownTicks")
                                                                        ))))))))
                        .then(Commands.literal("save_state")
                                .executes(context -> pregenSaveState(context.getSource())))
                        .then(Commands.literal("start")
                                .then(Commands.literal("radius")
                                        .then(Commands.argument("blocks", IntegerArgumentType.integer(0, 2048))
                                                .then(Commands.argument("minY", IntegerArgumentType.integer())
                                                        .then(Commands.argument("maxY", IntegerArgumentType.integer())
                                                                .then(Commands.argument("status", StringArgumentType.word())
                                                                        .executes(context -> pregenStartRadius(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "blocks"),
                                                                                IntegerArgumentType.getInteger(context, "minY"),
                                                                                IntegerArgumentType.getInteger(context, "maxY"),
                                                                                StringArgumentType.getString(context, "status")
                                                                        )))))))
                                .then(Commands.literal("cuboid")
                                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                                .then(Commands.argument("y1", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("y2", IntegerArgumentType.integer())
                                                                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                                        .then(Commands.argument("status", StringArgumentType.word())
                                                                                                .executes(context -> pregenStartCuboid(
                                                                                                        context.getSource(),
                                                                                                        IntegerArgumentType.getInteger(context, "x1"),
                                                                                                        IntegerArgumentType.getInteger(context, "y1"),
                                                                                                        IntegerArgumentType.getInteger(context, "z1"),
                                                                                                        IntegerArgumentType.getInteger(context, "x2"),
                                                                                                        IntegerArgumentType.getInteger(context, "y2"),
                                                                                                        IntegerArgumentType.getInteger(context, "z2"),
                                                                                                        StringArgumentType.getString(context, "status")
                                                                                                ))))))))))))
                .then(Commands.literal("backfill")
                        .then(Commands.literal("status")
                                .executes(context -> backfillStatus(context.getSource())))
                        .then(Commands.literal("enable")
                                .executes(context -> backfillEnable(context.getSource(), true)))
                        .then(Commands.literal("disable")
                                .executes(context -> backfillEnable(context.getSource(), false)))
                        .then(Commands.literal("configure")
                                .then(Commands.argument("maxVerticalRadius", IntegerArgumentType.integer(0, 128))
                                        .then(Commands.argument("delayTicks", IntegerArgumentType.integer(0, 20 * 60 * 60))
                                                .then(Commands.argument("status", StringArgumentType.word())
                                                        .executes(context -> backfillConfigure(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "maxVerticalRadius"),
                                                                IntegerArgumentType.getInteger(context, "delayTicks"),
                                                                StringArgumentType.getString(context, "status")
                                                        ))))))
                        .then(Commands.literal("reset_visits")
                                .executes(context -> columnVisitedClear(context.getSource()))))
                .then(Commands.literal("column")
                        .then(Commands.literal("visited")
                                .then(Commands.literal("status")
                                        .executes(context -> columnVisitedStatus(context.getSource())))
                                .then(Commands.literal("current")
                                        .executes(context -> columnVisitedCurrent(context.getSource())))
                                .then(Commands.literal("dump")
                                        .executes(context -> columnVisitedDump(context.getSource(), 10)))
                                .then(Commands.literal("clear")
                                        .executes(context -> columnVisitedClear(context.getSource())))))
                .then(Commands.literal("afk_pregen")
                        .then(Commands.literal("status")
                                .executes(context -> afkPregenStatus(context.getSource())))
                        .then(Commands.literal("enable")
                                .executes(context -> afkPregenEnable(context.getSource(), true)))
                        .then(Commands.literal("disable")
                                .executes(context -> afkPregenEnable(context.getSource(), false)))
                        .then(Commands.literal("configure")
                                .then(Commands.argument("afkAfterSeconds", IntegerArgumentType.integer(1, 3600))
                                        .then(Commands.argument("radiusBlocks", IntegerArgumentType.integer(16, 1024))
                                                .then(Commands.argument("verticalRadiusCubes", IntegerArgumentType.integer(0, 64))
                                                        .then(Commands.argument("status", StringArgumentType.word())
                                                                .executes(context -> afkPregenConfigure(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "afkAfterSeconds"),
                                                                        IntegerArgumentType.getInteger(context, "radiusBlocks"),
                                                                        IntegerArgumentType.getInteger(context, "verticalRadiusCubes"),
                                                                        StringArgumentType.getString(context, "status")
                                                                ))))))))
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


    private static int pregenStatus(CommandSourceStack source) {
        CubePregenManager.MANAGER.ensurePersistentLoaded(source.getServer());
        CubePregenSnapshot snapshot = CubePregenManager.MANAGER.snapshot();
        source.sendSuccess(() -> Component.literal("RWC pregen: state=" + pregenState(snapshot)
                + ", queued=" + snapshot.queuedCubes()
                + ", active=" + snapshot.activeProcessedCubes() + "/" + snapshot.activeTotalCubes()
                + ", target=" + snapshot.targetStatus()
                + ", job=" + snapshot.activeJobId()), false);
        source.sendSuccess(() -> Component.literal("RWC pregen totals: jobsStarted=" + snapshot.totalStartedJobs()
                + ", jobsDone=" + snapshot.totalCompletedJobs()
                + ", processed=" + snapshot.totalProcessedCubes()
                + ", generated=" + snapshot.totalGeneratedCubes()
                + ", skipped=" + snapshot.totalSkippedCubes()
                + ", failed=" + snapshot.totalFailedCubes()), false);
        source.sendSuccess(() -> Component.literal("RWC pregen tick: processed=" + snapshot.lastTickProcessed()
                + ", generated=" + snapshot.lastTickGenerated()
                + ", skipped=" + snapshot.lastTickSkipped()
                + ", failed=" + snapshot.lastTickFailed()
                + ", us=" + snapshot.lastTickMicros()
                + ", maxUs=" + snapshot.maxTickMicros()
                + ", budget=" + snapshot.maxCubesPerTick() + "/t " + snapshot.maxMillisPerTick() + "ms"), false);
        source.sendSuccess(() -> Component.literal("RWC pregen throttle: skip=" + snapshot.maxSkippedCubesPerTick()
                + "/t, generated=" + snapshot.generatedThisSecond() + "/" + snapshot.maxGeneratedCubesPerSecond()
                + "/s, expensive=" + snapshot.expensiveCubeMillis()
                + "ms, cooldown=" + snapshot.throttleCooldownTicks()
                + "/" + snapshot.cooldownAfterExpensiveTicks()
                + "t, reason=" + snapshot.throttleReason()), false);
        source.sendSuccess(() -> Component.literal("RWC backfill/AFK: visitedColumns=" + snapshot.visitedColumns()
                + ", backfillPending=" + snapshot.backfillPendingColumns()
                + ", backfillJobs=" + snapshot.backfillJobsStarted()
                + ", afk=" + snapshot.afkPlayers() + "/" + snapshot.afkTrackedPlayers()
                + ", afkJobs=" + snapshot.afkJobsStarted()), false);
        if (snapshot.activeMin() != null && snapshot.activeMax() != null) {
            source.sendSuccess(() -> Component.literal("RWC pregen bounds: min=" + formatCube(snapshot.activeMin())
                    + ", max=" + formatCube(snapshot.activeMax())
                    + ", remaining=" + snapshot.remainingCubes()), false);
        }
        if (!snapshot.lastError().isBlank()) {
            source.sendFailure(Component.literal("RWC pregen last error: " + snapshot.lastError()));
        }
        return snapshot.queuedCubes();
    }

    private static int pregenStartRadius(CommandSourceStack source, int radiusBlocks, int minY, int maxY, String statusName) {
        try {
            Vec3 sourcePos = source.getPosition();
            int centerX = Mth.floor(sourcePos.x);
            int centerZ = Mth.floor(sourcePos.z);
            CubeStatus targetStatus = parseCubeStatus(statusName);
            CubePos min = CubePos.fromBlock(centerX - radiusBlocks, Math.min(minY, maxY), centerZ - radiusBlocks);
            CubePos max = CubePos.fromBlock(centerX + radiusBlocks, Math.max(minY, maxY), centerZ + radiusBlocks);
            return startPregen(source, CubePregenJob.cuboid(min, max, targetStatus, "manual:radius"));
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to start RWC radius pregen: " + exception.getMessage()));
            return 0;
        }
    }

    private static int pregenStartCuboid(CommandSourceStack source, int x1, int y1, int z1, int x2, int y2, int z2, String statusName) {
        try {
            CubeStatus targetStatus = parseCubeStatus(statusName);
            CubePos first = CubePos.fromBlock(x1, y1, z1);
            CubePos second = CubePos.fromBlock(x2, y2, z2);
            return startPregen(source, CubePregenJob.cuboid(first, second, targetStatus, "manual:cuboid"));
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to start RWC cuboid pregen: " + exception.getMessage()));
            return 0;
        }
    }

    private static int startPregen(CommandSourceStack source, CubePregenJob job) {
        CubePregenJob started = CubePregenManager.MANAGER.start(job, cubeCache(source).settings());
        CubePregenManager.MANAGER.savePersistentNow(source.getServer());
        source.sendSuccess(() -> Component.literal("RWC pregen started: job=" + started.shortId()
                + ", target=" + started.targetStatus()
                + ", cubes=" + started.totalCubes()
                + ", min=" + formatCube(started.min())
                + ", max=" + formatCube(started.max())), false);
        return Mth.clamp((int) Math.min(Integer.MAX_VALUE, started.totalCubes()), 0, Integer.MAX_VALUE);
    }

    private static int pregenPause(CommandSourceStack source) {
        boolean changed = CubePregenManager.MANAGER.pause();
        if (changed) {
            CubePregenManager.MANAGER.savePersistentNow(source.getServer());
            source.sendSuccess(() -> Component.literal("RWC pregen paused."), false);
            return 1;
        }
        source.sendFailure(Component.literal("No active RWC pregen job to pause."));
        return 0;
    }

    private static int pregenResume(CommandSourceStack source) {
        boolean changed = CubePregenManager.MANAGER.resume();
        if (changed) {
            CubePregenManager.MANAGER.savePersistentNow(source.getServer());
            source.sendSuccess(() -> Component.literal("RWC pregen resumed."), false);
            return 1;
        }
        source.sendFailure(Component.literal("No active RWC pregen job to resume."));
        return 0;
    }

    private static int pregenStop(CommandSourceStack source) {
        int removed = CubePregenManager.MANAGER.stop();
        CubePregenManager.MANAGER.savePersistentNow(source.getServer());
        source.sendSuccess(() -> Component.literal("RWC pregen stopped: removed=" + removed), false);
        return removed;
    }

    private static int pregenClearQueue(CommandSourceStack source) {
        int removed = CubePregenManager.MANAGER.clearQueue();
        CubePregenManager.MANAGER.savePersistentNow(source.getServer());
        source.sendSuccess(() -> Component.literal("RWC pregen queue cleared: removed=" + removed), false);
        return removed;
    }

    private static int pregenConfigure(CommandSourceStack source, int maxCubesPerTick, int maxMillisPerTick) {
        CubePregenManager.MANAGER.configureBudget(maxCubesPerTick, maxMillisPerTick);
        CubePregenManager.MANAGER.savePersistentNow(source.getServer());
        source.sendSuccess(() -> Component.literal("RWC pregen budget: maxCubesPerTick="
                + CubePregenManager.MANAGER.budget().maxCubesPerTick()
                + ", maxMillisPerTick=" + CubePregenManager.MANAGER.budget().maxMillisPerTick()), false);
        return maxCubesPerTick;
    }

    private static String pregenState(CubePregenSnapshot snapshot) {
        if (snapshot.running()) {
            return snapshot.paused() ? "paused" : "running";
        }
        return "idle";
    }


    private static int pregenThrottleStatus(CommandSourceStack source) {
        CubePregenSnapshot snapshot = CubePregenManager.MANAGER.snapshot();
        source.sendSuccess(() -> Component.literal("RWC pregen throttle: skip=" + snapshot.maxSkippedCubesPerTick()
                + "/t, generated=" + snapshot.generatedThisSecond() + "/" + snapshot.maxGeneratedCubesPerSecond()
                + "/s, expensive=" + snapshot.expensiveCubeMillis()
                + "ms, cooldown=" + snapshot.throttleCooldownTicks()
                + "/" + snapshot.cooldownAfterExpensiveTicks()
                + "t, reason=" + snapshot.throttleReason()), false);
        return snapshot.throttleCooldownTicks();
    }

    private static int pregenThrottleSet(CommandSourceStack source, int maxSkippedPerTick, int maxGeneratedPerSecond, int expensiveCubeMs, int cooldownTicks) {
        CubePregenManager.MANAGER.configureThrottle(maxSkippedPerTick, maxGeneratedPerSecond, expensiveCubeMs, cooldownTicks);
        CubePregenManager.MANAGER.savePersistentNow(source.getServer());
        source.sendSuccess(() -> Component.literal("RWC pregen throttle configured: maxSkippedPerTick=" + maxSkippedPerTick
                + ", maxGeneratedPerSecond=" + maxGeneratedPerSecond
                + ", expensiveCubeMs=" + expensiveCubeMs
                + ", cooldownTicks=" + cooldownTicks), false);
        return maxGeneratedPerSecond;
    }

    private static int pregenSaveState(CommandSourceStack source) {
        CubePregenManager.MANAGER.savePersistentNow(source.getServer());
        source.sendSuccess(() -> Component.literal("RWC pregen/backfill state saved."), false);
        return 1;
    }

    private static int backfillStatus(CommandSourceStack source) {
        VerticalBackfillSnapshot snapshot = VerticalBackfillDaemon.snapshot();
        source.sendSuccess(() -> Component.literal("RWC backfill: enabled=" + snapshot.enabled()
                + ", pendingColumns=" + snapshot.pendingColumns()
                + ", jobsStarted=" + snapshot.jobsStarted()
                + ", target=" + snapshot.targetStatus()
                + ", reason=" + snapshot.lastReason()), false);
        source.sendSuccess(() -> Component.literal("RWC backfill config: maxVerticalRadius=" + snapshot.maxVerticalRadius()
                + ", delayTicks=" + snapshot.delayTicks()), false);
        return snapshot.pendingColumns();
    }

    private static int backfillEnable(CommandSourceStack source, boolean enabled) {
        VerticalBackfillDaemon.setEnabled(enabled);
        CubePregenManager.MANAGER.markPersistentDirty();
        CubePregenManager.MANAGER.savePersistentNow(source.getServer());
        source.sendSuccess(() -> Component.literal("RWC backfill " + (enabled ? "enabled" : "disabled") + "."), false);
        return enabled ? 1 : 0;
    }

    private static int backfillConfigure(CommandSourceStack source, int maxVerticalRadius, int delayTicks, String statusName) {
        try {
            CubeStatus status = parseCubeStatus(statusName);
            VerticalBackfillDaemon.configure(VerticalBackfillDaemon.enabled(), maxVerticalRadius, delayTicks, status);
            CubePregenManager.MANAGER.markPersistentDirty();
            CubePregenManager.MANAGER.savePersistentNow(source.getServer());
            source.sendSuccess(() -> Component.literal("RWC backfill configured: maxVerticalRadius=" + maxVerticalRadius
                    + ", delayTicks=" + delayTicks
                    + ", target=" + status), false);
            return maxVerticalRadius;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to configure RWC backfill: " + exception.getMessage()));
            return 0;
        }
    }

    private static int columnVisitedStatus(CommandSourceStack source) {
        CubePos cubePos = CubePos.fromBlock(Mth.floor(source.getPosition().x), Mth.floor(source.getPosition().y), Mth.floor(source.getPosition().z));
        ColumnVisitSnapshot snapshot = ColumnVisitTracker.snapshot(cubePos);
        source.sendSuccess(() -> Component.literal("RWC visited columns: total=" + snapshot.visitedColumns()
                + ", backfillDone=" + snapshot.backfillDoneColumns()), false);
        if (snapshot.currentColumn() != null) {
            source.sendSuccess(() -> Component.literal("RWC current visited column: "
                    + snapshot.currentColumn().x() + " " + snapshot.currentColumn().z()
                    + ", y=" + snapshot.currentMinVisitedCubeY() + ".." + snapshot.currentMaxVisitedCubeY()
                    + ", lastY=" + snapshot.currentLastVisitedCubeY()
                    + ", visits=" + snapshot.currentVisits()), false);
        }
        return snapshot.visitedColumns();
    }

    private static int columnVisitedCurrent(CommandSourceStack source) {
        CubePos cubePos = CubePos.fromBlock(Mth.floor(source.getPosition().x), Mth.floor(source.getPosition().y), Mth.floor(source.getPosition().z));
        return ColumnVisitTracker.current(cubePos).map(entry -> {
            source.sendSuccess(() -> Component.literal("RWC current column visit: column=" + entry.columnPos().x() + " " + entry.columnPos().z()
                    + ", minY=" + entry.minVisitedCubeY()
                    + ", maxY=" + entry.maxVisitedCubeY()
                    + ", lastY=" + entry.lastVisitedCubeY()
                    + ", visits=" + entry.visits()
                    + ", nextBackfillStep=" + entry.nextBackfillStep()
                    + ", done=" + entry.backfillDone()), false);
            return 1;
        }).orElseGet(() -> {
            source.sendFailure(Component.literal("Current RWC column has not been visited by the M13 tracker yet."));
            return 0;
        });
    }

    private static int columnVisitedDump(CommandSourceStack source, int limit) {
        List<ColumnVisitEntry> entries = ColumnVisitTracker.recentEntries(limit);
        source.sendSuccess(() -> Component.literal("RWC visited column dump: shown=" + entries.size()), false);
        for (ColumnVisitEntry entry : entries) {
            source.sendSuccess(() -> Component.literal("  column=" + entry.columnPos().x() + " " + entry.columnPos().z()
                    + " y=" + entry.minVisitedCubeY() + ".." + entry.maxVisitedCubeY()
                    + " lastY=" + entry.lastVisitedCubeY()
                    + " visits=" + entry.visits()
                    + " next=" + entry.nextBackfillStep()
                    + " done=" + entry.backfillDone()), false);
        }
        return entries.size();
    }

    private static int columnVisitedClear(CommandSourceStack source) {
        int removed = ColumnVisitTracker.clear();
        CubePregenManager.MANAGER.markPersistentDirty();
        CubePregenManager.MANAGER.savePersistentNow(source.getServer());
        source.sendSuccess(() -> Component.literal("RWC visited columns cleared: removed=" + removed), false);
        return removed;
    }

    private static int afkPregenStatus(CommandSourceStack source) {
        AfkPregenSnapshot snapshot = AfkPregenController.snapshot();
        source.sendSuccess(() -> Component.literal("RWC AFK pregen: enabled=" + snapshot.enabled()
                + ", trackedPlayers=" + snapshot.trackedPlayers()
                + ", afkPlayers=" + snapshot.afkPlayers()
                + ", jobsStarted=" + snapshot.jobsStarted()
                + ", target=" + snapshot.targetStatus()
                + ", reason=" + snapshot.lastReason()), false);
        source.sendSuccess(() -> Component.literal("RWC AFK pregen config: after=" + snapshot.afkAfterTicks()
                + "t, radius=" + snapshot.radiusBlocks()
                + ", verticalRadius=" + snapshot.verticalRadiusCubes()), false);
        return snapshot.afkPlayers();
    }

    private static int afkPregenEnable(CommandSourceStack source, boolean enabled) {
        AfkPregenController.setEnabled(enabled);
        CubePregenManager.MANAGER.markPersistentDirty();
        CubePregenManager.MANAGER.savePersistentNow(source.getServer());
        source.sendSuccess(() -> Component.literal("RWC AFK pregen " + (enabled ? "enabled" : "disabled") + "."), false);
        return enabled ? 1 : 0;
    }

    private static int afkPregenConfigure(CommandSourceStack source, int afkAfterSeconds, int radiusBlocks, int verticalRadiusCubes, String statusName) {
        try {
            CubeStatus status = parseCubeStatus(statusName);
            AfkPregenController.configure(AfkPregenController.enabled(), afkAfterSeconds * 20, radiusBlocks, verticalRadiusCubes, status);
            CubePregenManager.MANAGER.markPersistentDirty();
            CubePregenManager.MANAGER.savePersistentNow(source.getServer());
            source.sendSuccess(() -> Component.literal("RWC AFK pregen configured: afterSeconds=" + afkAfterSeconds
                    + ", radiusBlocks=" + radiusBlocks
                    + ", verticalRadiusCubes=" + verticalRadiusCubes
                    + ", target=" + status), false);
            return radiusBlocks;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to configure RWC AFK pregen: " + exception.getMessage()));
            return 0;
        }
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

    private static int entityStatus(CommandSourceStack source) {
        CubePos playerCube = null;
        if (source.getEntity() != null) {
            playerCube = CubePos.fromBlock(source.getEntity().blockPosition());
        }
        EntityTrackingSnapshot snapshot = EntityCubeTracker.snapshot(playerCube);
        source.sendSuccess(() -> Component.literal("RWC entity tracker: trackedEntities="
                + snapshot.trackedEntities()
                + ", entitySections=" + snapshot.entitySections()
                + ", scannedLastTick=" + snapshot.scannedLastTick()
                + ", playerCube=" + snapshot.playerCubeString()
                + ", entitiesInPlayerCube=" + snapshot.entitiesInPlayerCube()), false);
        source.sendSuccess(() -> Component.literal("RWC entity kinds: " + snapshot.kindBreakdown()), false);
        source.sendSuccess(() -> Component.literal("RWC entity perf: " + snapshot.perfLine()), false);
        source.sendSuccess(() -> Component.literal("RWC entity movement: addedLastTick="
                + snapshot.addedLastTick()
                + ", movedLastTick=" + snapshot.movedLastTick()
                + ", removedLastTick=" + snapshot.removedLastTick()
                + ", totalAdded=" + snapshot.totalAdded()
                + ", totalMoved=" + snapshot.totalMoved()
                + ", totalRemoved=" + snapshot.totalRemoved()), false);
        source.sendSuccess(() -> Component.literal("RWC busiest entity cube: "
                + snapshot.busiestCubeString()
                + ", entities=" + snapshot.busiestCubeEntities()
                + ", lastTickGameTime=" + snapshot.lastTickGameTime()), false);
        return snapshot.trackedEntities();
    }

    private static int entityDumpCurrent(CommandSourceStack source) {
        if (source.getEntity() == null) {
            source.sendFailure(Component.literal("Entity dump current requires an entity/player source."));
            return 0;
        }
        CubePos playerCube = CubePos.fromBlock(source.getEntity().blockPosition());
        List<EntityRef> refs = EntityCubeTracker.dumpCurrentCube(playerCube);
        source.sendSuccess(() -> Component.literal("RWC entity dump current cube=" + formatCube(playerCube)
                + ", shown=" + refs.size()), false);
        sendEntityRefs(source, refs);
        return refs.size();
    }

    private static int entityDumpBusiest(CommandSourceStack source) {
        List<EntityRef> refs = EntityCubeTracker.dumpBusiestCube();
        String cube = refs.isEmpty() ? "none" : formatCube(refs.get(0).cubePos());
        source.sendSuccess(() -> Component.literal("RWC entity dump busiest cube=" + cube
                + ", shown=" + refs.size()), false);
        sendEntityRefs(source, refs);
        return refs.size();
    }

    private static void sendEntityRefs(CommandSourceStack source, List<EntityRef> refs) {
        if (refs.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  no tracked entities in this cube"), false);
            return;
        }
        for (EntityRef ref : refs) {
            source.sendSuccess(() -> Component.literal("  #" + ref.entityId()
                    + " " + ref.kind()
                    + " " + ref.type()
                    + " cube=" + formatCube(ref.cubePos())
                    + " pos=" + formatDecimal(ref.x()) + " " + formatDecimal(ref.y()) + " " + formatDecimal(ref.z())
                    + (ref.alwaysTicking() ? " alwaysTicking" : "")), false);
        }
    }

    private static int entityReset(CommandSourceStack source) {
        EntityCubeTracker.reset();
        source.sendSuccess(() -> Component.literal("RWC entity tracker reset."), false);
        return 1;
    }

    private static int clientSyncStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("RWC client sync bridge: trackedPlayers="
                + CubicClientSyncBridge.trackedPlayers()
                + ", materializedCubes=" + CubicClientSyncBridge.totalMaterializedCubes()
                + ", queuedMaterializations=" + CubicClientSyncBridge.totalQueuedMaterializations()), false);
        source.sendSuccess(() -> Component.literal("RWC stream window: horizontalRadius="
                + CubicClientSyncBridge.streamHorizontalRadius()
                + ", verticalRadius=" + CubicClientSyncBridge.streamVerticalRadius()
                + ", maxMaterializedCubesPerTick=" + CubicClientSyncBridge.maxMaterializedCubesPerTick()
                + ", syncPacketIntervalTicks=" + CubicClientSyncBridge.syncPacketIntervalTicks()
                + ", eagerLoadRadius=" + CubicClientSyncBridge.eagerLoadHorizontalRadius()
                + "/" + CubicClientSyncBridge.eagerLoadVerticalRadius()
                + ", maxEagerClientLoadsPerTick=" + CubicClientSyncBridge.maxEagerClientLoadsPerTick()), false);
        source.sendSuccess(() -> Component.literal("RWC overlay=" + CubicClientSyncBridge.overlayModeName()
                + ", counters: playerWritesSaved=" + CubicClientSyncBridge.playerWritesSaved()
                + ", materializerWritesIgnored=" + CubicClientSyncBridge.materializerWritesIgnored()
                + ", commandWritesSaved=" + CubicClientSyncBridge.commandWritesSaved()
                + ", clientInvalidationsQueued=" + CubicClientSyncBridge.clientInvalidationsQueued()
                + ", clientMirrorsCleaned=" + CubicClientSyncBridge.clientMirrorsCleaned()
                + ", forcedClientLoads=" + CubicClientSyncBridge.forcedClientLoads()
                + ", immediatePlayerCubeMaterializations=" + CubicClientSyncBridge.immediatePlayerCubeMaterializations()
                + ", eagerClientLoads=" + CubicClientSyncBridge.eagerClientLoads()
                + ", eagerClientGeneratedLoads=" + CubicClientSyncBridge.eagerClientGeneratedLoads()
                + ", eagerLastTick=" + CubicClientSyncBridge.eagerClientLoadsLastTick()
                + "/" + CubicClientSyncBridge.eagerClientGeneratedLastTick()), false);
        return CubicClientSyncBridge.trackedPlayers();
    }

    private static int clientSyncReset(CommandSourceStack source) {
        int reset = CubicClientSyncBridge.resetAll();
        source.sendSuccess(() -> Component.literal("RWC client sync bridge reset: clearedPlayerStates=" + reset), false);
        return reset;
    }

    private static int clientSyncResetCounters(CommandSourceStack source) {
        CubicClientSyncBridge.resetCounters();
        source.sendSuccess(() -> Component.literal("RWC client sync counters reset."), false);
        return 1;
    }

    private static int clientSyncOverlay(CommandSourceStack source, int mode) {
        CubicClientSyncBridge.setOverlayMode(mode);
        source.sendSuccess(() -> Component.literal("RWC client sync overlay mode: " + CubicClientSyncBridge.overlayModeName()), false);
        return 1;
    }

    private static int clientSyncConfigure(CommandSourceStack source, int horizontalRadius, int verticalRadius, int maxPerTick, int syncIntervalTicks) {
        CubicClientSyncBridge.configureStream(horizontalRadius, verticalRadius, maxPerTick, syncIntervalTicks);
        source.sendSuccess(() -> Component.literal("RWC stream configured: horizontalRadius="
                + CubicClientSyncBridge.streamHorizontalRadius()
                + ", verticalRadius=" + CubicClientSyncBridge.streamVerticalRadius()
                + ", maxMaterializedCubesPerTick=" + CubicClientSyncBridge.maxMaterializedCubesPerTick()
                + ", syncPacketIntervalTicks=" + CubicClientSyncBridge.syncPacketIntervalTicks()), false);
        return 1;
    }

    private static int clientSyncConfigureDefault(CommandSourceStack source) {
        CubicClientSyncBridge.resetStreamConfig();
        source.sendSuccess(() -> Component.literal("RWC stream config reset to defaults."), false);
        return clientSyncStatus(source);
    }

    private static int clientSyncPersistenceCheck(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        try {
            CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
            ServerCubeCache cache = cubeCache(source);
            CubeGenerationSummary generated = CubeGenerationSummary.from(cache.generateTemporary(cubePos));
            source.sendSuccess(() -> Component.literal("RWC persistence check: cube=" + formatCube(cubePos)), false);
            source.sendSuccess(() -> Component.literal("Generated baseline: " + generated.oneLine()), false);

            cache.holder(cubePos).ifPresentOrElse(holder -> {
                CubeGenerationSummary loaded = CubeGenerationSummary.from(holder.cube());
                source.sendSuccess(() -> Component.literal("Loaded holder: " + formatHolder(holder)), false);
                source.sendSuccess(() -> Component.literal("Loaded summary: " + loaded.oneLine()), false);
                source.sendSuccess(() -> Component.literal("Loaded differs from generated baseline: " + !loaded.sameGeneratedData(generated)), false);
            }, () -> source.sendSuccess(() -> Component.literal("Loaded holder: none"), false));

            return cache.readPersisted(cubePos)
                    .map(cube -> {
                        CubeGenerationSummary persisted = CubeGenerationSummary.from(cube);
                        source.sendSuccess(() -> Component.literal("Persisted Region3D cube: yes"), false);
                        source.sendSuccess(() -> Component.literal("Persisted summary: " + persisted.oneLine()), false);
                        source.sendSuccess(() -> Component.literal("Persisted differs from generated baseline: " + !persisted.sameGeneratedData(generated)), false);
                        return 1;
                    })
                    .orElseGet(() -> {
                        source.sendSuccess(() -> Component.literal("Persisted Region3D cube: no"), false);
                        return 0;
                    });
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to run RWC persistence check: " + exception.getMessage()));
            return 0;
        }
    }

    private static int lightStatus(CommandSourceStack source) {
        ServerCubeCache cache = cubeCache(source);
        CubeLoadingSnapshot snapshot = cache.snapshot();
        source.sendSuccess(() -> Component.literal("M9 static light: totalRebuilt=" + snapshot.totalLightRebuilt()
                + ", rebuiltLastTick=" + snapshot.lightRebuiltLastTick()
                + ", dirtyQueue=" + snapshot.lightDirtyQueue()), false);
        source.sendSuccess(() -> Component.literal("Cube cache: loaded=" + snapshot.loadedCubes()
                + ", pending=" + snapshot.pendingLoads()
                + ", requested=" + snapshot.requestedCubes()), false);
        source.sendSuccess(() -> Component.literal("MVP scope: cube-local block light only; cross-cube propagation and vanilla visual injection are later work."), false);
        return snapshot.lightDirtyQueue();
    }

    private static int lightSummary(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        try {
            CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
            CubeLightDebug.LightSourceCube sourceCube = new CubeLightDebug(cubeCache(source)).cubeForRead(cubePos);
            StaticLightSummary summary = StaticLightSummary.from(sourceCube.cube());
            source.sendSuccess(() -> Component.literal("M9 light summary: cube=" + formatCube(cubePos)
                    + " source=" + sourceCube.source()), false);
            source.sendSuccess(() -> Component.literal(summary.oneLine()), false);
            return summary.litBlocks();
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to summarize static light: " + exception.getMessage()));
            return 0;
        }
    }

    private static int lightBlock(CommandSourceStack source, int cubeX, int cubeY, int cubeZ, int localX, int localY, int localZ) {
        try {
            CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
            CubeLightDebug.LightSourceCube sourceCube = new CubeLightDebug(cubeCache(source)).cubeForRead(cubePos);
            BlockState state = sourceCube.cube().getBlockState(localX, localY, localZ);
            int blockLight = sourceCube.cube().getBlockLight(localX, localY, localZ);
            int emission = StaticBlockLightLayer.emission(state);
            int dampening = StaticBlockLightLayer.lightDrop(state);
            int worldX = cubePos.minBlockX() + localX;
            int worldY = cubePos.minBlockY() + localY;
            int worldZ = cubePos.minBlockZ() + localZ;
            source.sendSuccess(() -> Component.literal("M9 light block: cube=" + formatCube(cubePos)
                    + " local=" + localX + " " + localY + " " + localZ
                    + " source=" + sourceCube.source()), false);
            source.sendSuccess(() -> Component.literal("World block: " + worldX + " " + worldY + " " + worldZ
                    + ", state=" + CubeGenerationHasher.blockStateDebugName(state)
                    + ", blockLight=" + blockLight
                    + ", emission=" + emission
                    + ", lightDrop=" + dampening), false);
            return blockLight;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to inspect static light block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int lightRebuildCube(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        try {
            CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
            return cubeCache(source).rebuildLight(cubePos, true)
                    .map(result -> {
                        source.sendSuccess(() -> Component.literal("M9 static light rebuilt and saved: " + result.oneLine()), false);
                        return 1;
                    })
                    .orElseGet(() -> {
                        source.sendFailure(Component.literal("Cube is outside cubic settings: " + formatCube(cubePos)));
                        return 0;
                    });
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to rebuild static light: " + exception.getMessage()));
            return 0;
        }
    }

    private static int lightRebuildColumn(CommandSourceStack source, int cubeX, int cubeZ, int minCubeY, int maxCubeY) {
        try {
            int minY = Math.min(minCubeY, maxCubeY);
            int maxY = Math.max(minCubeY, maxCubeY);
            int count = maxY - minY + 1;
            if (count > 64) {
                source.sendFailure(Component.literal("Light rebuild column range is too large: " + count + " cubes. Max is 64."));
                return 0;
            }

            ServerCubeCache cache = cubeCache(source);
            int rebuilt = 0;
            source.sendSuccess(() -> Component.literal("M9 static light rebuild column: cubeX=" + cubeX
                    + ", cubeZ=" + cubeZ + ", cubeY=" + minY + ".." + maxY), false);
            for (int cubeY = minY; cubeY <= maxY; cubeY++) {
                CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
                StaticBlockLightLayer.RebuildResult result = cache.rebuildLight(cubePos, true).orElse(null);
                if (result == null) {
                    continue;
                }
                rebuilt++;
                final int lineCubeY = cubeY;
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "%4d %s", lineCubeY, result.summary().oneLine())), false);
            }
            return rebuilt;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to rebuild static light column: " + exception.getMessage()));
            return 0;
        }
    }


    private static int skylightStatus(CommandSourceStack source) {
        ServerCubeCache cache = cubeCache(source);
        CubeLoadingSnapshot snapshot = cache.snapshot();
        source.sendSuccess(() -> Component.literal("M10.1 sky light: totalRebuiltCubes=" + snapshot.totalSkyLightRebuilt()
                + ", totalColumns=" + snapshot.totalSkyLightColumnsRebuilt()
                + ", rebuiltColumnsLastTick=" + snapshot.skyLightColumnsLastTick()
                + ", dirtyColumns=" + snapshot.skyLightDirtyColumns()), false);
        source.sendSuccess(() -> Component.literal("M10.1 sky budget: autoColumnsPerTick=" + snapshot.skyLightAutoColumnsPerTick()
                + ", debounceTicks=" + snapshot.skyLightDirtyDelayTicks()
                + ", changedLastTick=" + snapshot.skyLightChangedLastTick()
                + ", skippedLastTick=" + snapshot.skyLightSkippedUnchangedLastTick()
                + ", savedLastTick=" + snapshot.skyLightSavedChangedLastTick()), false);
        source.sendSuccess(() -> Component.literal("M10.1 sky timing: lastUs=" + snapshot.skyLightRebuildMicrosLastTick()
                + ", maxUs=" + snapshot.skyLightRebuildMicrosMax()
                + ", totalSkipped=" + snapshot.totalSkyLightSkippedUnchanged()
                + ", totalSaved=" + snapshot.totalSkyLightSavedChanged()), false);
        source.sendSuccess(() -> Component.literal("Cube cache: loaded=" + snapshot.loadedCubes()
                + ", pending=" + snapshot.pendingLoads()
                + ", requested=" + snapshot.requestedCubes()), false);
        source.sendSuccess(() -> Component.literal("MVP scope: finite-top vertical sky light over loaded columns; M10.1 throttles automatic rebuilds, while side tunnels and vanilla visual injection are later work."), false);
        return snapshot.skyLightDirtyColumns();
    }

    private static int skylightSummary(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        try {
            CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
            CubeLightDebug.LightSourceCube sourceCube = new CubeLightDebug(cubeCache(source)).cubeForRead(cubePos);
            SkyLightSummary summary = SkyLightSummary.from(sourceCube.cube());
            SkyLightTransferData transfer = SkyLightTransferData.fromCube(sourceCube.cube());
            source.sendSuccess(() -> Component.literal("M10.1 sky light summary: cube=" + formatCube(cubePos)
                    + " source=" + sourceCube.source()), false);
            source.sendSuccess(() -> Component.literal(summary.oneLine()), false);
            source.sendSuccess(() -> Component.literal("Sky transfer: " + transfer.oneLine()), false);
            return summary.litBlocks();
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to summarize sky light: " + exception.getMessage()));
            return 0;
        }
    }

    private static int skylightBlock(CommandSourceStack source, int cubeX, int cubeY, int cubeZ, int localX, int localY, int localZ) {
        try {
            CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
            CubeLightDebug.LightSourceCube sourceCube = new CubeLightDebug(cubeCache(source)).cubeForRead(cubePos);
            BlockState state = sourceCube.cube().getBlockState(localX, localY, localZ);
            int skyLight = sourceCube.cube().getSkyLight(localX, localY, localZ);
            int skyDrop = SkyLightLayer.skyDrop(state);
            int worldX = cubePos.minBlockX() + localX;
            int worldY = cubePos.minBlockY() + localY;
            int worldZ = cubePos.minBlockZ() + localZ;
            source.sendSuccess(() -> Component.literal("M10.1 sky light block: cube=" + formatCube(cubePos)
                    + " local=" + localX + " " + localY + " " + localZ
                    + " source=" + sourceCube.source()), false);
            source.sendSuccess(() -> Component.literal("World block: " + worldX + " " + worldY + " " + worldZ
                    + ", state=" + CubeGenerationHasher.blockStateDebugName(state)
                    + ", skyLight=" + skyLight
                    + ", skyDrop=" + skyDrop
                    + ", skyOpaque=" + SkyLightLayer.skyOpaque(state)), false);
            return skyLight;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to inspect sky light block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int skylightColumn(CommandSourceStack source, int cubeX, int cubeZ) {
        try {
            ColumnPos columnPos = new ColumnPos(cubeX, cubeZ);
            return cubeCache(source).columnSkyIndex(columnPos)
                    .map(index -> {
                        source.sendSuccess(() -> Component.literal("M10.1 ColumnSkyIndex: " + index.oneLine()), false);
                        source.sendSuccess(() -> Component.literal("Sample local 8 8: topOpaqueWorldY=" + formatOptionalWorldY(index.topOpaqueWorldY(8, 8))
                                + ", firstBlockedCubeY=" + formatOptionalCubeY(index.firstSkyBlockedCubeY(8, 8))
                                + ", bottomSky=" + index.bottomSkyLevel(8, 8)), false);
                        return index.indexedCubes();
                    })
                    .orElseGet(() -> {
                        source.sendFailure(Component.literal("No loaded cubes in sky column " + cubeX + " " + cubeZ));
                        return 0;
                    });
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to build ColumnSkyIndex: " + exception.getMessage()));
            return 0;
        }
    }

    private static int skylightRebuildCube(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        try {
            CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
            return cubeCache(source).rebuildSkyLightCube(cubePos, true)
                    .map(result -> {
                        source.sendSuccess(() -> Component.literal("M10.1 sky light rebuilt and saved as single open-sky cube: " + result.oneLine()), false);
                        return 1;
                    })
                    .orElseGet(() -> {
                        source.sendFailure(Component.literal("Cube is outside cubic settings: " + formatCube(cubePos)));
                        return 0;
                    });
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to rebuild sky light cube: " + exception.getMessage()));
            return 0;
        }
    }

    private static int skylightRebuildColumn(CommandSourceStack source, int cubeX, int cubeZ, int minCubeY, int maxCubeY) {
        try {
            int minY = Math.min(minCubeY, maxCubeY);
            int maxY = Math.max(minCubeY, maxCubeY);
            int count = maxY - minY + 1;
            if (count > 96) {
                source.sendFailure(Component.literal("Sky light rebuild column range is too large: " + count + " cubes. Max is 96."));
                return 0;
            }

            ColumnPos columnPos = new ColumnPos(cubeX, cubeZ);
            return cubeCache(source).rebuildSkyLightColumn(columnPos, minY, maxY, true)
                    .map(result -> {
                        source.sendSuccess(() -> Component.literal("M10.1 sky light rebuilt and saved column: cubeX=" + cubeX
                                + ", cubeZ=" + cubeZ + ", cubeY=" + minY + ".." + maxY), false);
                        source.sendSuccess(() -> Component.literal(result.oneLine()), false);
                        cubeCache(source).columnSkyIndex(columnPos).ifPresent(index ->
                                source.sendSuccess(() -> Component.literal("ColumnSkyIndex: " + index.oneLine()), false));
                        return result.rebuiltCubes();
                    })
                    .orElseGet(() -> {
                        source.sendFailure(Component.literal("No cubes rebuilt for sky column " + cubeX + " " + cubeZ));
                        return 0;
                    });
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to rebuild sky light column: " + exception.getMessage()));
            return 0;
        }
    }

    private static String formatOptionalWorldY(int worldY) {
        return worldY == ColumnSkyIndex.NO_WORLD_Y ? "none" : Integer.toString(worldY);
    }

    private static String formatOptionalCubeY(int cubeY) {
        return cubeY == ColumnSkyIndex.NO_CUBE_Y ? "none" : Integer.toString(cubeY);
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
            CubicClientSyncBridge.recordCommandWriteSaved();
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
            CubicClientSyncBridge.recordCommandWriteSaved();
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
            CubicClientSyncBridge.recordCommandWriteSaved();
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



    private static int cubeAccessStatus(CommandSourceStack source) {
        CubeMutationSnapshot snapshot = cubeCache(source).access().mutationSnapshot();
        source.sendSuccess(() -> Component.literal(formatMutationSnapshot("RWC cube access", snapshot)), false);
        source.sendSuccess(() -> Component.literal("RWC cube access totals: statusPromoted=" + snapshot.totalStatusPromoted()
                + ", holderLoaded=" + snapshot.totalHolderLoaded()
                + ", holderGenerated=" + snapshot.totalHolderGenerated()
                + ", savedImmediate=" + snapshot.totalSaved()
                + ", staticLightImmediate=" + snapshot.totalStaticLightRebuilt()
                + ", skyQueued=" + snapshot.totalSkyLightQueued()
                + ", skyRebuiltImmediate=" + snapshot.totalSkyLightRebuilt()), false);
        CubeDirtySnapshot dirty = cubeCache(source).snapshot().dirtySnapshot();
        source.sendSuccess(() -> Component.literal(formatDirtySnapshot("RWC dirty pipeline", dirty)), false);
        source.sendSuccess(() -> Component.literal(formatDirtyTotals("RWC dirty totals", dirty)), false);
        source.sendSuccess(() -> Component.literal(formatBlockEntitySnapshot("RWC cube block entities", cubeCache(source).blockEntitySnapshot())), false);
        source.sendSuccess(() -> Component.literal(formatScheduledTickSnapshot("RWC scheduled ticks", cubeCache(source).scheduledTickSnapshot())), false);
        return (int) Math.min(Integer.MAX_VALUE, snapshot.totalMutations());
    }

    private static int cubeAccessResetCounters(CommandSourceStack source) {
        cubeCache(source).resetMutationCounters();
        source.sendSuccess(() -> Component.literal("RWC cube access mutation counters reset."), false);
        return 1;
    }

    private static int cubeAccessGet(CommandSourceStack source, int x, int y, int z, boolean generateIfMissing) {
        BlockPos pos = new BlockPos(x, y, z);
        CubePos cubePos = CubePos.fromBlock(pos);
        try {
            Optional<BlockState> state = generateIfMissing
                    ? cubeCache(source).access().getOrGenerateBlockState(pos)
                    : cubeCache(source).access().getBlockState(pos);
            if (state.isEmpty()) {
                source.sendFailure(Component.literal("RWC cube access get: no stored cube block at pos=" + formatBlock(pos)
                        + ", cube=" + formatCube(cubePos) + ". Use get_generated to preview generator output."));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("RWC cube access get: pos=" + formatBlock(pos)
                    + ", cube=" + formatCube(cubePos)
                    + ", local=" + CubePos.local(pos.getX()) + " " + CubePos.local(pos.getY()) + " " + CubePos.local(pos.getZ())
                    + ", block=" + CubicTestDimensionService.blockStateName(state.get())
                    + ", source=" + (generateIfMissing ? "stored_or_generator" : "stored_only")), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to read through RWC cube access: " + exception.getMessage()));
            return 0;
        }
    }

    private static int cubeAccessSet(CommandSourceStack source, int x, int y, int z, String blockName) {
        BlockPos pos = new BlockPos(x, y, z);
        try {
            BlockState state = CubicTestDimensionService.parseMarkerBlock(blockName);
            CubeMutationResult result = cubeCache(source).access().setBlockState(
                    pos,
                    state,
                    CubeMutationContext.command(true).withReason("cube_access_set_command")
            );
            if (result.applied() && (result.changed() || result.statusPromoted())) {
                CubicClientSyncBridge.recordCommandWriteSaved();
            }
            source.sendSuccess(() -> Component.literal(formatMutationResult("RWC cube access set", result)), false);
            if (!result.applied()) {
                return 0;
            }
            return result.changed() || result.statusPromoted() ? 1 : 0;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to write through RWC cube access: " + exception.getMessage()));
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
                + ", generated=" + snapshot.generatedLastTick()
                + ", lightRebuilt=" + snapshot.lightRebuiltLastTick()
                + ", lightDirtyQueue=" + snapshot.lightDirtyQueue()
                + ", skyColumns=" + snapshot.skyLightColumnsLastTick()
                + ", skyDirtyColumns=" + snapshot.skyLightDirtyColumns()
                + ", unloaded=" + snapshot.unloadedLastTick()
                + ", requestLimitHit=" + snapshot.requestLimitHitLastTick()), false);
        source.sendSuccess(() -> Component.literal("Load budget: us=" + snapshot.loadMicrosLastTick()
                + ", maxUs=" + snapshot.loadMicrosMax()
                + ", maxLoadsPerTick=" + snapshot.maxLoadsPerTick()
                + ", maxGeneratedPerTick=" + snapshot.maxGeneratedLoadsPerTick()
                + ", maxLoadMicros=" + snapshot.maxLoadMicrosPerTick()
                + ", generatedBudgetHit=" + snapshot.loadGeneratedBudgetHitLastTick()
                + ", timeBudgetHit=" + snapshot.loadTimeBudgetHitLastTick()), false);
        source.sendSuccess(() -> Component.literal(formatMutationSnapshot("Cube access", snapshot.mutationSnapshot())), false);
        source.sendSuccess(() -> Component.literal(formatDirtySnapshot("Dirty pipeline", snapshot.dirtySnapshot())), false);
        source.sendSuccess(() -> Component.literal(formatDirtyTotals("Dirty totals", snapshot.dirtySnapshot())), false);
        source.sendSuccess(() -> Component.literal(formatBlockEntitySnapshot("Block entities", snapshot.blockEntitySnapshot())), false);
        source.sendSuccess(() -> Component.literal(formatScheduledTickSnapshot("Scheduled ticks", snapshot.scheduledTickSnapshot())), false);
        source.sendSuccess(() -> Component.literal("Totals: loaded=" + snapshot.totalLoaded()
                + ", generated=" + snapshot.totalGenerated()
                + ", lightRebuilt=" + snapshot.totalLightRebuilt()
                + ", skyRebuiltCubes=" + snapshot.totalSkyLightRebuilt()
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
                    CubeGenerationSummary summary = CubeGenerationSummary.from(holder.cube());
                    StaticLightSummary light = StaticLightSummary.from(holder.cube());
                    SkyLightSummary sky = SkyLightSummary.from(holder.cube());
                    source.sendSuccess(() -> Component.literal("Generation summary: " + summary.oneLine()), false);
                    source.sendSuccess(() -> Component.literal("Static light summary: " + light.oneLine()), false);
                    source.sendSuccess(() -> Component.literal("Sky light summary: " + sky.oneLine()), false);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendSuccess(() -> Component.literal("Holder is not loaded in M6 cache yet."), false);
                    return 0;
                });
    }

    private static int cubesSaveAll(CommandSourceStack source) {
        ServerCubeCache cache = cubeCache(source);
        int before = cache.debugSaveBacklog();
        int saved = cache.saveAllLoaded();
        int after = cache.debugSaveBacklog();
        source.sendSuccess(() -> Component.literal("Cube cache save_all DEBUG/BUDGETED sync flush: saved=" + saved
                + ", dirtyBefore=" + before
                + ", dirtyAfter=" + after
                + ", maxPerCall=" + ServerCubeCache.DEBUG_SAVE_ALL_MAX_SYNC_CUBES_PER_CALL
                + ". Normal gameplay uses async/idle dirty IO; run again only if you intentionally want more debug flush."), false);
        return saved;
    }

    private static int cubesUnloadAll(CommandSourceStack source) {
        ServerCubeCache cache = cubeCache(source);
        int dirtyBefore = cache.debugSaveBacklog();
        int unloaded = cache.unloadAllLoaded(true);
        int dirtyAfter = cache.debugSaveBacklog();
        source.sendSuccess(() -> Component.literal("Cube cache unloaded clean holders: unloaded=" + unloaded
                + ", dirtySkippedBefore=" + dirtyBefore
                + ", dirtyStillLoaded=" + dirtyAfter
                + ". Active tickets may reload clean holders next tick; dirty holders stay loaded until saved."), false);
        return unloaded;
    }


    private static int ownershipStatus(CommandSourceStack source) {
        CubeLoadingSnapshot snapshot = cubeCache(source).snapshot();
        source.sendSuccess(() -> Component.literal("RWC ownership status M14.9: loaded=" + snapshot.loadedCubes()
                + ", holders=" + snapshot.byHolderState()
                + ", tickets=" + snapshot.byTicketLevel()), false);
        source.sendSuccess(() -> Component.literal(formatDirtySnapshot("Ownership dirty", snapshot.dirtySnapshot())), false);
        source.sendSuccess(() -> Component.literal(formatBlockEntitySnapshot("Ownership BE", snapshot.blockEntitySnapshot())), false);
        source.sendSuccess(() -> Component.literal(formatScheduledTickSnapshot("Ownership ticks", snapshot.scheduledTickSnapshot())), false);
        source.sendSuccess(() -> Component.literal("Ownership note: this is internal validation only; no public mod API is exposed in M14."), false);
        return snapshot.loadedCubes();
    }

    private static int ownershipValidateCurrent(CommandSourceStack source) {
        if (source.getEntity() == null) {
            source.sendFailure(Component.literal("ownership validate_current requires an entity/player source."));
            return 0;
        }
        CubePos cubePos = CubePos.fromBlock(source.getEntity().blockPosition());
        return ownershipValidate(source, cubePos.x(), cubePos.y(), cubePos.z());
    }

    private static int ownershipValidate(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
        CubeOwnershipValidationSnapshot validation = new CubeOwnershipValidator(cubeCache(source)).validate(cubePos);
        source.sendSuccess(() -> Component.literal("RWC ownership validate: " + validation.oneLine()), false);
        return validation.ok() ? 1 : 0;
    }

    private static ServerCubeCache cubeCache(CommandSourceStack source) {
        return WorldCoreCubeLoading.cubicTestForServer(source.getServer());
    }

    private static CubeGenerationDebug generationDebug(CommandSourceStack source) {
        return new CubeGenerationDebug(cubeCache(source));
    }

    private static int genSummary(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        try {
            CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
            CubeGenerationDebug debug = generationDebug(source);
            CubeGenerationSummary summary = debug.summary(cubePos);
            boolean loaded = cubeCache(source).holder(cubePos).isPresent();
            source.sendSuccess(() -> Component.literal("Generated cube summary: " + formatCube(cubePos)
                    + " source=" + (loaded ? "cache" : "temporary-generator")), false);
            source.sendSuccess(() -> Component.literal(summary.oneLine()), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to summarize generated cube: " + exception.getMessage()));
            return 0;
        }
    }

    private static int genBlock(CommandSourceStack source, int cubeX, int cubeY, int cubeZ, int localX, int localY, int localZ) {
        try {
            CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
            LevelCube cube = generationDebug(source).cacheOrGenerated(cubePos);
            BlockState state = cube.getBlockState(localX, localY, localZ);
            int worldX = cubePos.minBlockX() + localX;
            int worldY = cubePos.minBlockY() + localY;
            int worldZ = cubePos.minBlockZ() + localZ;
            boolean loaded = cubeCache(source).holder(cubePos).isPresent();
            source.sendSuccess(() -> Component.literal("Cube " + formatCube(cubePos)
                    + " local " + localX + " " + localY + " " + localZ
                    + " source=" + (loaded ? "cache" : "temporary-generator")), false);
            source.sendSuccess(() -> Component.literal("World block: " + worldX + " " + worldY + " " + worldZ
                    + ", state=" + CubeGenerationHasher.blockStateDebugName(state)), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to inspect generated block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int genColumn(CommandSourceStack source, int cubeX, int cubeZ, int minCubeY, int maxCubeY) {
        try {
            int minY = Math.min(minCubeY, maxCubeY);
            int maxY = Math.max(minCubeY, maxCubeY);
            int count = maxY - minY + 1;
            if (count > 64) {
                source.sendFailure(Component.literal("Column debug range is too large: " + count + " cubes. Max is 64."));
                return 0;
            }

            CubeGenerationDebug debug = generationDebug(source);
            source.sendSuccess(() -> Component.literal("Generated column summary: cubeX=" + cubeX
                    + ", cubeZ=" + cubeZ + ", cubeY=" + minY + ".." + maxY), false);
            for (int cubeY = minY; cubeY <= maxY; cubeY++) {
                CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
                CubeGenerationSummary summary = debug.summary(cubePos);
                final int lineCubeY = cubeY;
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "%4d %s hash=%s",
                        lineCubeY,
                        summary.counts(),
                        CubeGenerationHasher.shortHex(summary.hash()))), false);
            }
            return count;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to summarize generated column: " + exception.getMessage()));
            return 0;
        }
    }

    private static int genVerify(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        try {
            CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
            CubeGenerationDebug.VerifyResult result = generationDebug(source).verify(cubePos);
            if (result.passed()) {
                source.sendSuccess(() -> Component.literal("Generation verify passed: cube=" + formatCube(cubePos)
                        + ", hash=" + CubeGenerationHasher.shortHex(result.first().hash())), false);
                source.sendSuccess(() -> Component.literal(result.first().oneLine()), false);
                return 1;
            }
            source.sendFailure(Component.literal("Generation verify FAILED: cube=" + formatCube(cubePos)
                    + ", firstHash=" + CubeGenerationHasher.shortHex(result.first().hash())
                    + ", secondHash=" + CubeGenerationHasher.shortHex(result.second().hash())));
            source.sendFailure(Component.literal("first=" + result.first().oneLine()));
            source.sendFailure(Component.literal("second=" + result.second().oneLine()));
            return 0;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to verify generated cube: " + exception.getMessage()));
            return 0;
        }
    }

    private static int genVerifyLoaded(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        try {
            CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
            return generationDebug(source).verifyLoaded(cubePos)
                    .map(result -> {
                        if (result.passed()) {
                            source.sendSuccess(() -> Component.literal("Loaded cube matches generator: cube=" + formatCube(cubePos)
                                    + ", hash=" + CubeGenerationHasher.shortHex(result.first().hash())), false);
                            source.sendSuccess(() -> Component.literal(result.first().oneLine()), false);
                            return 1;
                        }
                        source.sendFailure(Component.literal("Loaded cube differs from generator: cube=" + formatCube(cubePos)
                                + ", loadedHash=" + CubeGenerationHasher.shortHex(result.first().hash())
                                + ", generatedHash=" + CubeGenerationHasher.shortHex(result.second().hash())));
                        source.sendFailure(Component.literal("loaded=" + result.first().oneLine()));
                        source.sendFailure(Component.literal("generated=" + result.second().oneLine()));
                        return 0;
                    })
                    .orElseGet(() -> {
                        source.sendFailure(Component.literal("Cube is not loaded in cache: " + formatCube(cubePos)));
                        return 0;
                    });
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to verify loaded cube: " + exception.getMessage()));
            return 0;
        }
    }

    private static int genBenchmark(CommandSourceStack source, int radius) {
        try {
            Vec3 pos = source.getPosition();
            CubePos center = CubePos.fromBlock(Mth.floor(pos.x), Mth.floor(pos.y), Mth.floor(pos.z));
            CubeGenerationProfiler.Result result = CubeGenerationProfiler.benchmark(cubeCache(source), center, radius);
            source.sendSuccess(() -> Component.literal("Generated " + result.generatedCubes()
                    + " temporary cubes around " + formatCube(center)
                    + " radius=" + radius
                    + " in " + formatMillis(result.totalMillis()) + " ms"), false);
            source.sendSuccess(() -> Component.literal("avg=" + formatMillis(result.averageMillisPerCube())
                    + " ms/cube, min=" + formatMillis(result.minMillis())
                    + ", max=" + formatMillis(result.maxMillis())), false);
            return result.generatedCubes();
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to benchmark cube generator: " + exception.getMessage()));
            return 0;
        }
    }

    private static String formatMillis(double millis) {
        return String.format(Locale.ROOT, "%.3f", millis);
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


    private static int blockEntitiesStatus(CommandSourceStack source) {
        CubeBlockEntitySnapshot snapshot = cubeCache(source).blockEntitySnapshot();
        source.sendSuccess(() -> Component.literal(formatBlockEntitySnapshot("RWC cube block entities", snapshot)), false);
        return snapshot.trackedBlockEntities();
    }

    private static int blockEntitiesGet(CommandSourceStack source, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        Optional<CubeBlockEntityRef> ref = cubeCache(source).blockEntityAt(pos);
        if (ref.isEmpty()) {
            source.sendFailure(Component.literal("RWC cube block entity: none at pos=" + formatBlock(pos)
                    + ", cube=" + formatCube(CubePos.fromBlock(pos))));
            return 0;
        }
        CubeBlockEntityRef value = ref.get();
        source.sendSuccess(() -> Component.literal("RWC cube block entity: pos=" + formatBlock(pos)
                + ", cube=" + formatCube(value.cubePos())
                + ", local=" + value.localPos().x() + " " + value.localPos().y() + " " + value.localPos().z()
                + ", localIndex=" + value.localIndex()
                + ", block=" + value.blockId()
                + ", beId=" + value.blockEntityId()
                + ", placeholder=" + value.placeholder()
                + ", realNbt=" + value.realNbt()
                + ", tickingAllowed=" + value.tickingAllowed()), false);
        return 1;
    }

    private static int blockEntitiesCapture(CommandSourceStack source, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        ServerLevel level = CUBIC_TEST.level(source.getServer()).orElse(source.getLevel());
        boolean captured = cubeCache(source).captureBlockEntityFromVanilla(level, pos, "command_capture");
        if (!captured) {
            source.sendFailure(Component.literal("RWC cube block entity capture: no vanilla BlockEntity NBT at pos="
                    + formatBlock(pos) + ", cube=" + formatCube(CubePos.fromBlock(pos))));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("RWC cube block entity capture: captured real vanilla NBT at pos="
                + formatBlock(pos) + ", cube=" + formatCube(CubePos.fromBlock(pos))), false);
        return 1;
    }

    private static int blockEntitiesDumpCube(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
        Optional<Map<Integer, CompoundTag>> tags = cubeCache(source).blockEntityTags(cubePos);
        if (tags.isEmpty() || tags.get().isEmpty()) {
            source.sendSuccess(() -> Component.literal("RWC cube block entities: none in cube=" + formatCube(cubePos)), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("RWC cube block entities in cube=" + formatCube(cubePos)
                + ", count=" + tags.get().size()), false);
        int shown = 0;
        for (Map.Entry<Integer, CompoundTag> entry : tags.get().entrySet()) {
            if (shown++ >= 12) {
                source.sendSuccess(() -> Component.literal("RWC cube block entities: more hidden"), false);
                break;
            }
            CompoundTag tag = entry.getValue();
            int localIndex = entry.getKey();
            int localX = localIndex & CubePos.MASK;
            int localZ = (localIndex >> CubePos.SIZE_BITS) & CubePos.MASK;
            int localY = (localIndex >> (CubePos.SIZE_BITS * 2)) & CubePos.MASK;
            source.sendSuccess(() -> Component.literal("BE local=" + localX + " " + localY + " " + localZ
                    + ", index=" + localIndex
                    + ", id=" + tag.getStringOr("id", "missing")
                    + ", placeholder=" + tag.getBooleanOr("redlinePlaceholder", false)
                    + ", realNbt=" + tag.getBooleanOr("redlineRealNbt", false)
                    + ", keys=" + tag.keySet()), false);
        }
        return tags.get().size();
    }

    private static int scheduledTicksStatus(CommandSourceStack source) {
        CubeScheduledTickSnapshot snapshot = cubeCache(source).scheduledTickSnapshot();
        source.sendSuccess(() -> Component.literal(formatScheduledTickSnapshot("RWC scheduled ticks", snapshot)), false);
        return snapshot.blockTicks() + snapshot.fluidTicks();
    }

    private static int scheduledTicksAdd(CommandSourceStack source, CubeScheduledTickKind kind, int x, int y, int z, String target, int delayTicks) {
        BlockPos pos = new BlockPos(x, y, z);
        boolean added = cubeCache(source).addScheduledTick(pos, kind, normalizeTargetId(target), delayTicks, 0, "command_add_" + kind.name().toLowerCase(Locale.ROOT));
        if (!added) {
            source.sendFailure(Component.literal("RWC scheduled tick add failed: pos=" + formatBlock(pos)
                    + ", cube=" + formatCube(CubePos.fromBlock(pos))));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("RWC scheduled tick added: kind=" + kind
                + ", pos=" + formatBlock(pos)
                + ", cube=" + formatCube(CubePos.fromBlock(pos))
                + ", target=" + normalizeTargetId(target)
                + ", delayTicks=" + delayTicks), false);
        return 1;
    }

    private static int scheduledTicksDumpCube(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
        List<CubeScheduledTickData> ticks = cubeCache(source).scheduledTicks(cubePos);
        if (ticks.isEmpty()) {
            source.sendSuccess(() -> Component.literal("RWC scheduled ticks: none in cube=" + formatCube(cubePos)), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("RWC scheduled ticks in cube=" + formatCube(cubePos)
                + ", count=" + ticks.size()), false);
        for (CubeScheduledTickData tick : ticks.stream().limit(16).toList()) {
            source.sendSuccess(() -> Component.literal("tick kind=" + tick.kind()
                    + ", local=" + tick.localPos().x() + " " + tick.localPos().y() + " " + tick.localPos().z()
                    + ", target=" + tick.targetId()
                    + ", trigger=" + tick.triggerGameTime()
                    + ", priority=" + tick.priority()
                    + ", reason=" + tick.reason()), false);
        }
        if (ticks.size() > 16) {
            source.sendSuccess(() -> Component.literal("RWC scheduled ticks: more hidden"), false);
        }
        return ticks.size();
    }

    private static int scheduledTicksClearCube(CommandSourceStack source, int cubeX, int cubeY, int cubeZ) {
        CubePos cubePos = new CubePos(cubeX, cubeY, cubeZ);
        int removed = cubeCache(source).clearScheduledTicks(cubePos);
        source.sendSuccess(() -> Component.literal("RWC scheduled ticks cleared: cube=" + formatCube(cubePos)
                + ", removed=" + removed), false);
        return removed;
    }

    private static String normalizeTargetId(String raw) {
        return raw == null || raw.isBlank() ? "minecraft:air" : (raw.contains(":") ? raw : "minecraft:" + raw);
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


    private static CubeStatus parseCubeStatus(String statusName) {
        try {
            return CubeStatus.valueOf(statusName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown cube status '" + statusName + "'. Valid statuses: "
                    + String.join(", ", java.util.Arrays.stream(CubeStatus.values()).map(Enum::name).toList()));
        }
    }

    private static String formatDirtySnapshot(String prefix, CubeDirtySnapshot snapshot) {
        return prefix + ": activeDirty=" + snapshot.dirtyCubes()
                + " storageDirty=" + snapshot.storageDirtyCubes()
                + " clientDirty=" + snapshot.clientSyncDirtyCubes()
                + " lightDirty=" + snapshot.lightDirtyCubes()
                + " contentDirty=" + snapshot.contentDirtyCubes()
                + ", contentQ=" + snapshot.contentQueue()
                + ", saveQ=" + snapshot.saveQueue()
                + ", inFlight=" + snapshot.saveInFlight()
                + ", contentLast=" + snapshot.contentRebuiltLastTick()
                + ", savedDone=" + snapshot.savedLastTick()
                + ", submitted=" + snapshot.saveSubmittedLastTick()
                + ", failed=" + snapshot.saveFailedLastTick()
                + ", completions=" + snapshot.saveCompletionsDrainedLastTick()
                + ", contentUs=" + snapshot.contentMicrosLastTick()
                + ", saveWorkerUs=" + snapshot.saveMicrosLastTick()
                + ", completionDrainUs=" + snapshot.completionDrainMicrosLastTick()
                + ", saveBudgetHit=" + snapshot.saveBudgetHitLastTick()
                + ", completionBudgetHit=" + snapshot.completionBudgetHitLastTick()
                + ", idleSkip=" + snapshot.saveIdleSkipLastTick()
                + ", cooldown=" + snapshot.saveCooldownTicks()
                + ", reason=" + snapshot.saveLastReason()
                + ", lastDirty=" + formatNullableCube(snapshot.lastDirtyCube())
                + ", lastSubmitted=" + formatNullableCube(snapshot.lastSubmittedCube())
                + ", lastSaved=" + formatNullableCube(snapshot.lastSavedCube());
    }

    private static String formatDirtyTotals(String prefix, CubeDirtySnapshot snapshot) {
        return prefix + ": marked=" + snapshot.totalMarked()
                + ", contentRebuilt=" + snapshot.totalContentRebuilt()
                + ", savedDone=" + snapshot.totalSaved()
                + ", submitted=" + snapshot.totalSaveSubmitted()
                + ", failed=" + snapshot.totalSaveFailed()
                + ", completionDrained=" + snapshot.totalSaveCompletionsDrained()
                + ", maxContentUs=" + snapshot.contentMicrosMax()
                + ", maxSaveWorkerUs=" + snapshot.saveMicrosMax()
                + ", maxCompletionDrainUs=" + snapshot.completionDrainMicrosMax()
                + ", budget=content " + snapshot.maxContentPerTick()
                + "/t saveSubmit " + snapshot.maxSavesPerTick()
                + "/t " + snapshot.maxSaveMicrosPerTick() + "us"
                + " completion " + snapshot.maxSaveCompletionsPerTick()
                + "/t " + snapshot.maxCompletionDrainMicrosPerTick() + "us"
                + ", lastContent=" + formatNullableCube(snapshot.lastContentCube())
                + " " + snapshot.lastContentSummary().compact();
    }

    private static String formatBlockEntitySnapshot(String prefix, CubeBlockEntitySnapshot snapshot) {
        return prefix + ": tracked=" + snapshot.trackedBlockEntities()
                + ", sections=" + snapshot.sections()
                + ", real=" + snapshot.realNbtBlockEntities()
                + ", placeholders=" + snapshot.placeholderBlockEntities()
                + ", tickingAllowed=" + snapshot.tickingAllowedBlockEntities()
                + ", tickingBlocked=" + snapshot.tickingBlockedBlockEntities()
                + ", addedLast=" + snapshot.addedLastTick()
                + ", updatedLast=" + snapshot.updatedLastTick()
                + ", capturedLast=" + snapshot.realNbtCapturedLastTick()
                + ", removedLast=" + snapshot.removedLastTick()
                + ", rebuiltLast=" + snapshot.rebuiltCubesLastTick()
                + ", totalAdded=" + snapshot.totalAdded()
                + ", totalUpdated=" + snapshot.totalUpdated()
                + ", totalCaptured=" + snapshot.totalRealNbtCaptured()
                + ", totalRemoved=" + snapshot.totalRemoved()
                + ", totalRebuilt=" + snapshot.totalRebuiltCubes()
                + ", lastCube=" + formatNullableCube(snapshot.lastCube())
                + ", reason=" + snapshot.lastReason();
    }

    private static String formatScheduledTickSnapshot(String prefix, CubeScheduledTickSnapshot snapshot) {
        return prefix + ": cubes=" + snapshot.loadedCubesWithTicks()
                + ", block=" + snapshot.blockTicks()
                + ", fluid=" + snapshot.fluidTicks()
                + ", due=" + snapshot.dueTicks()
                + ", dueAllowed=" + snapshot.dueAllowed()
                + ", dueBlocked=" + snapshot.dueBlocked()
                + ", addedLast=" + snapshot.addedLastTick()
                + ", removedLast=" + snapshot.removedLastTick()
                + ", evaluatedLast=" + snapshot.evaluatedLastTick()
                + ", totalAdded=" + snapshot.totalAdded()
                + ", totalRemoved=" + snapshot.totalRemoved()
                + ", totalEvaluated=" + snapshot.totalEvaluated()
                + ", lastCube=" + formatNullableCube(snapshot.lastCube())
                + ", reason=" + snapshot.lastReason();
    }

    private static String formatMutationSnapshot(String prefix, CubeMutationSnapshot snapshot) {
        String lastCube = snapshot.lastCube() == null ? "none" : formatCube(snapshot.lastCube());
        String lastLocal = snapshot.lastLocal() == null ? "none" : snapshot.lastLocal().x() + " " + snapshot.lastLocal().y() + " " + snapshot.lastLocal().z();
        return prefix + ": mutations=" + snapshot.totalMutations()
                + ", applied=" + snapshot.totalApplied()
                + ", changed=" + snapshot.totalChanged()
                + ", unchanged=" + snapshot.totalUnchanged()
                + ", rejected=" + snapshot.totalRejected()
                + ", last=" + snapshot.lastOrigin()
                + "/" + snapshot.lastReason()
                + ", cube=" + lastCube
                + ", local=" + lastLocal
                + ", us=" + snapshot.lastElapsedMicros()
                + ", maxUs=" + snapshot.maxElapsedMicros();
    }

    private static String formatMutationResult(String prefix, CubeMutationResult result) {
        String previous = result.previousState() == null ? "none" : CubicTestDimensionService.blockStateName(result.previousState());
        String next = result.newState() == null ? "none" : CubicTestDimensionService.blockStateName(result.newState());
        return prefix + ": applied=" + result.applied()
                + ", changed=" + result.changed()
                + ", promoted=" + result.statusPromoted()
                + ", cube=" + formatCube(result.cubePos())
                + ", local=" + result.localPos().x() + " " + result.localPos().y() + " " + result.localPos().z()
                + ", previous=" + previous
                + ", new=" + next
                + ", saved=" + result.saved()
                + ", light=" + result.staticLightRebuilt()
                + ", skyQueued=" + result.skyLightQueued()
                + ", skyRebuilt=" + result.skyLightRebuilt()
                + ", holderLoaded=" + result.holderLoaded()
                + ", holderGenerated=" + result.holderGenerated()
                + ", us=" + result.elapsedMicros()
                + ", reason=" + result.reason();
    }

    private static String formatBlock(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String formatCube(CubePos cubePos) {
        return cubePos.x() + " " + cubePos.y() + " " + cubePos.z();
    }

    private static String formatNullableCube(CubePos cubePos) {
        return cubePos == null ? "none" : formatCube(cubePos);
    }

    private static String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private RedlineWorldCoreCommands() {
    }
}
