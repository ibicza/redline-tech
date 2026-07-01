package com.redline.worldcore.api.pos;

/**
 * Physical storage region coordinate.
 * One Region3D contains 16x16x16 cubes, i.e. 4096 cube entries.
 */
public record Region3DPos(int x, int y, int z) {
    public static final int SIZE_BITS = 4;
    public static final int SIZE = 1 << SIZE_BITS;
    public static final int MASK = SIZE - 1;
    public static final int ENTRY_COUNT = SIZE * SIZE * SIZE;

    public static Region3DPos fromCube(CubePos cubePos) {
        return fromCube(cubePos.x(), cubePos.y(), cubePos.z());
    }

    public static Region3DPos fromCube(int cubeX, int cubeY, int cubeZ) {
        return new Region3DPos(
                Math.floorDiv(cubeX, SIZE),
                Math.floorDiv(cubeY, SIZE),
                Math.floorDiv(cubeZ, SIZE)
        );
    }

    public static int local(int cubeCoord) {
        return cubeCoord & MASK;
    }

    public static int localIndex(CubePos cubePos) {
        return localIndex(local(cubePos.x()), local(cubePos.y()), local(cubePos.z()));
    }

    public static int localIndex(int localX, int localY, int localZ) {
        CubePos.checkLocal(localX, "localX");
        CubePos.checkLocal(localY, "localY");
        CubePos.checkLocal(localZ, "localZ");
        return localX | (localZ << SIZE_BITS) | (localY << (SIZE_BITS * 2));
    }

    public String fileName() {
        return "r." + x + "." + y + "." + z + ".r3d";
    }
}
