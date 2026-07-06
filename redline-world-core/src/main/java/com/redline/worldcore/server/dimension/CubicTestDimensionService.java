package com.redline.worldcore.server.dimension;

import com.redline.worldcore.RedlineWorldCore;
import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.storage.CubeRegionStorage;
import com.redline.worldcore.server.cube.WorldCoreCubeLoading;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.access.CubeMutationResult;
import com.redline.worldcore.server.cube.access.CubeMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * M4 cubic test dimension helper.
 *
 * <p>This service deliberately keeps the first playable test isolated from the Overworld.
 * It stores virtual 16x16x16 cubes through Region3D storage and exposes commands that can
 * verify high/deep Y coordinates before we replace vanilla chunk ownership with mixins.</p>
 */
public final class CubicTestDimensionService {
    public static final CubicDimensionSettings SETTINGS = CubicDimensionSettings.defaults();

    private static final int MATERIALIZE_FLAGS = 3;

    public boolean isRegistered(MinecraftServer server) {
        return server.getLevel(CubicDimensionKeys.CUBIC_TEST_LEVEL) != null;
    }

    public Optional<ServerLevel> level(MinecraftServer server) {
        return Optional.ofNullable(server.getLevel(CubicDimensionKeys.CUBIC_TEST_LEVEL));
    }

