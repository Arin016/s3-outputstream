package io.github.arinmallanna.s3outputstream;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Concrete {@link UploadStrategy} backed by the synchronous AWS S3Client.
 *
 * <p><b>SRP:</b> This class's sole responsibility is translating domain upload
 * operations into AWS SDK API calls. It holds no buffering logic, state management,
 * or stream semantics — that is S3OutputStream's job.
 *
 * <p><b>Testability:</b> S3OutputStream can be tested with a mock UploadStrategy
 * without needing to mock the S3Client directly, eliminating brittle SDK-level mocking.
 */
final class S3ClientUploadStrategy implements UploadStrategy {

    private final S3Client s3Client;

    S3ClientUploadStrategy(S3Client s3Client) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
    }

    @Override
    public String initiateUpload(String bucket, String key) {
        CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        return s3Client.createMultipartUpload(request).uploadId();
    }

    @Override
    public String uploadPart(String bucket, String key, String uploadId,
                             int partNumber, byte[] data, int length) {
        UploadPartRequest request = UploadPartRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .contentLength((long) length)
                .build();

        // Copy only the valid bytes to avoid sending garbage beyond `length`
        byte[] payload = (length == data.length) ? data : copyOf(data, length);
        return s3Client.uploadPart(request, RequestBody.fromBytes(payload)).eTag();
    }

    @Override
    public void completeUpload(String bucket, String key, String uploadId,
                               List<CompletedPartInfo> parts) {
        List<CompletedPart> sdkParts = parts.stream()
                .map(p -> CompletedPart.builder()
                        .partNumber(p.partNumber())
                        .eTag(p.eTag())
                        .build())
                .collect(Collectors.toList());

        s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(sdkParts).build())
                .build());
    }

    @Override
    public void abortUpload(String bucket, String key, String uploadId) {
        s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .build());
    }

    @Override
    public void putObject(String bucket, String key, byte[] data, int length) {
        byte[] payload = (length == data.length) ? data : copyOf(data, length);
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentLength((long) length)
                        .build(),
                RequestBody.fromBytes(payload));
    }

    private static byte[] copyOf(byte[] src, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(src, 0, copy, 0, length);
        return copy;
    }
}
