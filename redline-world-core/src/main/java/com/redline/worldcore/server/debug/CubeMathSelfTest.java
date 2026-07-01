package com.redline.worldcore.server.debug;

import com.redline.worldcore.api.pos.CubeLocalPos;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.pos.Region3DPos;

public final class CubeMathSelfTest {
    public static void runOrThrow() {
        expect(CubePos.blockToCube(0) == 0, "block 0 must be in cube 0");
        expect(CubePos.blockToCube(15) == 0, "block 15 must be in cube 0");
        expect(CubePos.blockToCube(16) == 1, "block 16 must be in cube 1");
        expect(CubePos.blockToCube(-1) == -1, "block -1 must be in cube -1");
        expect(CubePos.blockToCube(-16) == -1, "block -16 must be in cube -1");
        expect(CubePos.blockToCube(-17) == -2, "block -17 must be in cube -2");

        expect(CubePos.local(0) == 0, "local 0");
        expect(CubePos.local(15) == 15, "local 15");
        expect(CubePos.local(16) == 0, "local 16");
        expect(CubePos.local(-1) == 15, "local -1");
        expect(CubePos.local(-16) == 0, "local -16");
        expect(CubePos.local(-17) == 15, "local -17");

        expect(CubePos.localIndex(0, 0, 0) == 0, "index origin");
        expect(CubePos.localIndex(15, 0, 0) == 15, "index x edge");
        expect(CubePos.localIndex(0, 0, 1) == 16, "index z edge");
        expect(CubePos.localIndex(0, 1, 0) == 256, "index y edge");
        expect(new CubeLocalPos(15, 15, 15).localIndex() == 4095, "index max");

        CubePos negative = new CubePos(-1, -1, -1);
        Region3DPos negativeRegion = Region3DPos.fromCube(negative);
        expect(negativeRegion.equals(new Region3DPos(-1, -1, -1)), "negative cube must map to negative region");
        expect(Region3DPos.localIndex(negative) == 4095, "negative cube local region index");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private CubeMathSelfTest() {
    }
}
