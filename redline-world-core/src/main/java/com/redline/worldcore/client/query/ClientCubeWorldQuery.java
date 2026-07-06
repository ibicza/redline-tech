package com.redline.worldcore.client.query;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.client.sync.ClientCubeSectionSnapshot;
import com.redline.worldcore.client.sync.ClientCubeSectionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.Optional;
import java.util.function.Supplier;

/** Client-side cube-backed read layer for pick-block/raycast/client collision against native section store. */
public final class ClientCubeWorldQuery {
    private static final ThreadLocal<Boolean> VISUAL_SHELL_WRITE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ClientCubeWorldQuery() {
    }

    /**
     * Temporarily lets client visual-shell writes read the vanilla chunk storage instead of the cube-backed query layer.
     *
     * <p>Without this guard, ClientLevel#setBlock can ask Level#getBlockState for the old state after the cube store was
     * already updated by a delta. The mixin then returns the new cube state (usually AIR after breaking a block), vanilla
     * sees old == new, and the current render section is not rebuilt until the player crosses into another cube.</p>
     */
    public static void runWithoutCubeQueryForVisualShellWrite(Runnable action) {
        callWithoutCubeQueryForVisualShellWrite(() -> {
            action.run();
            return null;
        });
    }

    public static <T> T callWithoutCubeQueryForVisualShellWrite(Supplier<T> action) {
        boolean previous = VISUAL_SHELL_WRITE.get();
        VISUAL_SHELL_WRITE.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            VISUAL_SHELL_WRITE.set(previous);
        }
    }

    public static boolean isVisualShellWriteSuppressed() {
        return VISUAL_SHELL_WRITE.get();
    }

    public static boolean isCubicClientLevel(Level level) {
        return level.isClientSide() && level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL);
    }

    public static Optional<BlockState> blockState(Level level, BlockPos pos) {
        if (isVisualShellWriteSuppressed() || !isCubicClientLevel(level)) {
            return Optional.empty();
        }
        CubePos cubePos = CubePos.fromBlock(pos);
        Optional<ClientCubeSectionSnapshot> snapshot = ClientCubeSectionStore.get(cubePos);
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapshot.get().blockStateAtLocalIndex(CubePos.localIndexFromBlock(pos.getX(), pos.getY(), pos.getZ())));
    }

    public static Optional<FluidState> fluidState(Level level, BlockPos pos) {
        return blockState(level, pos).map(BlockState::getFluidState);
    }
}
