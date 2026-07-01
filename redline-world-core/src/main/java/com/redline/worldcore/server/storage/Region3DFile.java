package com.redline.worldcore.server.storage;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.pos.Region3DPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Low-level append-only Region3D file. Compaction is intentionally postponed beyond M3. */
public final class Region3DFile {
    private final Path path;
    private final Region3DPos regionPos;
    private final CubeSerializer serializer;

    public Region3DFile(Path path, Region3DPos regionPos, CubeSerializer serializer) {
        this.path = Objects.requireNonNull(path, "path");
        this.regionPos = Objects.requireNonNull(regionPos, "regionPos");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    public Path path() {
        return path;
    }

    public Region3DPos regionPos() {
        return regionPos;
    }

    public synchronized Optional<LevelCube> readCube(CubePos cubePos) throws IOException {
        requireInRegion(cubePos);
        if (!Files.exists(path)) {
            return Optional.empty();
        }

        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            Region3DHeader header = Region3DHeader.read(file);
            int index = Region3DPos.localIndex(cubePos);
            if (!header.hasEntry(index)) {
                return Optional.empty();
            }

            byte[] bytes = new byte[header.length(index)];
            file.seek(header.offset(index));
            file.readFully(bytes);
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            LevelCube cube = serializer.read(tag);
            if (!cube.cubePos().equals(cubePos)) {
                throw new IOException("CubeNBT position mismatch. Requested " + cubePos + " but read " + cube.cubePos());
            }
            return Optional.of(cube);
        }
    }

    public synchronized void writeCube(LevelCube cube) throws IOException {
        requireInRegion(cube.cubePos());
        Files.createDirectories(path.getParent());

        byte[] bytes = writeCompressed(serializer.write(cube));
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            Region3DHeader header = Region3DHeader.read(file);
            if (file.length() == 0L) {
                file.setLength(Region3DHeader.HEADER_SIZE);
            }

            long offset = Math.max(file.length(), Region3DHeader.HEADER_SIZE);
            file.seek(offset);
            file.write(bytes);
            header.setEntry(Region3DPos.localIndex(cube.cubePos()), offset, bytes.length, cube.status().ordinal());
            header.write(file);
        }
    }

    public synchronized boolean removeCube(CubePos cubePos) throws IOException {
        requireInRegion(cubePos);
        if (!Files.exists(path)) {
            return false;
        }

        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            Region3DHeader header = Region3DHeader.read(file);
            int index = Region3DPos.localIndex(cubePos);
            boolean existed = header.hasEntry(index);
            if (existed) {
                header.clearEntry(index);
                header.write(file);
            }
            return existed;
        }
    }

    public synchronized int usedEntries() throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            return Region3DHeader.read(file).usedEntries();
        }
    }

    private byte[] writeCompressed(CompoundTag tag) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        NbtIo.writeCompressed(tag, output);
        return output.toByteArray();
    }

    private void requireInRegion(CubePos cubePos) {
        Region3DPos actual = Region3DPos.fromCube(cubePos);
        if (!regionPos.equals(actual)) {
            throw new IllegalArgumentException("Cube " + cubePos + " belongs to region " + actual + ", not " + regionPos);
        }
    }
}
