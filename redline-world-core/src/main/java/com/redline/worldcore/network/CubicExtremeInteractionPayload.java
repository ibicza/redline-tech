package com.redline.worldcore.network;

import com.redline.worldcore.RedlineWorldCore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Client -> server native interaction packet for cube-only blocks outside the temporary vanilla height shell.
 *
 * <p>Normal vanilla packets are still useful inside the shell, but their BlockPos encoding is part of the problem at
 * extreme heights: vanilla/FriendlyByteBuf block-position packing only carries the DimensionType-era Y range.  A target
 * like Y=9000 or Y=-12000 is truncated before the server can see it.  This payload therefore serializes X/Y/Z as plain
 * ints and never calls {@code writeBlockPos}/{@code readBlockPos}.</p>
 *
 * <p>This compact payload is a temporary gameplay bridge until the final native cube renderer/interaction stack owns block
 * picking completely.</p>
 */
public record CubicExtremeInteractionPayload(
        Action action,
        BlockPos blockPos,
        Direction direction,
        InteractionHand hand,
        Vec3 hitLocation
) implements CustomPacketPayload {
    public static final Type<CubicExtremeInteractionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(RedlineWorldCore.MOD_ID, "cubic_extreme_interaction"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CubicExtremeInteractionPayload> CODEC = StreamCodec.ofMember(CubicExtremeInteractionPayload::write, CubicExtremeInteractionPayload::read);

    public enum Action {
        BREAK,
        PLACE,
        USE,
        PICK_BLOCK
    }

    public CubicExtremeInteractionPayload {
        if (action == null) {
            throw new IllegalArgumentException("action cannot be null");
        }
        if (blockPos == null) {
            throw new IllegalArgumentException("blockPos cannot be null");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction cannot be null");
        }
        if (hand == null) {
            throw new IllegalArgumentException("hand cannot be null");
        }
        if (hitLocation == null) {
            throw new IllegalArgumentException("hitLocation cannot be null");
        }
    }

    public static CubicExtremeInteractionPayload breakBlock(BlockPos pos, Direction direction) {
        return new CubicExtremeInteractionPayload(Action.BREAK, pos, direction, InteractionHand.MAIN_HAND, Vec3.atCenterOf(pos));
    }

    public static CubicExtremeInteractionPayload placeBlock(BlockPos clickedPos, Direction direction, InteractionHand hand, Vec3 hitLocation) {
        return new CubicExtremeInteractionPayload(Action.PLACE, clickedPos, direction, hand, hitLocation);
    }

    public static CubicExtremeInteractionPayload useBlock(BlockPos clickedPos, Direction direction, InteractionHand hand, Vec3 hitLocation) {
        return new CubicExtremeInteractionPayload(Action.USE, clickedPos, direction, hand, hitLocation);
    }

    public static CubicExtremeInteractionPayload pickBlock(BlockPos pos, Direction direction) {
        return new CubicExtremeInteractionPayload(Action.PICK_BLOCK, pos, direction, InteractionHand.MAIN_HAND, Vec3.atCenterOf(pos));
    }

    /**
     * Vanilla BlockPos network packing is intentionally not used here.  The packed long format is tied to vanilla build
     * height and corrupts cube-only positions outside that shell.
     */
    private static void writeBlockPos32(RegistryFriendlyByteBuf buffer, BlockPos pos) {
        buffer.writeInt(pos.getX());
        buffer.writeInt(pos.getY());
        buffer.writeInt(pos.getZ());
    }

    private static BlockPos readBlockPos32(RegistryFriendlyByteBuf buffer) {
        return new BlockPos(buffer.readInt(), buffer.readInt(), buffer.readInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeEnum(action);
        writeBlockPos32(buffer, blockPos);
        buffer.writeEnum(direction);
        buffer.writeEnum(hand);
        buffer.writeDouble(hitLocation.x);
        buffer.writeDouble(hitLocation.y);
        buffer.writeDouble(hitLocation.z);
    }

    private static CubicExtremeInteractionPayload read(RegistryFriendlyByteBuf buffer) {
        return new CubicExtremeInteractionPayload(
                buffer.readEnum(Action.class),
                readBlockPos32(buffer),
                buffer.readEnum(Direction.class),
                buffer.readEnum(InteractionHand.class),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble())
        );
    }
}
