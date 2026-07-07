package com.redline.worldcore.server.compat;

import com.redline.worldcore.RedlineWorldCore;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.network.CubicExtremeInteractionPayload;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Outside-vanilla-shell gameplay bridge for the true cube range.
 *
 * <p>M19.8.3 moves the bridge from "default block state" writes to vanilla-like state computation: BlockPlaceContext is
 * built with the real hit vector, blocks use their own getStateForPlacement, and neighbor-dependent states are refreshed
 * with Block.updateFromNeighbourShapes against the cube-backed Level reads.  This keeps fences, panes, stairs, doors and
 * similar blocks much closer to vanilla without reintroducing LevelChunkSection ownership.</p>
 */
public final class CubicExtremeGameplayBridge {
    private CubicExtremeGameplayBridge() {
    }

    public static void handleClientExtremeInteraction(ServerPlayer player, CubicExtremeInteractionPayload payload) {
        NativePacketActionResult result;
        if (payload.action() == CubicExtremeInteractionPayload.Action.BREAK) {
            result = breakOutsideShell(player, payload.blockPos(), payload.direction(), "native_client_payload_break");
            logPayloadResult(player, "break", result);
            return;
        }
        if (payload.action() == CubicExtremeInteractionPayload.Action.PLACE) {
            result = placeOutsideShell(player, payload.hand(), payload.blockPos(), payload.direction(), payload.hitLocation(), "native_client_payload_place");
            logPayloadResult(player, "place", result);
            return;
        }
        if (payload.action() == CubicExtremeInteractionPayload.Action.USE) {
            result = useOutsideShell(player, payload.hand(), payload.blockPos(), payload.direction(), payload.hitLocation(), "native_client_payload_use");
            logPayloadResult(player, "use", result);
            return;
        }
        if (payload.action() == CubicExtremeInteractionPayload.Action.PICK_BLOCK) {
            result = pickBlockOutsideShell(player, payload.blockPos(), "native_client_payload_pick");
            logPayloadResult(player, "pick", result);
        }
    }

    public static boolean handlePlayerAction(ServerPlayer player, ServerboundPlayerActionPacket packet) {
        if (!(player.level() instanceof ServerLevel level) || !CubicDimensionKeys.isCubicTest(level)) {
            return false;
        }
        BlockPos pos = packet.getPos();
        if (!shouldHandleOutsideShell(level, pos)) {
            return false;
        }

        ServerboundPlayerActionPacket.Action action = packet.getAction();
        if (action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) {
            return true;
        }
        if (action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                && action != ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
            return false;
        }

        return breakOutsideShell(player, pos, packet.getDirection(), "native_packet_break_" + action.name().toLowerCase()).consumed();
    }

    public static boolean handleUseItemOn(ServerPlayer player, ServerboundUseItemOnPacket packet) {
        if (!(player.level() instanceof ServerLevel level) || !CubicDimensionKeys.isCubicTest(level)) {
            return false;
        }
        InteractionHand hand = packet.getHand();
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult hit = packet.getHitResult();
        if (isNativePlaceItem(stack)) {
            NativePacketActionResult result = placeOutsideShell(player, hand, hit.getBlockPos(), hit.getDirection(), hit.getLocation(), "native_packet_place");
            return result.consumed();
        }
        NativePacketActionResult result = useOutsideShell(player, hand, hit.getBlockPos(), hit.getDirection(), hit.getLocation(), "native_packet_use");
        return result.consumed();
    }

