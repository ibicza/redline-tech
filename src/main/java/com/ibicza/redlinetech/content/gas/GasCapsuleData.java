package com.ibicza.redlinetech.content.gas;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record GasCapsuleData(String gasId, int amount) {
    public static final GasCapsuleData EMPTY = new GasCapsuleData("", 0);

    public static final Codec<GasCapsuleData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("gas_id").forGetter(GasCapsuleData::gasId),
            Codec.INT.fieldOf("amount").forGetter(GasCapsuleData::amount)
    ).apply(instance, GasCapsuleData::new));

    public boolean isEmpty() {
        return gasId == null || gasId.isBlank() || amount <= 0;
    }

    public boolean isFull() {
        return amount >= 16;
    }
}