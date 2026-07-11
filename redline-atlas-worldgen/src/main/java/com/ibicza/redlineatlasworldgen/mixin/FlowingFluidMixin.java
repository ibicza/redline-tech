package com.ibicza.redlineatlasworldgen.mixin;

import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.river.AtlasRiverIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.WaterFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;
import java.util.Map;

@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @Inject(method = "getSpread", at = @At("RETURN"), cancellable = true)
    private void redlineAtlasWorldgen$keepRiverSurfaceSupported(ServerLevel level, BlockPos pos, BlockState state,
                                                                CallbackInfoReturnable<Map<Direction, FluidState>> cir) {
        if (!AtlasWorldgenConfig.RIVER_FLOW_PHYSICS_ENABLED.get()
                || !((Object) this instanceof WaterFluid)
                || (AtlasWorldgenConfig.OVERWORLD_ONLY.get() && level.dimension() != Level.OVERWORLD)
                || !AtlasRiverIndex.active().sample(pos.getX(), pos.getZ()).hasRiverData()) {
            return;
        }
        if (!level.getFluidState(pos.below()).is(FluidTags.WATER)) {
            return;
        }

        Map<Direction, FluidState> original = cir.getReturnValue();
        if (original.isEmpty()) {
            return;
        }
        Map<Direction, FluidState> supported = new EnumMap<>(Direction.class);
        for (Map.Entry<Direction, FluidState> entry : original.entrySet()) {
            BlockPos target = pos.relative(entry.getKey());
            BlockState supportState = level.getBlockState(target.below());
            FluidState supportFluid = supportState.getFluidState();
            if (supportFluid.is(FluidTags.WATER) || supportState.blocksMotion()) {
                supported.put(entry.getKey(), entry.getValue());
            }
        }
        cir.setReturnValue(supported);
    }
}
