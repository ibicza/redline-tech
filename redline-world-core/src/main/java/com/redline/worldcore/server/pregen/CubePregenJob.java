package com.redline.worldcore.server.pregen;

import com.redline.worldcore.api.cube.CubeStatus;
import com.redline.worldcore.api.pos.CubePos;

import java.util.Objects;
import java.util.UUID;

/** One bounded manual M13.0 pregen request. Coordinates are cube coordinates, not blocks. */
public record CubePregenJob(
        UUID id,
        CubePos min,
        CubePos max,
        CubeStatus targetStatus,
        long totalCubes,
        String ownerDescription
) {
    public CubePregenJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(targetStatus, "targetStatus");
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("Invalid pregen bounds: " + min + " -> " + max);
        }
        if (totalCubes < 0L) {
            throw new IllegalArgumentException("totalCubes must be >= 0");
        }
        ownerDescription = ownerDescription == null ? "unknown" : ownerDescription;
    }

    public static CubePregenJob cuboid(CubePos first, CubePos second, CubeStatus targetStatus, String ownerDescription) {
        CubePos min = new CubePos(
                Math.min(first.x(), second.x()),
                Math.min(first.y(), second.y()),
                Math.min(first.z(), second.z())
        );
        CubePos max = new CubePos(
                Math.max(first.x(), second.x()),
                Math.max(first.y(), second.y()),
                Math.max(first.z(), second.z())
        );
        long total = ((long) max.x() - min.x() + 1L)
                * ((long) max.y() - min.y() + 1L)
                * ((long) max.z() - min.z() + 1L);
        return new CubePregenJob(UUID.randomUUID(), min, max, targetStatus, total, ownerDescription);
    }

    public String shortId() {
        return id.toString().substring(0, 8);
    }
}
