package io.github.arinmallanna.s3outputstream;

/** Local upload lifecycle; it does not prove remote absence after an ambiguous failure. */
public enum UploadState {
    /** Accepting bytes; no multipart upload has been initiated. */
    BUFFERING,
    /** Accepting bytes; a multipart upload ID is available. */
    MULTIPART_IN_PROGRESS,
    /** Publication call returned successfully. Terminal. */
    COMPLETED,
    /** Explicitly aborted locally; remote cleanup was attempted when possible. Terminal. */
    ABORTED,
    /** An operation failed; remote publication or cleanup may be uncertain. Terminal. */
    FAILED;

    /** @return whether no further writes or publication attempts are allowed */
    public boolean isTerminal() {
        return this == COMPLETED || this == ABORTED || this == FAILED;
    }

    /** @return whether producer bytes may be accepted */
    public boolean acceptsWrites() {
        return this == BUFFERING || this == MULTIPART_IN_PROGRESS;
    }
}
