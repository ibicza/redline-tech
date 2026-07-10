package com.ibicza.redlineatlasworldgen.heightmap;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Properties;

public final class RawHeightTile implements HeightTile {
    private final Path descriptorPath;
    private final Path dataPath;
    private final String id;
    private final String format;
    private final int width;
    private final int height;
    private final double south;
    private final double north;
    private final double west;
    private final double east;
    private final double priority;
    private final double resolutionMeters;
    private final double scale;
    private final double offset;
    private final double noData;
    private volatile double[] values;
    private volatile boolean failed;

    private RawHeightTile(Path descriptorPath, Path dataPath, String id, String format, int width, int height,
                          double south, double north, double west, double east, double priority,
                          double resolutionMeters, double scale, double offset, double noData) {
        this.descriptorPath = descriptorPath;
        this.dataPath = dataPath;
        this.id = id;
        this.format = format;
        this.width = width;
        this.height = height;
        this.south = south;
        this.north = north;
        this.west = west;
        this.east = east;
        this.priority = priority;
        this.resolutionMeters = resolutionMeters;
        this.scale = scale;
        this.offset = offset;
        this.noData = noData;
    }

    public static RawHeightTile fromProperties(Path descriptorPath) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(descriptorPath)) {
            properties.load(reader);
        }
        Path base = descriptorPath.getParent() == null ? Path.of(".") : descriptorPath.getParent();
        String data = required(properties, "data");
        String id = properties.getProperty("id", descriptorPath.getFileName().toString());
        String format = required(properties, "format").toLowerCase(Locale.ROOT);
        int width = Integer.parseInt(required(properties, "width"));
        int height = Integer.parseInt(required(properties, "height"));
        double south = Double.parseDouble(required(properties, "south"));
        double north = Double.parseDouble(required(properties, "north"));
        double west = Double.parseDouble(required(properties, "west"));
        double east = Double.parseDouble(required(properties, "east"));
        double resolution = Double.parseDouble(properties.getProperty("resolutionMeters", "240"));
        double priority = Double.parseDouble(properties.getProperty("priority", Double.toString(resolution)));
        double scale = Double.parseDouble(properties.getProperty("scale", "1"));
        double offset = Double.parseDouble(properties.getProperty("offset", "0"));
        double noData = Double.parseDouble(properties.getProperty("noData", "-32768"));
        return new RawHeightTile(descriptorPath, base.resolve(data).normalize(), id, format, width, height, south, north, west, east, priority, resolution, scale, offset, noData);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public double west() {
        return west;
    }

    @Override
    public double east() {
        return east;
    }

    @Override
    public double south() {
        return south;
    }

    @Override
    public double north() {
        return north;
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public double nominalResolutionMeters() {
        return resolutionMeters;
    }

    @Override
    public OptionalDouble sampleMeters(double latitude, double longitude) throws IOException {
        double[] data = loadValues();
        if (data == null) {
            return OptionalDouble.empty();
        }
        double fx = (longitude - west) / (east - west) * width;
        double fy = (north - latitude) / (north - south) * height;
        int x = clamp((int) Math.floor(fx), 0, width - 1);
        int y = clamp((int) Math.floor(fy), 0, height - 1);
        double value = data[y * width + x];
        if (!Double.isFinite(value) || Double.compare(value, noData) == 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value);
    }

    private double[] loadValues() throws IOException {
        double[] existing = values;
        if (existing != null || failed) {
            return existing;
        }
        synchronized (this) {
            if (values != null || failed) {
                return values;
            }
            if (!Files.isRegularFile(dataPath)) {
                RedlineAtlasWorldgen.LOGGER.warn("Missing custom height data file {} for {}", dataPath, descriptorPath);
                failed = true;
                return null;
            }
            double[] loaded;
            switch (format) {
                case "raw_i16_le" -> loaded = readI16(ByteOrder.LITTLE_ENDIAN);
                case "raw_i16_be" -> loaded = readI16(ByteOrder.BIG_ENDIAN);
                case "raw_f32_le" -> loaded = readF32(ByteOrder.LITTLE_ENDIAN);
                case "raw_f32_be" -> loaded = readF32(ByteOrder.BIG_ENDIAN);
                case "csv" -> loaded = readCsv();
                default -> throw new IOException("Unsupported height tile format: " + format);
            }
            values = loaded;
            RedlineAtlasWorldgen.LOGGER.info("Loaded custom height tile {} ({}x{}, {}, bounds lat {}..{}, lon {}..{})",
                    id, width, height, format, south, north, west, east);
            return loaded;
        }
    }

    private double[] readI16(ByteOrder order) throws IOException {
        byte[] bytes = Files.readAllBytes(dataPath);
        int expected = width * height * Short.BYTES;
        if (bytes.length < expected) {
            throw new IOException("Height tile " + dataPath + " is too small: " + bytes.length + " < " + expected);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(order);
        double[] result = new double[width * height];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffer.getShort() * scale + offset;
        }
        return result;
    }

    private double[] readF32(ByteOrder order) throws IOException {
        byte[] bytes = Files.readAllBytes(dataPath);
        int expected = width * height * Float.BYTES;
        if (bytes.length < expected) {
            throw new IOException("Height tile " + dataPath + " is too small: " + bytes.length + " < " + expected);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(order);
        double[] result = new double[width * height];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffer.getFloat() * scale + offset;
        }
        return result;
    }

    private double[] readCsv() throws IOException {
        double[] result = new double[width * height];
        int index = 0;
        try (BufferedReader reader = Files.newBufferedReader(dataPath)) {
            String line;
            while ((line = reader.readLine()) != null && index < result.length) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("[,;\\s]+") ;
                for (String part : parts) {
                    if (part.isBlank()) {
                        continue;
                    }
                    if (index >= result.length) {
                        break;
                    }
                    result[index++] = Double.parseDouble(part) * scale + offset;
                }
            }
        }
        if (index < result.length) {
            throw new IOException("CSV height tile " + dataPath + " has only " + index + " samples, expected " + result.length);
        }
        return result;
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing property '" + key + "'");
        }
        return value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
