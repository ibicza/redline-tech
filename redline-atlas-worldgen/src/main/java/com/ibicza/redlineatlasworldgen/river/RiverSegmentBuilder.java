package com.ibicza.redlineatlasworldgen.river;

import com.ibicza.redlineatlasworldgen.config.AtlasWorldgenConfig;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasCoordinateMapper;
import com.ibicza.redlineatlasworldgen.heightmap.AtlasHeightmapIndex;
import com.ibicza.redlineatlasworldgen.heightmap.GeoPoint;
import com.ibicza.redlineatlasworldgen.landcover.AtlasLandcoverIndex;
import com.ibicza.redlineatlasworldgen.landcover.LandcoverClass;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

final class RiverSegmentBuilder {
    private final RiverBuilderSampleCache sampleCache = new RiverBuilderSampleCache();

    RiverSegment build(RawHydroRiver raw) {
        List<WorldPoint> source = toWorld(raw.points());
        if (source.size() < 2) {
            return null;
        }
        source = densify(source, Math.max(4, AtlasWorldgenConfig.RIVER_REFINE_POINT_SPACING_BLOCKS.get()));
        if (raw.attributes().nextDownId() == 0L) {
            orientTerminalOutletByHeight(source);
        }
        List<FittedPoint> fitted = AtlasWorldgenConfig.RIVER_REFINE_ENABLED.get() ? refine(source) : unrefined(source);
        fitted = fillMissingHeights(fitted);
        if (fitted.size() < 2) {
            return null;
        }

        int count = fitted.size();
        double[] x = new double[count];
        double[] z = new double[count];
        double[] candidateSurface = new double[count];
        double[] width = new double[count];
        double[] depth = new double[count];
        boolean[] worldcover = new boolean[count];
        double verticalMeters = AtlasWorldgenConfig.VERTICAL_METERS_PER_BLOCK.get();

        for (int i = 0; i < count; i++) {
            FittedPoint point = fitted.get(i);
            x[i] = point.x();
            z[i] = point.z();
            WidthResult measured = widthAt(fitted, i, raw.attributes());
            width[i] = measured.widthBlocks();
            worldcover[i] = measured.worldcoverWater();
            candidateSurface[i] = bankAnchoredWaterSurface(fitted, i, width[i], verticalMeters);
            int depthBlocks = clamp((int) Math.round(width[i] * AtlasWorldgenConfig.RIVER_DEPTH_WIDTH_FACTOR.get()),
                    AtlasWorldgenConfig.RIVER_MIN_DEPTH_BLOCKS.get(), AtlasWorldgenConfig.RIVER_MAX_DEPTH_BLOCKS.get());
            depth[i] = depthBlocks * verticalMeters;
        }

        double[] water = lowerOnlyNonIncreasing(candidateSurface);
        limitDropByLoweringUpstream(water, x, z, verticalMeters);
        if (AtlasWorldgenConfig.RIVER_PROFILE_SNAP_TO_BLOCK.get()) {
            for (int i = 0; i < water.length; i++) {
                water[i] = Math.floor(water[i] / verticalMeters) * verticalMeters;
                if (i > 0) {
                    water[i] = Math.min(water[i - 1], water[i]);
                }
            }
        }
        return new RiverSegment(raw.sourceId(), raw.attributes(), x, z, width, water, depth, worldcover);
    }

    long sampleCacheRetainedBytesEstimate() {
        return sampleCache.retainedBytesEstimate();
    }

    private double bankAnchoredWaterSurface(List<FittedPoint> points, int index, double widthBlocks,
                                                  double verticalMetersPerBlock) {
        FittedPoint center = points.get(index);
        OptionalDouble support = bankSupportHeight(points, index, widthBlocks);
        if (support.isEmpty()) {
            return center.heightMeters();
        }
        // The cooked profile is the water surface, not the valley floor. Anchor it to the
        // lower cross-section bank and keep it exactly N blocks below the symmetric rim.
        double clearance = AtlasWorldgenConfig.RIVER_WATER_BELOW_BANK_BLOCKS.get() * verticalMetersPerBlock;
        return support.getAsDouble() - clearance;
    }

    private OptionalDouble bankSupportHeight(List<FittedPoint> points, int index, double widthBlocks) {
        FittedPoint center = points.get(index);
        FittedPoint before = points.get(Math.max(0, index - 1));
        FittedPoint after = points.get(Math.min(points.size() - 1, index + 1));
        double tangentX = after.x() - before.x();
        double tangentZ = after.z() - before.z();
        double tangentLength = Math.hypot(tangentX, tangentZ);
        if (tangentLength <= 1.0E-6D) {
            return OptionalDouble.empty();
        }
        double normalX = -tangentZ / tangentLength;
        double normalZ = tangentX / tangentLength;
        OptionalDouble left = sideSupportHeight(center, -normalX, -normalZ, widthBlocks);
        OptionalDouble right = sideSupportHeight(center, normalX, normalZ, widthBlocks);
        if (left.isPresent() && right.isPresent()) {
            return OptionalDouble.of(Math.min(left.getAsDouble(), right.getAsDouble()));
        }
        return left.isPresent() ? left : right;
    }

