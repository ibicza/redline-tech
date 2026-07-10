package com.ibicza.redlinechunkpriority.core;

import net.minecraft.world.level.ChunkPos;

public record ChunkPriorityTarget(ChunkPos pos, ChunkPriorityTier tier, int rank, String detail) {
    public String shortText() {
        return tier.name() + " " + pos.x() + "," + pos.z() + " #" + rank + " " + detail;
    }
}
