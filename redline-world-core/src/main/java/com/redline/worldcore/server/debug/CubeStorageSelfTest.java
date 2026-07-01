package com.redline.worldcore.server.debug;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.storage.CubeRegionStorage;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class CubeStorageSelfTest {
    public static Result runOrThrow() {
        Path tempDirectory;
        try {
            tempDirectory = Files.createTempDirectory("redline-world-core-storage-selftest-");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create temp storage directory", exception);
        }

        try {
            Result result = runIn(tempDirectory);
            deleteRecursively(tempDirectory);
            return result;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Storage self-test failed", exception);
        }
    }

    private static Result runIn(Path tempDirectory) {
        CubePos origin = new CubePos(0, 0, 0);
        CubePos sameRegion = new CubePos(1, 2, 3);
        CubePos negative = new CubePos(-1, -1, -1);
        CubePos far = new CubePos(32, -32, 48);

        CubeRegionStorage writer = new CubeRegionStorage(tempDirectory);
        writeProbeCube(writer, origin, 1);
        writeProbeCube(writer, sameRegion, 2);
        writeProbeCube(writer, negative, 3);
        writeProbeCube(writer, far, 4);
        writer.flushAll();

        expect(Files.exists(writer.regionPath(origin.regionPos())), "origin region file must exist");
        expect(Files.exists(writer.regionPath(negative.regionPos())), "negative region file must exist");
        expect(writer.usedEntries(origin.regionPos()) == 2, "origin region must contain two cube entries");

        CubeRegionStorage reader = new CubeRegionStorage(tempDirectory);
        assertProbeCube(reader, origin, 1);
        assertProbeCube(reader, sameRegion, 2);
        assertProbeCube(reader, negative, 3);
        assertProbeCube(reader, far, 4);

        LevelCube overwrite = reader.getOrCreate(origin);
        overwrite.setStatus(CubeStatus.LIGHT_READY);
        overwrite.setBlockState(0, 0, 0, Blocks.GOLD_BLOCK.defaultBlockState());
        reader.put(overwrite);

        CubeRegionStorage rereader = new CubeRegionStorage(tempDirectory);
        LevelCube overwritten = rereader.get(origin).orElseThrow(() -> new IllegalStateException("missing overwritten cube"));
        expect(overwritten.status() == CubeStatus.LIGHT_READY, "overwritten status must roundtrip");
        expect(overwritten.getBlockState(0, 0, 0).is(Blocks.GOLD_BLOCK), "overwritten block must roundtrip");

        expect(rereader.remove(negative), "remove must report existing cube");
        CubeRegionStorage afterRemove = new CubeRegionStorage(tempDirectory);
        expect(afterRemove.get(negative).isEmpty(), "removed cube must not load again");

        return new Result(tempDirectory, 4, 3);
    }

    private static void writeProbeCube(CubeRegionStorage storage, CubePos cubePos, int marker) {
        LevelCube cube = new LevelCube(cubePos);
        cube.setStatus(CubeStatus.FULL);
        cube.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        cube.setBlockState(1, 0, 0, Blocks.DIRT.defaultBlockState());
        cube.setBlockState(2, 0, 0, Blocks.COBBLESTONE.defaultBlockState());
        cube.setBlockState(3, 0, 0, marker % 2 == 0 ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.GLASS.defaultBlockState());
        cube.setBlockState(15, 15, 15, Blocks.DIAMOND_BLOCK.defaultBlockState());
        storage.put(cube);
    }

    private static void assertProbeCube(CubeRegionStorage storage, CubePos cubePos, int marker) {
        LevelCube cube = storage.get(cubePos).orElseThrow(() -> new IllegalStateException("missing cube " + cubePos));
        expect(cube.status() == CubeStatus.FULL, "status must roundtrip for " + cubePos);
        expect(cube.getBlockState(0, 0, 0).is(Blocks.STONE), "stone marker must roundtrip for " + cubePos);
        expect(cube.getBlockState(1, 0, 0).is(Blocks.DIRT), "dirt marker must roundtrip for " + cubePos);
        expect(cube.getBlockState(2, 0, 0).is(Blocks.COBBLESTONE), "cobble marker must roundtrip for " + cubePos);
        expect(cube.getBlockState(3, 0, 0).is(marker % 2 == 0 ? Blocks.OAK_PLANKS : Blocks.GLASS), "variant marker must roundtrip for " + cubePos);
        expect(cube.getBlockState(15, 15, 15).is(Blocks.DIAMOND_BLOCK), "edge marker must roundtrip for " + cubePos);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to delete " + path, exception);
                }
            });
        }
    }

    public record Result(Path tempDirectory, int writtenCubes, int survivingCubesAfterRemove) {
    }

    private CubeStorageSelfTest() {
    }
}
