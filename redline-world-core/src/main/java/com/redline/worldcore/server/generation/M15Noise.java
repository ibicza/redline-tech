package com.redline.worldcore.server.generation;

import net.minecraft.util.Mth;

/** Small allocation-free deterministic value-noise helper used by the Java reference generator. */
public final class M15Noise {
    public static double value2D(long seed, int x, int z, int scale) {
        if (scale <= 1) {
            return hashUnit(seed, x, 0, z);
        }
        int x0 = Math.floorDiv(x, scale);
        int z0 = Math.floorDiv(z, scale);
        int lx = Math.floorMod(x, scale);
        int lz = Math.floorMod(z, scale);
        double fx = smooth(lx / (double) scale);
        double fz = smooth(lz / (double) scale);

        double a = hashUnit(seed, x0, 0, z0);
        double b = hashUnit(seed, x0 + 1, 0, z0);
        double c = hashUnit(seed, x0, 0, z0 + 1);
        double d = hashUnit(seed, x0 + 1, 0, z0 + 1);
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fz);
    }

    public static double fbm2D(long seed, int x, int z, int baseScale, int octaves) {
        double sum = 0.0D;
        double amp = 1.0D;
        double ampSum = 0.0D;
        int scale = Math.max(2, baseScale);
        for (int octave = 0; octave < octaves; octave++) {
            sum += value2D(seed + octave * 0x9E3779B97F4A7C15L, x, z, scale) * amp;
            ampSum += amp;
            amp *= 0.5D;
            scale = Math.max(2, scale / 2);
        }
        return ampSum == 0.0D ? 0.0D : sum / ampSum;
    }

    public static double ridge2D(long seed, int x, int z, int baseScale, int octaves) {
        double v = fbm2D(seed, x, z, baseScale, octaves);
        return 1.0D - Math.abs(v);
    }

    public static int hashToRange(long seed, int x, int y, int z, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be > 0");
        }
        long value = hash(seed, x, y, z);
        return Math.floorMod((int) (value ^ (value >>> 32)), bound);
    }

    public static double hashUnit(long seed, int x, int y, int z) {
        long value = hash(seed, x, y, z);
        long bits = (value >>> 11) & ((1L << 53) - 1L);
        return (bits / (double) (1L << 53)) * 2.0D - 1.0D;
    }

    public static double smoothstep(double edge0, double edge1, double value) {
        if (edge0 == edge1) {
            return value >= edge1 ? 1.0D : 0.0D;
        }
        double t = Mth.clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double smooth(double t) {
        return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static long hash(long seed, int x, int y, int z) {
        long value = seed ^ 0xD1B54A32D192ED03L;
        value ^= x * 0x9E3779B97F4A7C15L;
        value ^= y * 0xC2B2AE3D27D4EB4FL;
        value ^= z * 0x165667B19E3779F9L;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    private M15Noise() {
    }
}
