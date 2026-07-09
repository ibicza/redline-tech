package com.redline.worldcore.server.cube.runtime;

import com.redline.worldcore.RedlineWorldCore;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.compat.CubicClientSyncBridge;
import com.redline.worldcore.server.compat.CubicNativeFluidTicker;
import com.redline.worldcore.server.compat.CubicNativeRedstoneBridge;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * M20.0 Cubic-Chunks-style runtime compatibility entrypoint.
 *
 * <p>The old M19 outside-shell path handled each vanilla system manually: water bridge, door bridge, redstone bridge,
 * piston bridge and so on.  That was useful to prove that true cube heights are reachable, but it scales badly.  This
 * class starts the Cubic Chunks style path instead: vanilla code is allowed to call {@link Level#setBlock}; the mixin
 * redirects those writes into {@link ServerCubeCache}, then this runtime fans out normal neighbor/fluid/redstone updates
 * from one cube-owned mutation path.</p>
 *
 * <p>This is intentionally not a global height extension.  It only handles the cubic_test dimension and only positions
 * outside the temporary vanilla shell.  Inside the shell vanilla chunks still do their normal work; outside the shell the
 * cube cache is the owner and this class is the compatibility boundary.</p>
 */
public final class CubicChunksStyleRuntime {
    private static final int MAX_NEIGHBOR_QUEUE = 32768;
    private static final int MAX_NEIGHBOR_JOBS_PER_TICK = 512;
    private static final int MAX_NEIGHBOR_DEPTH = 8;

    private static final ArrayDeque<NeighborJob> NEIGHBOR_QUEUE = new ArrayDeque<>();
    private static final Set<NeighborKey> QUEUED_NEIGHBORS = new LinkedHashSet<>();

    private static long totalLevelSetBlockRedirects;
    private static long totalRuntimeWrites;
    private static long totalNeighborJobs;
    private static long totalNeighborChangedCalls;
    private static long totalNeighborChangedFailures;
    private static long totalVanillaUseAttempts;
    private static long totalVanillaUseConsumed;
    private static long totalVanillaUseFailures;
    private static int levelSetBlockRedirectsLastTick;
    private static int runtimeWritesLastTick;
    private static int neighborJobsLastTick;
    private static int neighborCallsLastTick;
    private static int neighborFailuresLastTick;
    private static int vanillaUseAttemptsLastTick;
    private static int vanillaUseConsumedLastTick;
    private static int vanillaUseFailuresLastTick;

    private CubicChunksStyleRuntime() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        levelSetBlockRedirectsLastTick = 0;
        runtimeWritesLastTick = 0;
        neighborJobsLastTick = 0;
        neighborCallsLastTick = 0;
        neighborFailuresLastTick = 0;
        vanillaUseAttemptsLastTick = 0;
        vanillaUseConsumedLastTick = 0;
        vanillaUseFailuresLastTick = 0;

        ServerLevel level = event.getServer().getLevel(CubicDimensionKeys.CUBIC_TEST_LEVEL);
        if (level == null) {
            NEIGHBOR_QUEUE.clear();
            QUEUED_NEIGHBORS.clear();
            return;
        }
        drainNeighborQueue(level);
    }

    /**
     * Redirects vanilla Level#setBlock outside the shell into the cube backend.
     *
     * <p>This is the important M20 inversion: vanilla behavior may still be the thing that decides <em>what</em> state to
     * write, but the write lands in a CubePos, not in a giant fake column.</p>
     */
    public static boolean routeLevelSetBlock(ServerLevel level, BlockPos pos, BlockState state, int flags, int recursionLeft) {
        if (!isOutsideShell(level, pos)) {
            return false;
        }
        CubicClientSyncBridge.NativeBlockEditResult result = CubicClientSyncBridge.writeNativeSystemBlockEdit(
                level,
                pos.immutable(),
                state,
                "m20_level_set_block_redirect_flags" + flags + "_r" + recursionLeft
        );
        if (!result.applied()) {
            return false;
        }
        totalLevelSetBlockRedirects++;
        levelSetBlockRedirectsLastTick++;
        RuntimeProfiler.addCount("gameplay.m20_level_setblock_redirect", 1);
        if (result.changed()) {
            queueAfterMutation(level, pos, "level_set_block_redirect");
        }
        return true;
    }

    public static RuntimeWriteResult setBlock(ServerLevel level, BlockPos pos, BlockState state, String reason) {
        if (!isOutsideShell(level, pos)) {
            return RuntimeWriteResult.pass("inside_shell_or_outside_internal");
        }
        CubicClientSyncBridge.NativeBlockEditResult result = CubicClientSyncBridge.writeNativeSystemBlockEdit(
                level,
                pos.immutable(),
                state,
                reason == null ? "m20_runtime_write" : reason
        );
        if (!result.applied()) {
            return RuntimeWriteResult.rejected(result.reason());
        }
        totalRuntimeWrites++;
        runtimeWritesLastTick++;
        RuntimeProfiler.addCount("gameplay.m20_runtime_writes", 1);
        if (result.changed()) {
            queueAfterMutation(level, pos, reason);
        }
        return new RuntimeWriteResult(true, result.changed(), result.reason());
    }

    public static BlockState getBlockState(ServerLevel level, BlockPos pos) {
        if (!CubicDimensionKeys.isCubicTest(level)) {
            return level.getBlockState(pos);
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        return cache.readOrGenerateBlock(pos).orElseGet(() -> Blocks.AIR.defaultBlockState());
    }

    public static FluidState getFluidState(ServerLevel level, BlockPos pos) {
        return getBlockState(level, pos).getFluidState();
    }

    public static BlockGetter blockGetter(ServerLevel level) {
        return new CubeRuntimeBlockGetter(level, WorldCoreCubeLoading.cubicTestForServer(level.getServer()));
    }

    /**
     * Tries the real vanilla BlockState use path with cube-backed reads and Level#setBlock redirection.
     *
     * <p>For many simple interactive blocks this means their own Block#useItemOn logic can mutate through the M20
     * Level#setBlock redirect instead of being reimplemented in CubicExtremeGameplayBridge.  If vanilla returns PASS or
     * throws because it still needs a real LevelChunk/BlockEntity path, the caller can fall back to old narrow handlers.</p>
     */
    public static Optional<InteractionResult> tryVanillaUse(ServerLevel level, Player player, InteractionHand hand, BlockHitResult hit, BlockState state, String reason) {
        if (!isOutsideShell(level, hit.getBlockPos())) {
            return Optional.empty();
        }
        totalVanillaUseAttempts++;
        vanillaUseAttemptsLastTick++;
        try {
            ItemStack stack = player.getItemInHand(hand);
            InteractionResult result = state.useItemOn(stack, level, player, hand, hit);
            if (result.consumesAction()) {
                totalVanillaUseConsumed++;
                vanillaUseConsumedLastTick++;
                RuntimeProfiler.addCount("gameplay.m20_vanilla_use_consumed", 1);
                queueAfterMutation(level, hit.getBlockPos(), reason == null ? "vanilla_use" : reason);
                return Optional.of(result);
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            totalVanillaUseFailures++;
            vanillaUseFailuresLastTick++;
            RuntimeProfiler.addCount("gameplay.m20_vanilla_use_failed", 1);
            RedlineWorldCore.LOGGER.debug("RWC M20 vanilla use failed at {} state {}: {}", hit.getBlockPos(), state, exception.toString());
            return Optional.empty();
        }
    }

    public static void queueAfterMutation(ServerLevel level, BlockPos origin, String reason) {
        if (!isOutsideShell(level, origin)) {
            return;
        }
        CubicNativeFluidTicker.scheduleAround(level, origin);
        CubicNativeRedstoneBridge.refreshAround(level, origin, normalizeReason(reason) + "_redstone");
        queueNeighborChanged(level, origin, origin, 0, normalizeReason(reason) + "_neighbors");
        for (Direction direction : Direction.values()) {
            queueNeighborChanged(level, origin.relative(direction), origin, 0, normalizeReason(reason) + "_neighbor_" + direction.getName());
        }
    }

    public static void queueNeighborChanged(ServerLevel level, BlockPos pos, BlockPos changedNeighbor, int depth, String reason) {
        if (!isOutsideShell(level, pos) || depth > MAX_NEIGHBOR_DEPTH || NEIGHBOR_QUEUE.size() >= MAX_NEIGHBOR_QUEUE) {
            return;
        }
        NeighborKey key = new NeighborKey(pos.immutable(), changedNeighbor.immutable(), depth);
        if (!QUEUED_NEIGHBORS.add(key)) {
            return;
        }
        NEIGHBOR_QUEUE.add(new NeighborJob(pos.immutable(), changedNeighbor.immutable(), Math.max(0, depth), normalizeReason(reason)));
    }

    public static boolean isOutsideShell(ServerLevel level, BlockPos pos) {
        if (!CubicDimensionKeys.isCubicTest(level)) {
            return false;
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        return cache.settings().containsBlockY(pos.getY()) && !cache.settings().isBlockInsideVanillaShell(pos.getY());
    }

    public static CubeRuntimeSectionFacade sectionFacade(ServerLevel level, CubePos cubePos) {
        return new CubeRuntimeSectionFacade(level, WorldCoreCubeLoading.cubicTestForServer(level.getServer()), cubePos);
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                NEIGHBOR_QUEUE.size(),
                totalLevelSetBlockRedirects,
                totalRuntimeWrites,
                totalNeighborJobs,
                totalNeighborChangedCalls,
                totalNeighborChangedFailures,
                totalVanillaUseAttempts,
                totalVanillaUseConsumed,
                totalVanillaUseFailures,
                levelSetBlockRedirectsLastTick,
                runtimeWritesLastTick,
                neighborJobsLastTick,
                neighborCallsLastTick,
                neighborFailuresLastTick,
                vanillaUseAttemptsLastTick,
                vanillaUseConsumedLastTick,
                vanillaUseFailuresLastTick
        );
    }

    private static void drainNeighborQueue(ServerLevel level) {
        int processed = 0;
        while (processed < MAX_NEIGHBOR_JOBS_PER_TICK && !NEIGHBOR_QUEUE.isEmpty()) {
            NeighborJob job = NEIGHBOR_QUEUE.poll();
            QUEUED_NEIGHBORS.remove(new NeighborKey(job.pos(), job.changedNeighbor(), job.depth()));
            if (!isOutsideShell(level, job.pos())) {
                continue;
            }
            processed++;
            neighborJobsLastTick++;
            totalNeighborJobs++;
            RuntimeProfiler.addCount("gameplay.m20_neighbor_jobs", 1);
            applyNeighborChanged(level, job);
        }
    }

    private static void applyNeighborChanged(ServerLevel level, NeighborJob job) {
        BlockState state = getBlockState(level, job.pos());
        if (state.isAir()) {
            return;
        }
        BlockState neighbor = getBlockState(level, job.changedNeighbor());
        Block block = neighbor.getBlock();
        try {
            state.handleNeighborChanged(level, job.pos(), block, null, false);
            neighborCallsLastTick++;
            totalNeighborChangedCalls++;
            RuntimeProfiler.addCount("gameplay.m20_neighbor_changed_calls", 1);
        } catch (RuntimeException exception) {
            neighborFailuresLastTick++;
            totalNeighborChangedFailures++;
            RuntimeProfiler.addCount("gameplay.m20_neighbor_changed_failures", 1);
            RedlineWorldCore.LOGGER.debug("RWC M20 neighborChanged failed at {} from {} state {}: {}", job.pos(), job.changedNeighbor(), state, exception.toString());
        }
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "m20_runtime";
        }
        String normalized = reason.toLowerCase(Locale.ROOT).replace(' ', '_');
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }

    private record NeighborKey(BlockPos pos, BlockPos changedNeighbor, int depth) {
    }

    private record NeighborJob(BlockPos pos, BlockPos changedNeighbor, int depth, String reason) {
    }

    public record RuntimeWriteResult(boolean applied, boolean changed, String reason) {
        public static RuntimeWriteResult pass(String reason) {
            return new RuntimeWriteResult(false, false, reason);
        }

        public static RuntimeWriteResult rejected(String reason) {
            return new RuntimeWriteResult(false, false, reason);
        }
    }

    public record Snapshot(int neighborQueue,
                           long totalLevelSetBlockRedirects,
                           long totalRuntimeWrites,
                           long totalNeighborJobs,
                           long totalNeighborChangedCalls,
                           long totalNeighborChangedFailures,
                           long totalVanillaUseAttempts,
                           long totalVanillaUseConsumed,
                           long totalVanillaUseFailures,
                           int levelSetBlockRedirectsLastTick,
                           int runtimeWritesLastTick,
                           int neighborJobsLastTick,
                           int neighborCallsLastTick,
                           int neighborFailuresLastTick,
                           int vanillaUseAttemptsLastTick,
                           int vanillaUseConsumedLastTick,
                           int vanillaUseFailuresLastTick) {
    }
}
