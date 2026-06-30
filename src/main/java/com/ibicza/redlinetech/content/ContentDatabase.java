package com.ibicza.redlinetech.content;

import com.ibicza.redlinetech.content.material.MaterialCsvLoader;
import com.ibicza.redlinetech.content.material.MaterialDefinition;
import com.ibicza.redlinetech.content.ore.MetallicOreCsvLoader;
import com.ibicza.redlinetech.content.ore.MetallicOreDefinition;
import com.ibicza.redlinetech.content.ore.NonMetalOreCsvLoader;
import com.ibicza.redlinetech.content.ore.NonMetalOreDefinition;
import com.ibicza.redlinetech.content.ore.OreLikeDefinition;

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

    static {
        try {
            List<MaterialDefinition> materials = MaterialCsvLoader.load();
            Map<String, MaterialDefinition> materialsById = indexMaterials(materials);

            List<MetallicOreDefinition> metallicOres = MetallicOreCsvLoader.load(materialsById);
            List<NonMetalOreDefinition> nonMetalOres = NonMetalOreCsvLoader.load(materialsById);

            List<OreLikeDefinition> allOres = collectOres(metallicOres, nonMetalOres);

            validateLoadedContent(materials, allOres);

            MATERIALS = List.copyOf(materials);
            MATERIALS_BY_ID = Map.copyOf(materialsById);
            METALLIC_ORES = List.copyOf(metallicOres);
            NON_METAL_ORES = List.copyOf(nonMetalOres);
            ALL_ORES = List.copyOf(allOres);
        } catch (Exception exception) {
            throw new RuntimeException(
                    "Failed to load Redline Tech content tables. Check CSV files in src/main/resources/redline_content.",
                    exception
            );
        }
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
            List<OreLikeDefinition> allOres
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
    }

    private ContentDatabase() {
    }
}