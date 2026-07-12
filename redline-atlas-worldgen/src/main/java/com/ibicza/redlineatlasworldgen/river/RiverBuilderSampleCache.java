package com.ibicza.redlineatlasworldgen.river;

import java.util.OptionalDouble;

final class RiverBuilderSampleCache {
    private static final int ENTRY_COUNT = 1 << 18;
    private static final int MASK = ENTRY_COUNT - 1;

    private final long[] heightKeys = new long[ENTRY_COUNT];
    private final double[] heightValues = new double[ENTRY_COUNT];
    private final byte[] heightStates = new byte[ENTRY_COUNT];
    private final long[] waterKeys = new long[ENTRY_COUNT];
    private final byte[] waterValues = new byte[ENTRY_COUNT];

    OptionalDouble height(long key) {
        int index = index(key);
        if (heightStates[index] == 0 || heightKeys[index] != key) {
            return null;
        }
        return heightStates[index] == 2 ? OptionalDouble.of(heightValues[index]) : OptionalDouble.empty();
    }

    void putHeight(long key, OptionalDouble value) {
        int index = index(key);
        heightKeys[index] = key;
        if (value.isPresent()) {
            heightValues[index] = value.getAsDouble();
            heightStates[index] = 2;
        } else {
            heightValues[index] = Double.NaN;
            heightStates[index] = 1;
        }
    }

    Boolean water(long key) {
        int index = index(key);
        if (waterValues[index] == 0 || waterKeys[index] != key) {
            return null;
        }
        return waterValues[index] == 2;
    }

    void putWater(long key, boolean value) {
        int index = index(key);
        waterKeys[index] = key;
        waterValues[index] = (byte) (value ? 2 : 1);
    }

    long retainedBytesEstimate() {
        return ENTRY_COUNT * (Long.BYTES * 2L + Double.BYTES + 2L);
    }

    private static int index(long key) {
        long h = key;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (int) h & MASK;
    }
}
