package com.ibicza.redlineatlasworldgen.terrain;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public record QueuedChunk(ResourceKey<Level> dimension, ChunkPos pos, boolean automatic) {
}
