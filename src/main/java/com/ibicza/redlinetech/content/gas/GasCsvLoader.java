package com.ibicza.redlinetech.content.gas;

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
import static com.ibicza.redlinetech.content.csv.CsvParsers.enumValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.intValue;
import static com.ibicza.redlinetech.content.csv.CsvParsers.optionalString;
import static com.ibicza.redlinetech.content.csv.CsvParsers.string;

public final class GasCsvLoader {
    private static final String PATH = "redline_content/gases.csv";

    private static final Pattern EFFECT_ID_KEY = Pattern.compile("^effect_(\\d+)_id$");
    private static final Pattern EFFECT_DURATION_KEY = Pattern.compile("^effect_(\\d+)_duration_ticks$");
    private static final Pattern EFFECT_AMPLIFIER_KEY = Pattern.compile("^effect_(\\d+)_amplifier$");
    private static final Pattern EFFECT_CHANCE_KEY = Pattern.compile("^effect_(\\d+)_chance$");

    public static List<GasDefinition> load() {
        return CsvTableReader.readResource(PATH).stream()
                .filter(row -> booleanValue(row, "enabled"))
                .map(GasCsvLoader::loadOne)
                .toList();
    }

    private static GasDefinition loadOne(Map<String, String> row) {
        String id = string(row, "id");

        int alpha = intValue(row, "alpha");
        int spreadDelayTicks = intValue(row, "spread_delay_ticks");
        int maxAmount = intValue(row, "max_amount");
        double densityKgM3 = doubleValue(row, "density_kg_m3");
        float explosionPower = floatValue(row, "explosion_power");
        float escapeChance = floatValue(row, "escape_chance");

        if (alpha < 0 || alpha > 255) {
            throw new IllegalStateException("Gas " + id + " has invalid alpha: " + alpha);
        }

        if (densityKgM3 <= 0.0D) {
            throw new IllegalStateException("Gas " + id + " has invalid density_kg_m3: " + densityKgM3);
        }

        if (spreadDelayTicks < 1) {
            throw new IllegalStateException("Gas " + id + " has invalid spread_delay_ticks: " + spreadDelayTicks);
        }

        if (maxAmount < 1 || maxAmount > 16) {
            throw new IllegalStateException("Gas " + id + " has invalid max_amount: " + maxAmount + ". Use 1..16.");
        }

        if (explosionPower < 0.0F) {
            throw new IllegalStateException("Gas " + id + " has invalid explosion_power: " + explosionPower);
        }

        if (escapeChance < 0.0F || escapeChance > 1.0F) {
            throw new IllegalStateException("Gas " + id + " has invalid escape_chance: " + escapeChance);
        }

        return new GasDefinition(
                id,
                string(row, "ru_name"),
                string(row, "en_name"),
                color(row, "color"),
                alpha,
                densityKgM3,
                spreadDelayTicks,
                maxAmount,
                enumValue(row, "render_mode", GasRenderMode.class),
                booleanValue(row, "flammable"),
                explosionPower,
                booleanValue(row, "escape_to_atmosphere"),
                intValue(row, "escape_y_min"),
                escapeChance,
                parseEffects(row, id)
        );
    }

    private static List<GasEffectEntry> parseEffects(Map<String, String> row, String gasId) {
        List<Integer> indexes = findEffectIndexes(row);
        List<GasEffectEntry> result = new ArrayList<>();

        for (int index : indexes) {
            String effectIdKey = "effect_" + index + "_id";
            String durationKey = "effect_" + index + "_duration_ticks";
            String amplifierKey = "effect_" + index + "_amplifier";
            String chanceKey = "effect_" + index + "_chance";

            ensureEffectColumnsExist(row, gasId, effectIdKey, durationKey, amplifierKey, chanceKey);

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
                throw new IllegalStateException("Broken gas effect group in " + gasId + ": effect_" + index);
            }

            int durationTicks = Integer.parseInt(durationText);
            int amplifier = Integer.parseInt(amplifierText);
            float chance = Float.parseFloat(chanceText);

            if (durationTicks <= 0) {
                throw new IllegalStateException("Gas " + gasId + " has effect duration <= 0");
            }

            if (amplifier < 0) {
                throw new IllegalStateException("Gas " + gasId + " has effect amplifier < 0");
            }

            if (chance < 0.0F || chance > 1.0F) {
                throw new IllegalStateException("Gas " + gasId + " has effect chance outside 0..1");
            }

            result.add(new GasEffectEntry(effectId, durationTicks, amplifier, chance));
        }

        return List.copyOf(result);
    }

    private static void ensureEffectColumnsExist(
            Map<String, String> row,
            String gasId,
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
                    "Broken gas effect columns in "
                            + gasId
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

    private static double doubleValue(Map<String, String> row, String key) {
        return Double.parseDouble(string(row, key));
    }

    private static float floatValue(Map<String, String> row, String key) {
        return Float.parseFloat(string(row, key));
    }

    private GasCsvLoader() {
    }
}
