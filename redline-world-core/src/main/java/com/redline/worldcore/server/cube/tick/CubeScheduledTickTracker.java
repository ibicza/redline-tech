package com.redline.worldcore.server.cube.tick;

import com.redline.worldcore.api.cube.CubeScheduledTickData;
import com.redline.worldcore.api.cube.CubeScheduledTickKind;
import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.api.ticket.CubeTicketLevel;

import java.util.Map;

/**
 * Runtime diagnostics for M14.5 cube-owned scheduled tick queues.
 *
 * <p>This tracker does not execute vanilla ticks yet. It proves that block/fluid scheduled tick data lives in LevelCube,
 * survives CubeNBT roundtrips, and can be gated by cube ticket level before deeper vanilla interception is added.</p>
 */
public final class CubeScheduledTickTracker {
    private long totalAdded;
    private long totalRemoved;
    private long totalEvaluated;
    private int addedLastTick;
    private int removedLastTick;
    private int evaluatedLastTick;
    private int loadedCubesWithTicks;
    private int blockTicks;
    private int fluidTicks;
    private int dueTicks;
    private int dueAllowed;
    private int dueBlocked;
    private CubePos lastCube;
    private String lastReason = "none";

    public void beginTick() {
        addedLastTick = 0;
        removedLastTick = 0;
        evaluatedLastTick = 0;
    }

    public void recordAdded(CubePos cubePos, CubeScheduledTickKind kind, String reason) {
        totalAdded++;
        addedLastTick++;
        lastCube = cubePos;
        lastReason = reason == null ? "add_" + kind.name().toLowerCase(java.util.Locale.ROOT) : reason;
    }

    public void recordRemoved(CubePos cubePos, int count, String reason) {
        if (count <= 0) {
            return;
        }
        totalRemoved += count;
        removedLastTick += count;
        lastCube = cubePos;
        lastReason = reason == null ? "clear" : reason;
    }

    public void evaluate(Map<CubePos, LevelCube> cubes, Map<CubePos, CubeTicketLevel> levels, long gameTime) {
        loadedCubesWithTicks = 0;
        blockTicks = 0;
        fluidTicks = 0;
        dueTicks = 0;
        dueAllowed = 0;
        dueBlocked = 0;

        for (Map.Entry<CubePos, LevelCube> entry : cubes.entrySet()) {
            CubePos cubePos = entry.getKey();
            LevelCube cube = entry.getValue();
            int cubeBlockTicks = cube.scheduledBlockTickCount();
            int cubeFluidTicks = cube.scheduledFluidTickCount();
            if (cubeBlockTicks + cubeFluidTicks <= 0) {
                continue;
            }
            loadedCubesWithTicks++;
            blockTicks += cubeBlockTicks;
            fluidTicks += cubeFluidTicks;
            boolean allowed = levels.getOrDefault(cubePos, CubeTicketLevel.UNLOADED).isAtLeast(CubeTicketLevel.BLOCK_TICKING);
            for (CubeScheduledTickData tick : cube.copyScheduledBlockTicks()) {
                countDue(tick, allowed, gameTime);
            }
            for (CubeScheduledTickData tick : cube.copyScheduledFluidTicks()) {
                countDue(tick, allowed, gameTime);
            }
        }
        totalEvaluated++;
        evaluatedLastTick++;
    }

    public CubeScheduledTickSnapshot snapshot() {
        return new CubeScheduledTickSnapshot(loadedCubesWithTicks, blockTicks, fluidTicks, dueTicks, dueAllowed, dueBlocked,
                totalAdded, totalRemoved, totalEvaluated, addedLastTick, removedLastTick, evaluatedLastTick, lastCube, lastReason);
    }

    private void countDue(CubeScheduledTickData tick, boolean allowed, long gameTime) {
        if (tick.triggerGameTime() > gameTime) {
            return;
        }
        dueTicks++;
        if (allowed) {
            dueAllowed++;
        } else {
            dueBlocked++;
        }
    }
}
