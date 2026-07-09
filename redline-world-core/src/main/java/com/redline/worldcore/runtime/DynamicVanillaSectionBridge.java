package com.redline.worldcore.runtime;

import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.client.sync.ClientCubeSectionSnapshot;
import com.redline.worldcore.server.compat.CubicClientSyncBridge;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * M20.1 CubicChunks-style dynamic vanilla section layer.
 *
 * <p>This is the architectural pivot away from per-block native gameplay shims.  Instead of faking every vanilla
 * mechanic outside the temporary shell, this bridge materializes real {@link LevelChunkSection} instances keyed by
 * sectionY/cubeY.  Vanilla {@link LevelChunk} methods can then read/write normal 16x16x16 section storage while the
 * cube backend remains the source of truth for persistence and network sync.</p>
 *
 * <p>The storage is intentionally sparse: no 2048-section array is allocated for a chunk column.  A section exists only
 * after a cube snapshot/loaded cube/player edit touches it.</p>
 */
public final class DynamicVanillaSectionBridge {
    private static final CubicDimensionSettings SETTINGS = CubicDimensionSettings.defaults();
    private static final Map<Level, DynamicLevelSections> LEVEL_SECTIONS = new IdentityHashMap<>();

    private static long serverMaterialized;
    private static long clientMaterialized;
    private static long sectionCreates;
    private static long sectionHits;
    private static long sectionMisses;
    private static long chunkReads;
    private static long chunkWrites;
    private static long cubeSyncWrites;
    private static long cubeSyncRejected;
    private static long snapshotMirrors;
    private static long deltaMirrors;
    private static long unloads;

    private DynamicVanillaSectionBridge() {
    }

    public static boolean isCubicLevel(Level level) {
        return level != null && level.dimension().equals(CubicDimensionKeys.CUBIC_TEST_LEVEL);
    }

    public static boolean isInsideInternalRange(int blockY) {
        return SETTINGS.containsBlockY(blockY);
    }

    public static boolean isInsideVanillaShell(int blockY) {
        return SETTINGS.isBlockInsideVanillaShell(blockY);
    }

    /** True when this Y must be handled by sparse dynamic sections, not vanilla's fixed section array. */
    public static boolean isDynamicSectionY(Level level, int blockY) {
        return isCubicLevel(level) && isInsideInternalRange(blockY) && !isInsideVanillaShell(blockY);
    }

    public static Optional<LevelChunkSection> section(Level level, int chunkX, int chunkZ, int sectionY, boolean create) {
        if (!isCubicLevel(level) || !SETTINGS.containsCubeY(sectionY)) {
            sectionMisses++;
            return Optional.empty();
        }
        DynamicLevelSections sections = levelSections(level, create);
        if (sections == null) {
            sectionMisses++;
            return Optional.empty();
        }
        LevelChunkSection section = sections.section(chunkX, chunkZ, sectionY, create);
        if (section == null) {
            sectionMisses++;
            return Optional.empty();
        }
        sectionHits++;
        return Optional.of(section);
    }

    public static Optional<LevelChunkSection> section(LevelChunk chunk, int sectionY, boolean create) {
        Level level = chunk.getLevel();
        ChunkPos pos = chunk.getPos();
        return section(level, pos.x(), pos.z(), sectionY, create);
    }

