package io.github.arinmallanna.s3outputstream;

/** Immutable, identifier-free upload progress snapshot. Timings use a monotonic clock. */
public final class UploadEvent {
    /** Application lifecycle events; SDK retries are not individual events here. */
    public enum Type {
        /** Stream constructed. */ STARTED,
        /** Single PUT or multipart chosen. */ MODE_SELECTED,
        /** Producer bytes accepted. */ BYTES_WRITTEN,
        /** About to upload a part. */ PART_STARTED,
        /** Part acknowledged. */ PART_COMPLETED,
        /** Part upload threw. */ PART_FAILED,
        /** Publication acknowledged. */ COMPLETED,
        /** Local abort completed or attempted. */ ABORTED,
        /** Operation failed. */ FAILED,
        /** Cleanup attempt failed. */ CLEANUP_FAILED
    }
    /** Chosen storage protocol. */
    public enum Mode {
        /** Buffering before protocol selection. */ UNDECIDED,
        /** Single object request. */ SINGLE_PUT,
        /** Multipart protocol. */ MULTIPART
    }
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
    /** Returns event kind.
     * @return event kind
     */
    public Type type() { return type; }
    /** Returns selected protocol.
     * @return selected protocol
     */
    public Mode mode() { return mode; }
    /** Returns local state at event emission.
     * @return local state at event emission
     */
    public UploadState state() { return state; }
    /** Returns accepted producer bytes.
     * @return accepted producer bytes
     */
    public long bytesWritten() { return bytesWritten; }
    /** Returns bytes acknowledged by successful calls.
     * @return bytes acknowledged by successful calls
     */
    public long bytesUploaded() { return bytesUploaded; }
    /** Returns one-based part number, or zero for non-part events.
     * @return one-based part number, or zero for non-part events
     */
    public int partNumber() { return partNumber; }
    /** Returns nanoseconds since stream construction.
     * @return nanoseconds since stream construction
     */
    public long elapsedNanos() { return elapsedNanos; }
}
