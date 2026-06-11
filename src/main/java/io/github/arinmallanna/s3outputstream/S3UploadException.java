package io.github.arinmallanna.s3outputstream;

import java.io.IOException;

/**
 * Thrown when an S3 upload operation fails.
 *
 * <p>Wraps the underlying cause (typically an SDK exception) with context
 * about which bucket/key the failure occurred on, enabling meaningful
 * error messages without leaking SDK internals to callers.
 */
public final class S3UploadException extends IOException {

    private final String bucket;
    private final String key;

    public S3UploadException(String bucket, String key, String message, Throwable cause) {
        super(formatMessage(bucket, key, message), cause);
        this.bucket = bucket;
        this.key = key;
    }

    public S3UploadException(String bucket, String key, String message) {
        super(formatMessage(bucket, key, message));
        this.bucket = bucket;
        this.key = key;
    }

    public String getBucket() {
        return bucket;
    }

    public String getKey() {
        return key;
    }

    private static String formatMessage(String bucket, String key, String message) {
        return "S3 upload failed [s3://" + bucket + "/" + key + "]: " + message;
    }
}
