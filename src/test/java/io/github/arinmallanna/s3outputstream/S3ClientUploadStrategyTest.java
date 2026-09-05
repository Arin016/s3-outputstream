package io.github.arinmallanna.s3outputstream;

import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3ClientUploadStrategyTest {
    @Test void putBodyHasExactLengthAndCanBeReopenedForRetry() {
        S3Client client = mock(S3Client.class, withSettings().mockMaker(MockMakers.PROXY));
        Map<String, String> metadata = new HashMap<>(); metadata.put("kind", "test");
        S3ClientUploadStrategy strategy = new S3ClientUploadStrategy(client, "text/csv", metadata);
        metadata.put("kind", "changed");
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0); RequestBody body = call.getArgument(1);
            assertEquals("text/csv", request.contentType()); assertEquals(Map.of("kind", "test"), request.metadata());
            assertEquals(3L, request.contentLength()); assertEquals(3L, body.optionalContentLength().orElseThrow());
            try (InputStream first = body.contentStreamProvider().newStream();
                 InputStream retry = body.contentStreamProvider().newStream()) {
                assertEquals(1, first.read());
                assertArrayEquals(new byte[]{1, 2, 3}, retry.readAllBytes());
                assertArrayEquals(new byte[]{2, 3}, first.readAllBytes());
            }
            return PutObjectResponse.builder().build();
        });
        strategy.putObject("test-bucket", "test-key", new byte[]{1, 2, 3, 99}, 3);
        verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(client, never()).close();
    }

    @Test void multipartRequestsCarryMetadataAndOrderedReceipts() {
        S3Client client = mock(S3Client.class, withSettings().mockMaker(MockMakers.PROXY));
        S3ClientUploadStrategy strategy = new S3ClientUploadStrategy(client, "application/zip", Map.of("kind", "test"));
        when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class))).thenAnswer(call -> {
            CreateMultipartUploadRequest request = call.getArgument(0);
            assertEquals("application/zip", request.contentType()); assertEquals("test", request.metadata().get("kind"));
            return CreateMultipartUploadResponse.builder().uploadId("local-upload").build();
        });
        assertEquals("local-upload", strategy.initiateUpload("test-bucket", "test-key"));
        when(client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            UploadPartRequest request = call.getArgument(0); RequestBody body = call.getArgument(1);
            assertEquals(2, request.partNumber()); assertEquals(1L, request.contentLength());
            assertEquals("local-upload", request.uploadId());
            for (int attempt = 0; attempt < 2; attempt++) {
                try (InputStream stream = body.contentStreamProvider().newStream()) {
                    assertArrayEquals(new byte[]{42}, stream.readAllBytes());
                }
            }
            return UploadPartResponse.builder().eTag("etag-2").build();
        });
        assertEquals("etag-2", strategy.uploadPart("test-bucket", "test-key", "local-upload", 2, new byte[]{42, 99}, 1));
        when(client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class))).thenAnswer(call -> {
            CompleteMultipartUploadRequest request = call.getArgument(0);
            assertEquals(1, request.multipartUpload().parts().get(0).partNumber());
            assertEquals(2, request.multipartUpload().parts().get(1).partNumber());
            return CompleteMultipartUploadResponse.builder().build();
        });
        strategy.completeUpload("test-bucket", "test-key", "local-upload", List.of(
                new CompletedPartInfo(1, "etag-1"), new CompletedPartInfo(2, "etag-2")));
        strategy.abortUpload("test-bucket", "test-key", "local-upload");
        verify(client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        verify(client, never()).close();
    }
}
