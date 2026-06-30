package com.ibicza.redlinetech.content.ore;


import com.ibicza.redlinetech.content.block.MiningTier;
import com.ibicza.redlinetech.content.block.MiningTool;
import com.ibicza.redlinetech.content.common.FloatRange;
import com.ibicza.redlinetech.content.common.IntRange;
import com.ibicza.redlinetech.content.csv.CsvTableReader;
import com.ibicza.redlinetech.content.material.MaterialDefinition;
import com.ibicza.redlinetech.content.worldgen.OreWorldgenProfile;

import java.util.List;
import java.util.Map;

import static com.ibicza.redlinetech.content.csv.CsvParsers.booleanValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.color;
import static com.ibicza.redlinetech.content.csv.CsvParsers.enumValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.floatValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.intValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.string;

public final class NonMetalOreCsvLoader {
    private static final String PATH = "redline_content/non_metal_ores.csv";

    public static List<NonMetalOreDefinition> load(Map<String, MaterialDefinition> materialsById) {
        return CsvTableReader.readResource(PATH).stream()
                .filter(row -> booleanValue(row, "enabled"))
                .map(row -> {
                    String materialId = string(row, "material_id");

                    if (!materialsById.containsKey(materialId)) {
                        throw new IllegalStateException(
                                "Unknown material '" + materialId + "' in non-metal ore " + string(row, "id")
                        );
                    }

                    return new NonMetalOreDefinition(
                            string(row, "id"),
                            string(row, "ru_name"),
                            string(row, "en_name"),
                            materialId,
                            enumValue(row, "drop_style", NonMetalDropStyle.class),
                            floatValue(row, "hardness"),
                            floatValue(row, "resistance"),
                            enumValue(row, "tool", MiningTool.class),
                            enumValue(row, "tier", MiningTier.class),
                            new OreWorldgenProfile(
                                    new IntRange(intValue(row, "y_min"), intValue(row, "y_max")),
                                    new IntRange(intValue(row, "peak_min"), intValue(row, "peak_max")),
                                    new IntRange(intValue(row, "vein_min"), intValue(row, "vein_max")),
                                    new FloatRange(floatValue(row, "amount_min"), floatValue(row, "amount_max"))
                            ),
                            string(row, "base_texture"),
                            string(row, "overlay_texture"),
                            color(row, "color"),
                            booleanValue(row, "has_stone_variant"),
                            booleanValue(row, "has_deepslate_variant")
                    );
                })
                .toList();
    }

    private NonMetalOreCsvLoader() {
    }
}
