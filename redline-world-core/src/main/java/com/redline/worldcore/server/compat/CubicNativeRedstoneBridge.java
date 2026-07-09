package com.redline.worldcore.server.compat;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.profiler.RuntimeProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RedstoneSide;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Cube-native redstone bridge for outside-vanilla-shell gameplay.
 *
 * <p>It is still not a byte-for-byte vanilla redstone engine, but it now follows the important vanilla mechanics instead
 * of the old one-hop MVP: dust power propagates up to vanilla strength 15, repeaters/comparators emit only forward,
 * repeaters use delayed scheduled updates, buttons release after a delay, redstone lamps/doors/trapdoors/gates/pistons are
 * refreshed from neighboring power, and simple piston push/retract behavior works through the cube backend.</p>
 */
public final class CubicNativeRedstoneBridge {
    private static final int HORIZONTAL_RADIUS = 18;
    private static final int VERTICAL_RADIUS = 4;
    private static final int MAX_PASSES = 18;
    private static final int MAX_PISTON_PUSH = 12;

    private static long totalChanged;
    private static long totalDustChanged;
    private static long totalConsumerChanged;
    private static long totalPistonActions;
    private static int changedLastRefresh;
    private static int dustChangedLastRefresh;
    private static int consumerChangedLastRefresh;
    private static int pistonActionsLastRefresh;

    private CubicNativeRedstoneBridge() {
    }

    public static void refreshAround(ServerLevel level, BlockPos origin, String reason) {
        changedLastRefresh = 0;
        dustChangedLastRefresh = 0;
        consumerChangedLastRefresh = 0;
        pistonActionsLastRefresh = 0;
        if (!CubicDimensionKeys.isCubicTest(level)) {
            return;
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        if (!outsideShell(cache, origin)) {
            return;
        }

        Set<BlockPos> candidates = collectCandidates(cache, origin);
        int changedTotal = 0;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            int changedThisPass = 0;
            changedThisPass += refreshDust(level, cache, candidates, reason + "_pass" + pass);
            changedThisPass += refreshDiodes(level, cache, candidates, reason + "_pass" + pass);
            changedThisPass += refreshConsumers(level, cache, candidates, reason + "_pass" + pass);
            changedTotal += changedThisPass;
            if (changedThisPass == 0) {
                break;
            }
        }
        if (changedTotal > 0) {
            changedLastRefresh = changedTotal;
            totalChanged += changedTotal;
            RuntimeProfiler.addCount("gameplay.native_redstone_changed", changedTotal);
            RuntimeProfiler.addCount("gameplay.native_redstone_dust_changed", dustChangedLastRefresh);
            RuntimeProfiler.addCount("gameplay.native_redstone_consumers_changed", consumerChangedLastRefresh);
            RuntimeProfiler.addCount("gameplay.native_redstone_pistons", pistonActionsLastRefresh);
        }
    }

    public static boolean shouldHandle(ServerLevel level, BlockPos pos) {
        if (!level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL)) {
            return false;
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        return outsideShell(cache, pos);
    }

