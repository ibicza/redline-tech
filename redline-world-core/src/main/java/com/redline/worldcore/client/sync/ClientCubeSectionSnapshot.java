package com.redline.worldcore.client.sync;

import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Immutable client-side copy of one cube-native 16x16x16 section snapshot. */
public final class ClientCubeSectionSnapshot {
    private final CubePos cubePos;
    private final int statusOrdinal;
    private final long hash;
    private final List<BlockState> palette;
    private final int[] paletteIndices;
    private final byte[] blockLight;
    private final byte[] skyLight;

    public ClientCubeSectionSnapshot(
            CubePos cubePos,
            int statusOrdinal,
            long hash,
            List<BlockState> palette,
            int[] paletteIndices,
            byte[] blockLight,
            byte[] skyLight
    ) {
        this.cubePos = cubePos;
        this.statusOrdinal = statusOrdinal;
        this.hash = hash;
        this.palette = List.copyOf(palette);
        this.paletteIndices = paletteIndices.clone();
        this.blockLight = blockLight.clone();
        this.skyLight = skyLight.clone();
    }

    public CubePos cubePos() {
        return cubePos;
    }

    public int statusOrdinal() {
        return statusOrdinal;
    }

    public long hash() {
        return hash;
    }

    public int paletteSize() {
        return palette.size();
    }

    public BlockState blockStateAtLocalIndex(int localIndex) {
        return palette.get(paletteIndices[localIndex]);
    }

    public byte blockLightAtLocalIndex(int localIndex) {
        return blockLight[localIndex];
    }

    public byte skyLightAtLocalIndex(int localIndex) {
        return skyLight[localIndex];
    }

    public ClientCubeSectionSnapshot withBlockChanges(long newHash, int[] localIndices, List<BlockState> states) {
        if (localIndices.length != states.size()) {
            throw new IllegalArgumentException("localIndices/states length mismatch");
        }
        ArrayList<BlockState> newPalette = new ArrayList<>(palette);
        int[] newIndices = paletteIndices.clone();
        for (int index = 0; index < localIndices.length; index++) {
            int localIndex = localIndices[index];
            BlockState state = states.get(index);
            int paletteIndex = newPalette.indexOf(state);
            if (paletteIndex < 0) {
                paletteIndex = newPalette.size();
                newPalette.add(state);
            }
            newIndices[localIndex] = paletteIndex;
        }
        return new ClientCubeSectionSnapshot(cubePos, statusOrdinal, newHash, newPalette, newIndices, blockLight, skyLight);
    }

}
