package com.redline.worldcore.server.cube.ownership;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.cube.CubeHolder;
import com.redline.worldcore.server.cube.ServerCubeCache;
import com.redline.worldcore.server.cube.dirty.CubeContentSummary;

import java.util.Optional;

/** Internal M14.9 validator. It deliberately stays debug-only and does not expose a public mod API. */
public final class CubeOwnershipValidator {
    private final ServerCubeCache cache;

    public CubeOwnershipValidator(ServerCubeCache cache) {
        this.cache = cache;
    }

    public CubeOwnershipValidationSnapshot validate(CubePos cubePos) {
        Optional<CubeHolder> maybeHolder = cache.holder(cubePos);
        if (maybeHolder.isEmpty()) {
            return CubeOwnershipValidationSnapshot.missing(cubePos);
        }
        CubeHolder holder = maybeHolder.get();
        LevelCube cube = holder.cube();
        CubeContentSummary summary = CubeContentSummary.from(cube);
        int blockEntities = cube.blockEntityCount();
        int blockTicks = cube.scheduledBlockTickCount();
        int fluidTicks = cube.scheduledFluidTickCount();

        String problem = "none";
        boolean ok = true;
        if (blockEntities != summary.blockEntities()) {
            ok = false;
            problem = "block_entity_summary_mismatch";
        } else if (blockTicks != summary.scheduledBlockTicks()) {
            ok = false;
            problem = "block_tick_summary_mismatch";
        } else if (fluidTicks != summary.scheduledFluidTicks()) {
            ok = false;
            problem = "fluid_tick_summary_mismatch";
        }

        return new CubeOwnershipValidationSnapshot(
                cubePos,
                true,
                holder.dirty(),
                cube.status(),
                holder.state(),
                holder.ticketLevel(),
                blockEntities,
                blockTicks,
                fluidTicks,
                cache.clientSyncDirty(cubePos),
                summary,
                ok,
                problem
        );
    }
}
