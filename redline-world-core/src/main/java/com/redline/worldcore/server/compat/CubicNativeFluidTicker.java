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

import java.util.Comparator;
import java.util.HashMap;
import java.util.Optional;
import java.util.Map;
import java.util.PriorityQueue;

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
    private static final int MAX_STEPS_PER_TICK = 96;

    private static final PriorityQueue<ScheduledFluidTick> QUEUE = new PriorityQueue<>(Comparator.comparingLong(ScheduledFluidTick::dueTick));
    private static final Map<BlockPos, Long> QUEUED_DUE_TICKS = new HashMap<>();

    private static long totalScheduled;
    private static long totalProcessed;
    private static long totalSpread;
    private static long totalDecay;
    private static long totalInteractions;
    private static int scheduledLastTick;
    private static int processedLastTick;
    private static int spreadLastTick;
    private static int decayLastTick;
    private static int interactionLastTick;
    private static int delayedLastTick;

    private CubicNativeFluidTicker() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Optional<ServerLevel> maybeLevel = event.getServer().getLevel(CubicDimensionKeys.CUBIC_TEST_LEVEL) == null
                ? Optional.empty()
                : Optional.of(event.getServer().getLevel(CubicDimensionKeys.CUBIC_TEST_LEVEL));
        scheduledLastTick = 0;
        processedLastTick = 0;
        spreadLastTick = 0;
        decayLastTick = 0;
        interactionLastTick = 0;
        delayedLastTick = 0;
        if (maybeLevel.isEmpty()) {
            QUEUE.clear();
            QUEUED_DUE_TICKS.clear();
            return;
        }
        tick(maybeLevel.get(), WorldCoreCubeLoading.cubicTestForServer(event.getServer()));
    }

    public static void schedule(ServerLevel level, BlockPos pos) {
        if (!isCubicTestOutsideShell(level, pos)) {
            return;
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        enqueue(level, cache, pos, delayForPosition(cache, pos));
    }

    public static void scheduleAround(ServerLevel level, BlockPos pos) {
        if (!level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL)) {
            return;
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        scheduleAround(level, cache, pos, 1);
    }

    public static Snapshot snapshot() {
        return new Snapshot(QUEUE.size(), totalScheduled, totalProcessed, totalSpread, totalDecay, totalInteractions,
                scheduledLastTick, processedLastTick, spreadLastTick, decayLastTick, interactionLastTick, delayedLastTick);
    }

    private static void tick(ServerLevel level, ServerCubeCache cache) {
        int steps = 0;
        long now = level.getGameTime();
        while (steps < MAX_STEPS_PER_TICK && !QUEUE.isEmpty()) {
            ScheduledFluidTick queued = QUEUE.peek();
            if (queued.dueTick() > now) {
                delayedLastTick = QUEUE.size();
                break;
            }
            QUEUE.poll();
            Long currentDue = QUEUED_DUE_TICKS.get(queued.pos());
            if (currentDue == null || currentDue.longValue() != queued.dueTick()) {
                continue;
            }
            BlockPos pos = queued.pos();
            QUEUED_DUE_TICKS.remove(pos);
            steps++;
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
            FluidState fluid = state.getFluidState();
            int amount = fluidAmount(fluid);

            if (handleLavaWaterContact(level, cache, pos, state, kind, "native_fluid_contact")) {
                continue;
            }

            // Source blocks are stable producers. Flowing blocks are derived state and must be recomputed every time a
            // nearby source/flow changes; otherwise removing the source leaves orphan water/lava forever.
            if (!fluid.isSource()) {
                BlockState recomputed = recomputeFlowingState(level, cache, pos, kind);
                if (!recomputed.equals(state)) {
                    boolean changed = writeFluidState(level, pos, recomputed, "native_fluid_recompute");
                    if (changed) {
                        decayLastTick++;
                        totalDecay++;
                        scheduleAround(level, cache, pos, kind.tickDelay());
                    }
                    continue;
                }
            }

            if (amount <= 1) {
                continue;
            }
            boolean movedDown = trySpread(level, cache, pos, pos.below(), kind.flowing(8, true), "native_fluid_down");
            if (movedDown) {
                continue;
            }
            int nextAmount = amount - kind.horizontalDrop();
            if (nextAmount <= 0) {
                continue;
            }
            BlockState horizontal = kind.flowing(nextAmount, false);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                trySpread(level, cache, pos, pos.relative(direction), horizontal, "native_fluid_side");
            }
        }
        RuntimeProfiler.addCount("gameplay.native_fluid_queue", QUEUE.size());
        RuntimeProfiler.addCount("gameplay.native_fluid_processed", processedLastTick);
        RuntimeProfiler.addCount("gameplay.native_fluid_spread", spreadLastTick);
        RuntimeProfiler.addCount("gameplay.native_fluid_decay", decayLastTick);
        RuntimeProfiler.addCount("gameplay.native_fluid_interactions", interactionLastTick);
        RuntimeProfiler.addCount("gameplay.native_fluid_delayed", delayedLastTick);
    }

    private static BlockState recomputeFlowingState(ServerLevel level, ServerCubeCache cache, BlockPos pos, FluidKind kind) {
        Optional<BlockState> above = cache.readOrGenerateBlock(pos.above());
        if (sameFluid(kind, above)) {
            return kind.flowing(8, true);
        }

        int best = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            Optional<BlockState> neighbor = cache.readOrGenerateBlock(pos.relative(direction));
            if (neighbor.isEmpty()) {
                continue;
            }
            FluidState neighborFluid = neighbor.get().getFluidState();
            if (FluidKind.from(neighborFluid) != kind) {
                continue;
            }
            if (neighborFluid.isSource()) {
                best = Math.max(best, 7);
            } else {
                best = Math.max(best, fluidAmount(neighborFluid) - 1);
            }
        }
        if (best <= 0) {
            return Blocks.AIR.defaultBlockState();
        }
        return kind.flowing(best, false);
    }

    private static boolean sameFluid(FluidKind kind, Optional<BlockState> maybeState) {
        return maybeState.isPresent() && FluidKind.from(maybeState.get().getFluidState()) == kind;
    }

    private static boolean handleLavaWaterContact(ServerLevel level, ServerCubeCache cache, BlockPos pos, BlockState state, FluidKind kind, String reason) {
        if (kind == FluidKind.NONE) {
            return false;
        }

        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.relative(direction);
            Optional<BlockState> maybeNeighbor = cache.readOrGenerateBlock(neighborPos);
            if (maybeNeighbor.isEmpty()) {
                continue;
            }
            BlockState neighbor = maybeNeighbor.get();
            FluidKind neighborKind = FluidKind.from(neighbor.getFluidState());
            if (!kind.opposes(neighborKind)) {
                continue;
            }

            if (kind == FluidKind.LAVA) {
                return solidifyFluid(level, cache, pos, state, reason + "_lava");
            }
            return solidifyFluid(level, cache, neighborPos, neighbor, reason + "_water");
        }
        return false;
    }

    private static boolean solidifyFluid(ServerLevel level, ServerCubeCache cache, BlockPos pos, BlockState fluidState, String reason) {
        FluidKind kind = FluidKind.from(fluidState.getFluidState());
        if (kind != FluidKind.LAVA) {
            return false;
        }
        BlockState result = fluidState.getFluidState().isSource() ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
        boolean changed = writeFluidState(level, pos, result, reason + "_solidify");
        if (changed) {
            interactionLastTick++;
            totalInteractions++;
            scheduleAround(level, cache, pos, 1);
        }
        return changed;
    }

    private static boolean writeFluidState(ServerLevel level, BlockPos pos, BlockState state, String reason) {
        CubicClientSyncBridge.NativeBlockEditResult result = CubicClientSyncBridge.writeNativeSystemBlockEdit(level, pos, state, reason);
        return result.applied() && result.changed();
    }


    private static boolean trySpread(ServerLevel level, ServerCubeCache cache, BlockPos source, BlockPos target, BlockState state, String reason) {
        if (!isCubicTestOutsideShell(level, target)) {
            return false;
        }
        FluidKind targetKind = FluidKind.from(state.getFluidState());
        Optional<BlockState> existing = cache.readOrGenerateBlock(target);
        if (existing.isPresent()) {
            BlockState existingState = existing.get();
            if (!existingState.isAir()) {
                FluidKind existingKind = FluidKind.from(existingState.getFluidState());
                if (targetKind.opposes(existingKind)) {
                    return solidifyFluid(level, cache, target, existingState, reason + "_contact");
                }
                if (existingKind != targetKind || existingState.getFluidState().isSource()) {
                    return false;
                }
                if (fluidAmount(existingState.getFluidState()) >= fluidAmount(state.getFluidState())) {
                    return false;
                }
            }
        }
        if (!writeFluidState(level, target, state, reason)) {
            return false;
        }
        spreadLastTick++;
        totalSpread++;
        int delay = targetKind.tickDelay();
        enqueue(level, cache, target, delay);
        enqueue(level, cache, source, delay);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            enqueue(level, cache, target.relative(direction), delay);
        }
        return true;
    }

    private static void scheduleAround(ServerLevel level, ServerCubeCache cache, BlockPos pos, int minDelay) {
        enqueue(level, cache, pos, minDelay);
        for (Direction direction : Direction.values()) {
            enqueue(level, cache, pos.relative(direction), minDelay);
        }
    }

    private static int delayForPosition(ServerCubeCache cache, BlockPos pos) {
        Optional<BlockState> state = cache.readOrGenerateBlock(pos);
        if (state.isEmpty()) {
            return 1;
        }
        FluidKind kind = FluidKind.from(state.get().getFluidState());
        return kind == FluidKind.NONE ? 1 : kind.tickDelay();
    }

    private static void enqueue(ServerLevel level, ServerCubeCache cache, BlockPos pos, int delay) {
        if (!isCubicTestOutsideShell(level, pos)) {
            return;
        }
        BlockPos immutable = pos.immutable();
        long dueTick = level.getGameTime() + Math.max(1, delay);
        Long previous = QUEUED_DUE_TICKS.get(immutable);
        if (previous != null && previous <= dueTick) {
            return;
        }
        if (previous == null && QUEUE.size() >= MAX_QUEUE) {
            RuntimeProfiler.addCount("gameplay.native_fluid_queue_full", 1);
            return;
        }
        QUEUED_DUE_TICKS.put(immutable, dueTick);
        QUEUE.add(new ScheduledFluidTick(immutable, dueTick));
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

    private record ScheduledFluidTick(BlockPos pos, long dueTick) {
    }

    private enum FluidKind {
        NONE(1, 1),
        WATER(5, 1),
        LAVA(30, 2);

        private final int tickDelay;
        private final int horizontalDrop;

        FluidKind(int tickDelay, int horizontalDrop) {
            this.tickDelay = tickDelay;
            this.horizontalDrop = horizontalDrop;
        }

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

        int tickDelay() {
            return tickDelay;
        }

        int horizontalDrop() {
            return horizontalDrop;
        }

        boolean opposes(FluidKind other) {
            return (this == WATER && other == LAVA) || (this == LAVA && other == WATER);
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

    public record Snapshot(int queue, long totalScheduled, long totalProcessed, long totalSpread, long totalDecay, long totalInteractions,
                           int scheduledLastTick, int processedLastTick, int spreadLastTick, int decayLastTick, int interactionLastTick, int delayedLastTick) {
    }
}
