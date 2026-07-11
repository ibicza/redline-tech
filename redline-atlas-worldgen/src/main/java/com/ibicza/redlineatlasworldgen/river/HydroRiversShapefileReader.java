package com.ibicza.redlineatlasworldgen.river;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Minimal streaming reader for the PolyLine SHP + numeric DBF fields used by HydroRIVERS. */
final class HydroRiversShapefileReader {
    static List<RawHydroRiver> read(Path shpPath, Optional<RiverSourceBounds> filter,
                                    int minStrahlerOrder, int maxSegments) throws IOException {
        Path dbfPath = siblingIgnoringCase(shpPath, ".dbf");
        try (FileChannel shp = FileChannel.open(shpPath, StandardOpenOption.READ);
             DbfReader dbf = Files.isRegularFile(dbfPath) ? new DbfReader(dbfPath) : null) {
            validateHeader(shp, shpPath);
            List<RawHydroRiver> result = new ArrayList<>();
            long position = 100L;
            long fileSize = shp.size();
            int recordIndex = 0;
            ByteBuffer recordHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);

            while (position + 8L <= fileSize && result.size() < maxSegments) {
                recordHeader.clear();
                readFully(shp, recordHeader, position);
                recordHeader.flip();
                int recordNumber = recordHeader.getInt();
                int contentBytes = Math.multiplyExact(recordHeader.getInt(), 2);
                position += 8L;
                if (contentBytes < 4 || position + contentBytes > fileSize) {
                    throw new IOException("Corrupt SHP record " + recordNumber + " in " + shpPath);
                }

                HydroRiverAttributes attributes = dbf == null
                        ? HydroRiverAttributes.fallback(recordNumber)
                        : dbf.attributes(recordIndex, recordNumber);
                recordIndex++;
                if (attributes.strahlerOrder() < minStrahlerOrder) {
                    position += contentBytes;
                    continue;
                }

                ByteBuffer content = ByteBuffer.allocate(contentBytes).order(ByteOrder.LITTLE_ENDIAN);
                readFully(shp, content, position);
                content.flip();
                position += contentBytes;
                int shapeType = content.getInt();
                if (shapeType == 0) {
                    continue;
                }
                if (shapeType != 3 && shapeType != 13 && shapeType != 23) {
                    throw new IOException("Unsupported SHP shape type " + shapeType + " in " + shpPath
                            + "; HydroRIVERS must be PolyLine/PolyLineZ/PolyLineM");
                }
                if (content.remaining() < 40) {
                    continue;
                }

                double minLon = content.getDouble();
                double minLat = content.getDouble();
                double maxLon = content.getDouble();
                double maxLat = content.getDouble();
                if (filter.isPresent() && !filter.get().intersects(minLon, minLat, maxLon, maxLat)) {
                    continue;
                }
                int partCount = content.getInt();
                int pointCount = content.getInt();
                if (partCount <= 0 || pointCount < 2 || partCount > pointCount
                        || content.remaining() < partCount * 4L + pointCount * 16L) {
                    continue;
                }
                int[] parts = new int[partCount + 1];
                for (int i = 0; i < partCount; i++) {
                    parts[i] = content.getInt();
                }
                parts[partCount] = pointCount;
                double[] lon = new double[pointCount];
                double[] lat = new double[pointCount];
                for (int i = 0; i < pointCount; i++) {
                    lon[i] = content.getDouble();
                    lat[i] = content.getDouble();
                }

                for (int part = 0; part < partCount && result.size() < maxSegments; part++) {
                    int start = parts[part];
                    int end = parts[part + 1];
                    if (start < 0 || end > pointCount || end - start < 2) {
                        continue;
                    }
                    List<GeoRiverPoint> points = new ArrayList<>(end - start);
                    for (int i = start; i < end; i++) {
                        points.add(new GeoRiverPoint(lon[i], lat[i]));
                    }
                    String sourceId = shpPath.getFileName() + "#" + recordNumber + (partCount > 1 ? ":" + part : "");
                    result.add(new RawHydroRiver(sourceId, attributes, List.copyOf(points)));
                }
            }
            return result;
        }
    }

    private static void validateHeader(FileChannel channel, Path path) throws IOException {
        if (channel.size() < 100L) {
            throw new IOException("SHP file is shorter than its 100 byte header: " + path);
        }
        ByteBuffer header = ByteBuffer.allocate(100).order(ByteOrder.BIG_ENDIAN);
        readFully(channel, header, 0L);
        header.flip();
        if (header.getInt() != 9994) {
            throw new IOException("Not an ESRI shapefile (missing 9994 header): " + path);
        }
        header.order(ByteOrder.LITTLE_ENDIAN);
        int shapeType = header.getInt(32);
        if (shapeType != 3 && shapeType != 13 && shapeType != 23) {
            throw new IOException("HydroRIVERS SHP must contain polylines, found shape type " + shapeType + " in " + path);
        }
    }

    private static Path siblingIgnoringCase(Path shp, String extension) throws IOException {
        String base = stripExtension(shp.getFileName().toString());
        Path direct = shp.resolveSibling(base + extension);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        try (var stream = Files.list(shp.getParent())) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> stripExtension(path.getFileName().toString()).equalsIgnoreCase(base))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
                    .findFirst().orElse(direct);
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long position) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read < 0) {
                throw new IOException("Unexpected end of file");
            }
            position += read;
        }
    }

    private static final class DbfReader implements AutoCloseable {
        private final FileChannel channel;
        private final int recordCount;
        private final int headerLength;
        private final int recordLength;
        private final Map<String, Field> fields;

        private DbfReader(Path path) throws IOException {
            channel = FileChannel.open(path, StandardOpenOption.READ);
            ByteBuffer header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, header, 0L);
            header.flip();
            recordCount = header.getInt(4);
            headerLength = Short.toUnsignedInt(header.getShort(8));
            recordLength = Short.toUnsignedInt(header.getShort(10));
            if (headerLength < 33 || recordLength < 2) {
                throw new IOException("Invalid DBF header in " + path);
            }
            fields = readFields();
        }

        private Map<String, Field> readFields() throws IOException {
            Map<String, Field> result = new HashMap<>();
            int offset = 1;
            long position = 32L;
            ByteBuffer descriptor = ByteBuffer.allocate(32);
            while (position + 32L <= headerLength) {
                descriptor.clear();
                readFully(channel, descriptor, position);
                descriptor.flip();
                if ((descriptor.get(0) & 0xff) == 0x0d) {
                    break;
                }
                byte[] nameBytes = new byte[11];
                descriptor.get(nameBytes);
                int end = 0;
                while (end < nameBytes.length && nameBytes[end] != 0) {
                    end++;
                }
                String name = new String(nameBytes, 0, end, StandardCharsets.ISO_8859_1)
                        .trim().toUpperCase(Locale.ROOT);
                char type = (char) descriptor.get(11);
                int length = Byte.toUnsignedInt(descriptor.get(16));
                result.put(name, new Field(offset, length, type));
                offset += length;
                position += 32L;
            }
            return Map.copyOf(result);
        }

        private HydroRiverAttributes attributes(int index, int fallbackRecordNumber) throws IOException {
            if (index < 0 || index >= recordCount) {
                return HydroRiverAttributes.fallback(fallbackRecordNumber);
            }
            ByteBuffer record = ByteBuffer.allocate(recordLength);
            readFully(channel, record, headerLength + (long) index * recordLength);
            record.flip();
            if (record.get(0) == '*') {
                return HydroRiverAttributes.fallback(fallbackRecordNumber);
            }
            long id = longValue(record, "HYRIV_ID", fallbackRecordNumber);
            long next = longValue(record, "NEXT_DOWN", 0L);
            long main = longValue(record, "MAIN_RIV", id);
            int order = (int) longValue(record, "ORD_STRA", 1L);
            double discharge = doubleValue(record, "DIS_AV_CMS", 0.0D);
            double catchment = doubleValue(record, "CATCH_SKM", 0.0D);
            return new HydroRiverAttributes(id, next, main, Math.max(1, order),
                    Math.max(0.0D, discharge), Math.max(0.0D, catchment));
        }

        private long longValue(ByteBuffer record, String name, long fallback) {
            String value = text(record, name);
            if (value.isEmpty()) {
                return fallback;
            }
            try {
                return Math.round(Double.parseDouble(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private double doubleValue(ByteBuffer record, String name, double fallback) {
            String value = text(record, name);
            if (value.isEmpty()) {
                return fallback;
            }
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private String text(ByteBuffer record, String name) {
            Field field = fields.get(name);
            if (field == null || field.offset() + field.length() > record.limit()) {
                return "";
            }
            byte[] bytes = new byte[field.length()];
            ByteBuffer copy = record.duplicate();
            copy.position(field.offset());
            copy.get(bytes);
            return new String(bytes, StandardCharsets.ISO_8859_1).trim();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private record Field(int offset, int length, char type) {
    }

    private HydroRiversShapefileReader() {
    }
}