    private OptionalDouble sideSupportHeight(FittedPoint center, double normalX, double normalZ,
                                             double widthBlocks) {
        double[] distances = supportProbeDistances(widthBlocks);
        double[] values = new double[distances.length];
        int count = 0;
        for (double distance : distances) {
            OptionalDouble height = heightAt(center.x() + normalX * distance,
                    center.z() + normalZ * distance);
            if (height.isPresent()) {
                values[count++] = height.getAsDouble();
            }
        }
        if (count == 0) {
            return OptionalDouble.empty();
        }
        java.util.Arrays.sort(values, 0, count);
        // The lower bank is a hard ceiling for river water. A percentile can still select a
        // higher probe and cook a surface above the visible rim; use the minimum valid support
        // sample and let the downstream lower-only profile smooth the result.
        return OptionalDouble.of(values[0]);
    }

    private static double[] supportProbeDistances(double widthBlocks) {
        double halfWidth = Math.max(0.5D, widthBlocks * 0.5D);
        double bankWidth = Math.max(2.0D, AtlasWorldgenConfig.RIVER_BANK_WIDTH_BLOCKS.get());
        double step = Math.max(2.0D, AtlasWorldgenConfig.RIVER_REFINE_STEP_BLOCKS.get());
        return new double[]{
                halfWidth + 1.0D,
                halfWidth + Math.max(2.0D, bankWidth * 0.33D),
                halfWidth + Math.max(3.0D, bankWidth * 0.66D),
                halfWidth + bankWidth,
                halfWidth + bankWidth + step
        };
    }

    private List<WorldPoint> toWorld(List<GeoRiverPoint> points) {
        List<WorldPoint> result = new ArrayList<>(points.size());
        for (GeoRiverPoint point : points) {
            result.add(new WorldPoint(AtlasCoordinateMapper.toWorldX(point.longitude()),
                    AtlasCoordinateMapper.toWorldZ(point.latitude())));
        }
        return result;
    }

    private List<WorldPoint> densify(List<WorldPoint> source, int spacing) {
        List<WorldPoint> result = new ArrayList<>();
        result.add(source.getFirst());
        for (int i = 0; i < source.size() - 1; i++) {
            WorldPoint a = source.get(i);
            WorldPoint b = source.get(i + 1);
            double distance = Math.hypot(b.x() - a.x(), b.z() - a.z());
            int pieces = Math.max(1, (int) Math.ceil(distance / spacing));
            for (int piece = 1; piece <= pieces; piece++) {
                double t = piece / (double) pieces;
                result.add(new WorldPoint(lerp(a.x(), b.x(), t), lerp(a.z(), b.z(), t)));
            }
        }
        return result;
    }

    private void orientTerminalOutletByHeight(List<WorldPoint> points) {
        OptionalDouble first = heightAt(points.getFirst().x(), points.getFirst().z());
        OptionalDouble last = heightAt(points.getLast().x(), points.getLast().z());
        double tolerance = AtlasWorldgenConfig.VERTICAL_METERS_PER_BLOCK.get() * 2.0D;
        if (first.isPresent() && last.isPresent() && first.getAsDouble() + tolerance < last.getAsDouble()) {
            java.util.Collections.reverse(points);
        }
    }

    private List<FittedPoint> unrefined(List<WorldPoint> source) {
        List<FittedPoint> result = new ArrayList<>(source.size());
        for (WorldPoint point : source) {
            OptionalDouble height = heightAt(point.x(), point.z());
            result.add(new FittedPoint(point.x(), point.z(), height.orElse(Double.NaN)));
        }
        return result;
    }

