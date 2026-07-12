package com.ibicza.redlineatlasworldgen.mixin;

import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.river.AtlasRiverIndex;
import com.ibicza.redlineatlasworldgen.river.RiverKind;
import com.ibicza.redlineatlasworldgen.river.RiverSample;
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
                || (AtlasWorldgenConfig.OVERWORLD_ONLY.get() && level.dimension() != Level.OVERWORLD)) {
            return;
        }

        RiverSample river = AtlasRiverIndex.active().sample(pos.getX(), pos.getZ());
        if (!river.hasRiverData()) {
            return;
        }

        // Atlas river columns are already filled explicitly with source blocks after their bed and
        // mirrored banks are built. Vanilla horizontal spreading is therefore both unnecessary and
        // harmful: a source block can otherwise walk onto a high solid ledge outside the channel,
        // then pour down as a many-block waterfall wall far above the fitted gravel rim. Keep the
        // authoritative channel water exactly inside its rasterised cross-section.
        if (river.kind() == RiverKind.CHANNEL) {
            cir.setReturnValue(Map.of());
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
