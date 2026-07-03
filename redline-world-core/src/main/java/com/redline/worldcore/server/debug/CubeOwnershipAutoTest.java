package com.redline.worldcore.server.debug;

import com.redline.worldcore.api.cube.CubeScheduledTickData;
import com.redline.worldcore.api.cube.CubeScheduledTickKind;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
import com.redline.worldcore.server.compat.CubicClientSyncBridge;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.cube.ownership.CubeOwnershipValidationSnapshot;
import com.redline.worldcore.server.cube.ownership.CubeOwnershipValidator;
import com.redline.worldcore.server.dimension.CubicTestDimensionService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * M14.9.1 self-running ownership smoke test.
 *
 * <p>The command starts a small state machine instead of doing everything in one command tick. That gives the normal
 * server tick, dirty pipeline, client mirror bridge and storage cache a few real ticks between operations.</p>
 */
public final class CubeOwnershipAutoTest {
    private static final CubicTestDimensionService CUBIC_TEST = new CubicTestDimensionService();
    private static final int WAIT_SHORT = 10;
    private static final int WAIT_NORMAL = 30;

    private static ActiveTest active;

    private CubeOwnershipAutoTest() {
    }

    public static synchronized int start(CommandSourceStack source) {
        if (active != null) {
            source.sendFailure(Component.literal("RWC ownership auto_test is already running. Use /rwc ownership auto_test_cancel first."));
            return 0;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("RWC ownership auto_test requires a player source."));
            return 0;
        }

        Optional<ServerLevel> cubic = CUBIC_TEST.level(source.getServer());
        if (cubic.isEmpty()) {
            source.sendFailure(Component.literal("RWC ownership auto_test failed: cubic_test dimension is not loaded."));
            return 0;
        }

        CubePos playerCube = CubePos.fromBlock(player.blockPosition());
        CubePos persistenceCube = new CubePos(playerCube.x() + 96, playerCube.y(), playerCube.z() + 96);
        BlockPos markerPos = new BlockPos(persistenceCube.minBlockX() + 2, persistenceCube.minBlockY(), persistenceCube.minBlockZ() + 2);
        BlockPos blockTickPos = new BlockPos(persistenceCube.minBlockX() + 3, persistenceCube.minBlockY(), persistenceCube.minBlockZ() + 2);
        BlockPos fluidTickPos = new BlockPos(persistenceCube.minBlockX() + 4, persistenceCube.minBlockY(), persistenceCube.minBlockZ() + 2);
        BlockPos chestPos = new BlockPos(persistenceCube.minBlockX() + 5, persistenceCube.minBlockY(), persistenceCube.minBlockZ() + 2);
        BlockPos mirrorPos = new BlockPos(playerCube.minBlockX() + 7, playerCube.minBlockY(), playerCube.minBlockZ() + 7);

