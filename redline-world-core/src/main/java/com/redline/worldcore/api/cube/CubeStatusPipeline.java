package com.redline.worldcore.api.cube;

import java.util.Arrays;
import java.util.List;

public final class CubeStatusPipeline {
    public static final List<CubeStatus> ORDER = List.copyOf(Arrays.asList(CubeStatus.values()));

    public static boolean canAdvance(CubeStatus current, CubeStatus target) {
        return current.ordinal() <= target.ordinal();
    }

    private CubeStatusPipeline() {
    }
}
