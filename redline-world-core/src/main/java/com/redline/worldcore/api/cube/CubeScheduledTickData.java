package com.redline.worldcore.api.cube;

import com.redline.worldcore.api.pos.CubeLocalPos;
import com.redline.worldcore.api.pos.CubePos;
import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * Durable M14.5 scheduled tick descriptor owned by a 16x16x16 cube.
 *
 * <p>This is intentionally data-only. Real vanilla scheduled tick interception comes later; this record gives the cube
 * backend a stable storage shape and debug/API surface for block/fluid tick queues.</p>
 */
public record CubeScheduledTickData(
        CubeScheduledTickKind kind,
        int localIndex,
        CubeLocalPos localPos,
        BlockPos worldPos,
        String targetId,
        long triggerGameTime,
        int priority,
        String reason
) {
    public CubeScheduledTickData {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(localPos, "localPos");
        Objects.requireNonNull(worldPos, "worldPos");
        targetId = targetId == null || targetId.isBlank() ? "minecraft:air" : targetId;
        reason = reason == null || reason.isBlank() ? "none" : reason;
    }

    public static CubeScheduledTickData create(CubeScheduledTickKind kind, CubePos cubePos, BlockPos worldPos,
                                               String targetId, long triggerGameTime, int priority, String reason) {
        CubeLocalPos local = CubeLocalPos.fromBlock(worldPos);
        int localIndex = CubePos.localIndex(local.x(), local.y(), local.z());
        return new CubeScheduledTickData(kind, localIndex, local, worldPos, targetId, triggerGameTime, priority, reason);
    }

    public CubeScheduledTickData copy() {
        return new CubeScheduledTickData(kind, localIndex, localPos, worldPos, targetId, triggerGameTime, priority, reason);
    }
}
