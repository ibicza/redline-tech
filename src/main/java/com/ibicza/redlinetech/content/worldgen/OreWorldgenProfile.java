package com.ibicza.redlinetech.content.worldgen;


import com.ibicza.redlinetech.content.common.FloatRange;
import com.ibicza.redlinetech.content.common.IntRange;

public record OreWorldgenProfile(
        IntRange height,
        IntRange peakHeight,
        IntRange veinSize,
        FloatRange amountPerCubeChunk
) {
}