    private List<FittedPoint> refine(List<WorldPoint> source) {
        int radius = Math.max(0, AtlasWorldgenConfig.RIVER_REFINE_RADIUS_BLOCKS.get());
        int step = Math.max(1, AtlasWorldgenConfig.RIVER_REFINE_STEP_BLOCKS.get());
        List<FittedPoint> result = new ArrayList<>(source.size());
        FittedPoint previous = null;

        for (int i = 0; i < source.size(); i++) {
            WorldPoint center = source.get(i);
            WorldPoint before = source.get(Math.max(0, i - 1));
            WorldPoint after = source.get(Math.min(source.size() - 1, i + 1));
            double tangentX = after.x() - before.x();
            double tangentZ = after.z() - before.z();
            double tangentLength = Math.hypot(tangentX, tangentZ);
            if (tangentLength <= 1.0E-6D) {
                tangentX = 1.0D;
                tangentZ = 0.0D;
                tangentLength = 1.0D;
            }
            double normalX = -tangentZ / tangentLength;
            double normalZ = tangentX / tangentLength;
            List<Candidate> candidates = new ArrayList<>((radius * 2) / step + 3);
            double localMin = Double.POSITIVE_INFINITY;
            for (int offset = -radius; offset <= radius; offset += step) {
                double x = center.x() + normalX * offset;
                double z = center.z() + normalZ * offset;
                OptionalDouble height = heightAt(x, z);
                if (height.isEmpty()) {
                    continue;
                }
                Candidate candidate = new Candidate(x, z, height.getAsDouble(), offset, isWorldcoverWater(x, z));
                candidates.add(candidate);
                localMin = Math.min(localMin, candidate.heightMeters());
            }

            if (candidates.isEmpty()) {
                OptionalDouble height = heightAt(center.x(), center.z());
                double fallback = previous == null ? Double.NaN : previous.heightMeters();
                result.add(new FittedPoint(center.x(), center.z(), height.orElse(fallback)));
                previous = result.getLast();
                continue;
            }

            Candidate best = null;
            double bestCost = Double.POSITIVE_INFINITY;
            for (Candidate candidate : candidates) {
                double cost = (candidate.heightMeters() - localMin) * AtlasWorldgenConfig.RIVER_VALLEY_HEIGHT_WEIGHT.get()
                        + Math.abs(candidate.offset()) * AtlasWorldgenConfig.RIVER_SOURCE_DISTANCE_WEIGHT.get();
                if (candidate.worldcoverWater()) {
                    cost -= AtlasWorldgenConfig.RIVER_WORLDCOVER_WATER_BONUS.get();
                }
                if (previous != null && Double.isFinite(previous.heightMeters())) {
                    cost += Math.max(0.0D, candidate.heightMeters() - previous.heightMeters())
                            * AtlasWorldgenConfig.RIVER_UPHILL_WEIGHT.get();
                    double expected = Math.hypot(center.x() - source.get(i - 1).x(), center.z() - source.get(i - 1).z());
                    double actual = Math.hypot(candidate.x() - previous.x(), candidate.z() - previous.z());
                    cost += Math.abs(actual - expected) * 0.08D;
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    best = candidate;
                }
            }
            FittedPoint fitted = new FittedPoint(best.x(), best.z(), best.heightMeters());
            result.add(fitted);
            previous = fitted;
        }
        return result;
    }

    private List<FittedPoint> fillMissingHeights(List<FittedPoint> points) {
        int firstFinite = -1;
        for (int i = 0; i < points.size(); i++) {
            if (Double.isFinite(points.get(i).heightMeters())) {
                firstFinite = i;
                break;
            }
        }
        if (firstFinite < 0) {
            return List.of();
        }
        List<FittedPoint> result = new ArrayList<>(points);
        double firstHeight = points.get(firstFinite).heightMeters();
        for (int i = 0; i < firstFinite; i++) {
            FittedPoint point = result.get(i);
            result.set(i, new FittedPoint(point.x(), point.z(), firstHeight));
        }
        int previousFinite = firstFinite;
        for (int i = firstFinite + 1; i < result.size(); i++) {
            if (!Double.isFinite(result.get(i).heightMeters())) {
                continue;
            }
            int nextFinite = i;
            double a = result.get(previousFinite).heightMeters();
            double b = result.get(nextFinite).heightMeters();
            for (int gap = previousFinite + 1; gap < nextFinite; gap++) {
                FittedPoint point = result.get(gap);
                double t = (gap - previousFinite) / (double) (nextFinite - previousFinite);
                result.set(gap, new FittedPoint(point.x(), point.z(), lerp(a, b, t)));
            }
            previousFinite = nextFinite;
        }
        double finalHeight = result.get(previousFinite).heightMeters();
        for (int i = previousFinite + 1; i < result.size(); i++) {
            FittedPoint point = result.get(i);
            result.set(i, new FittedPoint(point.x(), point.z(), finalHeight));
        }
        return result;
    }

