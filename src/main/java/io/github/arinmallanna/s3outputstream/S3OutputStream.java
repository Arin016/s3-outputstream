package io.github.arinmallanna.s3outputstream;

import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A serial, synchronous S3 sink for sequential Java producers.
 *
 * <p>Call {@link #commit()} only after production and wrapper finalization succeed.
 * By default {@link #close()} aborts an uncommitted upload. Prefer
 * {@link #upload(Builder, Producer)} when composing with ZIP/document writers.
 * The legacy {@link Builder#autoCommitOnClose(boolean)} option explicitly restores
 * completion on close, including close during producer exception unwinding.</p>
 *
 * <p>Objects no larger than one configured part use PutObject at commit. Larger
 * objects use serial multipart upload. A full first buffer is retained until an
 * additional byte arrives. {@link #flush()} does not publish or upload a partial
 * part. Blocking part uploads apply backpressure to the writing thread.</p>
 *
 * <p>One reusable payload buffer is owned by this stream; SDK/HTTP buffers,
 * caller buffers and per-part ETag metadata require additional memory. Terminal
 * transitions release this stream's buffer, upload ID and ETag list. This does
 * not prove a GC timing or whole-process memory bound.</p>
 *
 * <p>Not thread-safe, including commit/abort and getters. Callbacks must not reenter
 * the stream. The caller owns and closes the supplied S3 client. Configure client
 * timeouts/retries and an S3 incomplete-multipart lifecycle rule operationally.
 * Cleanup is best effort; a failed commit response may hide a successful remote
 * publication. This class does not delete an existing/final object or resume
 * uploads after a crash.</p>
 */
public final class S3OutputStream extends OutputStream {
    static final int MIN_PART_SIZE_BYTES = MultipartLimits.MIN_PART_SIZE_BYTES;

    private final UploadStrategy uploadStrategy;
    private final String bucket;
    private final String key;
    private final int partSize;
    private final long expectedLength;
    private final long capacity;
    private final boolean autoCommitOnClose;
    private final UploadListener listener;
    private final long startedNanos = System.nanoTime();

    private byte[] buffer;
    private int position;
    private UploadState state = UploadState.BUFFERING;
    private String uploadId;
    private final List<CompletedPartInfo> completedParts = new ArrayList<>();
    private long totalBytesWritten;
    private long totalBytesUploaded;
    private int partsUploaded;
    private long listenerFailures;
    private boolean notifying;
    private UploadEvent.Mode mode = UploadEvent.Mode.UNDECIDED;

    // Package-private seam permits small-buffer state-machine tests. Public builds
    // always validate real S3 minimum/maximum part sizes before allocating.
    S3OutputStream(UploadStrategy strategy, String bucket, String key, int partSize) {
        this(strategy, bucket, key, partSize, -1, false, UploadListener.NONE);
    }

    private S3OutputStream(UploadStrategy strategy, String bucket, String key, int partSize,
                           long expectedLength, boolean autoCommitOnClose, UploadListener listener) {
        this.uploadStrategy = Objects.requireNonNull(strategy, "strategy");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.key = Objects.requireNonNull(key, "key");
        if (partSize <= 0) throw new IllegalArgumentException("partSize must be positive");
        this.partSize = partSize;
        this.capacity = (long) partSize * MultipartLimits.MAX_PARTS;
        this.expectedLength = expectedLength;
        this.autoCommitOnClose = autoCommitOnClose;
        this.listener = Objects.requireNonNull(listener, "listener");
        buffer = new byte[partSize];
        emit(UploadEvent.Type.STARTED, 0);
    }

    @Override
    public void write(int b) throws IOException {
        prepareWrite(1);
        try {
            if (position == partSize) flushBuffer();
            buffer[position++] = (byte) b;
            totalBytesWritten++;
            emit(UploadEvent.Type.BYTES_WRITTEN, 0);
        } catch (Exception e) {
            throw fail("write failed", e);
        } catch (Error e) {
            failError(e);
            throw e;
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.requireNonNull(b, "byte array");
        Objects.checkFromIndexSize(off, len, b.length);
        prepareWrite(len);
        try {
            int remaining = len;
            while (remaining > 0) {
                if (position == partSize) flushBuffer();
                int n = Math.min(remaining, partSize - position);
                System.arraycopy(b, off, buffer, position, n);
                position += n;
                off += n;
                remaining -= n;
                totalBytesWritten += n;
            }
            if (len > 0) emit(UploadEvent.Type.BYTES_WRITTEN, 0);
        } catch (Exception e) {
            throw fail("write failed", e);
        } catch (Error e) {
            failError(e);
            throw e;
        }
    }

    /**
     * Validates that the stream is open. Does not send buffered bytes or publish.
     * A partial part cannot be sent here because it may become a non-final part.
     *
     * @throws IOException if the stream is terminal or the thread is interrupted
     */
    @Override
    public void flush() throws IOException {
        prepareWrite(0);
    }

    /**
     * Publishes all accepted bytes after successful production. Repeating a
     * successful commit is a no-op; committing an aborted/failed stream fails.
     *
     * @throws IOException on upload failure, interruption or length mismatch
     */
    public void commit() throws IOException {
        ensureNotInListener();
        if (state == UploadState.COMPLETED) return;
        ensureAcceptsWrites();
        try {
            checkInterrupted();
            if (expectedLength >= 0 && totalBytesWritten != expectedLength) {
                throw new IOException("expectedLength mismatch: producer ended before the declared length");
            }
            if (state == UploadState.BUFFERING) {
                mode = UploadEvent.Mode.SINGLE_PUT;
                emit(UploadEvent.Type.MODE_SELECTED, 0);
                uploadStrategy.putObject(bucket, key, buffer, position);
                totalBytesUploaded += position;
            } else {
                if (position > 0) uploadCurrentPart();
                checkInterrupted();
                uploadStrategy.completeUpload(bucket, key, uploadId,
                        Collections.unmodifiableList(completedParts));
            }
            state = UploadState.COMPLETED;
            release();
            emit(UploadEvent.Type.COMPLETED, 0);
        } catch (Exception e) {
            throw fail("commit failed; remote publication may be unknown", e);
        } catch (Error e) {
            failError(e);
            throw e;
        }
    }

    /**
     * Aborts unless already terminal, or commits if legacy auto-commit is enabled.
     * In try-with-resources a cleanup failure is suppressed on a producer failure.
     *
     * @throws IOException if commit or abort fails
     */
    @Override
    public void close() throws IOException {
        ensureNotInListener();
        if (state.isTerminal()) return;
        if (autoCommitOnClose) commit();
        else abort();
    }

    /**
     * Marks the stream aborted and attempts remote multipart cleanup once.
     * Idempotent after any terminal state, including a failed cleanup attempt.
     * Never removes a final object. Cleanup failure is reported, not swallowed.
     *
     * @throws S3UploadException if remote cleanup fails
     */
    public void abort() throws S3UploadException {
        ensureNotInListener();
        if (state.isTerminal()) return;
        state = UploadState.ABORTED;
        Throwable cleanupFailure = cleanup();
        emit(UploadEvent.Type.ABORTED, 0);
        if (cleanupFailure instanceof Error) throw (Error) cleanupFailure;
        if (cleanupFailure != null) {
            throw new S3UploadException(bucket, key, "multipart cleanup failed", cleanupFailure);
        }
    }

    /** @return total bytes accepted, including bytes accepted before failure */
    public long getTotalBytesWritten() { return totalBytesWritten; }
    /** @return bytes acknowledged by successful PutObject/UploadPart calls */
    public long getTotalBytesUploaded() { return totalBytesUploaded; }
    /** @return successful multipart part count, retained after terminal cleanup */
    public int getPartsUploaded() { return partsUploaded; }
    /**
     * @return snapshot of acknowledged parts while open; empty after terminal cleanup
     */
    public List<CompletedPartInfo> getCompletedParts() {
        return Collections.unmodifiableList(new ArrayList<>(completedParts));
    }
    /** @return local lifecycle state; failure/abort does not prove remote absence */
    public UploadState getState() { return state; }
    /** @return configured application payload-buffer size in bytes */
    public int getPartSize() { return partSize; }
    /** @return maximum accepted object size in bytes with this fixed part size */
    public long getMaximumObjectSize() { return capacity; }
    /** @return currently retained payload-buffer bytes (zero after termination) */
    public int getRetainedBufferBytes() { return buffer == null ? 0 : buffer.length; }
    /** @return number of isolated listener RuntimeExceptions */
    public long getListenerFailures() { return listenerFailures; }

    private void prepareWrite(int length) throws IOException {
        ensureNotInListener();
        ensureAcceptsWrites();
        try {
            checkInterrupted();
            MultipartLimits.checkWrite(totalBytesWritten, length, capacity, expectedLength);
        } catch (Exception e) {
            throw fail("write rejected", e);
        }
    }

    private void flushBuffer() throws IOException {
        checkInterrupted();
        if (state == UploadState.BUFFERING) {
            mode = UploadEvent.Mode.MULTIPART;
            emit(UploadEvent.Type.MODE_SELECTED, 0);
            uploadId = Objects.requireNonNull(uploadStrategy.initiateUpload(bucket, key), "uploadId");
            if (uploadId.isEmpty()) throw new IOException("empty multipart upload ID");
            state = UploadState.MULTIPART_IN_PROGRESS;
        }
        uploadCurrentPart();
    }

    private void uploadCurrentPart() throws IOException {
        checkInterrupted();
        int partNumber = MultipartLimits.nextPartNumber(partsUploaded);
        emit(UploadEvent.Type.PART_STARTED, partNumber);
        try {
            String eTag = uploadStrategy.uploadPart(bucket, key, uploadId, partNumber, buffer, position);
            completedParts.add(new CompletedPartInfo(partNumber, eTag));
            partsUploaded++;
            totalBytesUploaded += position;
            position = 0;
            emit(UploadEvent.Type.PART_COMPLETED, partNumber);
        } catch (Exception e) {
            emit(UploadEvent.Type.PART_FAILED, partNumber);
            throw e;
        }
    }

    private S3UploadException fail(String message, Exception cause) {
        S3UploadException primary = new S3UploadException(bucket, key, message, cause);
        if (!state.isTerminal()) {
            state = UploadState.FAILED;
            Throwable secondary = cleanup();
            if (secondary != null && secondary != cause) primary.addSuppressed(secondary);
            emit(UploadEvent.Type.FAILED, 0);
        }
        return primary;
    }

    private void failError(Error primary) {
        if (!state.isTerminal()) {
            state = UploadState.FAILED;
            Throwable secondary = cleanup();
            if (secondary != null && secondary != primary) primary.addSuppressed(secondary);
        }
    }

    private Throwable cleanup() {
        Throwable failure = null;
        try {
            if (uploadId != null) uploadStrategy.abortUpload(bucket, key, uploadId);
        } catch (Throwable e) {
            failure = e;
        } finally {
            release();
        }
        if (failure != null) emit(UploadEvent.Type.CLEANUP_FAILED, 0);
        return failure;
    }

    private void release() {
        buffer = null;
        position = 0;
        uploadId = null;
        completedParts.clear();
    }

    private void ensureAcceptsWrites() throws S3UploadException {
        if (!state.acceptsWrites()) {
            throw new S3UploadException(bucket, key, "stream is " + state + " and cannot accept writes/commit");
        }
    }

    private void ensureNotInListener() {
        if (notifying) throw new IllegalStateException("upload listeners must not reenter the stream");
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("upload thread interrupted");
        }
    }

    private void emit(UploadEvent.Type type, int partNumber) {
        if (listener == UploadListener.NONE) return;
        notifying = true;
        try {
            listener.onEvent(new UploadEvent(type, mode, state, totalBytesWritten,
                    totalBytesUploaded, partNumber, System.nanoTime() - startedNanos));
        } catch (RuntimeException ignored) {
            listenerFailures++;
        } finally {
            notifying = false;
        }
    }

    /** A producer must finish all wrappers and propagate errors before returning. */
    @FunctionalInterface
    public interface Producer {
        /**
         * Writes the complete object. Closing the supplied view does not commit.
         * @param out producer-owned, close-shielded view of the upload
         * @throws IOException if production fails
         */
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * Runs a producer, commits only on normal return, and cleans up on failure.
     * Always disables legacy auto-commit without mutating the supplied builder.
     *
     * @param builder upload configuration
     * @param producer complete sequential producer
     * @throws IOException on producer/upload/cleanup failure
     */
    public static void upload(Builder builder, Producer producer) throws IOException {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(producer, "producer");
        try (S3OutputStream out = builder.build(false)) {
            producer.writeTo(new OutputStream() {
                private boolean closed;
                private void checkOpen() throws IOException {
                    if (closed) throw new IOException("producer output view is closed");
                }
                @Override public void write(int b) throws IOException { checkOpen(); out.write(b); }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    checkOpen(); out.write(b, off, len);
                }
                @Override public void flush() throws IOException { checkOpen(); out.flush(); }
                @Override public void close() { closed = true; }
            });
            out.commit();
        }
    }

    /** @return a new mutable builder; builders are not thread-safe */
    public static Builder builder() { return new Builder(); }

    /** Validated construction; the client remains owned by the caller. */
    public static final class Builder {
        private S3Client s3Client;
        private UploadStrategy uploadStrategy;
        private String bucket;
        private String key;
        private Integer partSize;
        private long expectedLength = -1;
        private boolean autoCommitOnClose;
        private UploadListener listener = UploadListener.NONE;
        private String contentType;
        private Map<String, String> metadata = Collections.emptyMap();

        private Builder() { }
        /** @param client synchronous S3 client @return this builder */
        public Builder s3Client(S3Client client) { s3Client = Objects.requireNonNull(client); return this; }
        /** @param value destination bucket @return this builder */
        public Builder bucket(String value) { bucket = value; return this; }
        /** @param value destination key @return this builder */
        public Builder key(String value) { key = value; return this; }
        /**
         * @param bytes fixed payload buffer size (default 5 MiB, or chosen for expectedLength)
         * @return this builder
         */
        public Builder partSize(int bytes) { partSize = bytes; return this; }
        /**
         * Declares the exact final serialized length, not an estimate. Overrun and
         * underrun are fatal. Automatically selects sufficient part size unless
         * an explicit size was supplied; an insufficient explicit size is rejected.
         * @param bytes nonnegative exact length
         * @return this builder
         */
        public Builder expectedLength(long bytes) {
            if (bytes < 0) throw new IllegalArgumentException("expectedLength must be nonnegative");
            expectedLength = bytes; return this;
        }
        /**
         * @param enabled true restores legacy publication on close, even after producer failure
         * @return this builder
         */
        public Builder autoCommitOnClose(boolean enabled) { autoCommitOnClose = enabled; return this; }
        /** @param value observer; RuntimeExceptions are counted and isolated @return this builder */
        public Builder listener(UploadListener value) { listener = Objects.requireNonNull(value); return this; }
        /** @param value nonblank object MIME type @return this builder */
        public Builder contentType(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("contentType must not be blank");
            contentType = value; return this;
        }
        /** @param value object user metadata, defensively copied @return this builder */
        public Builder metadata(Map<String, String> value) { metadata = Map.copyOf(value); return this; }
        Builder uploadStrategy(UploadStrategy strategy) { uploadStrategy = Objects.requireNonNull(strategy); return this; }
        /**
         * @return a new open upload
         * @throws IllegalArgumentException if required fields or size policy are invalid
         */
        public S3OutputStream build() { return build(autoCommitOnClose); }

        private S3OutputStream build(boolean automaticCommit) {
            if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("bucket is required");
            if (key == null || key.isEmpty()) throw new IllegalArgumentException("key is required");
            int selected = partSize == null ? MultipartLimits.partSizeFor(expectedLength < 0 ? 0 : expectedLength) : partSize;
            MultipartLimits.validatePartSize(selected);
            if (expectedLength > MultipartLimits.capacity(selected)) {
                throw new IllegalArgumentException("partSize cannot fit expectedLength within 10000 parts; choose at least "
                        + MultipartLimits.partSizeFor(expectedLength) + " bytes");
            }
            UploadStrategy strategy = uploadStrategy;
            if (strategy == null) {
                if (s3Client == null) throw new IllegalArgumentException("s3Client is required");
                strategy = new S3ClientUploadStrategy(s3Client, contentType, metadata);
            }
            return new S3OutputStream(strategy, bucket, key, selected, expectedLength, automaticCommit, listener);
        }
    }
}
