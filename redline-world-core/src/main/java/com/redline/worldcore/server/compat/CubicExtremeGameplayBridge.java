package com.redline.worldcore.server.compat;

import com.redline.worldcore.RedlineWorldCore;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import com.redline.worldcore.network.CubicExtremeInteractionPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Optional;

/**
 * M19.6 temporary gameplay bridge for real cube height outside the vanilla DimensionType shell.
 *
 * <p>The cube backend can already store/collide at Y=9000 and Y=-12000. Vanilla's normal packet handlers still reject
 * right-click placement before block/item code sees it, and the standard destroy path keeps leaning on the temporary
 * vanilla build height/section shell. This bridge handles only cubic_test positions that are inside the true internal
 * cube range but outside the temporary vanilla shell. Inside the shell vanilla remains in charge.</p>
 *
 * <p>Break hardness, block-specific placement properties and a real model renderer are intentionally still future work.
 * The goal here is to make ordinary hand packets mutate the cube backend instead of forcing the user to run debug
 * commands.</p>
 */
public final class CubicExtremeGameplayBridge {
    private CubicExtremeGameplayBridge() {
    }

    public static void handleClientExtremeInteraction(ServerPlayer player, CubicExtremeInteractionPayload payload) {
        if (payload.action() == CubicExtremeInteractionPayload.Action.BREAK) {
            NativePacketActionResult result = breakOutsideShell(player, payload.blockPos(), payload.direction(), "native_client_payload_break");
            logPayloadResult(player, "break", result);
            return;
        }
        if (payload.action() == CubicExtremeInteractionPayload.Action.PLACE) {
            NativePacketActionResult result = placeOutsideShell(player, payload.hand(), payload.blockPos(), payload.direction(), "native_client_payload_place");
            logPayloadResult(player, "place", result);
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
        if (!isNativePlaceItem(stack)) {
            return false;
        }
        if (!stack.isItemEnabled(level.enabledFeatures())) {
            return false;
        }

        BlockHitResult hit = packet.getHitResult();
        NativePacketActionResult result = placeOutsideShell(player, hand, hit.getBlockPos(), hit.getDirection(), "native_packet_place");
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

        CubicClientSyncBridge.NativeBlockEditResult result = CubicClientSyncBridge.writeNativePlayerBlockEdit(
                player,
                pos,
                Blocks.AIR.defaultBlockState(),
                reason
        );
        if (result.applied() && result.changed()) {
            playBreakSound(level, pos, previous);
            CubicNativeFluidTicker.scheduleAround(level, pos);
        }
        return NativePacketActionResult.consumed(result.applied(), result.message());
    }

    private static NativePacketActionResult placeOutsideShell(ServerPlayer player, InteractionHand hand, BlockPos clickedPos, Direction face, String reason) {
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

        BlockPos placePos = clickedPos.relative(face);
        if (!shouldHandleOutsideShell(level, clickedPos) && !shouldHandleOutsideShell(level, placePos)) {
            return NativePacketActionResult.pass("inside_shell_or_outside_internal");
        }

        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(level.getServer());
        if (!cache.settings().containsBlockY(placePos.getY())) {
            return NativePacketActionResult.consumed(false, "place_outside_internal_range");
        }
        Optional<BlockState> existing = cache.readOrGenerateBlock(placePos);
        if (existing.isPresent() && !existing.get().isAir()) {
            CubicClientSyncBridge.forceNativeSectionSnapshot(player, CubePos.fromBlock(placePos), true);
            return NativePacketActionResult.consumed(false, "place_target_not_air_snapshot_forced");
        }

        BlockState state = stateForNativePlace(stack);
        CubicClientSyncBridge.NativeBlockEditResult result = CubicClientSyncBridge.writeNativePlayerBlockEdit(
                player,
                placePos,
                state,
                reason
        );
        if (result.applied() && result.changed()) {
            consumeNativePlaceItem(player, hand, stack);
            playPlaceSound(level, placePos, state);
            if (!state.getFluidState().isEmpty()) {
                CubicNativeFluidTicker.scheduleAround(level, placePos);
            }
        }
        return NativePacketActionResult.consumed(result.applied(), result.message());
    }

    private static boolean isNativePlaceItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item instanceof BlockItem || stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET);
    }

    private static BlockState stateForNativePlace(ItemStack stack) {
        if (stack.is(Items.WATER_BUCKET)) {
            return Blocks.WATER.defaultBlockState();
        }
        if (stack.is(Items.LAVA_BUCKET)) {
            return Blocks.LAVA.defaultBlockState();
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            return block.defaultBlockState();
        }
        return Blocks.AIR.defaultBlockState();
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

    private record NativePacketActionResult(boolean consumed, boolean applied, String message) {
        static NativePacketActionResult pass(String message) {
            return new NativePacketActionResult(false, false, message);
        }

        static NativePacketActionResult consumed(boolean applied, String message) {
            return new NativePacketActionResult(true, applied, message);
        }
    }
}
