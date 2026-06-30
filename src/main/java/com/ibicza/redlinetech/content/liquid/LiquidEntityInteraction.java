package com.ibicza.redlinetech.content.liquid;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

public final class LiquidEntityInteraction {
    public static void applyInsideEffects(LivingEntity entity, LiquidDefinition liquid) {
        if (entity.level().isClientSide()) {
            return;
        }

        applyCsvEffects(entity, liquid);
        applyTemperatureEffects(entity, liquid);
    }

    private static void applyCsvEffects(LivingEntity entity, LiquidDefinition liquid) {
        for (LiquidEffectEntry effect : liquid.effects()) {
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
                                "Unknown liquid effect id: "
                                        + effect.effectId()
                                        + " in liquid "
                                        + liquid.id()
                        );
                    }
            );
        }
    }

    private static void applyTemperatureEffects(LivingEntity entity, LiquidDefinition liquid) {
        int temperature = liquid.temperature();

        if (temperature <= 160) {
            applyCryogenicEffects(entity);
            return;
        }

        if (temperature <= 273) {
            applyColdEffects(entity);
            return;
        }

        if (temperature >= 600) {
            applyIgnitingHotEffects(entity);
            return;
        }

        if (temperature >= 373) {
            applyHotEffects(entity);
        }
    }

    private static void applyCryogenicEffects(LivingEntity entity) {
        int maxFrozenTicks = entity.getTicksRequiredToFreeze() + 120;
        int newFrozenTicks = Math.min(maxFrozenTicks, entity.getTicksFrozen() + 20);
        entity.setTicksFrozen(newFrozenTicks);

        if (entity.tickCount % 20 == 0) {
            entity.hurt(entity.damageSources().freeze(), 2.0F);
        }
    }

    private static void applyColdEffects(LivingEntity entity) {
        int maxFrozenTicks = entity.getTicksRequiredToFreeze();
        int newFrozenTicks = Math.min(maxFrozenTicks, entity.getTicksFrozen() + 5);
        entity.setTicksFrozen(newFrozenTicks);
    }

    private static void applyIgnitingHotEffects(LivingEntity entity) {
        entity.setRemainingFireTicks(Math.max(entity.getRemainingFireTicks(), 100));

        if (entity.tickCount % 20 == 0) {
            entity.hurt(entity.damageSources().onFire(), 3.0F);
        }
    }

    private static void applyHotEffects(LivingEntity entity) {
        if (entity.tickCount % 20 == 0) {
            entity.hurt(entity.damageSources().hotFloor(), 1.5F);
        }
    }

    private LiquidEntityInteraction() {
    }
}