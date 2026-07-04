package com.redline.worldcore.server.sync;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * M17.2 server-side DTO for the future cube-native client section path.
 *
 * <p>This is deliberately independent from the temporary vanilla-shell materializer.  The snapshot is a compact,
 * immutable 16x16x16 section view: palette + palette indices + light arrays.  M17.3/M17.4 can serialize this into a
 * custom packet without first mirroring the cube through {@code Level#setBlock}.</p>
 */
public final class CubeSectionSnapshot {
    private final CubePos cubePos;
    private final CubeStatus status;
    private final long hash;
    private final List<BlockState> palette;
    private final int[] paletteIndices;
    private final byte[] blockLight;
    private final byte[] skyLight;

    public CubeSectionSnapshot(
            CubePos cubePos,
            CubeStatus status,
            long hash,
            List<BlockState> palette,
            int[] paletteIndices,
            byte[] blockLight,
            byte[] skyLight
    ) {
        this.cubePos = Objects.requireNonNull(cubePos, "cubePos");
        this.status = Objects.requireNonNull(status, "status");
        this.hash = hash;
        this.palette = List.copyOf(Objects.requireNonNull(palette, "palette"));
        this.paletteIndices = Objects.requireNonNull(paletteIndices, "paletteIndices").clone();
        this.blockLight = Objects.requireNonNull(blockLight, "blockLight").clone();
        this.skyLight = Objects.requireNonNull(skyLight, "skyLight").clone();
        if (this.paletteIndices.length != CubePos.BLOCK_COUNT) {
            throw new IllegalArgumentException("Cube section palette index array must be " + CubePos.BLOCK_COUNT + " entries");
        }
        if (this.blockLight.length != CubePos.BLOCK_COUNT || this.skyLight.length != CubePos.BLOCK_COUNT) {
            throw new IllegalArgumentException("Cube section light arrays must be " + CubePos.BLOCK_COUNT + " entries");
        }
    }

    public CubePos cubePos() {
        return cubePos;
    }

    public CubeStatus status() {
        return status;
    }

    public long hash() {
        return hash;
    }

    public List<BlockState> palette() {
        return palette;
    }

    public int paletteSize() {
        return palette.size();
    }

    public int[] copyPaletteIndices() {
        return paletteIndices.clone();
    }

    public byte[] copyBlockLight() {
        return blockLight.clone();
    }

    public byte[] copySkyLight() {
        return skyLight.clone();
    }

    public BlockState blockStateAtLocalIndex(int localIndex) {
        if (localIndex < 0 || localIndex >= CubePos.BLOCK_COUNT) {
            throw new IllegalArgumentException("Invalid cube local index: " + localIndex);
        }
        return palette.get(paletteIndices[localIndex]);
    }

    @Override
    public String toString() {
        return "CubeSectionSnapshot{" +
                "cubePos=" + cubePos +
                ", status=" + status +
                ", hash=" + hash +
                ", palette=" + palette.size() +
                ", blockLightHash=" + Arrays.hashCode(blockLight) +
                ", skyLightHash=" + Arrays.hashCode(skyLight) +
                '}';
    }
}
