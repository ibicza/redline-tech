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
import net.minecraft.world.level.block.Blocks;
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

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Small cube-native redstone MVP for outside-vanilla-shell gameplay.
 *
 * <p>Vanilla redstone is deeply wired into Level#setBlock, neighbor notifications, scheduled ticks and chunk sections.
 * This bridge does not try to be the final clone.  It gives the test range enough deterministic behavior to validate
 * cube-only interaction: lever/button/redstone-block power redstone dust/repeaters/comparators, powered outputs open
 * doors/trapdoors/gates and extend piston visuals.  All reads/writes go through the cube backend.</p>
 */
public final class CubicNativeRedstoneBridge {
    private static final int HORIZONTAL_RADIUS = 10;
    private static final int VERTICAL_RADIUS = 3;
    private static final int MAX_PASSES = 6;

    private CubicNativeRedstoneBridge() {
    }

    public static void refreshAround(ServerLevel level, BlockPos origin, String reason) {
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
            // Dust first, then diode outputs, then consumers.  The few passes let power settle across short lines.
            for (BlockPos pos : candidates) {
                BlockState state = stateAt(cache, pos);
                if (state.getBlock() instanceof RedStoneWireBlock && state.hasProperty(RedStoneWireBlock.POWER)) {
                    int power = Math.max(0, Math.min(15, maxNeighborPower(cache, pos) - 1));
                    if (state.getValue(RedStoneWireBlock.POWER) != power) {
                        if (write(level, pos, state.setValue(RedStoneWireBlock.POWER, power), reason + "_dust")) {
                            changedThisPass++;
                        }
                    }
                }
            }
            for (BlockPos pos : candidates) {
                BlockState state = stateAt(cache, pos);
                if (state.getBlock() instanceof RepeaterBlock && state.hasProperty(BlockStateProperties.POWERED)) {
                    Direction facing = horizontalFacing(state);
                    int input = powerInto(cache, pos.relative(facing.getOpposite()), facing);
                    boolean powered = input > 0;
                    if (state.getValue(BlockStateProperties.POWERED) != powered) {
                        if (write(level, pos, state.setValue(BlockStateProperties.POWERED, powered), reason + "_repeater")) {
                            changedThisPass++;
                            level.playSound(null, pos, SoundEvents.COMPARATOR_CLICK, SoundSource.BLOCKS, 0.25F, powered ? 0.65F : 0.5F);
                        }
                    }
                } else if (state.getBlock() instanceof ComparatorBlock && state.hasProperty(BlockStateProperties.POWERED)) {
                    Direction facing = horizontalFacing(state);
                    boolean powered = powerInto(cache, pos.relative(facing.getOpposite()), facing) > 0;
                    if (state.getValue(BlockStateProperties.POWERED) != powered) {
                        if (write(level, pos, state.setValue(BlockStateProperties.POWERED, powered), reason + "_comparator")) {
                            changedThisPass++;
                            level.playSound(null, pos, SoundEvents.COMPARATOR_CLICK, SoundSource.BLOCKS, 0.25F, powered ? 0.65F : 0.5F);
                        }
                    }
                }
            }
            for (BlockPos pos : candidates) {
                BlockState state = stateAt(cache, pos);
                boolean powered = hasAdjacentPower(cache, pos);
                if (state.getBlock() instanceof DoorBlock && state.hasProperty(BlockStateProperties.OPEN)) {
                    if (!state.is(Blocks.IRON_DOOR)) {
                        // Hand use already controls wooden doors. Redstone also works, but does not force closed every pass.
                        continue;
                    }
                    changedThisPass += setOpenPair(level, cache, pos, state, powered, reason + "_iron_door") ? 1 : 0;
                } else if (state.getBlock() instanceof TrapDoorBlock && state.hasProperty(BlockStateProperties.OPEN)) {
                    if (state.is(Blocks.IRON_TRAPDOOR) && state.getValue(BlockStateProperties.OPEN) != powered) {
                        if (write(level, pos, state.setValue(BlockStateProperties.OPEN, powered), reason + "_iron_trapdoor")) {
                            changedThisPass++;
                        }
                    }
                } else if (state.getBlock() instanceof FenceGateBlock && state.hasProperty(BlockStateProperties.OPEN)) {
                    // Keep gates player-controlled for now; vanilla also allows redstone, but forced closing felt noisy in tests.
                } else if (state.getBlock() instanceof PistonBaseBlock && state.hasProperty(BlockStateProperties.EXTENDED)) {
                    changedThisPass += setPistonExtended(level, cache, pos, state, powered, reason + "_piston") ? 1 : 0;
                }
            }
            changedTotal += changedThisPass;
            if (changedThisPass == 0) {
                break;
            }
        }
        if (changedTotal > 0) {
            RuntimeProfiler.addCount("gameplay.native_redstone_changed", changedTotal);
        }
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
                || state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof PistonBaseBlock
                || state.getBlock() == Blocks.REDSTONE_BLOCK;
    }

    private static int maxNeighborPower(ServerCubeCache cache, BlockPos pos) {
        int max = 0;
        for (Direction direction : Direction.values()) {
            max = Math.max(max, powerInto(cache, pos.relative(direction), direction.getOpposite()));
        }
        return max;
    }

    private static boolean hasAdjacentPower(ServerCubeCache cache, BlockPos pos) {
        return maxNeighborPower(cache, pos) > 0;
    }

    /**
     * Returns power emitted by sourcePos toward a target in direction sourceToTarget.
     */
    private static int powerInto(ServerCubeCache cache, BlockPos sourcePos, Direction sourceToTarget) {
        BlockState source = stateAt(cache, sourcePos);
        if (source.isAir()) {
            return 0;
        }
        if (source.is(Blocks.REDSTONE_BLOCK)) {
            return 15;
        }
        if (source.getBlock() instanceof LeverBlock && source.hasProperty(BlockStateProperties.POWERED)) {
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
        return 0;
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

    private static boolean setOpenPair(ServerLevel level, ServerCubeCache cache, BlockPos pos, BlockState state, boolean open, String reason) {
        if (!state.hasProperty(BlockStateProperties.OPEN) || state.getValue(BlockStateProperties.OPEN) == open) {
            return false;
        }
        boolean changed = write(level, pos, state.setValue(BlockStateProperties.OPEN, open), reason);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            BlockPos other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState otherState = stateAt(cache, other);
            if (otherState.getBlock() == state.getBlock() && otherState.hasProperty(BlockStateProperties.OPEN)) {
                changed |= write(level, other, otherState.setValue(BlockStateProperties.OPEN, open), reason + "_pair");
            }
        }
        return changed;
    }

    private static boolean setPistonExtended(ServerLevel level, ServerCubeCache cache, BlockPos pos, BlockState state, boolean extended, String reason) {
        boolean changed = false;
        Direction facing = horizontalFacing(state);
        BlockPos headPos = pos.relative(facing);
        BlockState headState = stateAt(cache, headPos);
        if (state.getValue(BlockStateProperties.EXTENDED) != extended) {
            changed |= write(level, pos, state.setValue(BlockStateProperties.EXTENDED, extended), reason + "_base");
        }
        if (extended) {
            if (headState.isAir()) {
                BlockState newHead = Blocks.PISTON_HEAD.defaultBlockState()
                        .setValue(BlockStateProperties.FACING, facing)
                        .setValue(PistonHeadBlock.TYPE, state.is(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT)
                        .setValue(PistonHeadBlock.SHORT, false);
                changed |= write(level, headPos, newHead, reason + "_head");
            }
        } else if (headState.getBlock() instanceof PistonHeadBlock) {
            changed |= write(level, headPos, Blocks.AIR.defaultBlockState(), reason + "_head_remove");
        }
        return changed;
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
}
