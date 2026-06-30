package com.ibicza.redlinetech.content;

import com.ibicza.redlinetech.content.liquid.LiquidCsvLoader;
import com.ibicza.redlinetech.content.liquid.LiquidDefinition;
import com.ibicza.redlinetech.content.material.MaterialCsvLoader;
import com.ibicza.redlinetech.content.material.MaterialDefinition;
import com.ibicza.redlinetech.content.ore.MetallicOreCsvLoader;
import com.ibicza.redlinetech.content.ore.MetallicOreDefinition;
import com.ibicza.redlinetech.content.ore.NonMetalOreCsvLoader;
import com.ibicza.redlinetech.content.ore.NonMetalOreDefinition;
import com.ibicza.redlinetech.content.ore.OreLikeDefinition;
import com.ibicza.redlinetech.content.dimension.DimensionEnvironmentCsvLoader;
import com.ibicza.redlinetech.content.dimension.DimensionEnvironmentDefinition;
import com.ibicza.redlinetech.content.gas.GasCsvLoader;
import com.ibicza.redlinetech.content.gas.GasDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContentDatabase {
    public static final List<MaterialDefinition> MATERIALS;
    public static final Map<String, MaterialDefinition> MATERIALS_BY_ID;
    public static final List<MetallicOreDefinition> METALLIC_ORES;
    public static final List<NonMetalOreDefinition> NON_METAL_ORES;
    public static final List<OreLikeDefinition> ALL_ORES;
    public static final List<LiquidDefinition> LIQUIDS;
    public static final Map<String, LiquidDefinition> LIQUIDS_BY_ID;
    public static final List<GasDefinition> GASES;
    public static final Map<String, GasDefinition> GASES_BY_ID;
    public static final List<DimensionEnvironmentDefinition> DIMENSION_ENVIRONMENTS;
    public static final Map<String, DimensionEnvironmentDefinition> DIMENSION_ENVIRONMENTS_BY_ID;

    static {
        try {
            List<MaterialDefinition> materials = MaterialCsvLoader.load();
            Map<String, MaterialDefinition> materialsById = indexMaterials(materials);

            List<MetallicOreDefinition> metallicOres = MetallicOreCsvLoader.load(materialsById);
            List<NonMetalOreDefinition> nonMetalOres = NonMetalOreCsvLoader.load(materialsById);

            List<OreLikeDefinition> allOres = collectOres(metallicOres, nonMetalOres);

            List<LiquidDefinition> liquids = LiquidCsvLoader.load();
            Map<String, LiquidDefinition> liquidsById = indexLiquids(liquids);

            List<GasDefinition> gases = GasCsvLoader.load();
            Map<String, GasDefinition> gasesById = indexGases(gases);
            List<DimensionEnvironmentDefinition> dimensionEnvironments = DimensionEnvironmentCsvLoader.load();
            Map<String, DimensionEnvironmentDefinition> dimensionEnvironmentsById = indexDimensionEnvironments(dimensionEnvironments);

            validateLoadedContent(materials, allOres, liquids, gases);

            MATERIALS = List.copyOf(materials);
            MATERIALS_BY_ID = Map.copyOf(materialsById);
            METALLIC_ORES = List.copyOf(metallicOres);
            NON_METAL_ORES = List.copyOf(nonMetalOres);
            ALL_ORES = List.copyOf(allOres);
            LIQUIDS = List.copyOf(liquids);
            LIQUIDS_BY_ID = Map.copyOf(liquidsById);
            GASES = List.copyOf(gases);
            GASES_BY_ID = Map.copyOf(gasesById);
            DIMENSION_ENVIRONMENTS = List.copyOf(dimensionEnvironments);
            DIMENSION_ENVIRONMENTS_BY_ID = Map.copyOf(dimensionEnvironmentsById);
        } catch (Exception exception) {
            throw new RuntimeException(
                    "Failed to load Redline Tech content tables. Check CSV files in src/main/resources/redline_content.",
                    exception
            );
        }
    }

    private static Map<String, GasDefinition> indexGases(List<GasDefinition> gases) {
        Map<String, GasDefinition> result = new LinkedHashMap<>();
        for (GasDefinition gas : gases) {
            GasDefinition previous = result.put(gas.id(), gas);
            if (previous != null) throw new IllegalStateException("Duplicate gas id: " + gas.id());
        }
        return result;
    }

    private static Map<String, DimensionEnvironmentDefinition> indexDimensionEnvironments(
            List<DimensionEnvironmentDefinition> dimensionEnvironments
    ) {
        Map<String, DimensionEnvironmentDefinition> result = new LinkedHashMap<>();
        for (DimensionEnvironmentDefinition environment : dimensionEnvironments) {
            DimensionEnvironmentDefinition previous = result.put(environment.dimensionId(), environment);
            if (previous != null) throw new IllegalStateException("Duplicate dimension environment id: " + environment.dimensionId());
        }
        return result;
    }


    private static Map<String, MaterialDefinition> indexMaterials(List<MaterialDefinition> materials) {
        Map<String, MaterialDefinition> result = new LinkedHashMap<>();

        for (MaterialDefinition material : materials) {
            MaterialDefinition previous = result.put(material.id(), material);

            if (previous != null) {
                throw new IllegalStateException("Duplicate material id: " + material.id());
            }
        }

        return result;
    }

    private static Map<String, LiquidDefinition> indexLiquids(List<LiquidDefinition> liquids) {
        Map<String, LiquidDefinition> result = new LinkedHashMap<>();

        for (LiquidDefinition liquid : liquids) {
            LiquidDefinition previous = result.put(liquid.id(), liquid);

            if (previous != null) {
                throw new IllegalStateException("Duplicate liquid id: " + liquid.id());
            }
        }

        return result;
    }

    private static List<OreLikeDefinition> collectOres(
            List<MetallicOreDefinition> metallicOres,
            List<NonMetalOreDefinition> nonMetalOres
    ) {
        List<OreLikeDefinition> result = new ArrayList<>();
        result.addAll(metallicOres);
        result.addAll(nonMetalOres);
        return result;
    }

    private static void validateLoadedContent(
            List<MaterialDefinition> materials,
            List<OreLikeDefinition> allOres,
            List<LiquidDefinition> liquids,
            List<GasDefinition> gases
    ) {
        if (materials.isEmpty()) {
            throw new IllegalStateException(
                    "No materials loaded. Check redline_content/materials.csv line breaks and enabled column."
            );
        }

        if (allOres.isEmpty()) {
            throw new IllegalStateException(
                    "No ores loaded. Check redline_content/metallic_ores.csv and redline_content/non_metal_ores.csv line breaks and enabled column."
            );
        }

        if (liquids.isEmpty()) {
            throw new IllegalStateException(
                    "No liquids loaded. Check redline_content/liquids.csv line breaks and enabled column."
            );
        }

        if (gases.isEmpty()) {
            throw new IllegalStateException("No gases loaded. Check redline_content/gases.csv.");
        }
    }

    private ContentDatabase() {
    }
}