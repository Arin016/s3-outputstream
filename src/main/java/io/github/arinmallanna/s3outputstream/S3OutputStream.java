package io.github.arinmallanna.s3outputstream;

import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An {@link OutputStream} that streams data directly to an Amazon S3 object
 * using the multipart upload API, with bounded memory usage.
 *
 * <h2>The Problem</h2>
 * <p>The AWS SDK for Java v2 provides no {@code OutputStream}-based upload API.
 * Libraries like Apache POI ({@code workbook.write(OutputStream)}), PDFBox
 * ({@code document.save(OutputStream)}), and {@code ZipOutputStream} all produce
 * output via {@code OutputStream}. Without this class, users must either buffer the
 * entire content in memory or write to a temporary file — both unacceptable for
 * large data at scale.</p>
 *
 * <h2>The Solution</h2>
 * <p>This class buffers writes into configurable-size parts (default 5 MB) and uploads
 * each part to S3 as it fills. On {@link #close()}, it either completes the multipart
 * upload or, if total data ≤ one part, uses a single PutObject to avoid multipart
 * overhead.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (S3OutputStream out = S3OutputStream.builder()
 *         .s3Client(s3Client)
 *         .bucket("my-bucket")
 *         .key("exports/report.zip")
 *         .partSize(10 * 1024 * 1024)  // optional: 10 MB parts
 *         .build()) {
 *
 *     ZipOutputStream zip = new ZipOutputStream(out);
 *     zip.putNextEntry(new ZipEntry("data.csv"));
 *     zip.write(csvBytes);
 *     zip.close();
 * }
 * // S3 object is now complete
 * }</pre>
 *
 * <h2>Memory Guarantee</h2>
 * <p>At most one part buffer ({@code partSize} bytes) is allocated. After each part
 * is uploaded, the buffer is reused for the next part. Peak heap usage =
 * {@code partSize + overhead}.</p>
 *
 * <h2>Failure Semantics</h2>
 * <ul>
 *   <li>If an exception occurs during {@link #close()}, the multipart upload is aborted
 *       to avoid orphaned parts accruing S3 storage costs.</li>
 *   <li>{@link #abort()} can be called explicitly in error-handling paths.</li>
 * </ul>
 *
 * <h2>Design Decisions</h2>
 * <ul>
 *   <li><b>Strategy Pattern</b> ({@link UploadStrategy}): decouples S3 API interaction
 *       from stream logic, enabling clean unit tests without SDK mocking.</li>
 *   <li><b>State enum</b> ({@link UploadState}): replaces scattered boolean flags with
 *       a type-safe finite state machine.</li>
 *   <li><b>Builder Pattern</b>: validated construction with sensible defaults.</li>
 * </ul>
 *
 * @see <a href="https://github.com/aws/aws-sdk-java-v2/issues/3128">aws-sdk-java-v2 #3128</a>
 * @see <a href="https://github.com/aws/aws-sdk-java-v2/issues/3131">aws-sdk-java-v2 #3131</a>
 */
public final class S3OutputStream extends OutputStream {

    /** S3 enforces a 5 MB minimum for multipart parts (except the final part). */
    static final int MIN_PART_SIZE_BYTES = 5 * 1024 * 1024;
    private static final int DEFAULT_PART_SIZE_BYTES = 5 * 1024 * 1024;

    private final UploadStrategy uploadStrategy;
    private final String bucket;
    private final String key;
    private final int partSize;

    private byte[] buffer;
    private int position;
    private UploadState state;
    private String uploadId;
    private final List<CompletedPartInfo> completedParts;
    private long totalBytesWritten;

    /**
     * Internal constructor — use {@link #builder()} for construction.
     */
    S3OutputStream(UploadStrategy uploadStrategy, String bucket, String key, int partSize) {
        this.uploadStrategy = Objects.requireNonNull(uploadStrategy);
        this.bucket = Objects.requireNonNull(bucket);
        this.key = Objects.requireNonNull(key);
        this.partSize = partSize;
        this.buffer = new byte[partSize];
        this.state = UploadState.BUFFERING;
        this.completedParts = new ArrayList<>();
    }

    // ─── OutputStream contract ─────────────────────────────────────────────────

    @Override
    public void write(int b) throws IOException {
        ensureAcceptsWrites();
        buffer[position++] = (byte) b;
        totalBytesWritten++;
        if (position == partSize) {
            flushBuffer();
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.requireNonNull(b, "byte array must not be null");
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException(
                    "off=" + off + ", len=" + len + ", array.length=" + b.length);
        }
        ensureAcceptsWrites();

        int remaining = len;
        int srcOffset = off;

        while (remaining > 0) {
            int space = partSize - position;
            int toCopy = Math.min(remaining, space);
            System.arraycopy(b, srcOffset, buffer, position, toCopy);
            position += toCopy;
            srcOffset += toCopy;
            remaining -= toCopy;
            totalBytesWritten += toCopy;

            if (position == partSize) {
                flushBuffer();
            }
        }
    }

    /**
     * Completes the S3 upload.
     *
     * <p>If total data fits in one part, uses PutObject (no multipart overhead).
     * Otherwise, flushes the remaining buffer as the final part and sends
     * CompleteMultipartUpload.</p>
     *
     * <p>On failure, aborts the multipart upload to prevent orphaned parts.</p>
     */
    @Override
    public void close() throws IOException {
        if (state.isTerminal()) return;

        try {
            if (state == UploadState.BUFFERING) {
                // Total data fit in one buffer — single PutObject (cheaper, no multipart)
                uploadStrategy.putObject(bucket, key, buffer, position);
            } else {
                // Flush remaining buffer as the final part (can be < 5MB — S3 allows it for the last part)
                if (position > 0) {
                    uploadCurrentPart();
                }
                uploadStrategy.completeUpload(bucket, key, uploadId, completedParts);
            }
            state = UploadState.COMPLETED;
        } catch (Exception e) {
            abortQuietly();
            throw new S3UploadException(bucket, key, "upload failed during close()", e);
        } finally {
            buffer = null; // Release the buffer regardless of outcome
        }
    }

    // ─── Public API beyond OutputStream ────────────────────────────────────────

    /**
     * Explicitly aborts the in-progress upload. Idempotent.
     *
     * <p>Use this in error-handling paths where you know the output is invalid
     * and don't want to complete a corrupt S3 object.</p>
     */
    public void abort() {
        if (state.isTerminal()) return;
        abortQuietly();
        buffer = null;
    }

    /** Total bytes written to this stream so far. */
    public long getTotalBytesWritten() {
        return totalBytesWritten;
    }

    /** Number of parts uploaded to S3 (0 while still in single-put mode). */
    public int getPartsUploaded() {
        return completedParts.size();
    }

    /** Returns an unmodifiable view of parts uploaded so far. */
    public List<CompletedPartInfo> getCompletedParts() {
        return Collections.unmodifiableList(completedParts);
    }

    /** Returns the current upload lifecycle state. */
    public UploadState getState() {
        return state;
    }

    // ─── Internal mechanics ────────────────────────────────────────────────────

    private void flushBuffer() throws IOException {
        try {
            if (state == UploadState.BUFFERING) {
                // First time exceeding one part — initiate multipart
                uploadId = uploadStrategy.initiateUpload(bucket, key);
                state = UploadState.MULTIPART_IN_PROGRESS;
            }
            uploadCurrentPart();
        } catch (Exception e) {
            abortQuietly();
            throw new S3UploadException(bucket, key, "part upload failed", e);
        }
    }

    private void uploadCurrentPart() {
        int partNumber = completedParts.size() + 1;
        String eTag = uploadStrategy.uploadPart(bucket, key, uploadId, partNumber, buffer, position);
        completedParts.add(new CompletedPartInfo(partNumber, eTag));
        position = 0; // Reset buffer for reuse
    }

    private void abortQuietly() {
        state = UploadState.ABORTED;
        if (uploadId != null) {
            try {
                uploadStrategy.abortUpload(bucket, key, uploadId);
            } catch (Exception suppressed) {
                // Best-effort abort — swallowing here is acceptable because:
                // (1) the caller is already handling the primary exception, and
                // (2) S3 lifecycle policies will garbage-collect incomplete uploads.
            }
        }
    }

    private void ensureAcceptsWrites() throws IOException {
        if (!state.acceptsWrites()) {
            throw new S3UploadException(bucket, key,
                    "stream is " + state.name().toLowerCase() + " and cannot accept writes");
        }
    }

    // ─── Builder ───────────────────────────────────────────────────────────────

    /**
     * Creates a new builder for S3OutputStream.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link S3OutputStream}.
     *
     * <p><b>Design Pattern:</b> Builder — enforces required parameters at construction
     * time with validation, prevents invalid object states, and provides a fluent API
     * with sensible defaults for optional parameters.
     */
    public static final class Builder {

        private S3Client s3Client;
        private UploadStrategy uploadStrategy;
        private String bucket;
        private String key;
        private int partSize = DEFAULT_PART_SIZE_BYTES;

        private Builder() {}

        /** The S3Client to use for uploads. Required (unless uploadStrategy is set directly). */
        public Builder s3Client(S3Client s3Client) {
            this.s3Client = s3Client;
            return this;
        }

        /** Target S3 bucket. Required. */
        public Builder bucket(String bucket) {
            this.bucket = bucket;
            return this;
        }

        /** Target S3 object key. Required. */
        public Builder key(String key) {
            this.key = key;
            return this;
        }

        /**
         * Part size in bytes. Default: 5 MB. Must be ≥ 5 MB (S3 minimum for multipart parts).
         * Larger values reduce the number of S3 API calls at the cost of more heap memory.
         */
        public Builder partSize(int partSize) {
            this.partSize = partSize;
            return this;
        }

        /**
         * Advanced: inject a custom UploadStrategy (primarily for testing).
         * If set, {@code s3Client} is not required.
         */
        Builder uploadStrategy(UploadStrategy strategy) {
            this.uploadStrategy = strategy;
            return this;
        }

        /**
         * Validates all parameters and constructs the S3OutputStream.
         *
         * @throws IllegalArgumentException if required parameters are missing or invalid
         */
        public S3OutputStream build() {
            UploadStrategy strategy = this.uploadStrategy;
            if (strategy == null) {
                if (s3Client == null) {
                    throw new IllegalArgumentException("s3Client is required");
                }
                strategy = new S3ClientUploadStrategy(s3Client);
            }
            if (bucket == null || bucket.isEmpty()) {
                throw new IllegalArgumentException("bucket is required");
            }
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException("key is required");
            }
            if (partSize < MIN_PART_SIZE_BYTES) {
                throw new IllegalArgumentException(
                        "partSize must be >= " + MIN_PART_SIZE_BYTES + " bytes (5 MB), got: " + partSize);
            }
            return new S3OutputStream(strategy, bucket, key, partSize);
        }
    }
}
