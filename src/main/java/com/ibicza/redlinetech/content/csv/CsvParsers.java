package com.ibicza.redlinetech.content.csv;


import java.util.Locale;
import java.util.Map;

public final class CsvParsers {
    public static String string(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required CSV value: " + key + " in row " + row);
        }
        return value.trim();
    }

    public static String optionalString(Map<String, String> row, String key) {
        String value = row.get(key);
        return value == null ? "" : value.trim();
    }

    public static int intValue(Map<String, String> row, String key) {
        return Integer.parseInt(string(row, key));
    }

    public static float floatValue(Map<String, String> row, String key) {
        return Float.parseFloat(string(row, key));
    }

    public static boolean booleanValue(Map<String, String> row, String key) {
        String value = string(row, key).toLowerCase(Locale.ROOT);

        return switch (value) {
            case "true", "yes", "1" -> true;
            case "false", "no", "0" -> false;
            default -> throw new IllegalStateException("Invalid boolean value for " + key + ": " + value);
        };
    }

    public static int color(Map<String, String> row, String key) {
        String value = string(row, key);

        if (!value.startsWith("#") || value.length() != 7) {
            throw new IllegalStateException("Invalid color value for " + key + ": " + value);
        }

        return Integer.parseInt(value.substring(1), 16);
    }

    public static <E extends Enum<E>> E enumValue(Map<String, String> row, String key, Class<E> enumClass) {
        String value = string(row, key)
                .trim()
                .toUpperCase(Locale.ROOT);

        return Enum.valueOf(enumClass, value);
    }

    private CsvParsers() {
    }
}
