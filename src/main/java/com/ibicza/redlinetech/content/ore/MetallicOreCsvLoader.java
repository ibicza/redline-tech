package com.ibicza.redlinetech.content.ore;

import com.ibicza.redlinetech.content.block.MiningTier;
import com.ibicza.redlinetech.content.block.MiningTool;
import com.ibicza.redlinetech.content.common.FloatRange;
import com.ibicza.redlinetech.content.common.IntRange;
import com.ibicza.redlinetech.content.csv.CsvTableReader;
import com.ibicza.redlinetech.content.material.MaterialDefinition;
import com.ibicza.redlinetech.content.worldgen.OreWorldgenProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ibicza.redlinetech.content.csv.CsvParsers.booleanValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.color;
import static com.ibicza.redlinetech.content.csv.CsvParsers.enumValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.floatValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.intValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.optionalString;
import static com.ibicza.redlinetech.content.csv.CsvParsers.string;

public final class MetallicOreCsvLoader {
    private static final String PATH = "redline_content/metallic_ores.csv";

    private static final Pattern COMPOSITION_MATERIAL_KEY =
            Pattern.compile("^comp_(\\d+)_material$");

    private static final Pattern COMPOSITION_CONTENT_KEY =
            Pattern.compile("^comp_(\\d+)_content$");

    public static List<MetallicOreDefinition> load(Map<String, MaterialDefinition> materialsById) {
        return CsvTableReader.readResource(PATH).stream()
                .filter(row -> booleanValue(row, "enabled"))
                .map(row -> loadOne(row, materialsById))
                .toList();
    }

    private static MetallicOreDefinition loadOne(
            Map<String, String> row,
            Map<String, MaterialDefinition> materialsById
    ) {
        List<OreCompositionEntry> composition = parseComposition(row, materialsById);

        if (composition.isEmpty()) {
            throw new IllegalStateException("Metallic ore has empty composition: " + string(row, "id"));
        }

        return new MetallicOreDefinition(
                string(row, "id"),
                string(row, "ru_name"),
                string(row, "en_name"),
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
                booleanValue(row, "has_deepslate_variant"),
                composition
        );
    }

    private static List<OreCompositionEntry> parseComposition(
            Map<String, String> row,
            Map<String, MaterialDefinition> materialsById
    ) {
        List<Integer> indexes = findCompositionIndexes(row);
        List<OreCompositionEntry> result = new ArrayList<>();
        Set<String> usedMaterialIds = new HashSet<>();

        if (indexes.isEmpty()) {
            throw new IllegalStateException(
                    "Metallic ore CSV row has no comp_N_material / comp_N_content columns: "
                            + string(row, "id")
            );
        }

        for (int index : indexes) {
            String materialKey = "comp_" + index + "_material";
            String contentKey = "comp_" + index + "_content";

            if (!row.containsKey(materialKey) || !row.containsKey(contentKey)) {
                throw new IllegalStateException(
                        "Broken ore composition columns in "
                                + string(row, "id")
                                + ". Both columns must exist in CSV header: "
                                + materialKey
                                + " and "
                                + contentKey
                );
            }

            String materialId = optionalString(row, materialKey);
            String contentText = optionalString(row, contentKey);

            if (materialId.isBlank() && contentText.isBlank()) {
                continue;
            }

            if (materialId.isBlank() || contentText.isBlank()) {
                throw new IllegalStateException(
                        "Broken ore composition pair in "
                                + string(row, "id")
                                + ": "
                                + materialKey
                                + "="
                                + materialId
                                + ", "
                                + contentKey
                                + "="
                                + contentText
                );
            }

            if (!materialsById.containsKey(materialId)) {
                throw new IllegalStateException(
                        "Unknown material '"
                                + materialId
                                + "' in ore "
                                + string(row, "id")
                );
            }

            float content = Float.parseFloat(contentText);

            if (content <= 0) {
                throw new IllegalStateException(
                        "Ore composition content must be positive in "
                                + string(row, "id")
                                + ": "
                                + materialId
                                + "="
                                + content
                );
            }

            if (!usedMaterialIds.add(materialId)) {
                throw new IllegalStateException(
                        "Duplicate material '"
                                + materialId
                                + "' in ore "
                                + string(row, "id")
                );
            }

            result.add(new OreCompositionEntry(materialId, content));
        }

        return List.copyOf(result);
    }

    private static List<Integer> findCompositionIndexes(Map<String, String> row) {
        Set<Integer> indexes = new HashSet<>();

        for (String key : row.keySet()) {
            Matcher materialMatcher = COMPOSITION_MATERIAL_KEY.matcher(key);

            if (materialMatcher.matches()) {
                indexes.add(Integer.parseInt(materialMatcher.group(1)));
                continue;
            }

            Matcher contentMatcher = COMPOSITION_CONTENT_KEY.matcher(key);

            if (contentMatcher.matches()) {
                indexes.add(Integer.parseInt(contentMatcher.group(1)));
            }
        }

        return indexes.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private MetallicOreCsvLoader() {
    }
}