    private WidthResult widthAt(List<FittedPoint> points, int index, HydroRiverAttributes attributes) {
        double metersPerBlock = AtlasWorldgenConfig.HORIZONTAL_METERS_PER_BLOCK.get();
        double dischargeWidthMeters = AtlasWorldgenConfig.RIVER_WIDTH_DISCHARGE_FACTOR.get()
                * Math.sqrt(Math.max(0.0D, attributes.dischargeCms()));
        double orderWidthMeters = AtlasWorldgenConfig.RIVER_MIN_WIDTH_BLOCKS.get() * metersPerBlock
                * Math.pow(1.35D, Math.max(0, attributes.strahlerOrder() - 1));
        double fallbackBlocks = Math.max(dischargeWidthMeters, orderWidthMeters) / metersPerBlock;

        FittedPoint center = points.get(index);
        if (!isWorldcoverWater(center.x(), center.z())) {
            return new WidthResult(clamp(fallbackBlocks,
                    AtlasWorldgenConfig.RIVER_MIN_WIDTH_BLOCKS.get(), AtlasWorldgenConfig.RIVER_MAX_WIDTH_BLOCKS.get()), false);
        }

        FittedPoint before = points.get(Math.max(0, index - 1));
        FittedPoint after = points.get(Math.min(points.size() - 1, index + 1));
        double tangentX = after.x() - before.x();
        double tangentZ = after.z() - before.z();
        double length = Math.max(1.0E-6D, Math.hypot(tangentX, tangentZ));
        double normalX = -tangentZ / length;
        double normalZ = tangentX / length;
        int scanStep = AtlasWorldgenConfig.RIVER_WIDTH_SCAN_STEP_BLOCKS.get();
        int max = AtlasWorldgenConfig.RIVER_MAX_WIDTH_BLOCKS.get() / 2;
        int left = scanWater(center, -normalX, -normalZ, scanStep, max);
        int right = scanWater(center, normalX, normalZ, scanStep, max);
        double measured = Math.max(scanStep, left + right + scanStep);
        double width = Math.max(fallbackBlocks, measured);
        return new WidthResult(clamp(width,
                AtlasWorldgenConfig.RIVER_MIN_WIDTH_BLOCKS.get(), AtlasWorldgenConfig.RIVER_MAX_WIDTH_BLOCKS.get()), true);
    }

    private int scanWater(FittedPoint center, double directionX, double directionZ, int step, int max) {
        int distance = 0;
        for (int d = step; d <= max; d += step) {
            if (!isWorldcoverWater(center.x() + directionX * d, center.z() + directionZ * d)) {
                break;
            }
            distance = d;
        }
        return distance;
    }

    private OptionalDouble heightAt(double x, double z) {
        int blockX = (int) Math.round(x);
        int blockZ = (int) Math.round(z);
        long key = key(blockX, blockZ);
        OptionalDouble cached = sampleCache.height(key);
        if (cached != null) {
            return cached;
        }
        GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
        var sample = AtlasHeightmapIndex.active().sample(geo.latitude(), geo.longitude());
        OptionalDouble value = sample.isPresent()
                ? OptionalDouble.of(sample.get().meters())
                : OptionalDouble.empty();
        sampleCache.putHeight(key, value);
        return value;
    }

    private boolean isWorldcoverWater(double x, double z) {
        int blockX = (int) Math.round(x);
        int blockZ = (int) Math.round(z);
        long key = key(blockX, blockZ);
        Boolean cached = sampleCache.water(key);
        if (cached != null) {
            return cached;
        }
        GeoPoint geo = AtlasCoordinateMapper.toGeo(blockX, blockZ);
        boolean water = AtlasLandcoverIndex.active().sample(geo.latitude(), geo.longitude())
                .map(sample -> sample.landcover() == LandcoverClass.WATER)
                .orElse(false);
        sampleCache.putWater(key, water);
        return water;
    }

    private static double[] lowerOnlyNonIncreasing(double[] values) {
        double[] result = values.clone();
        for (int i = 1; i < result.length; i++) {
            result[i] = Math.min(result[i - 1], result[i]);
        }
        return result;
    }

    private static void limitDropByLoweringUpstream(double[] water, double[] x, double[] z,
                                                    double verticalMetersPerBlock) {
        // One water-level block per eight horizontal blocks still permits steep mountain streams,
        // but turns DEM cliffs into a descending staircase. Crucially this pass only lowers the
        // upstream side; it never raises a downstream valley or a low bank.
        final double horizontalBlocksPerDropBlock = 8.0D;
        for (int i = water.length - 2; i >= 0; i--) {
            double distance = Math.max(1.0D, Math.hypot(x[i + 1] - x[i], z[i + 1] - z[i]));
            double allowedRiseMeters = Math.max(verticalMetersPerBlock,
                    distance / horizontalBlocksPerDropBlock * verticalMetersPerBlock);
            water[i] = Math.min(water[i], water[i + 1] + allowedRiseMeters);
        }
        for (int i = 1; i < water.length; i++) {
            water[i] = Math.min(water[i - 1], water[i]);
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record WorldPoint(double x, double z) {
    }

    private record FittedPoint(double x, double z, double heightMeters) {
    }

    private record Candidate(double x, double z, double heightMeters, int offset, boolean worldcoverWater) {
    }

    private record WidthResult(double widthBlocks, boolean worldcoverWater) {
    }
}
