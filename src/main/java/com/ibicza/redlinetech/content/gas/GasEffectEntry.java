package com.ibicza.redlinetech.content.gas;

public record GasEffectEntry(
        String effectId,
        int durationTicks,
        int amplifier,
        float chance
) {
}
