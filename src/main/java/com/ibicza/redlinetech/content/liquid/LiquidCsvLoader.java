package com.ibicza.redlinetech.content.liquid;

import com.ibicza.redlinetech.content.csv.CsvTableReader;

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
import static com.ibicza.redlinetech.content.csv.CsvParsers.intValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.optionalString;
import static com.ibicza.redlinetech.content.csv.CsvParsers.string;

public final class LiquidCsvLoader {
    private static final String PATH = "redline_content/liquids.csv";

    private static final Pattern EFFECT_ID_KEY =
            Pattern.compile("^effect_(\\d+)_id$");

    private static final Pattern EFFECT_DURATION_KEY =
            Pattern.compile("^effect_(\\d+)_duration_ticks$");

    private static final Pattern EFFECT_AMPLIFIER_KEY =
            Pattern.compile("^effect_(\\d+)_amplifier$");

    private static final Pattern EFFECT_CHANCE_KEY =
            Pattern.compile("^effect_(\\d+)_chance$");

    public static List<LiquidDefinition> load() {
        return CsvTableReader.readResource(PATH).stream()
                .filter(row -> booleanValue(row, "enabled"))
                .map(LiquidCsvLoader::loadOne)
                .toList();
    }

    private static LiquidDefinition loadOne(Map<String, String> row) {
        String id = string(row, "id");

        int alpha = intValue(row, "alpha");
        int flowDistance = intValue(row, "flow_distance");
        int flowDelayTicks = intValue(row, "flow_delay_ticks");
        int temperature = intValue(row, "t");

        if (alpha < 0 || alpha > 255) {
            throw new IllegalStateException("Liquid " + id + " has invalid alpha: " + alpha);
        }

        if (flowDistance < 1 || flowDistance > 8) {
            throw new IllegalStateException("Liquid " + id + " has invalid flow_distance: " + flowDistance);
        }

        if (flowDelayTicks < 1) {
            throw new IllegalStateException("Liquid " + id + " has invalid flow_delay_ticks: " + flowDelayTicks);
        }

        if (temperature < 1) {
            throw new IllegalStateException("Liquid " + id + " has invalid temperature: " + temperature);
        }

        return new LiquidDefinition(
                id,
                string(row, "ru_name"),
                string(row, "en_name"),
                color(row, "color"),
                alpha,
                flowDistance,
                flowDelayTicks,
                temperature,
                optionalString(row, "evaporates_to_gas_id"),
                parseEffects(row, id)
        );
    }

    private static List<LiquidEffectEntry> parseEffects(Map<String, String> row, String liquidId) {
        List<Integer> indexes = findEffectIndexes(row);
        List<LiquidEffectEntry> result = new ArrayList<>();

        for (int index : indexes) {
            String effectIdKey = "effect_" + index + "_id";
            String durationKey = "effect_" + index + "_duration_ticks";
            String amplifierKey = "effect_" + index + "_amplifier";
            String chanceKey = "effect_" + index + "_chance";

            ensureEffectColumnsExist(row, liquidId, effectIdKey, durationKey, amplifierKey, chanceKey);

            String effectId = optionalString(row, effectIdKey);
            String durationText = optionalString(row, durationKey);
            String amplifierText = optionalString(row, amplifierKey);
            String chanceText = optionalString(row, chanceKey);

            if (effectId.isBlank()
                    && durationText.isBlank()
                    && amplifierText.isBlank()
                    && chanceText.isBlank()) {
                continue;
            }

            if (effectId.isBlank()
                    || durationText.isBlank()
                    || amplifierText.isBlank()
                    || chanceText.isBlank()) {
                throw new IllegalStateException(
                        "Broken liquid effect group in "
                                + liquidId
                                + ": effect_"
                                + index
                );
            }

            int durationTicks = Integer.parseInt(durationText);
            int amplifier = Integer.parseInt(amplifierText);
            float chance = Float.parseFloat(chanceText);

            if (durationTicks <= 0) {
                throw new IllegalStateException("Liquid " + liquidId + " has effect duration <= 0");
            }

            if (amplifier < 0) {
                throw new IllegalStateException("Liquid " + liquidId + " has effect amplifier < 0");
            }

            if (chance < 0.0F || chance > 1.0F) {
                throw new IllegalStateException("Liquid " + liquidId + " has effect chance outside 0..1");
            }

            result.add(new LiquidEffectEntry(effectId, durationTicks, amplifier, chance));
        }

        return List.copyOf(result);
    }

    private static void ensureEffectColumnsExist(
            Map<String, String> row,
            String liquidId,
            String effectIdKey,
            String durationKey,
            String amplifierKey,
            String chanceKey
    ) {
        if (!row.containsKey(effectIdKey)
                || !row.containsKey(durationKey)
                || !row.containsKey(amplifierKey)
                || !row.containsKey(chanceKey)) {
            throw new IllegalStateException(
                    "Broken liquid effect columns in "
                            + liquidId
                            + ". Every effect_N group must contain id, duration_ticks, amplifier and chance."
            );
        }
    }

    private static List<Integer> findEffectIndexes(Map<String, String> row) {
        Set<Integer> indexes = new HashSet<>();

        for (String key : row.keySet()) {
            addEffectIndex(indexes, EFFECT_ID_KEY.matcher(key));
            addEffectIndex(indexes, EFFECT_DURATION_KEY.matcher(key));
            addEffectIndex(indexes, EFFECT_AMPLIFIER_KEY.matcher(key));
            addEffectIndex(indexes, EFFECT_CHANCE_KEY.matcher(key));
        }

        return indexes.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static void addEffectIndex(Set<Integer> indexes, Matcher matcher) {
        if (matcher.matches()) {
            indexes.add(Integer.parseInt(matcher.group(1)));
        }
    }

    private LiquidCsvLoader() {
    }
}