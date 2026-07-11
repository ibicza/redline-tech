package com.ibicza.redlineatlasworldgen.river;

import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;

import java.util.Arrays;

final class RiverSegment {
    private final String sourceId;
    private final HydroRiverAttributes attributes;
    private final double[] x;
    private final double[] z;
    private final double[] widthBlocks;
    private final double[] waterMeters;
    private final double[] depthMeters;
    private final boolean[] worldcoverWater;
    private final double minX;
    private final double maxX;
    private final double minZ;
    private final double maxZ;

    RiverSegment(String sourceId, HydroRiverAttributes attributes, double[] x, double[] z,
                 double[] widthBlocks, double[] waterMeters, double[] depthMeters,
                 boolean[] worldcoverWater) {
        if (x.length < 2 || x.length != z.length || x.length != widthBlocks.length
                || x.length != waterMeters.length || x.length != depthMeters.length
                || x.length != worldcoverWater.length) {
            throw new IllegalArgumentException("Invalid river segment arrays");
        }
        this.sourceId = sourceId;
        this.attributes = attributes;
        this.x = x;
        this.z = z;
        this.widthBlocks = widthBlocks;
        this.waterMeters = waterMeters;
        this.depthMeters = depthMeters;
        this.worldcoverWater = worldcoverWater;

        double localMinX = Double.POSITIVE_INFINITY;
        double localMaxX = Double.NEGATIVE_INFINITY;
        double localMinZ = Double.POSITIVE_INFINITY;
        double localMaxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < x.length; i++) {
            double expansion = widthBlocks[i] * 0.5D + AtlasWorldgenConfig.RIVER_BANK_WIDTH_BLOCKS.get() + 2.0D;
            localMinX = Math.min(localMinX, x[i] - expansion);
            localMaxX = Math.max(localMaxX, x[i] + expansion);
            localMinZ = Math.min(localMinZ, z[i] - expansion);
            localMaxZ = Math.max(localMaxZ, z[i] + expansion);
        }
        minX = localMinX;
        maxX = localMaxX;
        minZ = localMinZ;
        maxZ = localMaxZ;
    }

    RiverSample sample(double blockX, double blockZ) {
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        int bestEdge = -1;
        double bestT = 0.0D;
        for (int i = 0; i < x.length - 1; i++) {
            double dx = x[i + 1] - x[i];
            double dz = z[i + 1] - z[i];
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq <= 1.0E-9D ? 0.0D
                    : clamp01(((blockX - x[i]) * dx + (blockZ - z[i]) * dz) / lengthSq);
            double nearX = x[i] + dx * t;
            double nearZ = z[i] + dz * t;
            double ddx = blockX - nearX;
            double ddz = blockZ - nearZ;
            double distanceSq = ddx * ddx + ddz * ddz;
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestEdge = i;
                bestT = t;
            }
        }
        if (bestEdge < 0) {
            return RiverSample.none();
        }

        double distance = Math.sqrt(bestDistanceSq);
        double width = lerp(widthBlocks[bestEdge], widthBlocks[bestEdge + 1], bestT);
        double halfWidth = Math.max(0.5D, width * 0.5D);
        double bankWidth = AtlasWorldgenConfig.RIVER_BANK_WIDTH_BLOCKS.get();
        if (distance > halfWidth + bankWidth) {
            return RiverSample.none();
        }

        double surface = lerp(waterMeters[bestEdge], waterMeters[bestEdge + 1], bestT);
        if (AtlasWorldgenConfig.RIVER_PROFILE_SNAP_TO_BLOCK.get()) {
            double vertical = AtlasWorldgenConfig.VERTICAL_METERS_PER_BLOCK.get();
            surface = Math.floor(surface / vertical) * vertical;
        }
        double depth = lerp(depthMeters[bestEdge], depthMeters[bestEdge + 1], bestT);
        RiverKind kind = distance <= halfWidth ? RiverKind.CHANNEL : RiverKind.BANK;
        boolean exact = worldcoverWater[bestEdge] || worldcoverWater[bestEdge + 1];

        double edgeX = x[bestEdge + 1] - x[bestEdge];
        double edgeZ = z[bestEdge + 1] - z[bestEdge];
        double edgeLength = Math.hypot(edgeX, edgeZ);
        double normalX;
        double normalZ;
        if (edgeLength <= 1.0E-9D) {
            normalX = 1.0D;
            normalZ = 0.0D;
        } else {
            normalX = -edgeZ / edgeLength;
            normalZ = edgeX / edgeLength;
        }
        double centerX = x[bestEdge] + edgeX * bestT;
        double centerZ = z[bestEdge] + edgeZ * bestT;
        return new RiverSample(kind, exact, distance, halfWidth,
                Math.max(0.0D, distance - halfWidth), surface, surface - depth, depth,
                attributes.riverId(), attributes.nextDownId(), attributes.strahlerOrder(),
                attributes.dischargeCms(), sourceId, exact ? 10.0D : 30.0D,
                centerX, centerZ, normalX, normalZ);
    }

    CenterPoint nearestCenter(double blockX, double blockZ) {
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        double bestX = blockX;
        double bestZ = blockZ;
        for (int i = 0; i < x.length - 1; i++) {
            double dx = x[i + 1] - x[i];
            double dz = z[i + 1] - z[i];
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq <= 1.0E-9D ? 0.0D
                    : clamp01(((blockX - x[i]) * dx + (blockZ - z[i]) * dz) / lengthSq);
            double centerX = x[i] + dx * t;
            double centerZ = z[i] + dz * t;
            double distanceSq = square(blockX - centerX) + square(blockZ - centerZ);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestX = centerX;
                bestZ = centerZ;
            }
        }
        return new CenterPoint(bestX, bestZ, Math.sqrt(bestDistanceSq));
    }

    boolean boundsIntersects(double queryMinX, double queryMinZ, double queryMaxX, double queryMaxZ) {
        return maxX >= queryMinX && minX <= queryMaxX && maxZ >= queryMinZ && minZ <= queryMaxZ;
    }

    double minX() {
        return minX;
    }

    double maxX() {
        return maxX;
    }

    double minZ() {
        return minZ;
    }

    double maxZ() {
        return maxZ;
    }

    long riverId() {
        return attributes.riverId();
    }

    long nextDownId() {
        return attributes.nextDownId();
    }

    int strahlerOrder() {
        return attributes.strahlerOrder();
    }

    double dischargeCms() {
        return attributes.dischargeCms();
    }

    String sourceId() {
        return sourceId;
    }

    HydroRiverAttributes attributes() {
        return attributes;
    }

    double[] xPoints() {
        return x;
    }

    double[] zPoints() {
        return z;
    }

    double[] widthPoints() {
        return widthBlocks;
    }

    double[] waterPoints() {
        return waterMeters;
    }

    double[] depthPoints() {
        return depthMeters;
    }

    boolean[] worldcoverPoints() {
        return worldcoverWater;
    }

    double startWaterMeters() {
        return waterMeters[0];
    }

    double endWaterMeters() {
        return waterMeters[waterMeters.length - 1];
    }

    void lowerStartTo(double meters) {
        waterMeters[0] = Math.min(waterMeters[0], meters);
        enforceNonIncreasing();
    }

    void lowerEndTo(double meters) {
        waterMeters[waterMeters.length - 1] = Math.min(waterMeters[waterMeters.length - 1], meters);
        for (int i = waterMeters.length - 2; i >= 0; i--) {
            waterMeters[i] = Math.max(waterMeters[i], waterMeters[i + 1]);
        }
    }

    private void enforceNonIncreasing() {
        for (int i = 1; i < waterMeters.length; i++) {
            waterMeters[i] = Math.min(waterMeters[i - 1], waterMeters[i]);
        }
    }

    String shortText() {
        return "river=" + riverId() + " next=" + nextDownId() + " order=" + strahlerOrder()
                + " discharge=" + dischargeCms() + " points=" + x.length
                + " width=" + Arrays.stream(widthBlocks).min().orElse(0.0D) + ".."
                + Arrays.stream(widthBlocks).max().orElse(0.0D) + " blocks"
                + " water=" + startWaterMeters() + ".." + endWaterMeters() + "m source=" + sourceId;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double square(double value) {
        return value * value;
    }

    record CenterPoint(double x, double z, double distanceBlocks) {
    }
}
