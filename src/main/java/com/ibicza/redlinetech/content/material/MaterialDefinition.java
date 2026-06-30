package com.ibicza.redlinetech.content.material;

public record MaterialDefinition(
        String id,
        String ruName,
        String enName,
        MaterialKind kind,
        int color,
        boolean hasIngot,
        boolean hasDust,
        boolean hasPlate,
        boolean hasWire,
        boolean hasRod,
        boolean hasNugget,
        boolean hasBlock
) {
}
