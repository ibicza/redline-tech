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
    public static final List<MaterialDefinition> MATERIALS = MaterialCsvLoader.load();
    public static final Map<String, MaterialDefinition> MATERIALS_BY_ID = indexMaterials(MATERIALS);

    public static final List<MetallicOreDefinition> METALLIC_ORES =
            MetallicOreCsvLoader.load(MATERIALS_BY_ID);

    public static final List<NonMetalOreDefinition> NON_METAL_ORES =
            NonMetalOreCsvLoader.load(MATERIALS_BY_ID);

    public static final List<OreLikeDefinition> ALL_ORES = collectOres();

    private static Map<String, MaterialDefinition> indexMaterials(List<MaterialDefinition> materials) {
        Map<String, MaterialDefinition> result = new LinkedHashMap<>();

        for (MaterialDefinition material : materials) {
            MaterialDefinition previous = result.put(material.id(), material);

            if (previous != null) {
                throw new IllegalStateException("Duplicate material id: " + material.id());
            }
        }

        return Map.copyOf(result);
    }

    private static List<OreLikeDefinition> collectOres() {
        List<OreLikeDefinition> result = new ArrayList<>();
        result.addAll(METALLIC_ORES);
        result.addAll(NON_METAL_ORES);
        return List.copyOf(result);
    }

    private ContentDatabase() {
    }
}
