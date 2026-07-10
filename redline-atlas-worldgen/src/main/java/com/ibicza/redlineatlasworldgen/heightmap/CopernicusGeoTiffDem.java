package com.ibicza.redlineatlasworldgen.heightmap;

import com.ibicza.redlineatlasworldgen.RedlineAtlasWorldgen;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Small dependency-free reader for the Copernicus DEM COG GeoTIFF tiles.
 *
 * <p>It intentionally supports only the DEM subset we need for test worldgen:
 * single-band tiled Float32 TIFF, no JPEG/LZW, optional DEFLATE + Predictor=3.
 * This keeps the runtime module offline-friendly and avoids shipping GDAL/native libs.
 */
public final class CopernicusGeoTiffDem {
    private static final int TIFF_MAGIC = 42;
    private static final int BIG_TIFF_MAGIC = 43;
    private static final int COMPRESSION_NONE = 1;
    private static final int COMPRESSION_ADOBE_DEFLATE = 8;
    private static final int COMPRESSION_DEFLATE = 32946;
    private static final int SAMPLE_FORMAT_UNSIGNED = 1;
    private static final int SAMPLE_FORMAT_SIGNED = 2;
    private static final int SAMPLE_FORMAT_IEEE_FLOAT = 3;
    private static final int TAG_IMAGE_WIDTH = 256;
    private static final int TAG_IMAGE_LENGTH = 257;
    private static final int TAG_BITS_PER_SAMPLE = 258;
    private static final int TAG_COMPRESSION = 259;
    private static final int TAG_STRIP_OFFSETS = 273;
    private static final int TAG_SAMPLES_PER_PIXEL = 277;
    private static final int TAG_ROWS_PER_STRIP = 278;
    private static final int TAG_STRIP_BYTE_COUNTS = 279;
    private static final int TAG_PLANAR_CONFIGURATION = 284;
    private static final int TAG_PREDICTOR = 317;
    private static final int TAG_TILE_WIDTH = 322;
    private static final int TAG_TILE_LENGTH = 323;
    private static final int TAG_TILE_OFFSETS = 324;
    private static final int TAG_TILE_BYTE_COUNTS = 325;
    private static final int TAG_SAMPLE_FORMAT = 339;
    private static final int TAG_GDAL_NODATA = 42113;
    private static final int TYPE_BYTE = 1;
    private static final int TYPE_ASCII = 2;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_SBYTE = 6;
    private static final int TYPE_UNDEFINED = 7;
    private static final int TYPE_SLONG = 9;
    private static final int TYPE_FLOAT = 11;
    private static final int TYPE_DOUBLE = 12;
    private static final int TYPE_LONG8 = 16;
    private static final int TYPE_SLONG8 = 17;
    private static final int TYPE_IFD8 = 18;
    private static final int MAX_CACHED_DECOMPRESSED_TILES = 32;

