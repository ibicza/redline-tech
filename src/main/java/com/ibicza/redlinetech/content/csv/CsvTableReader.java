package com.ibicza.redlinetech.content.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CsvTableReader {
    public static List<Map<String, String>> readResource(String path) {
        try (InputStream inputStream = CsvTableReader.class.getClassLoader().getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new IllegalStateException("CSV resource not found: " + path);
            }

            List<String> lines = readUsefulLines(inputStream);

            if (lines.isEmpty()) {
                throw new IllegalStateException("CSV resource is empty: " + path);
            }

            String[] headers = splitCsvLine(removeBom(lines.getFirst()));

            if (headers.length == 0) {
                throw new IllegalStateException("CSV resource has empty header: " + path);
            }

            List<Map<String, String>> rows = new ArrayList<>();

            for (int index = 1; index < lines.size(); index++) {
                String line = lines.get(index);
                int lineNumber = index + 1;

                String[] rawValues = splitCsvLine(line);
                String[] values = normalizeColumnCount(path, lineNumber, headers, rawValues, line);

                Map<String, String> row = new LinkedHashMap<>();

                for (int column = 0; column < headers.length; column++) {
                    row.put(headers[column].trim(), values[column].trim());
                }

                rows.add(row);
            }

            return List.copyOf(rows);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read CSV resource: " + path, exception);
        }
    }

    private static List<String> readUsefulLines(InputStream inputStream) throws IOException {
        List<String> result = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)
        )) {
            String line;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                if (trimmed.isBlank()) {
                    continue;
                }

                if (trimmed.startsWith("#")) {
                    continue;
                }

                result.add(trimmed);
            }
        }

        return result;
    }

    private static String[] normalizeColumnCount(
            String path,
            int lineNumber,
            String[] headers,
            String[] values,
            String originalLine
    ) {
        if (values.length == headers.length) {
            return values;
        }

        if (values.length < headers.length) {
            return padMissingColumns(headers, values);
        }

        throw new IllegalStateException(
                "Invalid CSV row at "
                        + path
                        + ":"
                        + lineNumber
                        + ". Expected "
                        + headers.length
                        + " columns, got "
                        + values.length
                        + ". Row: "
                        + originalLine
        );
    }

    private static String[] padMissingColumns(String[] headers, String[] values) {
        String[] normalized = new String[headers.length];
        Arrays.fill(normalized, "");

        if (hasEnabledColumnAtEnd(headers) && values.length > 0 && isBooleanText(values[values.length - 1])) {
            for (int index = 0; index < values.length - 1; index++) {
                normalized[index] = values[index];
            }

            normalized[headers.length - 1] = values[values.length - 1];
            return normalized;
        }

        System.arraycopy(values, 0, normalized, 0, values.length);
        return normalized;
    }

    private static boolean hasEnabledColumnAtEnd(String[] headers) {
        return headers.length > 0 && "enabled".equals(headers[headers.length - 1].trim());
    }

    private static boolean isBooleanText(String value) {
        String normalized = value.trim();

        return "true".equalsIgnoreCase(normalized)
                || "false".equalsIgnoreCase(normalized);
    }

    private static String[] splitCsvLine(String line) {
        return line.split(",", -1);
    }

    private static String removeBom(String value) {
        if (value.startsWith("\uFEFF")) {
            return value.substring(1);
        }

        return value;
    }

    private CsvTableReader() {
    }
}