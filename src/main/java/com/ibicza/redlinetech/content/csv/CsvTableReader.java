package com.ibicza.redlinetech.content.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CsvTableReader {
    private static final String DELIMITER = ",";

    public static List<Map<String, String>> readResource(String path) {
        ClassLoader classLoader = CsvTableReader.class.getClassLoader();

        try (var input = classLoader.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("CSV resource not found: " + path);
            }

            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                List<String> lines = reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .filter(line -> !line.startsWith("#"))
                        .toList();

                if (lines.isEmpty()) {
                    return List.of();
                }

                String[] headers = split(removeBom(lines.get(0)));
                List<Map<String, String>> rows = new ArrayList<>();

                for (int lineIndex = 1; lineIndex < lines.size(); lineIndex++) {
                    String line = lines.get(lineIndex);
                    String[] values = split(line);

                    if (values.length != headers.length) {
                        throw new IllegalStateException(
                                "Invalid CSV row at " + path + ":" + (lineIndex + 1)
                                        + ". Expected " + headers.length
                                        + " columns, got " + values.length
                                        + ". Row: " + line
                        );
                    }

                    Map<String, String> row = new LinkedHashMap<>();
                    for (int column = 0; column < headers.length; column++) {
                        row.put(headers[column], values[column]);
                    }

                    rows.add(row);
                }

                return List.copyOf(rows);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read CSV resource: " + path, exception);
        }
    }

    private static String[] split(String line) {
        return line.split(DELIMITER, -1);
    }

    private static String removeBom(String value) {
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private CsvTableReader() {
    }
}