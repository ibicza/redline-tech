package com.ibicza.redlinetech.content.material;

public record MaterialDefinition(
        String id,
        String ruName,
        String enName,
        MaterialKind kind,
        int color,

        boolean hasIngot,
        boolean hasDust,
        boolean hasSmallDust,


        boolean hasPlate,
        boolean hasDensePlate,
        boolean hasCasing,

        boolean hasWire,
        boolean hasRod,
        boolean hasFoil,
        boolean hasRibbon,

        boolean hasNugget,
        boolean hasBlock
) {
}