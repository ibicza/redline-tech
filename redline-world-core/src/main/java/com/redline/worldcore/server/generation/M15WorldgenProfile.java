package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.generation.CubicDimensionSettings;
import net.minecraft.util.Mth;

/**
 * M15 vertical world profile derived from immutable cubic dimension settings.
 *
 * <p>Sea level is fixed at Y=0 for cubic_test, while layer heights and terrain amplitude scale with minY/maxY.
 * This keeps the Java generator deterministic now and leaves room for later world-creation settings.</p>
 */
public record M15WorldgenProfile(
        int minY,
        int maxY,
        int seaLevel,
        int bedrockTopY,
        int lavaBeltTopY,
        int deepslateTopY,
        int baseLandY,
        int lowestSurfaceY,
        int highestSurfaceY,
        int highMountainStartY,
        int snowLineY,
        int continentAmplitude,
        int hillAmplitude,
        int mountainAmplitude
) {
    public static M15WorldgenProfile from(CubicDimensionSettings settings) {
        int minY = settings.minBlockY();
        int maxY = settings.maxBlockY();
        int sea = settings.seaLevel();
        int down = settings.downwardDepthToSea();
        int up = settings.upwardHeightFromSea();

        int bedrockTop = minY + 7;
        int lavaBeltTop = minY + clampRounded(down * 0.30D, 192, 768);
        int deepslateTop = Math.min(sea - 128, minY + clampRounded(down * 0.94D, 512, down - 64));
        deepslateTop = Math.max(deepslateTop, lavaBeltTop + 64);
        deepslateTop = Math.min(deepslateTop, sea - 32);

        int baseLand = sea + clampRounded(up * 0.03125D, 48, 96);
        int continentAmplitude = clampRounded(up * 0.045D, 48, 192);
        int hillAmplitude = clampRounded(up * 0.025D, 24, 96);
        int mountainAmplitude = clampRounded(up * 0.11D, 128, 384);
        int lowestSurface = sea - clampRounded(down * 0.04D, 48, 160);
        int highestSurface = Math.min(maxY - 32, sea + clampRounded(up * 0.22D, 256, 768));
        int highMountainStart = sea + clampRounded(up * 0.09D, 160, 384);
        int snowLine = sea + clampRounded(up * 0.11D, 192, 512);

        return new M15WorldgenProfile(
                minY,
                maxY,
                sea,
                bedrockTop,
                lavaBeltTop,
                deepslateTop,
                baseLand,
                lowestSurface,
                highestSurface,
                highMountainStart,
                snowLine,
                continentAmplitude,
                hillAmplitude,
                mountainAmplitude
        );
    }

    public boolean inBedrockLayer(int y) {
        return y <= bedrockTopY;
    }

    public boolean inDeepLavaBelt(int y) {
        return y > bedrockTopY && y <= lavaBeltTopY;
    }

    public boolean inDeepslateLayer(int y) {
        return y <= deepslateTopY;
    }

    public String oneLine() {
        return "minY=" + minY
                + ", maxY=" + maxY
                + ", seaLevel=" + seaLevel
                + ", bedrock=" + minY + ".." + bedrockTopY
                + ", deepLavaBelt=" + (bedrockTopY + 1) + ".." + lavaBeltTopY
                + ", deepslate<= " + deepslateTopY
                + ", baseLandY=" + baseLandY
                + ", surfaceRange=" + lowestSurfaceY + ".." + highestSurfaceY
                + ", highMountainStart=" + highMountainStartY
                + ", snowLine=" + snowLineY;
    }

    private static int clampRounded(double value, int min, int max) {
        return Mth.clamp((int) Math.round(value), min, max);
    }
}
