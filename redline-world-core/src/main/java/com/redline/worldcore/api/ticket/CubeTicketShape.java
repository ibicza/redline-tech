package com.redline.worldcore.api.ticket;

import com.redline.worldcore.api.pos.CubePos;

import java.util.Objects;
import java.util.stream.Stream;

/** Shape of a ticket request. MVP supports single cube and cuboid enumeration. */
public record CubeTicketShape(CubeTicketShapeType type, CubePos min, CubePos max) {
    public CubeTicketShape {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("Invalid cube ticket bounds: " + min + " -> " + max);
        }
    }

    public static CubeTicketShape single(CubePos cubePos) {
        return new CubeTicketShape(CubeTicketShapeType.SINGLE_CUBE, cubePos, cubePos);
    }

    public static CubeTicketShape cuboid(CubePos min, CubePos max) {
        return new CubeTicketShape(CubeTicketShapeType.CUBOID, min, max);
    }

    public static CubeTicketShape centeredCuboid(CubePos center, int horizontalRadius, int verticalRadius) {
        if (horizontalRadius < 0 || verticalRadius < 0) {
            throw new IllegalArgumentException("Ticket radii must be >= 0");
        }
        return cuboid(
                center.offset(-horizontalRadius, -verticalRadius, -horizontalRadius),
                center.offset(horizontalRadius, verticalRadius, horizontalRadius)
        );
    }

    public boolean contains(CubePos cubePos) {
        return cubePos.x() >= min.x() && cubePos.x() <= max.x()
                && cubePos.y() >= min.y() && cubePos.y() <= max.y()
                && cubePos.z() >= min.z() && cubePos.z() <= max.z();
    }

    public long cubeCount() {
        long sizeX = (long) max.x() - min.x() + 1L;
        long sizeY = (long) max.y() - min.y() + 1L;
        long sizeZ = (long) max.z() - min.z() + 1L;
        return sizeX * sizeY * sizeZ;
    }

    public Stream<CubePos> stream() {
        Stream.Builder<CubePos> builder = Stream.builder();
        for (int y = min.y(); y <= max.y(); y++) {
            for (int z = min.z(); z <= max.z(); z++) {
                for (int x = min.x(); x <= max.x(); x++) {
                    builder.add(new CubePos(x, y, z));
                }
            }
        }
        return builder.build();
    }
}
