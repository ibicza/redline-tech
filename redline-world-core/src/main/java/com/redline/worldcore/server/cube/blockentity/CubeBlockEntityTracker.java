package com.redline.worldcore.server.cube.blockentity;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubeLocalPos;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.ticket.CubeTicketLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Cube-owned block entity runtime index.
 *
 * <p>M14.2 introduced durable placeholder slots. M14.3 adds a real vanilla BlockEntity NBT bridge, and M14.4 adds a
 * ticking gate model. This class still does not tick vanilla block entities; it records which cube owns a BE tag and
 * whether that cube is currently allowed to tick BE logic when the deeper mixin bridge arrives.</p>
 */
public final class CubeBlockEntityTracker {
    private static final int VANILLA_CAPTURE_SCAN_BUDGET = 8;

    private final Map<CubePos, CubeBlockEntitySection> sections = new LinkedHashMap<>();
    private final BlockEntityNbtBridge nbtBridge = new BlockEntityNbtBridge();

    private long totalAdded;
    private long totalRemoved;
    private long totalUpdated;
    private long totalRealNbtCaptured;
    private long totalRebuiltCubes;
    private int addedLastTick;
    private int removedLastTick;
    private int updatedLastTick;
    private int realNbtCapturedLastTick;
    private int rebuiltCubesLastTick;
    private CubePos lastCube;
    private String lastReason = "none";

    public void beginTick() {
        addedLastTick = 0;
        removedLastTick = 0;
        updatedLastTick = 0;
        realNbtCapturedLastTick = 0;
        rebuiltCubesLastTick = 0;
    }

    public void observeMutation(LevelCube cube, BlockPos worldPos, BlockState previous, BlockState next, String reason) {
        Objects.requireNonNull(cube, "cube");
        Objects.requireNonNull(worldPos, "worldPos");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");

        CubePos cubePos = cube.cubePos();
        int localIndex = CubePos.localIndex(CubePos.local(worldPos.getX()), CubePos.local(worldPos.getY()), CubePos.local(worldPos.getZ()));
        boolean previousOwned = isBlockEntityBlock(previous) || cube.blockEntityTag(localIndex).isPresent();
        boolean nextOwned = isBlockEntityBlock(next);

        if (!previousOwned && !nextOwned) {
            return;
        }

        if (nextOwned) {
            CompoundTag tag = cube.blockEntityTag(localIndex).orElseGet(() -> placeholderTag(worldPos, next));
            normalizeTag(tag, cubePos, worldPos, next, tag.getBooleanOr("redlinePlaceholder", !tag.contains("id")));
            cube.setBlockEntityTag(localIndex, tag);
            putRef(cubePos, worldPos, next, localIndex, tag, reason);
            return;
        }

        cube.removeBlockEntityTag(localIndex);
        removeRef(cubePos, localIndex, reason);
    }

    /** Captures real runtime vanilla BE NBT at a specific block position into the owning cube. */
    public boolean captureVanilla(LevelCube cube, ServerLevel level, BlockPos worldPos, String reason) {
        Objects.requireNonNull(cube, "cube");
        if (level == null || worldPos == null) {
            return false;
        }
        Optional<CompoundTag> captured = nbtBridge.capture(level, worldPos);
        if (captured.isEmpty()) {
            return false;
        }
        int localIndex = CubePos.localIndex(CubePos.local(worldPos.getX()), CubePos.local(worldPos.getY()), CubePos.local(worldPos.getZ()));
        BlockState state = cube.getBlockState(worldPos);
        CompoundTag tag = captured.get();
        normalizeTag(tag, cube.cubePos(), worldPos, state, false);
        cube.setBlockEntityTag(localIndex, tag);
        putRef(cube.cubePos(), worldPos, state, localIndex, tag, reason == null ? "vanilla_nbt_capture" : reason);
        totalRealNbtCaptured++;
        realNbtCapturedLastTick++;
        return true;
    }

    /** Scans a small number of placeholder refs and upgrades them with real vanilla NBT when available. */
    public int captureVanillaForLoaded(ServerLevel level, Map<CubePos, LevelCube> loadedCubes) {
        if (level == null || loadedCubes.isEmpty()) {
            return 0;
        }
        int captured = 0;
        List<CubeBlockEntityRef> candidates = new ArrayList<>();
        for (CubeBlockEntitySection section : sections.values()) {
            for (CubeBlockEntityRef ref : section.refs()) {
                if (!ref.realNbt()) {
                    candidates.add(ref);
                }
            }
        }
        for (CubeBlockEntityRef ref : candidates) {
            if (captured >= VANILLA_CAPTURE_SCAN_BUDGET) {
                break;
            }
            LevelCube cube = loadedCubes.get(ref.cubePos());
            if (cube != null && captureVanilla(cube, level, ref.worldPos(), "vanilla_nbt_scan")) {
                captured++;
            }
        }
        return captured;
    }

    public void evaluateTicking(Map<CubePos, CubeTicketLevel> loadedLevels) {
        for (CubeBlockEntitySection section : sections.values()) {
            CubeTicketLevel level = loadedLevels.getOrDefault(section.cubePos(), CubeTicketLevel.UNLOADED);
            boolean allowed = level.isAtLeast(CubeTicketLevel.BLOCK_TICKING);
            List<CubeBlockEntityRef> updated = new ArrayList<>();
            for (CubeBlockEntityRef ref : section.refs()) {
                if (ref.tickingAllowed() != allowed) {
                    updated.add(ref.withTickingAllowed(allowed));
                }
            }
            for (CubeBlockEntityRef ref : updated) {
                section.put(ref);
            }
        }
    }

