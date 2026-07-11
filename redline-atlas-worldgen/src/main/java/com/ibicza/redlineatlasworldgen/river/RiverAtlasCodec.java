package com.ibicza.redlineatlasworldgen.river;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

final class RiverAtlasCodec {
    private static final int MAGIC = 0x524C5256; // RLRV
    private static final int VERSION = 3;
    private static final double XZ_QUANTIZATION = 16.0D;
    private static final double WIDTH_QUANTIZATION = 16.0D;
    private static final double DEPTH_QUANTIZATION = 16.0D;
    private static final int MAX_SEGMENTS = 5_000_000;
    private static final int MAX_POINTS_PER_SEGMENT = 10_000_000;

    static CookedRiverAtlas read(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new InflaterInputStream(Files.newInputStream(path)), 1 << 16))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a Redline cooked river atlas: " + path);
            }
            int version = input.readUnsignedShort();
            if (version != VERSION) {
                throw new IOException("Unsupported .rriver version " + version + " in " + path);
            }
            String configurationFingerprint = input.readUTF();
            String sourceFingerprint = input.readUTF();
            int count = input.readInt();
            if (count < 0 || count > MAX_SEGMENTS) {
                throw new IOException("Invalid .rriver segment count " + count + " in " + path);
            }
            List<RiverSegment> segments = new ArrayList<>(count);
            for (int segmentIndex = 0; segmentIndex < count; segmentIndex++) {
                String sourceId = input.readUTF();
                HydroRiverAttributes attributes = new HydroRiverAttributes(input.readLong(), input.readLong(), input.readLong(),
                        input.readUnsignedByte(), input.readDouble(), input.readDouble());
                int points = input.readInt();
                if (points < 2 || points > MAX_POINTS_PER_SEGMENT) {
                    throw new IOException("Invalid .rriver point count " + points + " in " + path);
                }
                double[] x = new double[points];
                double[] z = new double[points];
                double[] width = new double[points];
                double[] water = new double[points];
                double[] depth = new double[points];
                boolean[] worldcover = new boolean[points];
                int quantizedX = input.readInt();
                int quantizedZ = input.readInt();
                for (int i = 0; i < points; i++) {
                    if (i > 0) {
                        quantizedX += readSignedVarInt(input);
                        quantizedZ += readSignedVarInt(input);
                    }
                    x[i] = quantizedX / XZ_QUANTIZATION;
                    z[i] = quantizedZ / XZ_QUANTIZATION;
                    width[i] = input.readUnsignedShort() / WIDTH_QUANTIZATION;
                    water[i] = input.readFloat();
                    depth[i] = input.readUnsignedShort() / DEPTH_QUANTIZATION;
                    worldcover[i] = input.readBoolean();
                }
                segments.add(new RiverSegment(sourceId, attributes, x, z, width, water, depth, worldcover));
            }
            return new CookedRiverAtlas(configurationFingerprint, sourceFingerprint, List.copyOf(segments));
        }
    }

    static void write(Path path, String configurationFingerprint, String sourceFingerprint,
                      List<RiverSegment> segments) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                new DeflaterOutputStream(Files.newOutputStream(temporary), deflater, 1 << 16), 1 << 16))) {
            output.writeInt(MAGIC);
            output.writeShort(VERSION);
            output.writeUTF(configurationFingerprint);
            output.writeUTF(sourceFingerprint);
            output.writeInt(segments.size());
            for (RiverSegment segment : segments) {
                output.writeUTF(segment.sourceId());
                HydroRiverAttributes attributes = segment.attributes();
                output.writeLong(attributes.riverId());
                output.writeLong(attributes.nextDownId());
                output.writeLong(attributes.mainRiverId());
                output.writeByte(attributes.strahlerOrder());
                output.writeDouble(attributes.dischargeCms());
                output.writeDouble(attributes.catchmentSquareKm());
                double[] x = segment.xPoints();
                double[] z = segment.zPoints();
                double[] width = segment.widthPoints();
                double[] water = segment.waterPoints();
                double[] depth = segment.depthPoints();
                boolean[] worldcover = segment.worldcoverPoints();
                output.writeInt(x.length);
                int previousX = quantizeCoordinate(x[0]);
                int previousZ = quantizeCoordinate(z[0]);
                output.writeInt(previousX);
                output.writeInt(previousZ);
                for (int i = 0; i < x.length; i++) {
                    int quantizedX = quantizeCoordinate(x[i]);
                    int quantizedZ = quantizeCoordinate(z[i]);
                    if (i > 0) {
                        writeSignedVarInt(output, quantizedX - previousX);
                        writeSignedVarInt(output, quantizedZ - previousZ);
                    }
                    output.writeShort(clampUnsignedShort((int) Math.round(width[i] * WIDTH_QUANTIZATION)));
                    output.writeFloat((float) water[i]);
                    output.writeShort(clampUnsignedShort((int) Math.round(depth[i] * DEPTH_QUANTIZATION)));
                    output.writeBoolean(worldcover[i]);
                    previousX = quantizedX;
                    previousZ = quantizedZ;
                }
            }
        } finally {
            deflater.end();
        }
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    record CookedRiverAtlas(String configurationFingerprint, String sourceFingerprint,
                            List<RiverSegment> segments) {
    }

    private static int quantizeCoordinate(double value) throws IOException {
        double quantized = Math.rint(value * XZ_QUANTIZATION);
        if (quantized < Integer.MIN_VALUE || quantized > Integer.MAX_VALUE) {
            throw new IOException("River coordinate exceeds .rriver v3 range: " + value);
        }
        return (int) quantized;
    }

    private static int clampUnsignedShort(int value) {
        return Math.max(0, Math.min(0xffff, value));
    }

    private static void writeSignedVarInt(DataOutputStream output, int value) throws IOException {
        int encoded = value << 1 ^ value >> 31;
        while ((encoded & ~0x7f) != 0) {
            output.writeByte(encoded & 0x7f | 0x80);
            encoded >>>= 7;
        }
        output.writeByte(encoded);
    }

    private static int readSignedVarInt(DataInputStream input) throws IOException {
        int encoded = 0;
        int shift = 0;
        while (shift < 35) {
            int next = input.readUnsignedByte();
            encoded |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                return encoded >>> 1 ^ -(encoded & 1);
            }
            shift += 7;
        }
        throw new IOException("Malformed signed varint in .rriver file");
    }

    private RiverAtlasCodec() {
    }
}
