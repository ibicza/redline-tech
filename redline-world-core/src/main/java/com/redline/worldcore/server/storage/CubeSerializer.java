package com.redline.worldcore.server.storage;

import com.redline.worldcore.api.cube.CubeScheduledTickData;
import com.redline.worldcore.api.cube.CubeScheduledTickKind;
import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Serializes one cube into the MVP CubeNBT format used by Region3D files. */
public final class CubeSerializer {
    public static final int FORMAT_VERSION = 3;

    private static final String TAG_DATA_VERSION = "DataVersion";
    private static final String TAG_FORMAT_VERSION = "RedlineCubeFormat";
    private static final String TAG_CUBE_X = "CubeX";
    private static final String TAG_CUBE_Y = "CubeY";
    private static final String TAG_CUBE_Z = "CubeZ";
    private static final String TAG_STATUS = "Status";
    private static final String TAG_BLOCK_PALETTE = "BlockPalette";
    private static final String TAG_BLOCK_INDICES = "BlockIndices";
    private static final String TAG_BLOCK_LIGHT = "BlockLight";
    private static final String TAG_SKY_LIGHT = "SkyLight";
    private static final String TAG_BLOCK_ENTITIES = "BlockEntities";
    private static final String TAG_BLOCK_TICKS = "BlockTicks";
    private static final String TAG_FLUID_TICKS = "FluidTicks";
    private static final String TAG_BLOCK_ENTITY_LOCAL_INDEX = "LocalIndex";
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
        tag.putByteArray(TAG_BLOCK_LIGHT, cube.copyBlockLight());
        tag.putByteArray(TAG_SKY_LIGHT, cube.copySkyLight());
        tag.put(TAG_BLOCK_ENTITIES, writeBlockEntities(cube));
        tag.put(TAG_BLOCK_TICKS, writeScheduledTicks(cube.copyScheduledBlockTicks()));
        tag.put(TAG_FLUID_TICKS, writeScheduledTicks(cube.copyScheduledFluidTicks()));

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
            cube.replaceBlockLight(tag.getByteArray(TAG_BLOCK_LIGHT).orElse(new byte[0]));
            cube.replaceSkyLight(tag.getByteArray(TAG_SKY_LIGHT).orElse(new byte[0]));
            readBlockEntities(tag, cube);
            readScheduledTicks(tag, cube);
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

        cube.replaceBlockLight(tag.getByteArray(TAG_BLOCK_LIGHT).orElse(new byte[0]));
        cube.replaceSkyLight(tag.getByteArray(TAG_SKY_LIGHT).orElse(new byte[0]));
        readBlockEntities(tag, cube);
        readScheduledTicks(tag, cube);
        return cube;
    }

    private ListTag writeBlockEntities(LevelCube cube) {
        ListTag list = new ListTag();
        for (Map.Entry<Integer, CompoundTag> entry : cube.copyBlockEntityData().entrySet()) {
            CompoundTag tag = entry.getValue().copy();
            tag.putInt(TAG_BLOCK_ENTITY_LOCAL_INDEX, entry.getKey());
            list.add(tag);
        }
        return list;
    }

    private void readBlockEntities(CompoundTag tag, LevelCube cube) {
        ListTag list = tag.getListOrEmpty(TAG_BLOCK_ENTITIES);
        if (list.isEmpty()) {
            return;
        }
        for (int index = 0; index < list.size(); index++) {
            CompoundTag blockEntityTag = list.getCompoundOrEmpty(index);
            int localIndex = blockEntityTag.getIntOr(TAG_BLOCK_ENTITY_LOCAL_INDEX, -1);
            if (localIndex < 0 || localIndex >= CubePos.BLOCK_COUNT) {
                int localX = CubePos.local(blockEntityTag.getIntOr("x", cube.cubePos().minBlockX()));
                int localY = CubePos.local(blockEntityTag.getIntOr("y", cube.cubePos().minBlockY()));
                int localZ = CubePos.local(blockEntityTag.getIntOr("z", cube.cubePos().minBlockZ()));
                localIndex = CubePos.localIndex(localX, localY, localZ);
            }
            cube.setBlockEntityTag(localIndex, blockEntityTag);
        }
    }


    private ListTag writeScheduledTicks(List<CubeScheduledTickData> ticks) {
        ListTag list = new ListTag();
        for (CubeScheduledTickData tick : ticks) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Kind", tick.kind().name());
            tag.putInt("LocalIndex", tick.localIndex());
            tag.putInt("x", tick.worldPos().getX());
            tag.putInt("y", tick.worldPos().getY());
            tag.putInt("z", tick.worldPos().getZ());
            tag.putString("Target", tick.targetId());
            tag.putLong("TriggerGameTime", tick.triggerGameTime());
            tag.putInt("Priority", tick.priority());
            tag.putString("Reason", tick.reason());
            list.add(tag);
        }
        return list;
    }

    private void readScheduledTicks(CompoundTag tag, LevelCube cube) {
        List<CubeScheduledTickData> blockTicks = readScheduledTickList(tag.getListOrEmpty(TAG_BLOCK_TICKS), CubeScheduledTickKind.BLOCK, cube);
        List<CubeScheduledTickData> fluidTicks = readScheduledTickList(tag.getListOrEmpty(TAG_FLUID_TICKS), CubeScheduledTickKind.FLUID, cube);
        cube.replaceScheduledTicks(blockTicks, fluidTicks);
    }

    private List<CubeScheduledTickData> readScheduledTickList(ListTag list, CubeScheduledTickKind expectedKind, LevelCube cube) {
        List<CubeScheduledTickData> ticks = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            CompoundTag tag = list.getCompoundOrEmpty(index);
            CubeScheduledTickKind kind = readTickKind(tag.getStringOr("Kind", expectedKind.name()), expectedKind);
            int worldX = tag.getIntOr("x", cube.cubePos().minBlockX());
            int worldY = tag.getIntOr("y", cube.cubePos().minBlockY());
            int worldZ = tag.getIntOr("z", cube.cubePos().minBlockZ());
            BlockPos worldPos = new BlockPos(worldX, worldY, worldZ);
            String target = tag.getStringOr("Target", "minecraft:air");
            long trigger = tag.getLongOr("TriggerGameTime", 0L);
            int priority = tag.getIntOr("Priority", 0);
            String reason = tag.getStringOr("Reason", "loaded_from_cube_nbt");
            ticks.add(CubeScheduledTickData.create(kind, cube.cubePos(), worldPos, target, trigger, priority, reason));
        }
        return ticks;
    }

    private CubeScheduledTickKind readTickKind(String raw, CubeScheduledTickKind fallback) {
        try {
            return CubeScheduledTickKind.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
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
