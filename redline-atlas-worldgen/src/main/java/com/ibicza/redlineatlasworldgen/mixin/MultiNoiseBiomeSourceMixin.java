package com.ibicza.redlineatlasworldgen.mixin;

import com.ibicza.redlineatlasworldgen.biome.AtlasBiomeContext;
import com.ibicza.redlineatlasworldgen.biome.AtlasBiomeHolderLookup;
import com.ibicza.redlineatlasworldgen.biome.AtlasBiomeResolver;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {
    @Inject(method = "getNoiseBiome", at = @At("RETURN"), cancellable = true)
    private void redlineAtlasWorldgen$guideBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler,
                                                 CallbackInfoReturnable<Holder<Biome>> cir) {
        Holder<Biome> vanilla = cir.getReturnValue();
        Optional<ResourceKey<Biome>> vanillaKey = vanilla.unwrapKey();
        if (vanillaKey.isEmpty()) {
            return;
        }

        int blockX = quartX << 2;
        int blockY = quartY << 2;
        int blockZ = quartZ << 2;
        Optional<AtlasBiomeContext> context = AtlasBiomeResolver.context(blockX, blockY, blockZ, 0L);
        if (context.isEmpty()) {
            return;
        }

        ResourceKey<Biome> selected = AtlasBiomeResolver.resolve(context.get(), vanillaKey.get());
        if (selected.equals(vanillaKey.get())) {
            return;
        }

        Holder<Biome> replacement = AtlasBiomeHolderLookup.find(this, selected, vanilla);
        if (replacement != vanilla) {
            cir.setReturnValue(replacement);
        }
    }
}