    private static NativePacketActionResult breakOutsideShell(ServerPlayer player, BlockPos pos, Direction direction, String reason) {
        if (!(player.level() instanceof ServerLevel level) || !CubicDimensionKeys.isCubicTest(level)) {
            return NativePacketActionResult.pass("not_cubic_test");
        }
        if (!shouldHandleOutsideShell(level, pos)) {
            return NativePacketActionResult.pass("inside_shell_or_outside_internal");
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        BlockState previous = cache.readOrGenerateBlock(pos).orElseGet(() -> Blocks.AIR.defaultBlockState());
        if (previous.isAir()) {
            CubicClientSyncBridge.forceNativeSectionSnapshot(player, CubePos.fromBlock(pos), true);
            return NativePacketActionResult.consumed(false, "target_air_snapshot_forced");
        }

        Set<BlockPos> toRemove = linkedPositions(pos);
        addDoubleBlockCounterpart(cache, previous, pos, toRemove);

        boolean applied = false;
        for (BlockPos removePos : toRemove) {
            if (!shouldHandleOutsideShell(level, removePos)) {
                continue;
            }
            Optional<BlockState> maybeState = cache.readOrGenerateBlock(removePos);
            if (maybeState.isEmpty() || maybeState.get().isAir()) {
                continue;
            }
            CubicClientSyncBridge.NativeBlockEditResult result = CubicClientSyncBridge.writeNativePlayerBlockEdit(
                    player,
                    removePos,
                    Blocks.AIR.defaultBlockState(),
                    reason
            );
            applied |= result.applied() && result.changed();
        }
        if (applied) {
            playBreakSound(level, pos, previous);
            for (BlockPos removePos : toRemove) {
                CubicNativeFluidTicker.scheduleAround(level, removePos);
                refreshNativeShapesAround(level, player, removePos, reason + "_neighbor_refresh");
                CubicNativeRedstoneBridge.refreshAround(level, removePos, reason + "_redstone_refresh");
            }
        }
        return NativePacketActionResult.consumed(applied, applied ? "removed=" + toRemove.size() : "remove_rejected");
    }

    private static NativePacketActionResult placeOutsideShell(ServerPlayer player, InteractionHand hand, BlockPos clickedPos, Direction face, Vec3 hitLocation, String reason) {
        if (!(player.level() instanceof ServerLevel level) || !CubicDimensionKeys.isCubicTest(level)) {
            return NativePacketActionResult.pass("not_cubic_test");
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!isNativePlaceItem(stack)) {
            return NativePacketActionResult.pass("not_native_place_item");
        }
        if (!stack.isItemEnabled(level.enabledFeatures())) {
            return NativePacketActionResult.consumed(false, "item_not_enabled");
        }

        BlockPos vanillaFallbackPlacePos = clickedPos.relative(face);
        if (!shouldHandleOutsideShell(level, clickedPos) && !shouldHandleOutsideShell(level, vanillaFallbackPlacePos)) {
            return NativePacketActionResult.pass("inside_shell_or_outside_internal");
        }

        NativePlacement placement = nativePlacementState(player, hand, stack, clickedPos, face, hitLocation);
        if (placement == null || placement.state().isAir()) {
            return NativePacketActionResult.consumed(false, "placement_state_null_or_air");
        }
        BlockPos placePos = placement.pos();
        if (!shouldHandleOutsideShell(level, placePos)) {
            return NativePacketActionResult.consumed(false, "place_pos_not_outside_shell");
        }

        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        Optional<BlockState> existing = cache.readOrGenerateBlock(placePos);
        if (existing.isPresent() && !existing.get().isAir() && (placement.context() == null || !existing.get().canBeReplaced(placement.context()))) {
            CubicClientSyncBridge.forceNativeSectionSnapshot(player, CubePos.fromBlock(placePos), true);
            return NativePacketActionResult.consumed(false, "place_target_not_replaceable_snapshot_forced");
        }
        if (requiresUpperHalf(placement.state())) {
            BlockPos upperPos = placePos.above();
            if (!shouldHandleOutsideShell(level, upperPos)) {
                return NativePacketActionResult.consumed(false, "upper_half_outside_internal_or_inside_shell");
            }
            Optional<BlockState> upperExisting = cache.readOrGenerateBlock(upperPos);
            if (upperExisting.isPresent() && !upperExisting.get().isAir()) {
                return NativePacketActionResult.consumed(false, "upper_half_not_air");
            }
        }
        if (placement.context() != null && !placement.state().canSurvive(level, placePos)) {
            return NativePacketActionResult.consumed(false, "placement_cannot_survive");
        }

        boolean applied = writePlayerBlock(player, placePos, placement.state(), reason);
        if (applied && requiresUpperHalf(placement.state())) {
            BlockState upper = placement.state().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
            applied |= writePlayerBlock(player, placePos.above(), upper, reason + "_upper_half");
        }
        if (applied) {
            consumeNativePlaceItem(player, hand, stack);
            playPlaceSound(level, placePos, placement.state());
            CubicNativeFluidTicker.scheduleAround(level, placePos);
            refreshNativeShapesAround(level, player, placePos, reason + "_neighbor_refresh");
            CubicNativeRedstoneBridge.refreshAround(level, placePos, reason + "_redstone_refresh");
            if (requiresUpperHalf(placement.state())) {
                refreshNativeShapesAround(level, player, placePos.above(), reason + "_upper_neighbor_refresh");
                CubicNativeRedstoneBridge.refreshAround(level, placePos.above(), reason + "_upper_redstone_refresh");
            }
        }
        return NativePacketActionResult.consumed(applied, applied ? "placed=" + placement.state() : "place_rejected");
    }

    private static NativePacketActionResult pickBlockOutsideShell(ServerPlayer player, BlockPos pos, String reason) {
        if (!(player.level() instanceof ServerLevel level) || !CubicDimensionKeys.isCubicTest(level)) {
            return NativePacketActionResult.pass("not_cubic_test");
        }
        if (!shouldHandleOutsideShell(level, pos)) {
            return NativePacketActionResult.pass("inside_shell_or_outside_internal");
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        BlockState state = cache.readOrGenerateBlock(pos).orElseGet(() -> Blocks.AIR.defaultBlockState());
        if (state.isAir()) {
            return NativePacketActionResult.consumed(false, "pick_target_air");
        }
        ItemStack clone = state.getCloneItemStack(level, pos, player.getAbilities().instabuild);
        if (clone.isEmpty()) {
            Item item = state.getBlock().asItem();
            if (item == Items.AIR) {
                return NativePacketActionResult.consumed(false, "pick_no_item");
            }
            clone = new ItemStack(item);
        }
        player.getInventory().addAndPickItem(clone);
        player.containerMenu.broadcastChanges();
        return NativePacketActionResult.consumed(true, "picked=" + clone.getHoverName().getString());
    }

    private static NativePacketActionResult useOutsideShell(ServerPlayer player, InteractionHand hand, BlockPos clickedPos, Direction face, Vec3 hitLocation, String reason) {
        if (!(player.level() instanceof ServerLevel level) || !CubicDimensionKeys.isCubicTest(level)) {
            return NativePacketActionResult.pass("not_cubic_test");
        }
        if (!shouldHandleOutsideShell(level, clickedPos)) {
            return NativePacketActionResult.pass("inside_shell_or_outside_internal");
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        BlockState state = cache.readOrGenerateBlock(clickedPos).orElseGet(() -> Blocks.AIR.defaultBlockState());
        if (state.isAir()) {
            return NativePacketActionResult.consumed(false, "use_target_air");
        }

        NativePacketActionResult useResult = applyNativeUse(player, level, cache, clickedPos, state, reason);
        if (useResult.consumed()) {
            return useResult;
        }

        return NativePacketActionResult.consumed(false, "no_native_use_handler");
    }

    private static NativePacketActionResult applyNativeUse(ServerPlayer player, ServerLevel level, ServerCubeCache cache, BlockPos clickedPos, BlockState state, String reason) {
        Block block = state.getBlock();

        if (block instanceof RepeaterBlock && state.hasProperty(RepeaterBlock.DELAY)) {
            BlockState toggled = state.cycle(RepeaterBlock.DELAY);
            boolean applied = writePlayerBlock(player, clickedPos, toggled, reason + "_repeater_delay");
            if (applied) {
                playComparatorClick(level, clickedPos);
                refreshNativeShapesAround(level, player, clickedPos, reason + "_repeater_refresh");
                CubicNativeRedstoneBridge.refreshAround(level, clickedPos, reason + "_repeater_redstone");
            }
            return NativePacketActionResult.consumed(applied, applied ? "repeater_delay=" + toggled.getValue(RepeaterBlock.DELAY) : "repeater_delay_rejected");
        }

        if (block instanceof ComparatorBlock && state.hasProperty(ComparatorBlock.MODE)) {
            BlockState toggled = state.cycle(ComparatorBlock.MODE);
            boolean applied = writePlayerBlock(player, clickedPos, toggled, reason + "_comparator_mode");
            if (applied) {
                playComparatorClick(level, clickedPos);
                refreshNativeShapesAround(level, player, clickedPos, reason + "_comparator_refresh");
                CubicNativeRedstoneBridge.refreshAround(level, clickedPos, reason + "_comparator_redstone");
            }
            return NativePacketActionResult.consumed(applied, applied ? "comparator_mode=" + toggled.getValue(ComparatorBlock.MODE) : "comparator_mode_rejected");
        }

        if (block instanceof LeverBlock && state.hasProperty(LeverBlock.POWERED)) {
            boolean powered = state.getValue(LeverBlock.POWERED);
            BlockState toggled = state.setValue(LeverBlock.POWERED, !powered);
            boolean applied = writePlayerBlock(player, clickedPos, toggled, reason + "_lever_powered");
            if (applied) {
                playLeverSound(level, clickedPos, !powered);
                refreshNativeShapesAround(level, player, clickedPos, reason + "_lever_refresh");
                CubicNativeRedstoneBridge.refreshAround(level, clickedPos, reason + "_lever_redstone");
            }
            return NativePacketActionResult.consumed(applied, applied ? "lever_powered=" + !powered : "lever_toggle_rejected");
        }

        if (block instanceof ButtonBlock && state.hasProperty(ButtonBlock.POWERED)) {
            if (state.getValue(ButtonBlock.POWERED)) {
                return NativePacketActionResult.consumed(true, "button_already_powered");
            }
            BlockState toggled = state.setValue(ButtonBlock.POWERED, true);
            boolean applied = writePlayerBlock(player, clickedPos, toggled, reason + "_button_press");
            if (applied) {
                playButtonSound(level, clickedPos, true);
                refreshNativeShapesAround(level, player, clickedPos, reason + "_button_refresh");
                CubicNativeRedstoneBridge.refreshAround(level, clickedPos, reason + "_button_redstone");
            }
            return NativePacketActionResult.consumed(applied, applied ? "button_powered=true" : "button_press_rejected");
        }

        if (block instanceof DoorBlock && state.hasProperty(DoorBlock.OPEN)) {
            if (state.is(Blocks.IRON_DOOR)) {
                return NativePacketActionResult.consumed(false, "iron_door_requires_redstone");
            }
            boolean open = state.getValue(DoorBlock.OPEN);
            boolean applied = toggleOpenPair(player, level, cache, clickedPos, state, !open, reason + "_door_toggle");
            if (applied) {
                playDoorSound(level, clickedPos, !open);
                refreshNativeShapesAround(level, player, clickedPos, reason + "_door_refresh");
                CubicNativeRedstoneBridge.refreshAround(level, clickedPos, reason + "_door_redstone");
            }
            return NativePacketActionResult.consumed(applied, applied ? "door_open=" + !open : "door_toggle_rejected");
        }

        if (block instanceof TrapDoorBlock && state.hasProperty(TrapDoorBlock.OPEN)) {
            if (state.is(Blocks.IRON_TRAPDOOR)) {
                return NativePacketActionResult.consumed(false, "iron_trapdoor_requires_redstone");
            }
            boolean open = state.getValue(TrapDoorBlock.OPEN);
            BlockState toggled = state.setValue(TrapDoorBlock.OPEN, !open);
            boolean applied = writePlayerBlock(player, clickedPos, toggled, reason + "_trapdoor_toggle");
            if (applied) {
                playTrapdoorSound(level, clickedPos, !open);
                refreshNativeShapesAround(level, player, clickedPos, reason + "_trapdoor_refresh");
                CubicNativeRedstoneBridge.refreshAround(level, clickedPos, reason + "_trapdoor_redstone");
            }
            return NativePacketActionResult.consumed(applied, applied ? "trapdoor_open=" + !open : "trapdoor_toggle_rejected");
        }

        if (block instanceof FenceGateBlock && state.hasProperty(FenceGateBlock.OPEN)) {
            boolean open = state.getValue(FenceGateBlock.OPEN);
            BlockState toggled = state.setValue(FenceGateBlock.OPEN, !open);
            boolean applied = writePlayerBlock(player, clickedPos, toggled, reason + "_fence_gate_toggle");
            if (applied) {
                playFenceGateSound(level, clickedPos, !open);
                refreshNativeShapesAround(level, player, clickedPos, reason + "_fence_gate_refresh");
                CubicNativeRedstoneBridge.refreshAround(level, clickedPos, reason + "_fence_gate_redstone");
            }
            return NativePacketActionResult.consumed(applied, applied ? "fence_gate_open=" + !open : "fence_gate_toggle_rejected");
        }

        return NativePacketActionResult.pass("no_specific_native_use_handler");
    }

    private static NativePlacement nativePlacementState(ServerPlayer player, InteractionHand hand, ItemStack stack, BlockPos clickedPos, Direction face, Vec3 hitLocation) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        if (stack.is(Items.WATER_BUCKET)) {
            return new NativePlacement(clickedPos.relative(face), Blocks.WATER.defaultBlockState(), null);
        }
        if (stack.is(Items.LAVA_BUCKET)) {
            return new NativePlacement(clickedPos.relative(face), Blocks.LAVA.defaultBlockState(), null);
        }
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        BlockHitResult hit = new BlockHitResult(hitLocation, face, clickedPos, false);
        BlockPlaceContext context = new BlockPlaceContext(player, hand, stack, hit);
        context = blockItem.updatePlacementContext(context);
        if (context == null || !context.canPlace()) {
            return null;
        }

        // Do not call DoorBlock#getStateForPlacement outside the vanilla shell.  Vanilla checks level.getMaxY(), which is
        // still the temporary DimensionType shell max and therefore returns null for Y=9000/-12000.  Build the same core
        // door state manually against the cube backend instead.
        if (blockItem.getBlock() instanceof DoorBlock doorBlock) {
            return nativeDoorPlacement(level, player, context, doorBlock);
        }

        BlockState state = blockItem.getBlock().getStateForPlacement(context);
        if (state == null) {
            return null;
        }
        if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            state = Block.updateFromNeighbourShapes(state, level, context.getClickedPos());
        }
        return new NativePlacement(context.getClickedPos(), state, context);
    }

    private static NativePlacement nativeDoorPlacement(ServerLevel level, ServerPlayer player, BlockPlaceContext context, DoorBlock doorBlock) {
        BlockPos placePos = context.getClickedPos();
        BlockPos upperPos = placePos.above();
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        if (!cache.settings().containsBlockY(placePos.getY()) || !cache.settings().containsBlockY(upperPos.getY())) {
            return null;
        }
        if (cache.settings().isBlockInsideVanillaShell(placePos.getY()) || cache.settings().isBlockInsideVanillaShell(upperPos.getY())) {
            return null;
        }
        BlockState upperExisting = cache.readOrGenerateBlock(upperPos).orElseGet(() -> Blocks.AIR.defaultBlockState());
        if (!upperExisting.canBeReplaced(context)) {
            return null;
        }
        Direction facing = player.getDirection();
        DoorHingeSide hinge = nativeDoorHinge(cache, context, placePos, facing);
        boolean powered = nativeHasAdjacentPower(cache, placePos) || nativeHasAdjacentPower(cache, upperPos);
        BlockState state = doorBlock.defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HINGE, hinge)
                .setValue(DoorBlock.POWERED, powered)
                .setValue(DoorBlock.OPEN, powered)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        return new NativePlacement(placePos, state, context);
    }

