package com.ibicza.redlinetech.content.liquid;

import com.ibicza.redlinetech.content.ContentDatabase;
import com.ibicza.redlinetech.content.block.RedlineGasBlock;
import com.ibicza.redlinetech.content.dimension.DimensionEnvironmentDefinition;
import com.ibicza.redlinetech.content.dimension.DimensionEnvironmentResolver;
import com.ibicza.redlinetech.content.gas.GasCloudSpawner;
import com.ibicza.redlinetech.content.gas.GasDefinition;
import com.ibicza.redlinetech.content.gas.RegisteredGas;
import com.ibicza.redlinetech.registry.ModGases;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class LiquidEvaporationLogic {
    public static boolean tryEvaporate(ServerLevel level, BlockPos pos, LiquidDefinition liquid) {
        if (!canEvaporate(level, pos, liquid)) {
            return false;
        }

        GasDefinition gasDefinition = ContentDatabase.GASES_BY_ID.get(liquid.evaporatesToGasId());

        if (gasDefinition == null) {
            throw new IllegalStateException(
                    "Liquid " + liquid.id() + " evaporates to unknown gas: " + liquid.evaporatesToGasId()
            );
        }

        RegisteredGas gas = ModGases.getByGasId(gasDefinition.id());

        if (gas == null) {
            throw new IllegalStateException(
                    "Gas is loaded but not registered as block: " + gasDefinition.id()
            );
        }

        DimensionEnvironmentDefinition environment = DimensionEnvironmentResolver.get(level);
        int gasUnits = calculateGasUnits(liquid, gasDefinition, environment);

        if (gasUnits <= 0) {
            return false;
        }

        level.removeBlock(pos, false);
        GasCloudSpawner.spawnGasUnits(level, pos, gas, gasUnits);
        return true;
    }

    public static boolean canEvaporate(ServerLevel level, BlockPos pos, LiquidDefinition liquid) {
        if (!liquid.evaporatesToGas()) {
            return false;
        }

        if (liquid.evaporationMode() == LiquidEvaporationMode.NONE) {
            return false;
        }

        DimensionEnvironmentDefinition environment = DimensionEnvironmentResolver.get(level);

        if (!environment.evaporationEnabled()) {
            return false;
        }

        if (liquid.evaporationMode() == LiquidEvaporationMode.DIMENSION_TEMPERATURE
                && environment.ambientTemperatureK() < liquid.boilingPointK()) {
            return false;
        }

        return hasGasContact(level, pos);
    }

    private static boolean hasGasContact(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos side = pos.relative(direction);
            BlockState state = level.getBlockState(side);

            if (state.isAir()) {
                return true;
            }

            if (state.getBlock() instanceof RedlineGasBlock) {
                return true;
            }
        }

        return false;
    }

    private static int calculateGasUnits(
            LiquidDefinition liquid,
            GasDefinition gas,
            DimensionEnvironmentDefinition environment
    ) {
        double gasBlocks = liquid.densityKgM3() / gas.densityKgM3();
        gasBlocks *= liquid.gasExpansionScale();
        gasBlocks *= environment.evaporationMultiplier();

        int gasUnits = (int) Math.round(gasBlocks * gas.maxAmount());
        return Math.min(gasUnits, liquid.maxGasUnitsFromOneBlock());
    }

    private LiquidEvaporationLogic() {
    }
}