    public void rebuildCube(LevelCube cube, String reason) {
        Objects.requireNonNull(cube, "cube");
        CubePos cubePos = cube.cubePos();
        CubeBlockEntitySection section = sections.computeIfAbsent(cubePos, CubeBlockEntitySection::new);
        section.clear();

        for (Map.Entry<Integer, CompoundTag> entry : cube.copyBlockEntityData().entrySet()) {
            int localIndex = entry.getKey();
            int localX = localIndex & CubePos.MASK;
            int localZ = (localIndex >> CubePos.SIZE_BITS) & CubePos.MASK;
            int localY = (localIndex >> (CubePos.SIZE_BITS * 2)) & CubePos.MASK;
            BlockPos worldPos = new BlockPos(cubePos.minBlockX() + localX, cubePos.minBlockY() + localY, cubePos.minBlockZ() + localZ);
            BlockState state = cube.getBlockState(localX, localY, localZ);
            CompoundTag tag = entry.getValue();
            section.put(new CubeBlockEntityRef(cubePos, new CubeLocalPos(localX, localY, localZ), worldPos, localIndex,
                    blockId(state), tag.getStringOr("id", blockId(state)),
                    tag.getBooleanOr("redlinePlaceholder", true), tag.getBooleanOr("redlineRealNbt", false), false));
        }

        if (section.isEmpty()) {
            sections.remove(cubePos);
        }
        totalRebuiltCubes++;
        rebuiltCubesLastTick++;
        lastCube = cubePos;
        lastReason = reason == null ? "rebuild" : reason;
    }

    public Optional<CubeBlockEntityRef> get(CubePos cubePos, int localIndex) {
        CubeBlockEntitySection section = sections.get(cubePos);
        return section == null ? Optional.empty() : section.get(localIndex);
    }

    public void removeCube(CubePos cubePos) {
        CubeBlockEntitySection removed = sections.remove(cubePos);
        if (removed != null && !removed.isEmpty()) {
            totalRemoved += removed.size();
            removedLastTick += removed.size();
            lastCube = cubePos;
            lastReason = "cube_unloaded";
        }
    }

    public CubeBlockEntitySnapshot snapshot() {
        int total = 0;
        int real = 0;
        int placeholders = 0;
        int tickingAllowed = 0;
        int tickingBlocked = 0;
        for (CubeBlockEntitySection section : sections.values()) {
            for (CubeBlockEntityRef ref : section.refs()) {
                total++;
                if (ref.realNbt()) {
                    real++;
                }
                if (ref.placeholder()) {
                    placeholders++;
                }
                if (ref.tickingAllowed()) {
                    tickingAllowed++;
                } else {
                    tickingBlocked++;
                }
            }
        }
        return new CubeBlockEntitySnapshot(total, sections.size(), real, placeholders, tickingAllowed, tickingBlocked,
                totalAdded, totalRemoved, totalUpdated, totalRealNbtCaptured, totalRebuiltCubes,
                addedLastTick, removedLastTick, updatedLastTick, realNbtCapturedLastTick, rebuiltCubesLastTick,
                lastCube, lastReason);
    }

    private void putRef(CubePos cubePos, BlockPos worldPos, BlockState state, int localIndex, CompoundTag tag, String reason) {
        CubeBlockEntitySection section = sections.computeIfAbsent(cubePos, CubeBlockEntitySection::new);
        boolean existed = section.get(localIndex).isPresent();
        boolean previousTicking = section.get(localIndex).map(CubeBlockEntityRef::tickingAllowed).orElse(false);
        CubeLocalPos local = new CubeLocalPos(CubePos.local(worldPos.getX()), CubePos.local(worldPos.getY()), CubePos.local(worldPos.getZ()));
        section.put(new CubeBlockEntityRef(cubePos, local, worldPos, localIndex, blockId(state), tag.getStringOr("id", blockId(state)),
                tag.getBooleanOr("redlinePlaceholder", true), tag.getBooleanOr("redlineRealNbt", false), previousTicking));
        if (existed) {
            totalUpdated++;
            updatedLastTick++;
        } else {
            totalAdded++;
            addedLastTick++;
        }
        lastCube = cubePos;
        lastReason = reason == null ? "mutation" : reason;
    }

    private void removeRef(CubePos cubePos, int localIndex, String reason) {
        CubeBlockEntitySection section = sections.get(cubePos);
        if (section == null) {
            return;
        }
        if (section.remove(localIndex).isPresent()) {
            totalRemoved++;
            removedLastTick++;
        }
        if (section.isEmpty()) {
            sections.remove(cubePos);
        }
        lastCube = cubePos;
        lastReason = reason == null ? "mutation_remove" : reason;
    }

    private static void normalizeTag(CompoundTag tag, CubePos cubePos, BlockPos worldPos, BlockState state, boolean placeholder) {
        tag.putInt("x", worldPos.getX());
        tag.putInt("y", worldPos.getY());
        tag.putInt("z", worldPos.getZ());
        tag.putString("redlineOwner", "cube");
        tag.putString("redlineOwnerCube", cubePos.x() + " " + cubePos.y() + " " + cubePos.z());
        tag.putBoolean("redlinePlaceholder", placeholder);
        tag.putBoolean("redlineRealNbt", !placeholder);
        if (!tag.contains("id")) {
            tag.putString("id", blockId(state));
        }
    }

    private static CompoundTag placeholderTag(BlockPos worldPos, BlockState state) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", blockId(state));
        tag.putInt("x", worldPos.getX());
        tag.putInt("y", worldPos.getY());
        tag.putInt("z", worldPos.getZ());
        tag.putBoolean("redlinePlaceholder", true);
        tag.putBoolean("redlineRealNbt", false);
        return tag;
    }

    private static boolean isBlockEntityBlock(BlockState state) {
        return state.getBlock() instanceof EntityBlock;
    }

    private static String blockId(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "minecraft:air" : id.toString();
    }
}
