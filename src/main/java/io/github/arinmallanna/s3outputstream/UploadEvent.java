package io.github.arinmallanna.s3outputstream;

/** Immutable, identifier-free upload progress snapshot. Timings use a monotonic clock. */
public final class UploadEvent {
    /** Application lifecycle events; SDK retries are not individual events here. */
    public enum Type {
        STARTED, MODE_SELECTED, BYTES_WRITTEN, PART_STARTED, PART_COMPLETED,
        PART_FAILED, COMPLETED, ABORTED, FAILED, CLEANUP_FAILED
    }
    /** Chosen storage protocol. */
    public enum Mode { UNDECIDED, SINGLE_PUT, MULTIPART }
    private final Type type;
    private final Mode mode;
    private final UploadState state;
    private final long bytesWritten;
    private final long bytesUploaded;
    private final int partNumber;
    private final long elapsedNanos;

    UploadEvent(Type type, Mode mode, UploadState state, long written, long uploaded, int part, long elapsed) {
        this.type = type; this.mode = mode; this.state = state;
        bytesWritten = written; bytesUploaded = uploaded; partNumber = part; elapsedNanos = elapsed;
    }
    /** @return event kind */
    public Type type() { return type; }
    /** @return selected protocol */
    public Mode mode() { return mode; }
    /** @return local state at event emission */
    public UploadState state() { return state; }
    /** @return accepted producer bytes */
    public long bytesWritten() { return bytesWritten; }
    /** @return bytes acknowledged by successful calls */
    public long bytesUploaded() { return bytesUploaded; }
    /** @return one-based part number, or zero for non-part events */
    public int partNumber() { return partNumber; }
    /** @return nanoseconds since stream construction */
    public long elapsedNanos() { return elapsedNanos; }
}
