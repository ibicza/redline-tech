package com.ibicza.redlineatlasworldgen.mixin;

import com.ibicza.redlineatlasworldgen.terrain.AtlasNoiseContext;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps vanilla structure terrain adaptation in world-space while atlas terrain density is sampled
 * in shifted density-space.
 *
 * <p>After M32, structure pieces and jigsaw junctions are created at their final atlas-correct Y.
 * Beardifier directly compares density query coordinates with those world-space boxes. Only its
 * read of {@link DensityFunction.FunctionContext#blockY()} must therefore bypass the atlas density
 * shift. Restricting the bypass to this single invocation leaves caves, aquifers, surface noise and
 * every unrelated density function unchanged.</p>
 */
@Mixin(Beardifier.class)
public abstract class BeardifierMixin {
    @Redirect(
            method = "compute",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;blockY()I"
            )
    )
    private int redlineAtlasWorldgen$readWorldSpaceY(DensityFunction.FunctionContext context) {
        AtlasNoiseContext.beginBeardifierQuery();
        try {
            return context.blockY();
        } finally {
            AtlasNoiseContext.endBeardifierQuery();
        }
    }
}
