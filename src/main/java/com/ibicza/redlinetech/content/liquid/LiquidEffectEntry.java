package com.ibicza.redlinetech.content.liquid;

public record LiquidEffectEntry(
        String effectId,
        int durationTicks,
        int amplifier,
        float chance
) {
}