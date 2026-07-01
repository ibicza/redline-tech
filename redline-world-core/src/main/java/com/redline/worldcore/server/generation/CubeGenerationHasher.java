package com.redline.worldcore.server.generation;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/** Stable debug hash for one generated 16x16x16 cube. Used to verify deterministic generation before client sync. */
public final class CubeGenerationHasher {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    public static long hash(LevelCube cube) {
        long hash = FNV_OFFSET;
        hash = mixInt(hash, cube.cubePos().x());
        hash = mixInt(hash, cube.cubePos().y());
        hash = mixInt(hash, cube.cubePos().z());
        hash = mixString(hash, cube.status().name());

        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    hash = mixString(hash, blockStateDebugName(cube.getBlockState(localX, localY, localZ)));
                }
            }
        }
        return hash;
    }

    public static String shortHex(long hash) {
        return Long.toUnsignedString(hash, 16);
    }

    public static String blockStateDebugName(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "minecraft:air" : id.toString();
    }

    private static long mixInt(long hash, int value) {
        hash = mixByte(hash, value);
        hash = mixByte(hash, value >>> 8);
        hash = mixByte(hash, value >>> 16);
        hash = mixByte(hash, value >>> 24);
        return hash;
    }

    private static long mixString(long hash, String value) {
        for (int index = 0; index < value.length(); index++) {
            hash = mixByte(hash, value.charAt(index));
        }
        return mixByte(hash, 0);
    }

    private static long mixByte(long hash, int value) {
        hash ^= value & 0xFFL;
        hash *= FNV_PRIME;
        return hash;
    }

    private CubeGenerationHasher() {
    }
}
