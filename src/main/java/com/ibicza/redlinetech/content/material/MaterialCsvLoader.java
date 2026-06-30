package com.ibicza.redlinetech.content.material;

import com.ibicza.redlinetech.content.csv.CsvTableReader;

import java.util.List;
import java.util.Map;

import static com.ibicza.redlinetech.content.csv.CsvParsers.booleanValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.color;
import static com.ibicza.redlinetech.content.csv.CsvParsers.enumValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.string;

public final class MaterialCsvLoader {
    private static final String PATH = "redline_content/materials.csv";

    public static List<MaterialDefinition> load() {
        return CsvTableReader.readResource(PATH).stream()
                .filter(row -> booleanValue(row, "enabled"))
                .map(MaterialCsvLoader::loadOne)
                .toList();
    }

    private static MaterialDefinition loadOne(Map<String, String> row) {
        return new MaterialDefinition(
                string(row, "id"),
                string(row, "ru_name"),
                string(row, "en_name"),
                enumValue(row, "kind", MaterialKind.class),
                color(row, "color"),

                optionalBoolean(row, "has_ingot"),
                optionalBoolean(row, "has_dust"),
                optionalBoolean(row, "has_small_dust"),

                optionalBoolean(row, "has_plate"),
                optionalBoolean(row, "has_dense_plate"),
                optionalBoolean(row, "has_casing"),

                optionalBoolean(row, "has_wire"),
                optionalBoolean(row, "has_rod"),
                optionalBoolean(row, "has_foil"),
                optionalBoolean(row, "has_ribbon"),

                optionalBoolean(row, "has_nugget"),
                optionalBoolean(row, "has_block")
        );
    }

    private static boolean optionalBoolean(Map<String, String> row, String column) {
        String value = row.get(column);

        if (value == null || value.isBlank()) {
            return false;
        }

        return Boolean.parseBoolean(value.trim());
    }

    private MaterialCsvLoader() {
    }
}