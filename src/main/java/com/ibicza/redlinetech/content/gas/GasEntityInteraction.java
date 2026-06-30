package com.ibicza.redlinetech.content.gas;

import com.ibicza.redlinetech.content.liquid.LiquidEffectResolver;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

public final class GasEntityInteraction {
    public static void applyInsideEffects(LivingEntity entity, GasDefinition gas) {
        if (entity.level().isClientSide()) {
            return;
        }

        for (GasEffectEntry effect : gas.effects()) {
            if (entity.getRandom().nextFloat() > effect.chance()) {
                continue;
            }

            LiquidEffectResolver.resolve(effect.effectId()).ifPresentOrElse(
                    mobEffect -> entity.addEffect(new MobEffectInstance(
                            mobEffect,
                            effect.durationTicks(),
                            effect.amplifier()
                    )),
                    () -> {
                        throw new IllegalStateException(
                                "Unknown gas effect id: "
                                        + effect.effectId()
                                        + " in gas "
                                        + gas.id()
                        );
                    }
            );
        }
    }

    private GasEntityInteraction() {
    }
}
