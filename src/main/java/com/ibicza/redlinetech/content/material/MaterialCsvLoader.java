package com.ibicza.redlinetech.content.material;


import com.ibicza.redlinetech.content.csv.CsvTableReader;

import java.util.List;

import static com.ibicza.redlinetech.content.csv.CsvParsers.booleanValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.color;
import static com.ibicza.redlinetech.content.csv.CsvParsers.enumValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.string;

public final class MaterialCsvLoader {
    private static final String PATH = "redline_content/materials.csv";

    public static List<MaterialDefinition> load() {
        return CsvTableReader.readResource(PATH).stream()
                .filter(row -> booleanValue(row, "enabled"))
                .map(row -> new MaterialDefinition(
                        string(row, "id"),
                        string(row, "ru_name"),
                        string(row, "en_name"),
                        enumValue(row, "kind", MaterialKind.class),
                        color(row, "color"),
                        booleanValue(row, "has_ingot"),
                        booleanValue(row, "has_dust"),
                        booleanValue(row, "has_plate"),
                        booleanValue(row, "has_wire"),
                        booleanValue(row, "has_rod"),
                        booleanValue(row, "has_nugget"),
                        booleanValue(row, "has_block")
                ))
                .toList();
    }

    private MaterialCsvLoader() {
    }
}