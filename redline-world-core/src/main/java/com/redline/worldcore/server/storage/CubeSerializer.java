package com.redline.worldcore.server.storage;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Serializes one cube into the MVP CubeNBT format used by Region3D files. */
public final class CubeSerializer {
    public static final int FORMAT_VERSION = 1;

    private static final String TAG_DATA_VERSION = "DataVersion";
    private static final String TAG_FORMAT_VERSION = "RedlineCubeFormat";
    private static final String TAG_CUBE_X = "CubeX";
    private static final String TAG_CUBE_Y = "CubeY";
    private static final String TAG_CUBE_Z = "CubeZ";
    private static final String TAG_STATUS = "Status";
    private static final String TAG_BLOCK_PALETTE = "BlockPalette";
    private static final String TAG_BLOCK_INDICES = "BlockIndices";
    private static final String TAG_CONTENT_FLAGS = "ContentFlags";
    private static final String TAG_CUSTOM_DATA = "CustomData";

    public CubeSerializer() {
    }

    public CompoundTag write(LevelCube cube) {
        Objects.requireNonNull(cube, "cube");

        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_DATA_VERSION, 0);
        tag.putInt(TAG_FORMAT_VERSION, FORMAT_VERSION);
        tag.putInt(TAG_CUBE_X, cube.cubePos().x());
        tag.putInt(TAG_CUBE_Y, cube.cubePos().y());
        tag.putInt(TAG_CUBE_Z, cube.cubePos().z());
        tag.putString(TAG_STATUS, cube.status().name());

        PaletteWriteResult palette = writePalette(cube);
        tag.put(TAG_BLOCK_PALETTE, palette.paletteTag());
        tag.putIntArray(TAG_BLOCK_INDICES, palette.indices());

        // Reserved for M4+ migration. The field exists now so old files keep a stable shape.
        tag.putInt(TAG_CONTENT_FLAGS, 0);
        tag.put(TAG_CUSTOM_DATA, new CompoundTag());
        return tag;
    }

    public LevelCube read(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");

        CubePos cubePos = new CubePos(
                tag.getIntOr(TAG_CUBE_X, 0),
                tag.getIntOr(TAG_CUBE_Y, 0),
                tag.getIntOr(TAG_CUBE_Z, 0)
        );
        LevelCube cube = new LevelCube(cubePos);
        cube.setStatus(readStatus(tag.getStringOr(TAG_STATUS, CubeStatus.FULL.name())));

        ListTag paletteTag = tag.getListOrEmpty(TAG_BLOCK_PALETTE);
        int[] indices = tag.getIntArray(TAG_BLOCK_INDICES).orElse(new int[0]);
        if (paletteTag.isEmpty() || indices.length == 0) {
            cube.fill(Blocks.AIR.defaultBlockState());
            return cube;
        }

        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int index = 0; index < paletteTag.size(); index++) {
            palette[index] = readBlockState(paletteTag.getCompoundOrEmpty(index));
        }

        for (int localIndex = 0; localIndex < CubePos.BLOCK_COUNT; localIndex++) {
            int paletteIndex = localIndex < indices.length ? indices[localIndex] : 0;
            if (paletteIndex < 0 || paletteIndex >= palette.length) {
                paletteIndex = 0;
            }
            int localX = localIndex & CubePos.MASK;
            int localZ = (localIndex >> CubePos.SIZE_BITS) & CubePos.MASK;
            int localY = (localIndex >> (CubePos.SIZE_BITS * 2)) & CubePos.MASK;
            cube.setBlockState(localX, localY, localZ, palette[paletteIndex]);
        }
        return cube;
    }

    private PaletteWriteResult writePalette(LevelCube cube) {
        Map<BlockState, Integer> paletteMap = new LinkedHashMap<>();
        int[] indices = new int[CubePos.BLOCK_COUNT];

        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    int localIndex = CubePos.localIndex(localX, localY, localZ);
                    BlockState state = cube.getBlockState(localX, localY, localZ);
                    Integer paletteIndex = paletteMap.get(state);
                    if (paletteIndex == null) {
                        paletteIndex = paletteMap.size();
                        paletteMap.put(state, paletteIndex);
                    }
                    indices[localIndex] = paletteIndex;
                }
            }
        }

        ListTag paletteTag = new ListTag();
        for (BlockState state : paletteMap.keySet()) {
            paletteTag.add(writeBlockState(state));
        }
        return new PaletteWriteResult(paletteTag, indices);
    }

    private static CompoundTag writeBlockState(BlockState state) {
        CompoundTag tag = new CompoundTag();
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        tag.putString("Name", blockId == null ? "minecraft:air" : blockId.toString());

        if (!state.getProperties().isEmpty()) {
            CompoundTag propertiesTag = new CompoundTag();
            for (Property<?> property : state.getProperties()) {
                propertiesTag.putString(property.getName(), propertyValueName(state, property));
            }
            tag.put("Properties", propertiesTag);
        }
        return tag;
    }

    private static BlockState readBlockState(CompoundTag tag) {
        Identifier blockId = Identifier.tryParse(tag.getStringOr("Name", "minecraft:air"));
        if (blockId == null) {
            return Blocks.AIR.defaultBlockState();
        }

        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(blockId);
        BlockState state = block.orElse(Blocks.AIR).defaultBlockState();
        CompoundTag propertiesTag = tag.getCompoundOrEmpty("Properties");

        for (String propertyName : propertiesTag.keySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(propertyName);
            if (property != null) {
                state = applyProperty(state, property, propertiesTag.getStringOr(propertyName, ""));
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> String propertyValueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyProperty(BlockState state, Property property, String valueName) {
        Optional<? extends Comparable> parsed = property.getValue(valueName);
        if (parsed.isEmpty()) {
            return state;
        }
        return (BlockState) state.setValue(property, parsed.get());
    }

    private static CubeStatus readStatus(String name) {
        try {
            return CubeStatus.valueOf(name);
        } catch (IllegalArgumentException exception) {
            return CubeStatus.FULL;
        }
    }

    private record PaletteWriteResult(ListTag paletteTag, int[] indices) {
    }
}
