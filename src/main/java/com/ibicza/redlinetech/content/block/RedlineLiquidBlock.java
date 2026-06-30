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
import com.ibicza.redlinetech.content.liquid.LiquidEvaporationLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

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

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);

        if (!level.isClientSide() && definition.evaporatesToGas()) {
            level.scheduleTick(pos, this, Math.max(20, definition.flowDelayTicks() * 4));
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getFluidState().isSource()) {
            return;
        }

        if (LiquidEvaporationLogic.tryEvaporate(level, pos, definition)) {
            return;
        }

        if (definition.evaporatesToGas()) {
            level.scheduleTick(pos, this, Math.max(20, definition.flowDelayTicks() * 4));
        }
    }
}