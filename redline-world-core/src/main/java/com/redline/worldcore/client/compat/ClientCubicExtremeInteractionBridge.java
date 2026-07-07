package com.redline.worldcore.client.compat;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.client.query.ClientCubeWorldQuery;
import com.redline.worldcore.network.CubicExtremeInteractionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.Optional;

/**
 * Client-side half of the outside-shell interaction bridge.
 *
 * <p>M19.8.3 extends the payload with an exact hit vector and a USE action.  The exact hit vector is required for
 * vanilla placement math (stairs top/bottom, slabs, doors, panes, wall/fence shapes), and USE is needed because blocks
 * like doors/trapdoors normally mutate themselves through vanilla Level#setBlock, which cannot write outside the temporary
 * vanilla shell.</p>
 */
public final class ClientCubicExtremeInteractionBridge {
    private ClientCubicExtremeInteractionBridge() {
    }

    public static boolean startDestroyBlock(Minecraft minecraft, BlockPos pos, Direction direction) {
        if (!isOutsideShellCubeBlock(minecraft, pos)) {
            return false;
        }
        playLocalBreakSound(minecraft, pos);
        ClientPacketDistributor.sendToServer(CubicExtremeInteractionPayload.breakBlock(pos, direction));
        return true;
    }


    public static boolean pickBlock(Minecraft minecraft) {
        if (minecraft.gameMode == null || !(minecraft.hitResult instanceof BlockHitResult hit)) {
            return false;
        }
        if (hit.getType() != HitResult.Type.BLOCK || !isOutsideShellCubeBlock(minecraft, hit.getBlockPos())) {
            return false;
        }
        ClientPacketDistributor.sendToServer(CubicExtremeInteractionPayload.pickBlock(hit.getBlockPos(), hit.getDirection()));
        return true;
    }

    public static InteractionResult useItemOn(Minecraft minecraft, InteractionHand hand, BlockHitResult hit) {
        if (minecraft.player == null) {
            return null;
        }
        BlockPos clickedPos = hit.getBlockPos();
        BlockPos placePos = clickedPos.relative(hit.getDirection());
        ItemStack stack = minecraft.player.getItemInHand(hand);

        if (isOutsideShellCubeBlock(minecraft, clickedPos)) {
            Optional<BlockState> clickedState = ClientCubeWorldQuery.blockState(minecraft.level, clickedPos);
            // Vanilla tries block-use before item placement.  Keep that ordering for outside-shell interactive blocks too,
            // otherwise levers/trapdoors/repeaters only work with an empty hand because BlockItem placement steals the click.
            if (clickedState.isPresent() && isNativeUseCandidate(clickedState.get())) {
                ClientPacketDistributor.sendToServer(CubicExtremeInteractionPayload.useBlock(clickedPos, hit.getDirection(), hand, hit.getLocation()));
                return InteractionResult.SUCCESS;
            }
        }

        if (isNativePlaceItem(stack)) {
            if (!isOutsideShellCubeBlock(minecraft, clickedPos) && !isOutsideShellAir(minecraft, placePos)) {
                return null;
            }
            playLocalPlaceSound(minecraft, placePos, stack);
            ClientPacketDistributor.sendToServer(CubicExtremeInteractionPayload.placeBlock(clickedPos, hit.getDirection(), hand, hit.getLocation()));
            return InteractionResult.SUCCESS;
        }

        if (isOutsideShellCubeBlock(minecraft, clickedPos)) {
            ClientPacketDistributor.sendToServer(CubicExtremeInteractionPayload.useBlock(clickedPos, hit.getDirection(), hand, hit.getLocation()));
            return InteractionResult.SUCCESS;
        }
        return null;
    }

    private static boolean isNativeUseCandidate(BlockState state) {
        return state.getBlock() instanceof LeverBlock
                || state.getBlock() instanceof ButtonBlock
                || state.getBlock() instanceof RepeaterBlock
                || state.getBlock() instanceof ComparatorBlock
                || state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock;
    }

    private static boolean isNativePlaceItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item instanceof BlockItem || stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET);
    }

    private static void playLocalBreakSound(Minecraft minecraft, BlockPos pos) {
        if (minecraft.level == null) {
            return;
        }
        Optional<BlockState> state = ClientCubeWorldQuery.blockState(minecraft.level, pos);
        if (state.isEmpty() || state.get().isAir()) {
            return;
        }
        minecraft.level.playLocalSound(pos, state.get().getSoundType().getBreakSound(), SoundSource.BLOCKS,
                (state.get().getSoundType().getVolume() + 1.0F) / 2.0F, state.get().getSoundType().getPitch() * 0.8F, false);
    }

    private static void playLocalPlaceSound(Minecraft minecraft, BlockPos pos, ItemStack stack) {
        if (minecraft.level == null) {
            return;
        }
        if (stack.is(Items.WATER_BUCKET)) {
            minecraft.level.playLocalSound(pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F, false);
            return;
        }
        if (stack.is(Items.LAVA_BUCKET)) {
            minecraft.level.playLocalSound(pos, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 1.0F, 1.0F, false);
            return;
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            BlockState state = blockItem.getBlock().defaultBlockState();
            minecraft.level.playLocalSound(pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS,
                    (state.getSoundType().getVolume() + 1.0F) / 2.0F, state.getSoundType().getPitch() * 0.8F, false);
        }
    }

    private static boolean isOutsideShellCubeBlock(Minecraft minecraft, BlockPos pos) {
        ClientLevel level = minecraft.level;
        if (level == null || !level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL)) {
            return false;
        }
        CubicDimensionSettings settings = CubicDimensionSettings.defaults();
        if (!settings.containsBlockY(pos.getY()) || settings.isBlockInsideVanillaShell(pos.getY())) {
            return false;
        }
        Optional<BlockState> state = ClientCubeWorldQuery.blockState(level, pos);
        return state.isPresent() && !state.get().isAir();
    }

    private static boolean isOutsideShellAir(Minecraft minecraft, BlockPos pos) {
        ClientLevel level = minecraft.level;
        if (level == null || !level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL)) {
            return false;
        }
        CubicDimensionSettings settings = CubicDimensionSettings.defaults();
        if (!settings.containsBlockY(pos.getY()) || settings.isBlockInsideVanillaShell(pos.getY())) {
            return false;
        }
        Optional<BlockState> state = ClientCubeWorldQuery.blockState(level, pos);
        return state.isEmpty() || state.get().isAir();
    }
}
