package com.ibicza.redlinetech.effect;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.Function;

public class OilCoatedEffect extends MobEffect {
    public OilCoatedEffect() {
        super(MobEffectCategory.HARMFUL, 0x6FB36F);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int tickCount, int amplifier) {
        int interval = Math.max(20, 60 - amplifier * 10);
        return tickCount % interval == 0;
    }

    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
        float damage = 0.5F + amplifier * 0.25F;
        entity.hurt(entity.damageSources().magic(), damage);
        return true;
    }

}
