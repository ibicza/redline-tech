package com.ibicza.redlinetech.content.dimension;

import com.ibicza.redlinetech.content.csv.CsvTableReader;

import java.util.List;
import java.util.Map;

import static com.ibicza.redlinetech.content.csv.CsvParsers.booleanValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.floatValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.intValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.string;

public final class DimensionEnvironmentCsvLoader {
    private static final String PATH = "redline_content/dimension_environments.csv";

    public static List<DimensionEnvironmentDefinition> load() {
        return CsvTableReader.readResource(PATH).stream()
                .filter(row -> booleanValue(row, "enabled"))
                .map(DimensionEnvironmentCsvLoader::loadOne)
                .toList();
    }

    private static DimensionEnvironmentDefinition loadOne(Map<String, String> row) {
        return new DimensionEnvironmentDefinition(
                string(row, "dimension_id"),
                intValue(row, "ambient_temperature_k"),
                floatValue(row, "pressure_kpa"),
                booleanValue(row, "evaporation_enabled"),
                floatValue(row, "evaporation_multiplier"),
                booleanValue(row, "gas_escape_enabled"),
                intValue(row, "gas_escape_y_min")
        );
    }

    private DimensionEnvironmentCsvLoader() {
    }
}