    public Path storageRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve(RedlineWorldCore.MOD_ID)
                .resolve("cubic_test");
    }

    public CubeRegionStorage storage(MinecraftServer server) {
        return new CubeRegionStorage(storageRoot(server));
    }

    public VirtualSetResult setVirtualBlock(MinecraftServer server, BlockPos blockPos, BlockState state) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(blockPos, "blockPos");
        Objects.requireNonNull(state, "state");
        ensureInsideConfiguredHeight(blockPos.getY());

        CubePos cubePos = CubePos.fromBlock(blockPos);
        WorldCoreCubeLoading.cubicTestForServer(server)
                .mutateBlock(blockPos, state, CubeMutationContext.command(true).withReason("cubic_test_virtual_set"));
        return new VirtualSetResult(cubePos, state);
    }

    public Optional<VirtualGetResult> getVirtualBlock(MinecraftServer server, BlockPos blockPos) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(blockPos, "blockPos");
        ensureInsideConfiguredHeight(blockPos.getY());

        CubePos cubePos = CubePos.fromBlock(blockPos);
        Optional<BlockState> cached = WorldCoreCubeLoading.cubicTestForServer(server).readBlock(blockPos);
        if (cached.isPresent()) {
            return Optional.of(new VirtualGetResult(cubePos, cached.get()));
        }
        return storage(server).get(cubePos)
                .map(cube -> new VirtualGetResult(cubePos, cube.getBlockState(blockPos)));
    }

    public FillCubeResult fillVirtualCube(MinecraftServer server, CubePos cubePos, BlockState state) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(cubePos, "cubePos");
        Objects.requireNonNull(state, "state");
        if (!SETTINGS.containsCubeY(cubePos.y())) {
            throw new IllegalArgumentException("CubeY " + cubePos.y() + " is outside configured cubic test range "
                    + SETTINGS.minCubeY() + ".." + SETTINGS.maxCubeY());
        }

        LevelCube cube = new LevelCube(cubePos);
        cube.setStatus(CubeStatus.FULL);
        cube.fill(state);
        storage(server).put(cube);
        return new FillCubeResult(cubePos, state, CubePos.BLOCK_COUNT);
    }

    public ProbeResult writeProbeCube(MinecraftServer server, CubePos cubePos) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(cubePos, "cubePos");
        if (!SETTINGS.containsCubeY(cubePos.y())) {
            throw new IllegalArgumentException("CubeY " + cubePos.y() + " is outside configured cubic test range "
                    + SETTINGS.minCubeY() + ".." + SETTINGS.maxCubeY());
        }

        LevelCube cube = new LevelCube(cubePos);
        cube.setStatus(CubeStatus.FULL);
        cube.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        cube.setBlockState(1, 1, 1, Blocks.GLASS.defaultBlockState());
        cube.setBlockState(8, 8, 8, Blocks.GOLD_BLOCK.defaultBlockState());
        cube.setBlockState(15, 15, 15, Blocks.DIAMOND_BLOCK.defaultBlockState());
        storage(server).put(cube);
        return new ProbeResult(cubePos, true, "written");
    }

    public ProbeResult verifyProbeCube(MinecraftServer server, CubePos cubePos) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(cubePos, "cubePos");
        LevelCube cube = storage(server).get(cubePos)
                .orElseThrow(() -> new IllegalStateException("Probe cube is missing: " + cubePos));

        expect(cube.status() == CubeStatus.FULL, "status must be FULL");
        expect(cube.getBlockState(0, 0, 0).is(Blocks.STONE), "local 0 0 0 must be stone");
        expect(cube.getBlockState(1, 1, 1).is(Blocks.GLASS), "local 1 1 1 must be glass");
        expect(cube.getBlockState(8, 8, 8).is(Blocks.GOLD_BLOCK), "local 8 8 8 must be gold_block");
        expect(cube.getBlockState(15, 15, 15).is(Blocks.DIAMOND_BLOCK), "local 15 15 15 must be diamond_block");
        return new ProbeResult(cubePos, true, "verified");
    }

    public MaterializeResult materializeVirtualCube(MinecraftServer server, ServerLevel level, CubePos cubePos) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(cubePos, "cubePos");

        if (!isInsideVanillaShell(level, cubePos)) {
            throw new IllegalArgumentException("Cube " + cubePos + " is outside the temporary vanilla shell for "
                    + level.dimension().identifier() + ": level=" + level.getMinY() + ".." + level.getMaxY()
                    + ", configured shell=" + SETTINGS.vanillaShellMinY() + ".." + SETTINGS.vanillaShellMaxY()
                    + ". It still exists in cube-only storage; use native cube sync/render instead of vanilla materialization.");
        }

        LevelCube cube = storage(server).get(cubePos)
                .orElseThrow(() -> new IllegalStateException("Cube is missing in cubic storage: " + cubePos));

        int changed = 0;
        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    BlockState state = cube.getBlockState(localX, localY, localZ);
                    BlockPos blockPos = new BlockPos(
                            cubePos.minBlockX() + localX,
                            cubePos.minBlockY() + localY,
                            cubePos.minBlockZ() + localZ
                    );
                    if (level.setBlock(blockPos, state, MATERIALIZE_FLAGS)) {
                        changed++;
                    }
                }
            }
        }
        return new MaterializeResult(cubePos, changed);
    }

    public HeightReport heightReport(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return new HeightReport(
                SETTINGS.minCubeY(),
                SETTINGS.maxCubeY(),
                SETTINGS.minBlockY(),
                SETTINGS.maxBlockY(),
                SETTINGS.blockHeight(),
                SETTINGS.vanillaShellMinY(),
                SETTINGS.vanillaShellMaxY(),
                SETTINGS.vanillaShellMaxY() - SETTINGS.vanillaShellMinY() + 1,
                level.getMinY(),
                level.getMaxY(),
                level.getHeight(),
                SETTINGS.containsBlockY(CubicDimensionSettings.EXTREME_HIGH_TEST_Y),
                SETTINGS.containsBlockY(CubicDimensionSettings.EXTREME_LOW_TEST_Y),
                SETTINGS.isBlockInsideVanillaShell(CubicDimensionSettings.EXTREME_HIGH_TEST_Y),
                SETTINGS.isBlockInsideVanillaShell(CubicDimensionSettings.EXTREME_LOW_TEST_Y)
        );
    }

    public ExtremeHeightProbeResult runExtremeHeightProbe(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        ServerCubeCache cache = WorldCoreCubeLoading.cubicTestForServer(server);
        BlockPos highPos = new BlockPos(0, CubicDimensionSettings.EXTREME_HIGH_TEST_Y, 0);
        BlockPos lowPos = new BlockPos(0, CubicDimensionSettings.EXTREME_LOW_TEST_Y, 0);
        BlockState highState = Blocks.GOLD_BLOCK.defaultBlockState();
        BlockState lowState = Blocks.DIAMOND_BLOCK.defaultBlockState();

        ExtremeHeightProbeEntry high = writeSaveUnloadVerify(cache, highPos, highState, "m19_4_extreme_high_probe");
        ExtremeHeightProbeEntry low = writeSaveUnloadVerify(cache, lowPos, lowState, "m19_4_extreme_low_probe");
        return new ExtremeHeightProbeResult(high, low);
    }

    private static ExtremeHeightProbeEntry writeSaveUnloadVerify(ServerCubeCache cache, BlockPos pos, BlockState state, String reason) {
        CubePos cubePos = CubePos.fromBlock(pos);
        CubeMutationResult mutation = cache.access().setBlockState(pos, state, CubeMutationContext.command(true).withReason(reason));
        if (!mutation.applied()) {
            throw new IllegalStateException("Mutation rejected at " + pos + ": " + mutation.reason());
        }
        boolean saved = cache.forceSaveCube(cubePos);
        boolean unloaded = cache.debugUnloadCube(cubePos, true);
        BlockState reloaded = cache.readBlock(pos)
                .orElseThrow(() -> new IllegalStateException("Reloaded block is missing at " + pos + ", cube=" + cubePos));
        if (!reloaded.equals(state)) {
            throw new IllegalStateException("Reload mismatch at " + pos + ": expected=" + blockStateName(state)
                    + ", actual=" + blockStateName(reloaded));
        }
        return new ExtremeHeightProbeEntry(pos, cubePos, state, saved, unloaded, mutation.changed() || mutation.statusPromoted());
    }

    public static boolean isInsideVanillaShell(ServerLevel level, CubePos cubePos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(cubePos, "cubePos");
        return SETTINGS.isCubeInsideVanillaShell(cubePos)
                && !level.isOutsideBuildHeight(cubePos.minBlockY())
                && !level.isOutsideBuildHeight(cubePos.maxBlockY());
    }

    public static BlockState parseMarkerBlock(String marker) {
        String normalized = marker.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "air" -> Blocks.AIR.defaultBlockState();
            case "stone" -> Blocks.STONE.defaultBlockState();
            case "dirt" -> Blocks.DIRT.defaultBlockState();
            case "cobble", "cobblestone" -> Blocks.COBBLESTONE.defaultBlockState();
            case "glass" -> Blocks.GLASS.defaultBlockState();
            case "gold", "gold_block" -> Blocks.GOLD_BLOCK.defaultBlockState();
            case "diamond", "diamond_block" -> Blocks.DIAMOND_BLOCK.defaultBlockState();
            default -> parseRegistryBlock(normalized).defaultBlockState();
        };
    }

    public static String blockStateName(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "minecraft:air" : id.toString();
    }

    private static Block parseRegistryBlock(String rawId) {
        Identifier id = rawId.contains(":")
                ? Identifier.tryParse(rawId)
                : Identifier.fromNamespaceAndPath("minecraft", rawId);
        if (id == null) {
            throw new IllegalArgumentException("Invalid block id: " + rawId);
        }
        return BuiltInRegistries.BLOCK.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown block id: " + id));
    }

    private static void ensureInsideConfiguredHeight(int blockY) {
        if (blockY < SETTINGS.minBlockY() || blockY > SETTINGS.maxBlockY()) {
            throw new IllegalArgumentException("BlockY " + blockY + " is outside configured cubic test range "
                    + SETTINGS.minBlockY() + ".." + SETTINGS.maxBlockY());
        }
    }

    private static void expect(boolean value, String message) {
        if (!value) {
            throw new IllegalStateException(message);
        }
    }

    public record VirtualSetResult(CubePos cubePos, BlockState state) {
    }

    public record VirtualGetResult(CubePos cubePos, BlockState state) {
    }

    public record FillCubeResult(CubePos cubePos, BlockState state, int blockCount) {
    }

    public record ProbeResult(CubePos cubePos, boolean ok, String action) {
    }

    public record MaterializeResult(CubePos cubePos, int changedBlocks) {
    }

    public record HeightReport(
            int internalMinCubeY,
            int internalMaxCubeY,
            int internalMinBlockY,
            int internalMaxBlockY,
            int internalBlockHeight,
            int vanillaShellMinY,
            int vanillaShellMaxY,
            int vanillaShellHeight,
            int dimensionTypeMinY,
            int dimensionTypeMaxY,
            int dimensionTypeHeight,
            boolean highProbeInsideInternal,
            boolean lowProbeInsideInternal,
            boolean highProbeInsideVanillaShell,
            boolean lowProbeInsideVanillaShell
    ) {
    }

    public record ExtremeHeightProbeResult(ExtremeHeightProbeEntry high, ExtremeHeightProbeEntry low) {
    }

    public record ExtremeHeightProbeEntry(BlockPos blockPos, CubePos cubePos, BlockState state, boolean saved, boolean unloaded, boolean changed) {
    }
}

