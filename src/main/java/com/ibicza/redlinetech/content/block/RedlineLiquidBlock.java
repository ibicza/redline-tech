package com.ibicza.redlinetech.content.block;

import com.ibicza.redlinetech.content.liquid.LiquidDefinition;
import com.ibicza.redlinetech.content.liquid.LiquidEntityInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;

public final class RedlineLiquidBlock extends LiquidBlock {
    private final LiquidDefinition definition;

    public RedlineLiquidBlock(
            FlowingFluid fluid,
            LiquidDefinition definition,
            BlockBehaviour.Properties properties
    ) {
        super(fluid, properties);
        this.definition = definition;
    }

    public LiquidDefinition definition() {
        return definition;
    }

    @Override
    protected void entityInside(
            BlockState state,
            Level level,
            BlockPos pos,
            Entity entity,
            InsideBlockEffectApplier effectApplier,
            boolean inside
    ) {
        super.entityInside(state, level, pos, entity, effectApplier, inside);

        if (level.isClientSide()) {
            return;
        }

        if (entity instanceof LivingEntity livingEntity) {
            LiquidEntityInteraction.applyInsideEffects(livingEntity, definition);
        }
    }
}