    private static DoorHingeSide nativeDoorHinge(ServerCubeCache cache, BlockPlaceContext context, BlockPos pos, Direction facing) {
        BlockPos above = pos.above();
        Direction left = facing.getCounterClockWise();
        Direction right = facing.getClockWise();
        BlockPos leftPos = pos.relative(left);
        BlockPos rightPos = pos.relative(right);
        BlockState leftState = cache.readOrGenerateBlock(leftPos).orElseGet(() -> Blocks.AIR.defaultBlockState());
        BlockState leftAboveState = cache.readOrGenerateBlock(above.relative(left)).orElseGet(() -> Blocks.AIR.defaultBlockState());
        BlockState rightState = cache.readOrGenerateBlock(rightPos).orElseGet(() -> Blocks.AIR.defaultBlockState());
        BlockState rightAboveState = cache.readOrGenerateBlock(above.relative(right)).orElseGet(() -> Blocks.AIR.defaultBlockState());

        int balance = (leftState.isCollisionShapeFullBlock(context.getLevel(), leftPos) ? -1 : 0)
                + (leftAboveState.isCollisionShapeFullBlock(context.getLevel(), above.relative(left)) ? -1 : 0)
                + (rightState.isCollisionShapeFullBlock(context.getLevel(), rightPos) ? 1 : 0)
                + (rightAboveState.isCollisionShapeFullBlock(context.getLevel(), above.relative(right)) ? 1 : 0);
        boolean doorLeft = leftState.getBlock() instanceof DoorBlock && leftState.hasProperty(DoorBlock.HALF) && leftState.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
        boolean doorRight = rightState.getBlock() instanceof DoorBlock && rightState.hasProperty(DoorBlock.HALF) && rightState.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
        if ((!doorLeft || doorRight) && balance <= 0) {
            if ((!doorRight || doorLeft) && balance >= 0) {
                int stepX = facing.getStepX();
                int stepZ = facing.getStepZ();
                Vec3 click = context.getClickLocation();
                double clickX = click.x - (double) pos.getX();
                double clickZ = click.z - (double) pos.getZ();
                boolean rightHinge = stepX < 0 && clickZ < 0.5D
                        || stepX > 0 && clickZ > 0.5D
                        || stepZ < 0 && clickX > 0.5D
                        || stepZ > 0 && clickX < 0.5D;
                return rightHinge ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
            }
            return DoorHingeSide.LEFT;
        }
        return DoorHingeSide.RIGHT;
    }