    private final Path path;
    private final ByteOrder byteOrder;
    private final boolean bigTiff;
    private final long firstIfdOffset;
    private final int width;
    private final int height;
    private final int bitsPerSample;
    private final int bytesPerSample;
    private final int sampleFormat;
    private final int samplesPerPixel;
    private final int planarConfiguration;
    private final int compression;
    private final int predictor;
    private final int tileWidth;
    private final int tileLength;
    private final long[] tileOffsets;
    private final long[] tileByteCounts;
    private final boolean tiled;
    private final int rowsPerStrip;
    private final double noData;
    private final Map<Integer, float[]> decodedTileCache = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, float[]> eldest) {
            return size() > MAX_CACHED_DECOMPRESSED_TILES;
        }
    };

    private CopernicusGeoTiffDem(Path path, ByteOrder byteOrder, boolean bigTiff, long firstIfdOffset, Map<Integer, IfdEntry> ifd) throws IOException {
        this.path = path;
        this.byteOrder = byteOrder;
        this.bigTiff = bigTiff;
        this.firstIfdOffset = firstIfdOffset;
        this.width = requiredInt(ifd, TAG_IMAGE_WIDTH, "ImageWidth");
        this.height = requiredInt(ifd, TAG_IMAGE_LENGTH, "ImageLength");
        this.bitsPerSample = firstOrDefault(ifd, TAG_BITS_PER_SAMPLE, 32);
        this.bytesPerSample = bitsPerSample / 8;
        this.sampleFormat = firstOrDefault(ifd, TAG_SAMPLE_FORMAT, bitsPerSample <= 32 ? SAMPLE_FORMAT_UNSIGNED : SAMPLE_FORMAT_IEEE_FLOAT);
        this.samplesPerPixel = firstOrDefault(ifd, TAG_SAMPLES_PER_PIXEL, 1);
        this.planarConfiguration = firstOrDefault(ifd, TAG_PLANAR_CONFIGURATION, 1);
        this.compression = firstOrDefault(ifd, TAG_COMPRESSION, COMPRESSION_NONE);
        this.predictor = firstOrDefault(ifd, TAG_PREDICTOR, 1);
        this.noData = readNoData(ifd);

        if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 32 && bitsPerSample != 64) {
            throw new IOException("Unsupported GeoTIFF bitsPerSample=" + bitsPerSample + " for " + path);
        }
        if (samplesPerPixel != 1) {
            throw new IOException("Unsupported GeoTIFF SamplesPerPixel=" + samplesPerPixel + " for " + path + "; only single-band DEM tiles are supported");
        }
        if (planarConfiguration != 1) {
            throw new IOException("Unsupported GeoTIFF PlanarConfiguration=" + planarConfiguration + " for " + path);
        }
        if (compression != COMPRESSION_NONE && compression != COMPRESSION_ADOBE_DEFLATE && compression != COMPRESSION_DEFLATE) {
            throw new IOException("Unsupported GeoTIFF compression=" + compression + " for " + path + "; supported: none/deflate");
        }
        if (predictor != 1 && predictor != 2 && predictor != 3) {
            throw new IOException("Unsupported GeoTIFF Predictor=" + predictor + " for " + path);
        }
        if (predictor == 3 && sampleFormat != SAMPLE_FORMAT_IEEE_FLOAT) {
            throw new IOException("GeoTIFF Predictor=3 requires floating point samples, sampleFormat=" + sampleFormat + " for " + path);
        }

        long[] offsets = numbersOrNull(ifd, TAG_TILE_OFFSETS);
        long[] counts = numbersOrNull(ifd, TAG_TILE_BYTE_COUNTS);
        if (offsets != null && counts != null) {
            this.tiled = true;
            this.tileWidth = requiredInt(ifd, TAG_TILE_WIDTH, "TileWidth");
            this.tileLength = requiredInt(ifd, TAG_TILE_LENGTH, "TileLength");
            this.tileOffsets = offsets;
            this.tileByteCounts = counts;
            this.rowsPerStrip = 0;
        } else {
            long[] stripOffsets = numbersOrNull(ifd, TAG_STRIP_OFFSETS);
            long[] stripCounts = numbersOrNull(ifd, TAG_STRIP_BYTE_COUNTS);
            if (stripOffsets == null || stripCounts == null) {
                throw new IOException("GeoTIFF has neither tiles nor strips: " + path);
            }
            this.tiled = false;
            this.rowsPerStrip = firstOrDefault(ifd, TAG_ROWS_PER_STRIP, height);
            this.tileWidth = width;
            this.tileLength = rowsPerStrip;
            this.tileOffsets = stripOffsets;
            this.tileByteCounts = stripCounts;
        }

        if (tileWidth <= 0 || tileLength <= 0 || tileOffsets.length == 0 || tileOffsets.length != tileByteCounts.length) {
            throw new IOException("Invalid GeoTIFF tile/strip table for " + path);
        }
    }

    public static CopernicusGeoTiffDem open(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a file: " + path);
        }
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            if (file.length() < 16) {
                throw new IOException("File is too small to be a TIFF: " + path);
            }
            byte[] endian = new byte[2];
            file.readFully(endian);
            ByteOrder order;
            if (endian[0] == 'I' && endian[1] == 'I') {
                order = ByteOrder.LITTLE_ENDIAN;
            } else if (endian[0] == 'M' && endian[1] == 'M') {
                order = ByteOrder.BIG_ENDIAN;
            } else {
                throw new IOException("Unsupported TIFF byte order in " + path);
            }

            int magic = readUnsignedShort(file, order);
            boolean big;
            long ifdOffset;
            if (magic == TIFF_MAGIC) {
                big = false;
                ifdOffset = readUnsignedInt(file, order);
            } else if (magic == BIG_TIFF_MAGIC) {
                big = true;
                int offsetSize = readUnsignedShort(file, order);
                int zero = readUnsignedShort(file, order);
                if (offsetSize != 8 || zero != 0) {
                    throw new IOException("Unsupported BigTIFF header in " + path);
                }
                ifdOffset = readLong(file, order);
            } else {
                throw new IOException("Unsupported TIFF magic " + magic + " in " + path);
            }

            Map<Integer, IfdEntry> ifd = readIfd(file, order, big, ifdOffset);
            return new CopernicusGeoTiffDem(path, order, big, ifdOffset, ifd);
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int tileWidth() {
        return tileWidth;
    }

    public int tileLength() {
        return tileLength;
    }

    public int compression() {
        return compression;
    }

    public int predictor() {
        return predictor;
    }

    public int bitsPerSample() {
        return bitsPerSample;
    }

    public int sampleFormat() {
        return sampleFormat;
    }

    public double samplePixel(int x, int y) throws IOException {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return Double.NaN;
        }
        int tileX = x / tileWidth;
        int tileY = y / tileLength;
        int tilesAcross = Math.max(1, (width + tileWidth - 1) / tileWidth);
        int tileIndex = tileY * tilesAcross + tileX;
        if (tileIndex < 0 || tileIndex >= tileOffsets.length) {
            return Double.NaN;
        }
        float[] tile = loadDecodedTile(tileIndex);
        int localX = x - tileX * tileWidth;
        int localY = y - tileY * tileLength;
        int index = localY * tileWidth + localX;
        if (index < 0 || index >= tile.length) {
            return Double.NaN;
        }
        float value = tile[index];
        if (!Float.isFinite(value) || Double.compare(value, noData) == 0 || value < -12000.0F || value > 12000.0F) {
            return Double.NaN;
        }
        return value;
    }

    private float[] loadDecodedTile(int tileIndex) throws IOException {
        synchronized (decodedTileCache) {
            float[] cached = decodedTileCache.get(tileIndex);
            if (cached != null) {
                return cached;
            }
        }

        float[] decoded = readDecodedTile(tileIndex);
        synchronized (decodedTileCache) {
            decodedTileCache.put(tileIndex, decoded);
        }
        return decoded;
    }

    private float[] readDecodedTile(int tileIndex) throws IOException {
        long offset = tileOffsets[tileIndex];
        long byteCount = tileByteCounts[tileIndex];
        if (offset <= 0 || byteCount <= 0 || byteCount > Integer.MAX_VALUE) {
            return new float[tileWidth * tileLength];
        }

        byte[] compressed = new byte[(int) byteCount];
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(offset);
            file.readFully(compressed);
        }

        int expectedBytes = Math.multiplyExact(Math.multiplyExact(tileWidth, tileLength), bytesPerSample * samplesPerPixel);
        byte[] data = switch (compression) {
            case COMPRESSION_NONE -> compressed.length == expectedBytes ? compressed : Arrays.copyOf(compressed, expectedBytes);
            case COMPRESSION_ADOBE_DEFLATE, COMPRESSION_DEFLATE -> inflateDeflate(compressed, expectedBytes);
            default -> throw new IOException("Unsupported TIFF compression " + compression + " in " + path);
        };

        if (predictor == 2) {
            applyHorizontalPredictor(data, tileWidth, tileLength, bytesPerSample, samplesPerPixel);
        } else if (predictor == 3) {
            applyFloatingPointPredictor(data, tileWidth, tileLength, bytesPerSample, samplesPerPixel, byteOrder);
        }

        float[] result = new float[tileWidth * tileLength];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(byteOrder);
        for (int i = 0; i < result.length; i++) {
            result[i] = switch (sampleFormat) {
                case SAMPLE_FORMAT_IEEE_FLOAT -> switch (bitsPerSample) {
                    case 32 -> buffer.getFloat();
                    case 64 -> (float) buffer.getDouble();
                    default -> throw new IOException("Unsupported float bitsPerSample=" + bitsPerSample + " in " + path);
                };
                case SAMPLE_FORMAT_SIGNED -> switch (bitsPerSample) {
                    case 8 -> buffer.get();
                    case 16 -> buffer.getShort();
                    case 32 -> buffer.getInt();
                    default -> throw new IOException("Unsupported signed bitsPerSample=" + bitsPerSample + " in " + path);
                };
                case SAMPLE_FORMAT_UNSIGNED -> switch (bitsPerSample) {
                    case 8 -> buffer.get() & 0xFF;
                    case 16 -> buffer.getShort() & 0xFFFF;
                    case 32 -> (float) (buffer.getInt() & 0xFFFFFFFFL);
                    default -> throw new IOException("Unsupported unsigned bitsPerSample=" + bitsPerSample + " in " + path);
                };
                default -> throw new IOException("Unsupported sampleFormat=" + sampleFormat + " in " + path);
            };
        }
        return result;
    }

    private static byte[] inflateDeflate(byte[] compressed, int expectedBytes) throws IOException {
        try {
            return inflate(compressed, expectedBytes, false);
        } catch (IOException first) {
            try {
                return inflate(compressed, expectedBytes, true);
            } catch (IOException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private static byte[] inflate(byte[] compressed, int expectedBytes, boolean nowrap) throws IOException {
        Inflater inflater = new Inflater(nowrap);
        byte[] output = new byte[expectedBytes];
        inflater.setInput(compressed);
        int total = 0;
        try {
            while (!inflater.finished() && total < output.length) {
                int read = inflater.inflate(output, total, output.length - total);
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                total += read;
            }
        } catch (DataFormatException ex) {
            throw new IOException("Invalid DEFLATE stream in TIFF tile", ex);
        } finally {
            inflater.end();
        }
        if (total != expectedBytes) {
            throw new IOException("Unexpected decompressed TIFF tile size: " + total + " != " + expectedBytes + " nowrap=" + nowrap);
        }
        return output;
    }

    private static void applyHorizontalPredictor(byte[] data, int width, int height, int bytesPerSample, int samplesPerPixel) {
        int strideBytes = bytesPerSample * samplesPerPixel;
        int rowSize = width * strideBytes;
        for (int row = 0; row < height; row++) {
            int rowOffset = row * rowSize;
            for (int i = strideBytes; i < rowSize; i++) {
                int value = (data[rowOffset + i] & 0xFF) + (data[rowOffset + i - strideBytes] & 0xFF);
                data[rowOffset + i] = (byte) value;
            }
        }
    }

    /**
     * Decodes TIFF Predictor=3. This mirrors libtiff's fpAcc routine:
     * horizontal byte accumulation first, then de-planarise floating-point bytes.
     */
    private static void applyFloatingPointPredictor(byte[] data, int width, int height, int bytesPerSample, int samplesPerPixel, ByteOrder byteOrder) {
        int stride = samplesPerPixel;
        int rowSize = width * bytesPerSample * samplesPerPixel;
        byte[] rowCopy = new byte[rowSize];
        for (int row = 0; row < height; row++) {
            int rowOffset = row * rowSize;

            if (stride == 1) {
                for (int i = 1; i < rowSize; i++) {
                    data[rowOffset + i] = (byte) ((data[rowOffset + i] + data[rowOffset + i - 1]) & 0xFF);
                }
            } else {
                for (int i = stride; i < rowSize; i++) {
                    data[rowOffset + i] = (byte) ((data[rowOffset + i] + data[rowOffset + i - stride]) & 0xFF);
                }
            }

            System.arraycopy(data, rowOffset, rowCopy, 0, rowSize);
            int sampleCount = rowSize / bytesPerSample;
            for (int sample = 0; sample < sampleCount; sample++) {
                for (int b = 0; b < bytesPerSample; b++) {
                    int sourcePlane = byteOrder == ByteOrder.BIG_ENDIAN ? b : bytesPerSample - b - 1;
                    data[rowOffset + sample * bytesPerSample + b] = rowCopy[sourcePlane * sampleCount + sample];
                }
            }
        }
    }

    private static Map<Integer, IfdEntry> readIfd(RandomAccessFile file, ByteOrder order, boolean bigTiff, long offset) throws IOException {
        file.seek(offset);
        long count = bigTiff ? readLong(file, order) : readUnsignedShort(file, order);
        if (count < 0 || count > 4096) {
            throw new IOException("Suspicious TIFF IFD entry count: " + count);
        }
        Map<Integer, IfdEntry> entries = new LinkedHashMap<>();
        for (long i = 0; i < count; i++) {
            int tag = readUnsignedShort(file, order);
            int type = readUnsignedShort(file, order);
            long valueCount = bigTiff ? readLong(file, order) : readUnsignedInt(file, order);
            int valueFieldSize = bigTiff ? 8 : 4;
            byte[] valueOrOffset = new byte[valueFieldSize];
            file.readFully(valueOrOffset);
            entries.put(tag, new IfdEntry(file, order, bigTiff, tag, type, valueCount, valueOrOffset));
        }
        return entries;
    }

    private static int requiredInt(Map<Integer, IfdEntry> ifd, int tag, String name) throws IOException {
        long[] values = numbersOrNull(ifd, tag);
        if (values == null || values.length == 0) {
            throw new IOException("Missing GeoTIFF tag " + name + " (" + tag + ")");
        }
        if (values[0] < Integer.MIN_VALUE || values[0] > Integer.MAX_VALUE) {
            throw new IOException("GeoTIFF tag " + name + " is out of int range: " + values[0]);
        }
        return (int) values[0];
    }

    private static int firstOrDefault(Map<Integer, IfdEntry> ifd, int tag, int fallback) throws IOException {
        long[] values = numbersOrNull(ifd, tag);
        if (values == null || values.length == 0) {
            return fallback;
        }
        return (int) values[0];
    }

    private static long[] numbersOrNull(Map<Integer, IfdEntry> ifd, int tag) throws IOException {
        IfdEntry entry = ifd.get(tag);
        if (entry == null) {
            return null;
        }
        return entry.readNumbers();
    }

    private double readNoData(Map<Integer, IfdEntry> ifd) {
        IfdEntry entry = ifd.get(TAG_GDAL_NODATA);
        if (entry == null) {
            return -32767.0D;
        }
        try {
            String text = entry.readAscii().trim();
            int zero = text.indexOf('\0');
            if (zero >= 0) {
                text = text.substring(0, zero).trim();
            }
            return Double.parseDouble(text);
        } catch (Exception ex) {
            RedlineAtlasWorldgen.LOGGER.debug("Could not read GDAL_NODATA from {}: {}", path, ex.toString());
            return -32767.0D;
        }
    }

    private static int readUnsignedShort(RandomAccessFile file, ByteOrder order) throws IOException {
        int b0 = file.read();
        int b1 = file.read();
        if ((b0 | b1) < 0) {
            throw new EOFException();
        }
        return order == ByteOrder.LITTLE_ENDIAN ? (b0 | (b1 << 8)) : ((b0 << 8) | b1);
    }

    private static long readUnsignedInt(RandomAccessFile file, ByteOrder order) throws IOException {
        byte[] bytes = new byte[4];
        file.readFully(bytes);
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes).order(order).getInt());
    }

    private static long readLong(RandomAccessFile file, ByteOrder order) throws IOException {
        byte[] bytes = new byte[8];
        file.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(order).getLong();
    }

    private static int typeSize(int type) throws IOException {
        return switch (type) {
            case TYPE_BYTE, TYPE_ASCII, TYPE_SBYTE, TYPE_UNDEFINED -> 1;
            case TYPE_SHORT -> 2;
            case TYPE_LONG, TYPE_SLONG, TYPE_FLOAT -> 4;
            case TYPE_DOUBLE, TYPE_LONG8, TYPE_SLONG8, TYPE_IFD8 -> 8;
            default -> throw new IOException("Unsupported TIFF field type=" + type);
        };
    }

    private static final class IfdEntry {
        private final RandomAccessFile file;
        private final ByteOrder order;
        private final boolean bigTiff;
        private final int tag;
        private final int type;
        private final long count;
        private final byte[] valueOrOffset;

        private IfdEntry(RandomAccessFile file, ByteOrder order, boolean bigTiff, int tag, int type, long count, byte[] valueOrOffset) {
            this.file = file;
            this.order = order;
            this.bigTiff = bigTiff;
            this.tag = tag;
            this.type = type;
            this.count = count;
            this.valueOrOffset = valueOrOffset;
        }

        private long[] readNumbers() throws IOException {
            byte[] bytes = readValueBytes();
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(order);
            int n = Math.toIntExact(count);
            long[] result = new long[n];
            for (int i = 0; i < n; i++) {
                result[i] = switch (type) {
                    case TYPE_BYTE, TYPE_UNDEFINED -> buffer.get() & 0xFFL;
                    case TYPE_SBYTE -> buffer.get();
                    case TYPE_SHORT -> buffer.getShort() & 0xFFFFL;
                    case TYPE_LONG -> Integer.toUnsignedLong(buffer.getInt());
                    case TYPE_SLONG -> buffer.getInt();
                    case TYPE_LONG8, TYPE_IFD8 -> buffer.getLong();
                    case TYPE_SLONG8 -> buffer.getLong();
                    default -> throw new IOException("Tag " + tag + " has non-integer TIFF type=" + type);
                };
            }
            return result;
        }

        private String readAscii() throws IOException {
            if (type != TYPE_ASCII) {
                throw new IOException("Tag " + tag + " is not ASCII");
            }
            return new String(readValueBytes(), StandardCharsets.US_ASCII);
        }

        private byte[] readValueBytes() throws IOException {
            int typeSize = typeSize(type);
            long byteCountLong = Math.multiplyExact(count, (long) typeSize);
            if (byteCountLong > Integer.MAX_VALUE) {
                throw new IOException("TIFF tag " + tag + " value is too large: " + byteCountLong);
            }
            int byteCount = (int) byteCountLong;
            int inlineLimit = bigTiff ? 8 : 4;
            if (byteCount <= inlineLimit) {
                return Arrays.copyOf(valueOrOffset, byteCount);
            }
            long offset = bigTiff ? ByteBuffer.wrap(valueOrOffset).order(order).getLong() : Integer.toUnsignedLong(ByteBuffer.wrap(valueOrOffset).order(order).getInt());
            byte[] bytes = new byte[byteCount];
            synchronized (file) {
                file.seek(offset);
                file.readFully(bytes);
            }
            return bytes;
        }
    }
}
