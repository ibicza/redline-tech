package com.redline.worldcore.server.storage;

import com.redline.worldcore.api.pos.Region3DPos;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/** Fixed-size Region3D header. One header entry points to one compressed CubeNBT blob. */
final class Region3DHeader {
    static final int MAGIC = 0x52334431; // R3D1
    static final int VERSION = 1;
    static final int HEADER_SIZE = Integer.BYTES * 3
            + Region3DPos.ENTRY_COUNT * (Long.BYTES + Integer.BYTES + Long.BYTES + Integer.BYTES);

    private final long[] offsets = new long[Region3DPos.ENTRY_COUNT];
    private final int[] lengths = new int[Region3DPos.ENTRY_COUNT];
    private final long[] timestamps = new long[Region3DPos.ENTRY_COUNT];
    private final int[] statusFlags = new int[Region3DPos.ENTRY_COUNT];
    private int flags;

    static Region3DHeader empty() {
        return new Region3DHeader();
    }

    static Region3DHeader read(RandomAccessFile file) throws IOException {
        if (file.length() == 0L) {
            return empty();
        }
        if (file.length() < HEADER_SIZE) {
            throw new IOException("Region3D file is smaller than header: " + file.length() + " < " + HEADER_SIZE);
        }

        file.seek(0L);
        int magic = file.readInt();
        int version = file.readInt();
        Region3DHeader header = new Region3DHeader();
        header.flags = file.readInt();

        if (magic != MAGIC) {
            throw new IOException("Bad Region3D magic: 0x" + Integer.toHexString(magic));
        }
        if (version != VERSION) {
            throw new IOException("Unsupported Region3D version: " + version);
        }

        for (int index = 0; index < Region3DPos.ENTRY_COUNT; index++) {
            header.offsets[index] = file.readLong();
            header.lengths[index] = file.readInt();
            header.timestamps[index] = file.readLong();
            header.statusFlags[index] = file.readInt();
        }
        return header;
    }

    void write(RandomAccessFile file) throws IOException {
        file.seek(0L);
        file.writeInt(MAGIC);
        file.writeInt(VERSION);
        file.writeInt(flags);
        for (int index = 0; index < Region3DPos.ENTRY_COUNT; index++) {
            file.writeLong(offsets[index]);
            file.writeInt(lengths[index]);
            file.writeLong(timestamps[index]);
            file.writeInt(statusFlags[index]);
        }
    }

    boolean hasEntry(int index) {
        checkIndex(index);
        return offsets[index] >= HEADER_SIZE && lengths[index] > 0;
    }

    long offset(int index) {
        checkIndex(index);
        return offsets[index];
    }

    int length(int index) {
        checkIndex(index);
        return lengths[index];
    }

    void setEntry(int index, long offset, int length, int statusFlag) {
        checkIndex(index);
        this.offsets[index] = offset;
        this.lengths[index] = length;
        this.timestamps[index] = System.currentTimeMillis();
        this.statusFlags[index] = statusFlag;
    }

    void clearEntry(int index) {
        checkIndex(index);
        this.offsets[index] = 0L;
        this.lengths[index] = 0;
        this.timestamps[index] = System.currentTimeMillis();
        this.statusFlags[index] = 0;
    }

    int usedEntries() {
        int count = 0;
        for (int length : lengths) {
            if (length > 0) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return "Region3DHeader{usedEntries=" + usedEntries()
                + ", flags=" + flags
                + ", headerSize=" + HEADER_SIZE
                + ", firstLengths=" + Arrays.toString(Arrays.copyOf(lengths, 8))
                + '}';
    }

    private static void checkIndex(int index) {
        if (index < 0 || index >= Region3DPos.ENTRY_COUNT) {
            throw new IllegalArgumentException("Region3D entry index must be in [0, 4095], got " + index);
        }
    }
}
