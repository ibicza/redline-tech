package com.ibicza.redlinetech.effect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

public final class ChemicalBurnEffect extends MobEffect {
    public ChemicalBurnEffect() {
        super(MobEffectCategory.HARMFUL, 0xC8D96A);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int tickCount, int amplifier) {
        int interval = Math.max(10, 30 - amplifier * 5);
        return tickCount % interval == 0;
    }

    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
        float damage = 1.0F + amplifier * 0.5F;
        entity.hurt(entity.damageSources().magic(), damage);
        return true;
    }
}