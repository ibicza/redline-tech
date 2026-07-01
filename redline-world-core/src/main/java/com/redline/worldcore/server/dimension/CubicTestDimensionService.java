package com.redline.worldcore.server.dimension;

import com.redline.worldcore.RedlineWorldCore;
import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.dimension.CubicDimensionKeys;
import com.redline.worldcore.api.generation.CubicDimensionSettings;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.storage.CubeRegionStorage;
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
        CubeRegionStorage storage = storage(server);
        LevelCube cube = storage.getOrCreate(cubePos);
        cube.setStatus(CubeStatus.FULL);
        cube.setBlockState(blockPos, state);
        storage.put(cube);
        return new VirtualSetResult(cubePos, state);
    }

    public Optional<VirtualGetResult> getVirtualBlock(MinecraftServer server, BlockPos blockPos) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(blockPos, "blockPos");
        ensureInsideConfiguredHeight(blockPos.getY());

        CubePos cubePos = CubePos.fromBlock(blockPos);
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

        if (cubePos.minBlockY() < level.getMinY() || cubePos.maxBlockY() >= level.getMaxY()) {
            throw new IllegalArgumentException("Cube " + cubePos + " is outside vanilla build height for "
                    + level.dimension().identifier() + ": " + level.getMinY() + ".." + (level.getMaxY() - 1)
                    + ". It still exists in cubic storage, but cannot be materialized into vanilla blocks yet.");
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
}
