package com.ibicza.redlinetech.content.block;

import com.ibicza.redlinetech.content.gas.GasDefinition;
import com.ibicza.redlinetech.content.gas.GasEntityInteraction;
import com.ibicza.redlinetech.content.gas.GasIgnitionLogic;
import com.ibicza.redlinetech.content.gas.GasMovementLogic;
import com.ibicza.redlinetech.content.gas.GasRenderMode;
import com.ibicza.redlinetech.content.gas.RegisteredGas;
import com.ibicza.redlinetech.registry.ModGases;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class RedlineGasBlock extends Block {
    public static final IntegerProperty AMOUNT = IntegerProperty.create("amount", 1, 16);

    private static final VoxelShape NO_COLLISION = Shapes.empty();
    private static final VoxelShape FULL_SELECTION =
            Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);

    private static final VoxelShape[] FLOOR_SHAPES = createFloorShapes();
    private static final VoxelShape[] CEILING_SHAPES = createCeilingShapes();

    private final GasDefinition definition;

    public RedlineGasBlock(GasDefinition definition, Properties properties) {
        super(properties);
        this.definition = definition;
        registerDefaultState(stateDefinition.any().setValue(AMOUNT, clampAmount(definition.maxAmount())));
    }

    public GasDefinition definition() {
        return definition;
    }

    public RegisteredGas registeredGas() {
        RegisteredGas registeredGas = ModGases.getByGasId(definition.id());

        if (registeredGas == null) {
            throw new IllegalStateException("Gas is not registered: " + definition.id());
        }

        return registeredGas;
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
    protected VoxelShape getShape(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context
    ) {
        int amount = clampAmount(state.getValue(AMOUNT));
        int index = amount - 1;

        if (definition.renderMode() == GasRenderMode.FLOOR_LAYER) {
            return FLOOR_SHAPES[index];
        }

        if (definition.renderMode() == GasRenderMode.CEILING_LAYER) {
            return CEILING_SHAPES[index];
        }

        return FULL_SELECTION;
    }

    @Override
    protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getShape(state, level, pos, CollisionContext.empty());
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context
    ) {
        return NO_COLLISION;
    }

    @Override
    protected VoxelShape getVisualShape(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context
    ) {
        return NO_COLLISION;
    }

    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return true;
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

    private static VoxelShape[] createFloorShapes() {
        VoxelShape[] shapes = new VoxelShape[16];

        for (int amount = 1; amount <= 16; amount++) {
            double maxY = amount;
            shapes[amount - 1] = Block.box(
                    0.0D,
                    0.0D,
                    0.0D,
                    16.0D,
                    maxY,
                    16.0D
            );
        }

        return shapes;
    }

    private static VoxelShape[] createCeilingShapes() {
        VoxelShape[] shapes = new VoxelShape[16];

        for (int amount = 1; amount <= 16; amount++) {
            double minY = 16.0D - amount;
            shapes[amount - 1] = Block.box(
                    0.0D,
                    minY,
                    0.0D,
                    16.0D,
                    16.0D,
                    16.0D
            );
        }

        return shapes;
    }

    private static int clampAmount(int amount) {
        return Math.max(1, Math.min(16, amount));
    }
}