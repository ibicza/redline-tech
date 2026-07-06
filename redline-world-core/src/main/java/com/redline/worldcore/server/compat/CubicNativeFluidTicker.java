package com.redline.worldcore.server.compat;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * M19.7 MVP cube-native fluid ticking for positions outside the temporary vanilla shell.
 *
 * <p>Vanilla fluid ticks are owned by LevelChunk scheduled ticks, so water/lava placed at true cubic heights do not flow
 * through the normal engine yet.  This tiny queue is deliberately conservative: it only handles vanilla water/lava, only
 * flows into air, and processes a small budget per server tick.  It is a gameplay bridge until scheduled fluid ticks are
 * executed directly from LevelCube.</p>
 */
public final class CubicNativeFluidTicker {
    private static final int MAX_QUEUE = 8192;
    private static final int MAX_STEPS_PER_TICK = 128;
    private static final ArrayDeque<BlockPos> QUEUE = new ArrayDeque<>();
    private static final Set<BlockPos> QUEUED = new HashSet<>();
    private static long totalScheduled;
    private static long totalProcessed;
    private static long totalSpread;
    private static int scheduledLastTick;
    private static int processedLastTick;
    private static int spreadLastTick;

    private CubicNativeFluidTicker() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Optional<ServerLevel> maybeLevel = event.getServer().getLevel(CubicDimensionKeys.CUBIC_TEST_LEVEL) == null
                ? Optional.empty()
                : Optional.of(event.getServer().getLevel(CubicDimensionKeys.CUBIC_TEST_LEVEL));
        scheduledLastTick = 0;
        processedLastTick = 0;
        spreadLastTick = 0;
        if (maybeLevel.isEmpty()) {
            QUEUE.clear();
            QUEUED.clear();
            return;
        }
        tick(maybeLevel.get(), WorldCoreCubeLoading.cubicTestForServer(event.getServer()));
    }

    public static void schedule(ServerLevel level, BlockPos pos) {
        if (!isCubicTestOutsideShell(level, pos)) {
            return;
        }
        enqueue(pos);
    }

    public static void scheduleAround(ServerLevel level, BlockPos pos) {
        schedule(level, pos);
        for (Direction direction : Direction.values()) {
            schedule(level, pos.relative(direction));
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(QUEUE.size(), totalScheduled, totalProcessed, totalSpread, scheduledLastTick, processedLastTick, spreadLastTick);
    }

    private static void tick(ServerLevel level, ServerCubeCache cache) {
        int steps = 0;
        while (steps++ < MAX_STEPS_PER_TICK && !QUEUE.isEmpty()) {
            BlockPos pos = QUEUE.removeFirst();
            QUEUED.remove(pos);
            processedLastTick++;
            totalProcessed++;
            if (!isCubicTestOutsideShell(level, pos)) {
                continue;
            }
            Optional<BlockState> maybeState = cache.readOrGenerateBlock(pos);
            if (maybeState.isEmpty()) {
                continue;
            }
            BlockState state = maybeState.get();
            FluidKind kind = FluidKind.from(state.getFluidState());
            if (kind == FluidKind.NONE) {
                continue;
            }
            int amount = fluidAmount(state.getFluidState());
            if (amount <= 1) {
                continue;
            }
            boolean movedDown = trySpread(level, cache, pos, pos.below(), kind.flowing(8, true), "native_fluid_down");
            if (movedDown) {
                continue;
            }
            int nextAmount = amount - 1;
            BlockState horizontal = kind.flowing(nextAmount, false);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                trySpread(level, cache, pos, pos.relative(direction), horizontal, "native_fluid_side");
            }
        }
        RuntimeProfiler.addCount("gameplay.native_fluid_queue", QUEUE.size());
        RuntimeProfiler.addCount("gameplay.native_fluid_processed", processedLastTick);
        RuntimeProfiler.addCount("gameplay.native_fluid_spread", spreadLastTick);
    }


    private static boolean trySpread(ServerLevel level, ServerCubeCache cache, BlockPos source, BlockPos target, BlockState state, String reason) {
        if (!isCubicTestOutsideShell(level, target)) {
            return false;
        }
        Optional<BlockState> existing = cache.readOrGenerateBlock(target);
        if (existing.isPresent() && !existing.get().isAir()) {
            return false;
        }
        CubicClientSyncBridge.NativeBlockEditResult result = CubicClientSyncBridge.writeNativeSystemBlockEdit(level, target, state, reason);
        if (!result.applied() || !result.changed()) {
            return false;
        }
        spreadLastTick++;
        totalSpread++;
        schedule(level, target);
        schedule(level, source);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            schedule(level, target.relative(direction));
        }
        return true;
    }

    private static void enqueue(BlockPos pos) {
        BlockPos immutable = pos.immutable();
        if (!QUEUED.add(immutable)) {
            return;
        }
        if (QUEUE.size() >= MAX_QUEUE) {
            BlockPos dropped = QUEUE.removeFirst();
            QUEUED.remove(dropped);
        }
        QUEUE.addLast(immutable);
        scheduledLastTick++;
        totalScheduled++;
    }

    private static boolean isCubicTestOutsideShell(ServerLevel level, BlockPos pos) {
        if (!level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL)) {
            return false;
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        return cache.settings().containsBlockY(pos.getY()) && !cache.settings().isBlockInsideVanillaShell(pos.getY());
    }

    private static int fluidAmount(FluidState state) {
        if (state.isSource()) {
            return 8;
        }
        return Math.max(0, state.getAmount());
    }

    private enum FluidKind {
        NONE,
        WATER,
        LAVA;

        static FluidKind from(FluidState state) {
            if (state.isEmpty()) {
                return NONE;
            }
            if (state.getType().isSame(Fluids.WATER)) {
                return WATER;
            }
            if (state.getType().isSame(Fluids.LAVA)) {
                return LAVA;
            }
            return NONE;
        }

        BlockState flowing(int amount, boolean falling) {
            int clamped = Math.max(1, Math.min(8, amount));
            return switch (this) {
                case WATER -> Fluids.FLOWING_WATER.getFlowing(clamped, falling).createLegacyBlock();
                case LAVA -> Fluids.FLOWING_LAVA.getFlowing(clamped, falling).createLegacyBlock();
                case NONE -> Blocks.AIR.defaultBlockState();
            };
        }
    }

    public record Snapshot(int queue, long totalScheduled, long totalProcessed, long totalSpread,
                           int scheduledLastTick, int processedLastTick, int spreadLastTick) {
    }
}