    public static boolean releaseButton(ServerLevel level, BlockPos pos, String reason) {
        if (!shouldHandle(level, pos)) {
            return false;
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        BlockState state = stateAt(cache, pos);
        if (!(state.getBlock() instanceof ButtonBlock) || !state.hasProperty(BlockStateProperties.POWERED) || !state.getValue(BlockStateProperties.POWERED)) {
            return false;
        }
        boolean changed = write(level, pos, state.setValue(BlockStateProperties.POWERED, false), reason + "_button_release");
        if (changed) {
            level.playSound(null, pos, SoundEvents.WOODEN_BUTTON_CLICK_OFF, SoundSource.BLOCKS, 0.3F, 0.5F);
            refreshAround(level, pos, reason + "_button_release_refresh");
        }
        return changed;
    }

    public static boolean applyScheduledDiodePower(ServerLevel level, BlockPos pos, boolean powered, String reason) {
        if (!shouldHandle(level, pos)) {
            return false;
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        BlockState state = stateAt(cache, pos);
        if (!(state.getBlock() instanceof RepeaterBlock || state.getBlock() instanceof ComparatorBlock) || !state.hasProperty(BlockStateProperties.POWERED)) {
            return false;
        }
        boolean inputStillPowered = diodeInputPower(cache, pos, state) > 0;
        if (inputStillPowered != powered) {
            return false;
        }
        if (state.getValue(BlockStateProperties.POWERED) == powered) {
            return false;
        }
        boolean changed = write(level, pos, state.setValue(BlockStateProperties.POWERED, powered), reason + "_diode_scheduled");
        if (changed) {
            level.playSound(null, pos, SoundEvents.COMPARATOR_CLICK, SoundSource.BLOCKS, 0.25F, powered ? 0.65F : 0.5F);
            refreshAround(level, pos, reason + "_diode_scheduled_refresh");
        }
        return changed;
    }

    public static Snapshot snapshot() {
        return new Snapshot(totalChanged, totalDustChanged, totalConsumerChanged, totalPistonActions,
                changedLastRefresh, dustChangedLastRefresh, consumerChangedLastRefresh, pistonActionsLastRefresh);
    }

    private static int refreshDust(ServerLevel level, ServerCubeCache cache, Set<BlockPos> candidates, String reason) {
        int changed = 0;
        for (BlockPos pos : candidates) {
            BlockState state = stateAt(cache, pos);
            if (!(state.getBlock() instanceof RedStoneWireBlock) || !state.hasProperty(RedStoneWireBlock.POWER)) {
                continue;
            }
            int power = computeDustPower(cache, pos);
            BlockState refreshed = applyDustConnections(cache, pos, state.setValue(RedStoneWireBlock.POWER, power));
            if (!refreshed.equals(state) && write(level, pos, refreshed, reason + "_dust")) {
                changed++;
                dustChangedLastRefresh++;
                totalDustChanged++;
            }
        }
        return changed;
    }

    private static int refreshDiodes(ServerLevel level, ServerCubeCache cache, Set<BlockPos> candidates, String reason) {
        int scheduled = 0;
        for (BlockPos pos : candidates) {
            BlockState state = stateAt(cache, pos);
            if (!(state.getBlock() instanceof RepeaterBlock || state.getBlock() instanceof ComparatorBlock) || !state.hasProperty(BlockStateProperties.POWERED)) {
                continue;
            }
            boolean powered = diodeInputPower(cache, pos, state) > 0;
            if (state.getValue(BlockStateProperties.POWERED) == powered) {
                continue;
            }
            int delay = diodeDelay(state);
            CubicNativeRedstoneTicker.scheduleDiode(level, pos, powered, delay, reason + "_diode_delay");
            scheduled++;
        }
        return 0;
    }

    private static int refreshConsumers(ServerLevel level, ServerCubeCache cache, Set<BlockPos> candidates, String reason) {
        int changed = 0;
        for (BlockPos pos : candidates) {
            BlockState state = stateAt(cache, pos);
            boolean powered = maxNeighborPower(cache, pos) > 0;
            if (state.is(Blocks.REDSTONE_LAMP) && state.hasProperty(BlockStateProperties.LIT)) {
                if (state.getValue(BlockStateProperties.LIT) != powered && write(level, pos, state.setValue(BlockStateProperties.LIT, powered), reason + "_lamp")) {
                    changed++;
                    consumerChangedLastRefresh++;
                    totalConsumerChanged++;
                }
            } else if (state.getBlock() instanceof DoorBlock && state.hasProperty(BlockStateProperties.OPEN)) {
                changed += refreshOpenablePair(level, cache, pos, state, powered, reason + "_door") ? 1 : 0;
            } else if (state.getBlock() instanceof TrapDoorBlock && state.hasProperty(BlockStateProperties.OPEN)) {
                changed += refreshPoweredOpenable(level, pos, state, powered, reason + "_trapdoor") ? 1 : 0;
            } else if (state.getBlock() instanceof FenceGateBlock && state.hasProperty(BlockStateProperties.OPEN)) {
                changed += refreshPoweredOpenable(level, pos, state, powered, reason + "_fence_gate") ? 1 : 0;
            } else if (state.getBlock() instanceof PistonBaseBlock && state.hasProperty(BlockStateProperties.EXTENDED)) {
                if (setPistonExtended(level, cache, pos, state, powered, reason + "_piston")) {
                    changed++;
                    pistonActionsLastRefresh++;
                    totalPistonActions++;
                }
            } else if (isRedstoneTorch(state)) {
                changed += refreshTorch(level, cache, pos, state, reason + "_torch") ? 1 : 0;
            }
        }
        return changed;
    }

    private static Set<BlockPos> collectCandidates(ServerCubeCache cache, BlockPos origin) {
        LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
        for (int y = -VERTICAL_RADIUS; y <= VERTICAL_RADIUS; y++) {
            for (int x = -HORIZONTAL_RADIUS; x <= HORIZONTAL_RADIUS; x++) {
                for (int z = -HORIZONTAL_RADIUS; z <= HORIZONTAL_RADIUS; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (outsideShell(cache, pos)) {
                        BlockState state = stateAt(cache, pos);
                        if (isRedstoneRelevant(state)) {
                            result.add(pos);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static boolean isRedstoneRelevant(BlockState state) {
        return state.getBlock() instanceof RedStoneWireBlock
                || state.getBlock() instanceof RepeaterBlock
                || state.getBlock() instanceof ComparatorBlock
                || state.getBlock() instanceof LeverBlock
                || state.getBlock() instanceof ButtonBlock
                || state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof PistonBaseBlock
                || state.getBlock() instanceof PistonHeadBlock
                || isRedstoneTorch(state)
                || state.is(Blocks.REDSTONE_LAMP)
                || state.is(Blocks.REDSTONE_BLOCK)
                || state.hasProperty(BlockStateProperties.POWERED);
    }

    private static int computeDustPower(ServerCubeCache cache, BlockPos pos) {
        int max = 0;
        for (Direction direction : Direction.values()) {
            BlockPos sourcePos = pos.relative(direction);
            BlockState source = stateAt(cache, sourcePos);
            if (source.getBlock() instanceof RedStoneWireBlock && source.hasProperty(RedStoneWireBlock.POWER)) {
                max = Math.max(max, source.getValue(RedStoneWireBlock.POWER) - 1);
            } else {
                max = Math.max(max, directPowerInto(cache, sourcePos, direction.getOpposite()));
                max = Math.max(max, conductorPowerInto(cache, sourcePos, direction.getOpposite()));
            }
        }
        return Math.max(0, Math.min(15, max));
    }

    private static int maxNeighborPower(ServerCubeCache cache, BlockPos pos) {
        int max = 0;
        for (Direction direction : Direction.values()) {
            BlockPos sourcePos = pos.relative(direction);
            max = Math.max(max, directPowerInto(cache, sourcePos, direction.getOpposite()));
            max = Math.max(max, conductorPowerInto(cache, sourcePos, direction.getOpposite()));
        }
        return max;
    }

    private static int diodeInputPower(ServerCubeCache cache, BlockPos pos, BlockState state) {
        Direction facing = horizontalFacing(state);
        BlockPos rear = pos.relative(facing.getOpposite());
        return Math.max(directPowerInto(cache, rear, facing), conductorPowerInto(cache, rear, facing));
    }

    /** Power emitted by sourcePos toward a target in direction sourceToTarget. */
    private static int directPowerInto(ServerCubeCache cache, BlockPos sourcePos, Direction sourceToTarget) {
        BlockState source = stateAt(cache, sourcePos);
        if (source.isAir()) {
            return 0;
        }
        if (source.is(Blocks.REDSTONE_BLOCK)) {
            return 15;
        }
        if (isRedstoneTorch(source)) {
            if (!source.hasProperty(BlockStateProperties.LIT) || !source.getValue(BlockStateProperties.LIT)) {
                return 0;
            }
            Direction attachedTo = torchAttachedDirection(source);
            return sourceToTarget == attachedTo ? 0 : 15;
        }
        if (source.getBlock() instanceof LeverBlock && source.hasProperty(BlockStateProperties.POWERED)) {
            return source.getValue(BlockStateProperties.POWERED) ? 15 : 0;
        }
        if (source.getBlock() instanceof ButtonBlock && source.hasProperty(BlockStateProperties.POWERED)) {
            return source.getValue(BlockStateProperties.POWERED) ? 15 : 0;
        }
        if (source.getBlock() instanceof RedStoneWireBlock && source.hasProperty(RedStoneWireBlock.POWER)) {
            return source.getValue(RedStoneWireBlock.POWER);
        }
        if ((source.getBlock() instanceof RepeaterBlock || source.getBlock() instanceof ComparatorBlock)
                && source.hasProperty(BlockStateProperties.POWERED)
                && source.getValue(BlockStateProperties.POWERED)) {
            Direction facing = horizontalFacing(source);
            return facing == sourceToTarget ? 15 : 0;
        }
        if (source.hasProperty(BlockStateProperties.POWERED)
                && source.getValue(BlockStateProperties.POWERED)
                && !(source.getBlock() instanceof DoorBlock)
                && !(source.getBlock() instanceof TrapDoorBlock)
                && !(source.getBlock() instanceof FenceGateBlock)
                && !(source.getBlock() instanceof RepeaterBlock)
                && !(source.getBlock() instanceof ComparatorBlock)
                && !(source.getBlock() instanceof PistonBaseBlock)) {
            return 15;
        }
        if (source.hasProperty(BlockStateProperties.POWER)) {
            try {
                Integer power = source.getValue(BlockStateProperties.POWER);
                return Math.max(0, Math.min(15, power));
            } catch (IllegalArgumentException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static int conductorPowerInto(ServerCubeCache cache, BlockPos conductorPos, Direction conductorToTarget) {
        BlockState conductor = stateAt(cache, conductorPos);
        if (!isConductor(conductor)) {
            return 0;
        }
        int max = 0;
        for (Direction side : Direction.values()) {
            if (side == conductorToTarget) {
                continue;
            }
            max = Math.max(max, directPowerInto(cache, conductorPos.relative(side), side.getOpposite()));
        }
        return max;
    }

    private static boolean isConductor(BlockState state) {
        return !state.isAir()
                && !state.getFluidState().isSource()
                && state.getBlock() instanceof PistonBaseBlock == false
                && state.canOcclude();
    }

    private static BlockState applyDustConnections(ServerCubeCache cache, BlockPos pos, BlockState state) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            state = setDustSide(state, direction, dustConnectsTo(cache, pos, direction));
        }
        return state;
    }

    private static boolean dustConnectsTo(ServerCubeCache cache, BlockPos pos, Direction direction) {
        BlockState neighbor = stateAt(cache, pos.relative(direction));
        return neighbor.getBlock() instanceof RedStoneWireBlock
                || neighbor.getBlock() instanceof RepeaterBlock
                || neighbor.getBlock() instanceof ComparatorBlock
                || neighbor.getBlock() instanceof LeverBlock
                || neighbor.getBlock() instanceof ButtonBlock
                || neighbor.is(Blocks.REDSTONE_BLOCK)
                || isRedstoneTorch(neighbor)
                || neighbor.hasProperty(BlockStateProperties.POWERED);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setDustSide(BlockState state, Direction direction, boolean connected) {
        String name = direction.getName();
        for (Property property : state.getProperties()) {
            if (property.getName().equals(name) && property.getValueClass() == RedstoneSide.class) {
                return state.setValue(property, connected ? RedstoneSide.SIDE : RedstoneSide.NONE);
            }
        }
        return state;
    }

    private static boolean refreshPoweredOpenable(ServerLevel level, BlockPos pos, BlockState state, boolean powered, String reason) {
        boolean changed = false;
        BlockState next = state;
        if (next.hasProperty(BlockStateProperties.POWERED) && next.getValue(BlockStateProperties.POWERED) != powered) {
            next = next.setValue(BlockStateProperties.POWERED, powered);
            if (next.hasProperty(BlockStateProperties.OPEN)) {
                next = next.setValue(BlockStateProperties.OPEN, powered);
            }
        } else if (!next.hasProperty(BlockStateProperties.POWERED) && next.hasProperty(BlockStateProperties.OPEN) && next.getValue(BlockStateProperties.OPEN) != powered) {
            next = next.setValue(BlockStateProperties.OPEN, powered);
        }
        if (!next.equals(state) && write(level, pos, next, reason)) {
            changed = true;
            consumerChangedLastRefresh++;
            totalConsumerChanged++;
        }
        return changed;
    }

    private static boolean refreshOpenablePair(ServerLevel level, ServerCubeCache cache, BlockPos pos, BlockState state, boolean powered, String reason) {
        if (!state.hasProperty(BlockStateProperties.OPEN)) {
            return false;
        }
        boolean wasPowered = state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
        if (wasPowered == powered) {
            return false;
        }
        BlockState next = state;
        if (next.hasProperty(BlockStateProperties.POWERED)) {
            next = next.setValue(BlockStateProperties.POWERED, powered);
        }
        next = next.setValue(BlockStateProperties.OPEN, powered);
        boolean changed = write(level, pos, next, reason);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            BlockPos other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState otherState = stateAt(cache, other);
            if (otherState.getBlock() == state.getBlock() && otherState.hasProperty(BlockStateProperties.OPEN)) {
                BlockState otherNext = otherState;
                if (otherNext.hasProperty(BlockStateProperties.POWERED)) {
                    otherNext = otherNext.setValue(BlockStateProperties.POWERED, powered);
                }
                otherNext = otherNext.setValue(BlockStateProperties.OPEN, powered);
                changed |= write(level, other, otherNext, reason + "_pair");
            }
        }
        if (changed) {
            consumerChangedLastRefresh++;
            totalConsumerChanged++;
        }
        return changed;
    }

    private static boolean refreshTorch(ServerLevel level, ServerCubeCache cache, BlockPos pos, BlockState state, String reason) {
        if (!state.hasProperty(BlockStateProperties.LIT)) {
            return false;
        }
        Direction attachedTo = torchAttachedDirection(state);
        boolean shouldBeLit = directPowerInto(cache, pos.relative(attachedTo), attachedTo.getOpposite()) == 0
                && conductorPowerInto(cache, pos.relative(attachedTo), attachedTo.getOpposite()) == 0;
        if (state.getValue(BlockStateProperties.LIT) == shouldBeLit) {
            return false;
        }
        boolean changed = write(level, pos, state.setValue(BlockStateProperties.LIT, shouldBeLit), reason);
        if (changed) {
            consumerChangedLastRefresh++;
            totalConsumerChanged++;
        }
        return changed;
    }

    private static Direction torchAttachedDirection(BlockState state) {
        if (state.is(Blocks.REDSTONE_WALL_TORCH) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        return Direction.DOWN;
    }

    private static boolean setPistonExtended(ServerLevel level, ServerCubeCache cache, BlockPos pos, BlockState state, boolean extended, String reason) {
        Direction facing = horizontalFacing(state);
        if (extended) {
            return extendPiston(level, cache, pos, state, facing, reason);
        }
        return retractPiston(level, cache, pos, state, facing, reason);
    }

    private static boolean extendPiston(ServerLevel level, ServerCubeCache cache, BlockPos pos, BlockState state, Direction facing, String reason) {
        if (state.getValue(BlockStateProperties.EXTENDED)) {
            return false;
        }
        BlockPos headPos = pos.relative(facing);
        BlockPos scan = headPos;
        int count = 0;
        while (count < MAX_PISTON_PUSH) {
            BlockState scanState = stateAt(cache, scan);
            if (scanState.isAir()) {
                break;
            }
            if (scanState.getDestroySpeed(level, scan) < 0.0F || scanState.getBlock() instanceof PistonBaseBlock || scanState.getBlock() instanceof PistonHeadBlock) {
                return false;
            }
            scan = scan.relative(facing);
            count++;
        }
        if (count >= MAX_PISTON_PUSH && !stateAt(cache, scan).isAir()) {
            return false;
        }
        boolean changed = false;
        for (int i = count; i > 0; i--) {
            BlockPos from = headPos.relative(facing, i - 1);
            BlockPos to = headPos.relative(facing, i);
            changed |= write(level, to, stateAt(cache, from), reason + "_push");
        }
        BlockState newHead = Blocks.PISTON_HEAD.defaultBlockState()
                .setValue(BlockStateProperties.FACING, facing)
                .setValue(PistonHeadBlock.TYPE, state.is(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT)
                .setValue(PistonHeadBlock.SHORT, false);
        changed |= write(level, headPos, newHead, reason + "_head");
        changed |= write(level, pos, state.setValue(BlockStateProperties.EXTENDED, true), reason + "_base");
        return changed;
    }

    private static boolean retractPiston(ServerLevel level, ServerCubeCache cache, BlockPos pos, BlockState state, Direction facing, String reason) {
        if (!state.getValue(BlockStateProperties.EXTENDED)) {
            return false;
        }
        boolean changed = false;
        BlockPos headPos = pos.relative(facing);
        BlockState headState = stateAt(cache, headPos);
        if (headState.getBlock() instanceof PistonHeadBlock) {
            changed |= write(level, headPos, Blocks.AIR.defaultBlockState(), reason + "_head_remove");
        }
        if (state.is(Blocks.STICKY_PISTON)) {
            BlockPos pullFrom = headPos.relative(facing);
            BlockState pulled = stateAt(cache, pullFrom);
            if (!pulled.isAir() && pulled.getDestroySpeed(level, pullFrom) >= 0.0F && !(pulled.getBlock() instanceof PistonBaseBlock) && !(pulled.getBlock() instanceof PistonHeadBlock)) {
                changed |= write(level, headPos, pulled, reason + "_sticky_pull");
                changed |= write(level, pullFrom, Blocks.AIR.defaultBlockState(), reason + "_sticky_clear");
            }
        }
        changed |= write(level, pos, state.setValue(BlockStateProperties.EXTENDED, false), reason + "_base");
        return changed;
    }

    private static Direction horizontalFacing(BlockState state) {
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        if (state.hasProperty(BlockStateProperties.FACING)) {
            Direction facing = state.getValue(BlockStateProperties.FACING);
            return facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        }
        return Direction.NORTH;
    }

    private static int diodeDelay(BlockState state) {
        if (state.getBlock() instanceof RepeaterBlock && state.hasProperty(RepeaterBlock.DELAY)) {
            return Math.max(1, state.getValue(RepeaterBlock.DELAY)) * 2;
        }
        return 2;
    }

    private static boolean isRedstoneTorch(BlockState state) {
        return state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH);
    }

    private static BlockState stateAt(ServerCubeCache cache, BlockPos pos) {
        Optional<BlockState> state = cache.readOrGenerateBlock(pos);
        return state.orElseGet(() -> Blocks.AIR.defaultBlockState());
    }

    private static boolean outsideShell(ServerCubeCache cache, BlockPos pos) {
        return cache.settings().containsBlockY(pos.getY()) && !cache.settings().isBlockInsideVanillaShell(pos.getY());
    }

    private static boolean write(ServerLevel level, BlockPos pos, BlockState state, String reason) {
        CubicClientSyncBridge.NativeBlockEditResult result = CubicClientSyncBridge.writeNativeSystemBlockEdit(level, pos, state, reason);
        return result.applied() && result.changed();
    }

    public record Snapshot(long totalChanged,
                           long totalDustChanged,
                           long totalConsumerChanged,
                           long totalPistonActions,
                           int changedLastRefresh,
                           int dustChangedLastRefresh,
                           int consumerChangedLastRefresh,
                           int pistonActionsLastRefresh) {
    }
}
