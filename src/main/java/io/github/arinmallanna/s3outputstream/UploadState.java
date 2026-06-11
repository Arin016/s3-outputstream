package io.github.arinmallanna.s3outputstream;

/**
 * Represents the lifecycle state of an S3OutputStream upload.
 *
 * <p>Enforces valid state transitions and eliminates the scattered boolean flags
 * (closed, aborted, multipartInitiated) anti-pattern.
 */
enum UploadState {

    /** Stream is open and accepting writes. No multipart initiated yet. */
    BUFFERING,

    /** Multipart upload initiated; parts are being uploaded as buffer fills. */
    MULTIPART_IN_PROGRESS,

    /** Upload completed successfully. Terminal state. */
    COMPLETED,

    /** Upload was aborted (either explicitly or due to error). Terminal state. */
    ABORTED;

    boolean isTerminal() {
        return this == COMPLETED || this == ABORTED;
    }

    boolean acceptsWrites() {
        return this == BUFFERING || this == MULTIPART_IN_PROGRESS;
    }
}
