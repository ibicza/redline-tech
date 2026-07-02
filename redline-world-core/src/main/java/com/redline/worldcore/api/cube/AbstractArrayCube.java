package com.redline.worldcore.api.cube;

import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

abstract class AbstractArrayCube implements CubeAccess {
    private final CubePos cubePos;
    private final BlockState[] states = new BlockState[CubePos.BLOCK_COUNT];
    private final byte[] blockLight = new byte[CubePos.BLOCK_COUNT];
    private final byte[] skyLight = new byte[CubePos.BLOCK_COUNT];
    private final Map<Integer, CompoundTag> blockEntities = new LinkedHashMap<>();
    private final List<CubeScheduledTickData> scheduledBlockTicks = new ArrayList<>();
    private final List<CubeScheduledTickData> scheduledFluidTicks = new ArrayList<>();
    private CubeStatus status;

    protected AbstractArrayCube(CubePos cubePos, CubeStatus status) {
        this.cubePos = Objects.requireNonNull(cubePos, "cubePos");
        this.status = Objects.requireNonNull(status, "status");
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
    }

    @Override
    public CubePos cubePos() {
        return cubePos;
    }

    @Override
    public CubeStatus status() {
        return status;
    }

    @Override
    public void setStatus(CubeStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    @Override
    public BlockState getBlockState(int localX, int localY, int localZ) {
        return states[CubePos.localIndex(localX, localY, localZ)];
    }

    @Override
    public void setBlockState(int localX, int localY, int localZ, BlockState state) {
        states[CubePos.localIndex(localX, localY, localZ)] = Objects.requireNonNull(state, "state");
    }

    @Override
    public int getBlockLight(int localX, int localY, int localZ) {
        return Byte.toUnsignedInt(blockLight[CubePos.localIndex(localX, localY, localZ)]);
    }

    @Override
    public void setBlockLight(int localX, int localY, int localZ, int level) {
        blockLight[CubePos.localIndex(localX, localY, localZ)] = (byte) Mth.clamp(level, 0, 15);
    }

    @Override
    public byte[] copyBlockLight() {
        return blockLight.clone();
    }

    @Override
    public void replaceBlockLight(byte[] light) {
        Arrays.fill(blockLight, (byte) 0);
        if (light == null) {
            return;
        }
        int count = Math.min(blockLight.length, light.length);
        for (int index = 0; index < count; index++) {
            blockLight[index] = (byte) Mth.clamp(Byte.toUnsignedInt(light[index]), 0, 15);
        }
    }

    @Override
    public void clearBlockLight() {
        Arrays.fill(blockLight, (byte) 0);
    }

    @Override
    public int getSkyLight(int localX, int localY, int localZ) {
        return Byte.toUnsignedInt(skyLight[CubePos.localIndex(localX, localY, localZ)]);
    }

    @Override
    public void setSkyLight(int localX, int localY, int localZ, int level) {
        skyLight[CubePos.localIndex(localX, localY, localZ)] = (byte) Mth.clamp(level, 0, 15);
    }

    @Override
    public byte[] copySkyLight() {
        return skyLight.clone();
    }

    @Override
    public void replaceSkyLight(byte[] light) {
        Arrays.fill(skyLight, (byte) 0);
        if (light == null) {
            return;
        }
        int count = Math.min(skyLight.length, light.length);
        for (int index = 0; index < count; index++) {
            skyLight[index] = (byte) Mth.clamp(Byte.toUnsignedInt(light[index]), 0, 15);
        }
    }

    @Override
    public void clearSkyLight() {
        Arrays.fill(skyLight, (byte) 0);
    }

    public void fill(BlockState state) {
        Arrays.fill(states, Objects.requireNonNull(state, "state"));
        blockEntities.clear();
        scheduledBlockTicks.clear();
        scheduledFluidTicks.clear();
    }

    public Optional<CompoundTag> blockEntityTag(int localX, int localY, int localZ) {
        return blockEntityTag(CubePos.localIndex(localX, localY, localZ));
    }

    public Optional<CompoundTag> blockEntityTag(int localIndex) {
        CompoundTag tag = blockEntities.get(localIndex);
        return tag == null ? Optional.empty() : Optional.of(tag.copy());
    }

    public void setBlockEntityTag(int localX, int localY, int localZ, CompoundTag tag) {
        setBlockEntityTag(CubePos.localIndex(localX, localY, localZ), tag);
    }

    public void setBlockEntityTag(int localIndex, CompoundTag tag) {
        if (localIndex < 0 || localIndex >= CubePos.BLOCK_COUNT) {
            throw new IllegalArgumentException("Invalid cube local index: " + localIndex);
        }
        blockEntities.put(localIndex, Objects.requireNonNull(tag, "tag").copy());
    }

    public void removeBlockEntityTag(int localX, int localY, int localZ) {
        removeBlockEntityTag(CubePos.localIndex(localX, localY, localZ));
    }

    public void removeBlockEntityTag(int localIndex) {
        blockEntities.remove(localIndex);
    }

    public int blockEntityCount() {
        return blockEntities.size();
    }

    public Map<Integer, CompoundTag> copyBlockEntityData() {
        Map<Integer, CompoundTag> copy = new LinkedHashMap<>();
        for (Map.Entry<Integer, CompoundTag> entry : blockEntities.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }

    public void replaceBlockEntityData(Map<Integer, CompoundTag> data) {
        blockEntities.clear();
        if (data == null || data.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, CompoundTag> entry : data.entrySet()) {
            int localIndex = entry.getKey();
            if (localIndex < 0 || localIndex >= CubePos.BLOCK_COUNT || entry.getValue() == null) {
                continue;
            }
            blockEntities.put(localIndex, entry.getValue().copy());
        }
    }

    public void addScheduledTick(CubeScheduledTickData tick) {
        Objects.requireNonNull(tick, "tick");
        if (tick.localIndex() < 0 || tick.localIndex() >= CubePos.BLOCK_COUNT) {
            throw new IllegalArgumentException("Invalid cube local index for scheduled tick: " + tick.localIndex());
        }
        if (tick.kind() == CubeScheduledTickKind.BLOCK) {
            scheduledBlockTicks.add(tick.copy());
        } else {
            scheduledFluidTicks.add(tick.copy());
        }
    }

    public int scheduledBlockTickCount() {
        return scheduledBlockTicks.size();
    }

    public int scheduledFluidTickCount() {
        return scheduledFluidTicks.size();
    }

    public List<CubeScheduledTickData> copyScheduledBlockTicks() {
        List<CubeScheduledTickData> copy = new ArrayList<>(scheduledBlockTicks.size());
        for (CubeScheduledTickData tick : scheduledBlockTicks) {
            copy.add(tick.copy());
        }
        return copy;
    }

    public List<CubeScheduledTickData> copyScheduledFluidTicks() {
        List<CubeScheduledTickData> copy = new ArrayList<>(scheduledFluidTicks.size());
        for (CubeScheduledTickData tick : scheduledFluidTicks) {
            copy.add(tick.copy());
        }
        return copy;
    }

    public void replaceScheduledTicks(List<CubeScheduledTickData> blockTicks, List<CubeScheduledTickData> fluidTicks) {
        scheduledBlockTicks.clear();
        scheduledFluidTicks.clear();
        if (blockTicks != null) {
            for (CubeScheduledTickData tick : blockTicks) {
                if (tick != null && tick.kind() == CubeScheduledTickKind.BLOCK) {
                    scheduledBlockTicks.add(tick.copy());
                }
            }
        }
        if (fluidTicks != null) {
            for (CubeScheduledTickData tick : fluidTicks) {
                if (tick != null && tick.kind() == CubeScheduledTickKind.FLUID) {
                    scheduledFluidTicks.add(tick.copy());
                }
            }
        }
    }

    public int clearScheduledTicks() {
        int removed = scheduledBlockTicks.size() + scheduledFluidTicks.size();
        scheduledBlockTicks.clear();
        scheduledFluidTicks.clear();
        return removed;
    }
}

