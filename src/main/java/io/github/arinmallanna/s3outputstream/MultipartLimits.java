package io.github.arinmallanna.s3outputstream;

import java.io.IOException;

/** Fixed-part capacity arithmetic; all calculations run without allocating payloads. */
public final class MultipartLimits {
    /** S3's minimum non-final part size: 5 MiB. */
    public static final int MIN_PART_SIZE_BYTES = 5 * 1024 * 1024;
    /** Java array-backed implementation limit, below S3's 5 GiB part maximum. */
    public static final int MAX_PART_SIZE_BYTES = Integer.MAX_VALUE - 8;
    /** S3 permits part numbers 1 through 10,000. */
    public static final int MAX_PARTS = 10_000;
    private MultipartLimits() { }

    /** Computes the fixed-part size policy without allocating a payload.
     * @param length exact final object length in bytes
     * @return minimum supported part size covering that length in at most 10,000 parts
     * @throws IllegalArgumentException if the array-backed policy cannot cover the length
     */
    public static int partSizeFor(long length) {
        if (length < 0) throw new IllegalArgumentException("length must be nonnegative");
        long required = length / MAX_PARTS + (length % MAX_PARTS == 0 ? 0 : 1);
        if (required > MAX_PART_SIZE_BYTES) {
            throw new IllegalArgumentException("length exceeds this array-backed implementation's capacity");
        }
        return (int) Math.max(MIN_PART_SIZE_BYTES, required);
    }

    /** Computes the fixed-part size policy without allocating a payload.
     * @param partSize payload buffer bytes
     * @return maximum fixed-part object bytes
     */
    public static long capacity(int partSize) {
        validatePartSize(partSize);
        return (long) partSize * MAX_PARTS;
    }

    static void validatePartSize(int bytes) {
        if (bytes < MIN_PART_SIZE_BYTES || bytes > MAX_PART_SIZE_BYTES) {
            throw new IllegalArgumentException("partSize must be between " + MIN_PART_SIZE_BYTES + " and "
                    + MAX_PART_SIZE_BYTES + " bytes; allocation also requires sufficient JVM heap");
        }
    }

    static int nextPartNumber(int uploaded) throws IOException {
        if (uploaded < 0 || uploaded >= MAX_PARTS) {
            throw new IOException("multipart part limit reached (10000); choose a larger partSize before writing");
        }
        return uploaded + 1;
    }

    static void checkWrite(long written, int length, long capacity, long expected) throws IOException {
        if (written < 0 || length < 0 || written > capacity || length > capacity - written) {
            throw new IOException("fixed-part capacity exceeded; choose a larger partSize or expectedLength before writing");
        }
        if (expected >= 0 && (written > expected || length > expected - written)) {
            throw new IOException("write exceeds expectedLength");
        }
    }
}
