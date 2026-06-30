package com.ibicza.redlinetech.content.block;

import com.ibicza.redlinetech.content.gas.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import com.ibicza.redlinetech.content.gas.RegisteredGas;
import com.ibicza.redlinetech.registry.ModGases;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.Orientation;

public final class RedlineGasBlock extends Block {
    public static final IntegerProperty AMOUNT = IntegerProperty.create("amount", 1, 16);

    private final GasDefinition definition;

    public RedlineGasBlock(GasDefinition definition, Properties properties) {
        super(properties);
        this.definition = definition;
        registerDefaultState(stateDefinition.any().setValue(AMOUNT, definition.maxAmount()));
    }

    public RegisteredGas registeredGas() {
        RegisteredGas registeredGas = ModGases.getByGasId(definition.id());

        if (registeredGas == null) {
            throw new IllegalStateException("Gas is not registered: " + definition.id());
        }

        return registeredGas;
    }

    public GasDefinition definition() {
        return definition;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AMOUNT);
    }

    @Override
    protected void onPlace(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState oldState,
            boolean movedByPiston
    ) {
        super.onPlace(state, level, pos, oldState, movedByPiston);

        if (!level.isClientSide()) {
            level.scheduleTick(pos, this, definition.spreadDelayTicks());
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        GasMovementLogic.tick(level, pos, state, this, random);
    }

    @Override
    protected void neighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block block,
            Orientation orientation,
            boolean movedByPiston
    ) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);

        if (level.isClientSide()) {
            return;
        }

        if (definition.flammable() && level instanceof ServerLevel serverLevel) {
            if (GasIgnitionLogic.hasIgniterNearby(serverLevel, pos)) {
                serverLevel.scheduleTick(pos, this, 1);
                return;
            }
        }

        level.scheduleTick(pos, this, definition.spreadDelayTicks());
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
            GasEntityInteraction.applyInsideEffects(livingEntity, definition);
        }
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state) {
        return true;
    }

    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacentBlockState, Direction side) {
        return adjacentBlockState.is(this);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