    private static boolean nativeHasAdjacentPower(ServerCubeCache cache, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState state = cache.readOrGenerateBlock(pos.relative(direction)).orElseGet(() -> Blocks.AIR.defaultBlockState());
            if (state.is(Blocks.REDSTONE_BLOCK)) {
                return true;
            }
            if (state.getBlock() instanceof LeverBlock && state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED)) {
                return true;
            }
        }
        return false;
    }

    private static boolean writePlayerBlock(ServerPlayer player, BlockPos pos, BlockState state, String reason) {
        CubicClientSyncBridge.NativeBlockEditResult result = CubicClientSyncBridge.writeNativePlayerBlockEdit(player, pos, state, reason);
        return result.applied() && result.changed();
    }

    private static void refreshNativeShapesAround(ServerLevel level, ServerPlayer player, BlockPos origin, String reason) {
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        Set<BlockPos> toRefresh = linkedPositions(origin);
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = origin.relative(direction);
            toRefresh.add(neighbor);
            for (Direction second : Direction.Plane.HORIZONTAL) {
                toRefresh.add(neighbor.relative(second));
            }
        }
        for (BlockPos pos : toRefresh) {
            if (!shouldHandleOutsideShell(level, pos)) {
                continue;
            }
            Optional<BlockState> maybeState = cache.readOrGenerateBlock(pos);
            if (maybeState.isEmpty() || maybeState.get().isAir()) {
                continue;
            }
            BlockState state = maybeState.get();
            if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                CubicClientSyncBridge.forceNativeSectionSnapshot(player, CubePos.fromBlock(pos), false);
                continue;
            }
            BlockState refreshed;
            try {
                refreshed = Block.updateFromNeighbourShapes(state, level, pos);
            } catch (RuntimeException exception) {
                RedlineWorldCore.LOGGER.debug("RWC native shape refresh skipped at {}: {}", pos, exception.toString());
                continue;
            }
            if (!refreshed.equals(state)) {
                CubicClientSyncBridge.writeNativeSystemBlockEdit(level, pos, refreshed, reason);
                CubicNativeFluidTicker.scheduleAround(level, pos);
            } else {
                CubicClientSyncBridge.forceNativeSectionSnapshot(player, CubePos.fromBlock(pos), false);
            }
        }
    }

    private static boolean requiresUpperHalf(BlockState state) {
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
    }

    private static void addDoubleBlockCounterpart(ServerCubeCache cache, BlockState state, BlockPos pos, Set<BlockPos> result) {
        if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return;
        }
        BlockPos other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        Optional<BlockState> maybeOther = cache.readOrGenerateBlock(other);
        if (maybeOther.isPresent() && maybeOther.get().getBlock() == state.getBlock()) {
            result.add(other);
        }
    }

    private static Set<BlockPos> linkedPositions(BlockPos origin) {
        LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
        result.add(origin.immutable());
        return result;
    }

    private static boolean isNativePlaceItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item instanceof BlockItem || stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET);
    }

    private static void consumeNativePlaceItem(ServerPlayer player, InteractionHand hand, ItemStack stack) {
        if (player.getAbilities().instabuild) {
            return;
        }
        if (stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET)) {
            player.setItemInHand(hand, new ItemStack(Items.BUCKET));
        } else {
            stack.shrink(1);
        }
        player.containerMenu.broadcastChanges();
    }

    private static void playBreakSound(ServerLevel level, BlockPos pos, BlockState previous) {
        level.playSound(null, pos, previous.getSoundType().getBreakSound(), SoundSource.BLOCKS,
                (previous.getSoundType().getVolume() + 1.0F) / 2.0F, previous.getSoundType().getPitch() * 0.8F);
    }

    private static void playPlaceSound(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.WATER)) {
            level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
            return;
        }
        if (state.is(Blocks.LAVA)) {
            level.playSound(null, pos, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 1.0F, 1.0F);
            return;
        }
        level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS,
                (state.getSoundType().getVolume() + 1.0F) / 2.0F, state.getSoundType().getPitch() * 0.8F);
    }

    private static boolean toggleOpenPair(ServerPlayer player, ServerLevel level, ServerCubeCache cache, BlockPos clickedPos, BlockState state, boolean open, String reason) {
        boolean applied = writePlayerBlock(player, clickedPos, state.setValue(BlockStateProperties.OPEN, open), reason);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            BlockPos other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? clickedPos.above() : clickedPos.below();
            Optional<BlockState> otherState = cache.readOrGenerateBlock(other);
            if (otherState.isPresent() && otherState.get().getBlock() == state.getBlock() && otherState.get().hasProperty(BlockStateProperties.OPEN)) {
                applied |= writePlayerBlock(player, other, otherState.get().setValue(BlockStateProperties.OPEN, open), reason + "_pair");
            }
        }
        return applied;
    }

    private static void playDoorSound(ServerLevel level, BlockPos pos, boolean open) {
        level.playSound(null, pos, open ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private static void playTrapdoorSound(ServerLevel level, BlockPos pos, boolean open) {
        level.playSound(null, pos, open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private static void playFenceGateSound(ServerLevel level, BlockPos pos, boolean open) {
        level.playSound(null, pos, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private static void playLeverSound(ServerLevel level, BlockPos pos, boolean powered) {
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.3F, powered ? 0.6F : 0.5F);
    }

    private static void playButtonSound(ServerLevel level, BlockPos pos, boolean powered) {
        level.playSound(null, pos, powered ? SoundEvents.WOODEN_BUTTON_CLICK_ON : SoundEvents.WOODEN_BUTTON_CLICK_OFF, SoundSource.BLOCKS, 0.3F, powered ? 0.6F : 0.5F);
    }

    private static void playComparatorClick(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.COMPARATOR_CLICK, SoundSource.BLOCKS, 0.3F, 0.55F);
    }

    private static void logPayloadResult(ServerPlayer player, String action, NativePacketActionResult result) {
        if (!result.consumed()) {
            RedlineWorldCore.LOGGER.debug("RWC extreme {} payload passed for {}: {}", action, player.getScoreboardName(), result.message());
            return;
        }
        RedlineWorldCore.LOGGER.debug("RWC extreme {} payload consumed for {}: applied={}, message={}", action, player.getScoreboardName(), result.applied(), result.message());
    }

    public static boolean isInsideTrueCubicRange(ServerLevel level, double y) {
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        return y >= cache.settings().minBlockY() && y <= cache.settings().maxBlockY() + 1.0D;
    }

    public static boolean shouldSuppressBelowWorldKill(ServerLevel level, double y) {
        if (!CubicDimensionKeys.isCubicTest(level)) {
            return false;
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        return y >= cache.settings().minBlockY() - 64.0D;
    }

    private static boolean shouldHandleOutsideShell(ServerLevel level, BlockPos pos) {
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        return cache.settings().containsBlockY(pos.getY()) && !cache.settings().isBlockInsideVanillaShell(pos.getY());
    }

    private record NativePlacement(BlockPos pos, BlockState state, BlockPlaceContext context) {
    }

    private record NativePacketActionResult(boolean consumed, boolean applied, String message) {
        static NativePacketActionResult pass(String message) {
            return new NativePacketActionResult(false, false, message);
        }

        static NativePacketActionResult consumed(boolean applied, String message) {
            return new NativePacketActionResult(true, applied, message);
        }
    }
}
