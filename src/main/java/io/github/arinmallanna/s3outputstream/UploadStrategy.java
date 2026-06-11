package io.github.arinmallanna.s3outputstream;

/**
 * Strategy interface for S3 upload execution.
 *
 * <p><b>Design Pattern:</b> Strategy — decouples the upload mechanism from the
 * stream buffering logic. Enables testing with a mock implementation, and allows
 * future extension (e.g., an async variant) without modifying the OutputStream.
 *
 * <p><b>SOLID:</b> ISP (clients depend only on upload behavior, not the full S3Client),
 * DIP (S3OutputStream depends on this abstraction, not on S3Client directly).
 */
interface UploadStrategy {

    /**
     * Initiates a multipart upload and returns the upload ID.
     */
    String initiateUpload(String bucket, String key);

    /**
     * Uploads a single part and returns its ETag.
     *
     * @param bucket    target bucket
     * @param key       target object key
     * @param uploadId  the multipart upload ID
     * @param partNumber sequential part number (1-based)
     * @param data      part content (exactly {@code length} bytes from offset 0)
     * @param length    number of valid bytes in {@code data}
     * @return the ETag of the uploaded part
     */
    String uploadPart(String bucket, String key, String uploadId, int partNumber, byte[] data, int length);

    /**
     * Completes the multipart upload, assembling all parts into the final object.
     */
    void completeUpload(String bucket, String key, String uploadId, java.util.List<CompletedPartInfo> parts);

    /**
     * Aborts the multipart upload, cleaning up any uploaded parts on S3.
     */
    void abortUpload(String bucket, String key, String uploadId);

    /**
     * Uploads a complete object in a single PUT (used when total data fits in one part).
     * Avoids multipart overhead for small writes.
     */
    void putObject(String bucket, String key, byte[] data, int length);
}
