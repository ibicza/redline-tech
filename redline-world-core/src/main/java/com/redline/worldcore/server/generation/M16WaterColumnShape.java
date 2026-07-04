package com.redline.worldcore.server.generation;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per X/Z cached M16 hydrology result used while filling one 16x16x16 cube.
 *
 * <p>M16.7 deliberately computes water/shore shape once per column, not once per block. Hydrology shaping can sample
 * neighbouring columns, rivers, lake rims and ocean shore distances, so recomputing it for every Y block caused multi-
 * minute server stalls around rivers and large water bodies.</p>
 */
public record M16WaterColumnShape(
        M16WaterSample water,
        boolean dryShoreActive,
        int dryShoreSurfaceY,
        int dryShoreSoftDepth,
        boolean dryShoreRaise,
        BlockState dryShoreTopState,
        BlockState dryShoreSubState
) {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public static M16WaterColumnShape waterBody(M16WaterSample water) {
        return new M16WaterColumnShape(water, false, Integer.MAX_VALUE, 0, false, AIR, AIR);
    }

    public static M16WaterColumnShape dry(M16WaterSample water, boolean shoreActive, int shoreSurfaceY, int shoreSoftDepth,
                                          boolean shoreRaise, BlockState shoreTopState, BlockState shoreSubState) {
        return new M16WaterColumnShape(water, shoreActive, shoreSurfaceY, shoreSoftDepth, shoreRaise, shoreTopState, shoreSubState);
    }
}
