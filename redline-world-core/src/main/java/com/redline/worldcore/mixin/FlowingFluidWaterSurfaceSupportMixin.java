package com.redline.worldcore.mixin;

import com.redline.worldcore.server.compat.WaterSurfaceSupportDebug;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla behavior:
 *   FlowingFluid treats same-fluid blocks below a side candidate as a hole/downflow path. Water placed on top of water
 *   therefore does not behave like water placed on a solid floor; it tends to merge/fall instead of spreading as a thin
 *   horizontal surface layer.
 *
 * Cubic behavior:
 *   Redline rivers need a water surface to act like a horizontal support for spread, but only horizontally. A water
 *   surface under the current/target cell is allowed to behave like a floor for side spreading, while it is explicitly
 *   forbidden to grant a downward-flow path. This override is global for water, not limited to cubic_test, by design.
 *
 * Reason:
 *   M16 rivers can step down through terrain. Letting water spread over water as a support smooths river surfaces and
 *   gives a current-like edge without reintroducing the old leak into not-yet-generated empty space.
 *
 * Risk:
 *   Global water behavior is slightly different from vanilla. The rule is intentionally narrow: only same-water support
 *   is affected, lava and other fluids are untouched, and downward flow through that support is blocked.
 *
 * Fallback:
 *   Remove this mixin entry from redline_world_core.mixins.json to return to vanilla water behavior.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidWaterSurfaceSupportMixin {
    @Shadow
    public abstract Fluid getSource();

    @Shadow
    private boolean canMaybePassThrough(BlockGetter level, BlockPos sourcePos, BlockState sourceState, Direction direction,
                                        BlockPos testPos, BlockState testState, FluidState testFluidState) {
        throw new AssertionError();
    }

    @Inject(method = "isWaterHole", at = @At("HEAD"), cancellable = true)
    private void redline$treatWaterBelowAsHorizontalSupport(BlockGetter level, BlockPos topPos, BlockState topState,
                                                            BlockPos bottomPos, BlockState bottomState,
                                                            CallbackInfoReturnable<Boolean> cir) {
        if (!redline$isWater()) {
            return;
        }
        FluidState bottomFluid = bottomState.getFluidState();
        if (bottomFluid.getType().isSame(Fluids.WATER)) {
            WaterSurfaceSupportDebug.recordWaterHoleBlocked();
            cir.setReturnValue(false);
        }
    }

    @Redirect(
            method = "spread",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FlowingFluid;canMaybePassThrough(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Z",
                    ordinal = 0
            )
    )
    private boolean redline$blockDownflowThroughWaterSupport(FlowingFluid instance, BlockGetter level, BlockPos sourcePos,
                                                             BlockState sourceState, Direction direction, BlockPos testPos,
                                                             BlockState testState, FluidState testFluidState) {
        if (direction == Direction.DOWN && redline$isWater() && testFluidState.getType().isSame(Fluids.WATER)) {
            WaterSurfaceSupportDebug.recordDownflowBlocked();
            return false;
        }
        return this.canMaybePassThrough(level, sourcePos, sourceState, direction, testPos, testState, testFluidState);
    }

    private boolean redline$isWater() {
        return this.getSource().isSame(Fluids.WATER);
    }
}
