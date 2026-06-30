package com.ibicza.redlinetech.content.liquid;

import com.ibicza.redlinetech.registry.ModEffects;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

import java.util.Optional;

public final class LiquidEffectResolver {
    public static Optional<Holder<MobEffect>> resolve(String effectId) {
        return switch (effectId) {
            case "minecraft:poison" -> Optional.of(MobEffects.POISON);
            case "minecraft:slowness" -> Optional.of(MobEffects.SLOWNESS);
            case "minecraft:nausea" -> Optional.of(MobEffects.NAUSEA);
            case "minecraft:weakness" -> Optional.of(MobEffects.WEAKNESS);
            case "minecraft:wither" -> Optional.of(MobEffects.WITHER);
            case "minecraft:blindness" -> Optional.of(MobEffects.BLINDNESS);
            case "minecraft:hunger" -> Optional.of(MobEffects.HUNGER);

            case "redline_tech:radiation" -> Optional.of(ModEffects.RADIATION);
            case "redline_tech:chemical_burn" -> Optional.of(ModEffects.CHEMICAL_BURN);
            case "redline_tech:toxic_exposure" -> Optional.of(ModEffects.TOXIC_EXPOSURE);
            case "redline_tech:oil_coated" -> Optional.of(ModEffects.OIL_COATED);

            default -> Optional.empty();
        };
    }

    private LiquidEffectResolver() {
    }
}