    public static Optional<BlockState> readBlockState(Level level, BlockPos pos) {
        if (!isDynamicSectionY(level, pos.getY())) {
            return Optional.empty();
        }
        int sectionY = SectionPos.blockToSectionCoord(pos.getY());
        Optional<LevelChunkSection> section = section(level, SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()), sectionY, false);
        if (section.isEmpty()) {
            return Optional.empty();
        }
        chunkReads++;
        return Optional.of(section.get().getBlockState(pos.getX() & CubePos.MASK, pos.getY() & CubePos.MASK, pos.getZ() & CubePos.MASK));
    }

    public static Optional<FluidState> readFluidState(Level level, BlockPos pos) {
        Optional<BlockState> state = readBlockState(level, pos);
        return state.map(BlockState::getFluidState);
    }

    public static Optional<BlockState> levelChunkGetBlockState(LevelChunk chunk, BlockPos pos) {
        Level level = chunk.getLevel();
        if (!isDynamicSectionY(level, pos.getY())) {
            return Optional.empty();
        }
        return readBlockState(level, pos).or(() -> materializeServerCubeAndRead(level, pos));
    }

    public static Optional<FluidState> levelChunkGetFluidState(LevelChunk chunk, int x, int y, int z) {
        Level level = chunk.getLevel();
        if (!isDynamicSectionY(level, y)) {
            return Optional.empty();
        }
        BlockPos pos = new BlockPos(x, y, z);
        Optional<BlockState> state = levelChunkGetBlockState(chunk, pos);
        return Optional.of(state.map(BlockState::getFluidState).orElseGet(Fluids.EMPTY::defaultFluidState));
    }

    public static Optional<BlockState> levelChunkSetBlockState(LevelChunk chunk, BlockPos pos, BlockState state, int flags) {
        Level level = chunk.getLevel();
        if (!isDynamicSectionY(level, pos.getY())) {
            return Optional.empty();
        }
        int sectionY = SectionPos.blockToSectionCoord(pos.getY());
        LevelChunkSection section = section(chunk, sectionY, true).orElse(null);
        if (section == null) {
            return Optional.empty();
        }
        BlockState previous = section.setBlockState(pos.getX() & CubePos.MASK, pos.getY() & CubePos.MASK, pos.getZ() & CubePos.MASK, state);
        chunkWrites++;
        if (level instanceof ServerLevel serverLevel) {
            CubicClientSyncBridge.NativeBlockEditResult result = CubicClientSyncBridge.writeNativeSystemBlockEdit(serverLevel, pos, state, "dynamic_vanilla_section_set_block");
            if (result.applied()) {
                cubeSyncWrites++;
            } else {
                cubeSyncRejected++;
            }
        }
        return Optional.of(previous == state ? state : previous);
    }

    public static Optional<LevelChunkSection> levelChunkGetSectionByIndex(LevelChunk chunk, int sectionIndex) {
        int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
        if (sectionY >= SETTINGS.vanillaShellRange().minCubeY() && sectionY <= SETTINGS.vanillaShellRange().maxCubeY()) {
            return Optional.empty();
        }
        return section(chunk, sectionY, false);
    }

    public static void mirrorClientSnapshot(Level clientLevel, ClientCubeSectionSnapshot snapshot) {
        if (!isCubicLevel(clientLevel)) {
            return;
        }
        CubePos cubePos = snapshot.cubePos();
        if (!SETTINGS.containsCubeY(cubePos.y())) {
            return;
        }
        LevelChunkSection section = section(clientLevel, cubePos.x(), cubePos.z(), cubePos.y(), true).orElse(null);
        if (section == null) {
            return;
        }
        fillSectionFromSnapshot(section, snapshot);
        snapshotMirrors++;
        clientMaterialized++;
    }

    public static void mirrorClientDelta(Level clientLevel, CubePos cubePos, int[] localIndices, BlockState[] states) {
        if (!isCubicLevel(clientLevel) || localIndices.length != states.length) {
            return;
        }
        LevelChunkSection section = section(clientLevel, cubePos.x(), cubePos.z(), cubePos.y(), true).orElse(null);
        if (section == null) {
            return;
        }
        for (int i = 0; i < localIndices.length; i++) {
            int localIndex = localIndices[i];
            int localX = localIndex & CubePos.MASK;
            int localZ = (localIndex >> CubePos.SIZE_BITS) & CubePos.MASK;
            int localY = (localIndex >> (CubePos.SIZE_BITS * 2)) & CubePos.MASK;
            section.setBlockState(localX, localY, localZ, states[i]);
        }
        deltaMirrors++;
    }

    public static void unloadClientSection(Level clientLevel, CubePos cubePos) {
        DynamicLevelSections sections = levelSections(clientLevel, false);
        if (sections == null) {
            return;
        }
        if (sections.remove(cubePos.x(), cubePos.z(), cubePos.y())) {
            unloads++;
        }
    }

    public static void clear(Level level) {
        synchronized (LEVEL_SECTIONS) {
            LEVEL_SECTIONS.remove(level);
        }
    }

    public static Snapshot snapshot() {
        int levels;
        int sections;
        synchronized (LEVEL_SECTIONS) {
            levels = LEVEL_SECTIONS.size();
            sections = 0;
            for (DynamicLevelSections value : LEVEL_SECTIONS.values()) {
                sections += value.size();
            }
        }
        return new Snapshot(levels, sections, serverMaterialized, clientMaterialized, sectionCreates, sectionHits, sectionMisses,
                chunkReads, chunkWrites, cubeSyncWrites, cubeSyncRejected, snapshotMirrors, deltaMirrors, unloads);
    }

    private static Optional<BlockState> materializeServerCubeAndRead(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(serverLevel.getServer());
        CubePos cubePos = CubePos.fromBlock(pos);
        if (!cache.settings().containsCube(cubePos)) {
            return Optional.empty();
        }
        Optional<BlockState> maybeState = cache.readLoadedBlock(pos);
        if (maybeState.isEmpty()) {
            return Optional.empty();
        }
        LevelChunkSection section = section(level, cubePos.x(), cubePos.z(), cubePos.y(), true).orElse(null);
        if (section == null) {
            return maybeState;
        }
        fillSectionFromServerCube(section, cache, cubePos);
        serverMaterialized++;
        return maybeState;
    }

    private static void fillSectionFromServerCube(LevelChunkSection section, ServerCubeCache cache, CubePos cubePos) {
        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            int y = cubePos.minBlockY() + localY;
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                int z = cubePos.minBlockZ() + localZ;
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    int x = cubePos.minBlockX() + localX;
                    BlockState state = cache.readLoadedBlock(new BlockPos(x, y, z)).orElseGet(() -> Blocks.AIR.defaultBlockState());
                    section.setBlockState(localX, localY, localZ, state);
                }
            }
        }
    }

    private static void fillSectionFromSnapshot(LevelChunkSection section, ClientCubeSectionSnapshot snapshot) {
        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    int localIndex = CubePos.localIndex(localX, localY, localZ);
                    section.setBlockState(localX, localY, localZ, snapshot.blockStateAtLocalIndex(localIndex));
                }
            }
        }
    }

    private static DynamicLevelSections levelSections(Level level, boolean create) {
        synchronized (LEVEL_SECTIONS) {
            DynamicLevelSections sections = LEVEL_SECTIONS.get(level);
            if (sections == null && create) {
                sections = new DynamicLevelSections(level);
                LEVEL_SECTIONS.put(level, sections);
            }
            return sections;
        }
    }

    private static long key(int chunkX, int chunkZ, int sectionY) {
        long x = (long) chunkX & 0x3FFFFFL;
        long z = (long) chunkZ & 0x3FFFFFL;
        long y = (long) sectionY & 0xFFFFFL;
        return x | (z << 22) | (y << 44);
    }

    private static final class DynamicLevelSections {
        private final WeakReference<Level> level;
        private final Map<Long, LevelChunkSection> sections = new HashMap<>();
        private PalettedContainerFactory factory;

        private DynamicLevelSections(Level level) {
            this.level = new WeakReference<>(level);
        }

        private LevelChunkSection section(int chunkX, int chunkZ, int sectionY, boolean create) {
            long key = key(chunkX, chunkZ, sectionY);
            LevelChunkSection existing = sections.get(key);
            if (existing != null || !create) {
                return existing;
            }
            Level owner = level.get();
            if (owner == null) {
                return null;
            }
            if (factory == null) {
                factory = PalettedContainerFactory.create(owner.registryAccess());
            }
            LevelChunkSection created = new LevelChunkSection(factory);
            sections.put(key, created);
            sectionCreates++;
            return created;
        }

        private boolean remove(int chunkX, int chunkZ, int sectionY) {
            return sections.remove(key(chunkX, chunkZ, sectionY)) != null;
        }

        private int size() {
            return sections.size();
        }
    }

    public record Snapshot(
            int levels,
            int sections,
            long serverMaterialized,
            long clientMaterialized,
            long sectionCreates,
            long sectionHits,
            long sectionMisses,
            long chunkReads,
            long chunkWrites,
            long cubeSyncWrites,
            long cubeSyncRejected,
            long snapshotMirrors,
            long deltaMirrors,
            long unloads
    ) {
        public String oneLine() {
            return "levels=" + levels
                    + ", sections=" + sections
                    + ", serverMaterialized=" + serverMaterialized
                    + ", clientMaterialized=" + clientMaterialized
                    + ", creates=" + sectionCreates
                    + ", hits/misses=" + sectionHits + "/" + sectionMisses
                    + ", reads/writes=" + chunkReads + "/" + chunkWrites
                    + ", cubeSync=" + cubeSyncWrites + "/" + cubeSyncRejected
                    + ", snapshot/delta/unload=" + snapshotMirrors + "/" + deltaMirrors + "/" + unloads;
        }
    }
}