        active = new ActiveTest(player.getUUID(), persistenceCube, markerPos, blockTickPos, fluidTickPos, chestPos, mirrorPos);
        message(source.getServer(), player.getUUID(), "RWC ownership auto_test started: persistenceCube=" + fmt(persistenceCube)
                + ", marker=" + fmt(markerPos) + ", mirror=" + fmt(mirrorPos));
        message(source.getServer(), player.getUUID(), "RWC ownership auto_test: do not edit the marker/mirror blocks until PASS/FAIL is printed.");
        return 1;
    }

    public static synchronized int cancel(CommandSourceStack source) {
        boolean had = active != null;
        active = null;
        source.sendSuccess(() -> Component.literal("RWC ownership auto_test cancelled=" + had), false);
        return had ? 1 : 0;
    }

    public static synchronized void onServerTick(ServerTickEvent.Post event) {
        if (active == null) {
            return;
        }
        if (active.waitTicks > 0) {
            active.waitTicks--;
            return;
        }
        try {
            boolean done = active.step(event.getServer());
            if (done) {
                active = null;
            }
        } catch (RuntimeException exception) {
            message(event.getServer(), active.playerId, "RWC ownership auto_test FAIL: exception=" + exception.getMessage());
            active = null;
        }
    }

    private static final class ActiveTest {
        private final UUID playerId;
        private final CubePos persistenceCube;
        private final BlockPos markerPos;
        private final BlockPos blockTickPos;
        private final BlockPos fluidTickPos;
        private final BlockPos chestPos;
        private final BlockPos mirrorPos;
        private int phase;
        private int waitTicks;
        private boolean failed;
        private String failReason = "none";
        private long invalidationsBefore;
        private long mirrorsCleanedBefore;

        private ActiveTest(UUID playerId, CubePos persistenceCube, BlockPos markerPos, BlockPos blockTickPos,
                           BlockPos fluidTickPos, BlockPos chestPos, BlockPos mirrorPos) {
            this.playerId = playerId;
            this.persistenceCube = persistenceCube;
            this.markerPos = markerPos;
            this.blockTickPos = blockTickPos;
            this.fluidTickPos = fluidTickPos;
            this.chestPos = chestPos;
            this.mirrorPos = mirrorPos;
        }

        private boolean step(MinecraftServer server) {
            ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(server);
            switch (phase) {
                case 0 -> {
                    invalidationsBefore = CubicClientSyncBridge.clientInvalidationsQueued();
                    mirrorsCleanedBefore = CubicClientSyncBridge.clientMirrorsCleaned();
                    set(cache, markerPos, Blocks.DIAMOND_BLOCK.defaultBlockState(), "auto_test_marker");
                    set(cache, chestPos, Blocks.CHEST.defaultBlockState(), "auto_test_chest");
                    set(cache, mirrorPos, Blocks.GOLD_BLOCK.defaultBlockState(), "auto_test_mirror");
                    cache.addScheduledTick(blockTickPos, CubeScheduledTickKind.BLOCK, "minecraft:stone", 4000, 0, "auto_test_block_tick");
                    cache.addScheduledTick(fluidTickPos, CubeScheduledTickKind.FLUID, "minecraft:water", 4000, 0, "auto_test_fluid_tick");
                    message(server, playerId, "RWC ownership auto_test phase 1/7: wrote marker/chest/ticks/mirror; waiting " + WAIT_NORMAL + " ticks.");
                    phase = 1;
                    waitTicks = WAIT_NORMAL;
                    return false;
                }
                case 1 -> {
                    checkRuntime(cache);
                    boolean saved = cache.forceSaveCube(persistenceCube);
                    if (!saved) {
                        fail("forceSaveCube returned false");
                    }
                    message(server, playerId, "RWC ownership auto_test phase 2/7: runtime check " + status()
                            + ", forceSaved=" + saved + "; waiting " + WAIT_SHORT + " ticks.");
                    phase = 2;
                    waitTicks = WAIT_SHORT;
                    return false;
                }
                case 2 -> {
                    boolean unloaded = cache.debugUnloadCube(persistenceCube, true);
                    message(server, playerId, "RWC ownership auto_test phase 3/7: unloaded persistence cube=" + unloaded
                            + "; waiting " + WAIT_NORMAL + " ticks.");
                    phase = 3;
                    waitTicks = WAIT_NORMAL;
                    return false;
                }
                case 3 -> {
                    checkPersistedUnloaded(cache);
                    message(server, playerId, "RWC ownership auto_test phase 4/7: persisted-unloaded check " + status()
                            + "; loading cube back; waiting " + WAIT_NORMAL + " ticks.");
                    cache.debugLoadCube(persistenceCube, CubeTicketLevel.FULL);
                    phase = 4;
                    waitTicks = WAIT_NORMAL;
                    return false;
                }
                case 4 -> {
                    checkLoaded(cache);
                    message(server, playerId, "RWC ownership auto_test phase 5/7: loaded check " + status()
                            + "; waiting for client mirror " + WAIT_NORMAL + " ticks.");
                    phase = 5;
                    waitTicks = WAIT_NORMAL;
                    return false;
                }
                case 5 -> {
                    long invalidationsDelta = CubicClientSyncBridge.clientInvalidationsQueued() - invalidationsBefore;
                    long mirrorsDelta = CubicClientSyncBridge.clientMirrorsCleaned() - mirrorsCleanedBefore;
                    if (invalidationsDelta > 256) {
                        fail("client invalidation dedupe suspicious: delta=" + invalidationsDelta);
                    }
                    message(server, playerId, "RWC ownership auto_test phase 6/7: client mirror counters invalidationsDelta="
                            + invalidationsDelta + ", mirrorsCleanedDelta=" + mirrorsDelta + ", " + status());
                    phase = 6;
                    waitTicks = WAIT_SHORT;
                    return false;
                }
                case 6 -> {
                    CubeOwnershipValidationSnapshot validation = new CubeOwnershipValidator(cache).validate(persistenceCube);
                    if (!validation.ok()) {
                        fail("ownership validator problem=" + validation.problem());
                    }
                    if (failed) {
                        message(server, playerId, "RWC ownership auto_test FAIL: " + failReason + "; validation=" + validation.oneLine());
                    } else {
                        message(server, playerId, "RWC ownership auto_test PASS: marker/ticks/BE persisted through save->unload->reload; validation=" + validation.oneLine());
                    }
                    message(server, playerId, "RWC ownership auto_test left test blocks for inspection: marker=" + fmt(markerPos)
                            + ", chest=" + fmt(chestPos) + ", mirror=" + fmt(mirrorPos));
                    return true;
                }
                default -> {
                    return true;
                }
            }
        }

        private void checkRuntime(ServerCubeCache cache) {
            checkBlock(cache, markerPos, Blocks.DIAMOND_BLOCK.defaultBlockState(), "runtime marker");
            checkTicks(cache, "runtime ticks");
            checkBlockEntities(cache, "runtime BE");
        }

        private void checkPersistedUnloaded(ServerCubeCache cache) {
            checkBlock(cache, markerPos, Blocks.DIAMOND_BLOCK.defaultBlockState(), "persisted marker");
            checkTicks(cache, "persisted ticks");
            checkBlockEntities(cache, "persisted BE");
        }

        private void checkLoaded(ServerCubeCache cache) {
            checkBlock(cache, markerPos, Blocks.DIAMOND_BLOCK.defaultBlockState(), "loaded marker");
            checkTicks(cache, "loaded ticks");
            checkBlockEntities(cache, "loaded BE");
        }

        private void checkBlock(ServerCubeCache cache, BlockPos pos, BlockState expected, String label) {
            Optional<BlockState> actual = cache.readBlock(pos);
            if (actual.isEmpty() || !actual.get().equals(expected)) {
                fail(label + " expected=" + expected.getBlock() + " actual=" + actual.map(state -> state.getBlock().toString()).orElse("missing"));
            }
        }

        private void checkTicks(ServerCubeCache cache, String label) {
            List<CubeScheduledTickData> ticks = cache.scheduledTicks(persistenceCube);
            boolean hasBlock = ticks.stream().anyMatch(tick -> tick.kind() == CubeScheduledTickKind.BLOCK && tick.localPos().x() == CubePos.local(blockTickPos.getX()));
            boolean hasFluid = ticks.stream().anyMatch(tick -> tick.kind() == CubeScheduledTickKind.FLUID && tick.localPos().x() == CubePos.local(fluidTickPos.getX()));
            if (!hasBlock || !hasFluid) {
                fail(label + " missing scheduled ticks: count=" + ticks.size() + ", hasBlock=" + hasBlock + ", hasFluid=" + hasFluid);
            }
        }

        private void checkBlockEntities(ServerCubeCache cache, String label) {
            int localIndex = CubePos.localIndex(CubePos.local(chestPos.getX()), CubePos.local(chestPos.getY()), CubePos.local(chestPos.getZ()));
            boolean hasChest = cache.blockEntityTags(persistenceCube)
                    .map(tags -> tags.containsKey(localIndex))
                    .orElse(false);
            if (!hasChest) {
                fail(label + " missing chest block entity tag at localIndex=" + localIndex);
            }
        }

        private void set(ServerCubeCache cache, BlockPos pos, BlockState state, String reason) {
            cache.access().setBlockState(pos, state, com.redline.worldcore.server.cube.access.CubeMutationContext.command(true).withReason(reason));
        }

        private void fail(String reason) {
            if (!failed) {
                failed = true;
                failReason = reason;
            }
        }

        private String status() {
            return failed ? "FAIL_SO_FAR reason=" + failReason : "ok";
        }
    }

    private static void message(MinecraftServer server, UUID playerId, String text) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }

    private static String fmt(CubePos pos) {
        return pos.x() + " " + pos.y() + " " + pos.z();
    }

    private static String fmt(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
