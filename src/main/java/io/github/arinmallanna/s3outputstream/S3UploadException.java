package io.github.arinmallanna.s3outputstream;

import java.io.IOException;

/**
 * Thrown when an S3 upload operation fails.
 *
 * <p>Wraps the underlying cause (typically an SDK exception) with context
 * through explicit bucket/key getters. Default messages omit destination identifiers.
 * The underlying SDK cause may contain identifiers; sanitize it before logging.
 */
public final class S3UploadException extends IOException {

    private static final long serialVersionUID = 1L;

    /** Destination bucket retained in the serialized exception. */
    private final String bucket;
    /** Destination key retained in the serialized exception. */
    private final String key;

    /** Creates an upload exception with explicitly accessible destination context.
     * @param bucket destination retained for explicit inspection
     * @param key destination retained for explicit inspection
     * @param message identifier-free operation summary
     * @param cause underlying failure; may contain sensitive SDK context
     */
    public S3UploadException(String bucket, String key, String message, Throwable cause) {
        super(formatMessage(bucket, key, message), cause);
        this.bucket = bucket;
        this.key = key;
    }

    /** Creates an upload exception with explicitly accessible destination context.
     * @param bucket destination retained for explicit inspection
     * @param key destination retained for explicit inspection
     * @param message identifier-free operation summary
     */
    public S3UploadException(String bucket, String key, String message) {
        super(formatMessage(bucket, key, message));
        this.bucket = bucket;
        this.key = key;
    }

    /** Returns destination bucket; sanitize before logging.
     * @return destination bucket; sanitize before logging
     */
    public String getBucket() {
        return bucket;
    }

    /** Returns destination key; sanitize before logging.
     * @return destination key; sanitize before logging
     */
    public String getKey() {
        return key;
    }

    private static String formatMessage(String bucket, String key, String message) {
        return "S3 upload failed: " + message;
    }
}
