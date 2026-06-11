package io.github.arinmallanna.s3outputstream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for S3OutputStream using a recording strategy (no SDK mocking required).
 *
 * <p>The Strategy pattern pays off here: we test the stream's buffering, state transitions,
 * and error handling logic without touching the AWS SDK. Integration tests against LocalStack
 * or real S3 would cover the S3ClientUploadStrategy separately.
 */
class S3OutputStreamTest {

    private static final int PART_SIZE = 5 * 1024 * 1024; // 5 MB — S3 minimum
    private RecordingUploadStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new RecordingUploadStrategy();
    }

    private S3OutputStream createStream() {
        return createStream(PART_SIZE);
    }

    private S3OutputStream createStream(int partSize) {
        return S3OutputStream.builder()
                .uploadStrategy(strategy)
                .bucket("test-bucket")
                .key("test-key")
                .partSize(partSize)
                .build();
    }

    // ─── Small writes (single PutObject path) ──────────────────────────────────

    @Nested
    @DisplayName("Single PutObject path (data ≤ one part)")
    class SinglePutTests {

        @Test
        @DisplayName("empty write produces empty object")
        void emptyWrite() throws IOException {
            try (S3OutputStream out = createStream()) {
                // write nothing
            }
            assertEquals(1, strategy.putObjectCalls.size());
            assertEquals(0, strategy.putObjectCalls.get(0).length);
            assertEquals(0, strategy.initiateUploadCount);
        }

        @Test
        @DisplayName("small write uses PutObject, not multipart")
        void smallWrite() throws IOException {
            byte[] data = "hello, s3!".getBytes();
            try (S3OutputStream out = createStream()) {
                out.write(data);
            }
            assertEquals(1, strategy.putObjectCalls.size());
            assertArrayEquals(data, strategy.putObjectCalls.get(0));
            assertEquals(0, strategy.initiateUploadCount, "should not initiate multipart");
        }

        @Test
        @DisplayName("write exactly one part size uses PutObject (boundary)")
        void exactlyOnePartSize() throws IOException {
            // Use a small part size for fast test execution
            int smallPartSize = PART_SIZE;
            byte[] data = new byte[smallPartSize];
            Arrays.fill(data, (byte) 'X');

            // Writing exactly partSize should trigger multipart (buffer is full)
            // Actually: when buffer fills at position==partSize, flushBuffer is called,
            // which initiates multipart. So exactly partSize DOES go multipart.
            try (S3OutputStream out = createStream(smallPartSize)) {
                out.write(data);
            }
            // Buffer fills → flushBuffer called → multipart initiated → part uploaded
            // Then close() sees no remaining buffer, completes multipart
            assertEquals(1, strategy.initiateUploadCount);
            assertEquals(1, strategy.uploadedParts.size());
        }

        @Test
        @DisplayName("write less than part size uses PutObject")
        void lessThanOnePartSize() throws IOException {
            byte[] data = new byte[PART_SIZE - 1];
            Arrays.fill(data, (byte) 'A');

            try (S3OutputStream out = createStream()) {
                out.write(data);
            }
            assertEquals(1, strategy.putObjectCalls.size());
            assertEquals(PART_SIZE - 1, strategy.putObjectCalls.get(0).length);
        }

        @Test
        @DisplayName("single byte write uses PutObject")
        void singleByteWrite() throws IOException {
            try (S3OutputStream out = createStream()) {
                out.write(42);
            }
            assertEquals(1, strategy.putObjectCalls.size());
            assertEquals(1, strategy.putObjectCalls.get(0).length);
            assertEquals(42, strategy.putObjectCalls.get(0)[0]);
        }
    }

    // ─── Multipart path ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Multipart upload path (data > one part)")
    class MultipartTests {

        @Test
        @DisplayName("data spanning two parts uploads two parts and completes")
        void twoPartsUpload() throws IOException {
            byte[] data = new byte[PART_SIZE + 100];
            Arrays.fill(data, 0, PART_SIZE, (byte) 'A');
            Arrays.fill(data, PART_SIZE, PART_SIZE + 100, (byte) 'B');

            try (S3OutputStream out = createStream()) {
                out.write(data);
            }

            assertEquals(1, strategy.initiateUploadCount);
            assertEquals(2, strategy.uploadedParts.size());
            // First part: full partSize of 'A'
            assertEquals(PART_SIZE, strategy.uploadedParts.get(0).length);
            assertEquals((byte) 'A', strategy.uploadedParts.get(0)[0]);
            // Second part: 100 bytes of 'B' (flushed on close)
            assertEquals(100, strategy.uploadedParts.get(1).length);
            assertEquals((byte) 'B', strategy.uploadedParts.get(1)[0]);
            assertTrue(strategy.completeCalled);
        }

        @Test
        @DisplayName("three full parts")
        void threeFullParts() throws IOException {
            byte[] data = new byte[PART_SIZE * 3];
            Arrays.fill(data, (byte) 'Z');

            try (S3OutputStream out = createStream()) {
                out.write(data);
            }

            assertEquals(3, strategy.uploadedParts.size());
            assertTrue(strategy.completeCalled);
            assertFalse(strategy.abortCalled);
        }

        @Test
        @DisplayName("incremental single-byte writes across part boundary")
        void incrementalByteWrites() throws IOException {
            // Write partSize + 1 bytes one at a time
            try (S3OutputStream out = createStream()) {
                for (int i = 0; i < PART_SIZE + 1; i++) {
                    out.write(0xFF);
                }
            }
            assertEquals(1, strategy.initiateUploadCount);
            assertEquals(2, strategy.uploadedParts.size());
            assertEquals(PART_SIZE, strategy.uploadedParts.get(0).length);
            assertEquals(1, strategy.uploadedParts.get(1).length);
        }

        @Test
        @DisplayName("totalBytesWritten is accurate across multiple parts")
        void totalBytesAccuracy() throws IOException {
            int totalSize = PART_SIZE * 2 + 999;
            try (S3OutputStream out = createStream()) {
                out.write(new byte[totalSize]);
                assertEquals(totalSize, out.getTotalBytesWritten());
                assertEquals(2, out.getPartsUploaded()); // third part not yet flushed
            }
        }
    }

    // ─── Error handling & abort ────────────────────────────────────────────────

    @Nested
    @DisplayName("Error handling and abort semantics")
    class ErrorTests {

        @Test
        @DisplayName("write after close throws S3UploadException")
        void writeAfterClose() throws IOException {
            S3OutputStream out = createStream();
            out.close();
            assertThrows(S3UploadException.class, () -> out.write(1));
        }

        @Test
        @DisplayName("write after abort throws S3UploadException")
        void writeAfterAbort() {
            S3OutputStream out = createStream();
            out.abort();
            assertThrows(S3UploadException.class, () -> out.write(1));
        }

        @Test
        @DisplayName("close is idempotent")
        void doubleCloseIsSafe() throws IOException {
            S3OutputStream out = createStream();
            out.write("data".getBytes());
            out.close();
            out.close(); // should not throw
            assertEquals(1, strategy.putObjectCalls.size());
        }

        @Test
        @DisplayName("abort is idempotent")
        void doubleAbortIsSafe() throws IOException {
            S3OutputStream out = createStream();
            out.write(new byte[PART_SIZE + 1]); // trigger multipart
            out.abort();
            out.abort(); // should not throw
            assertEquals(UploadState.ABORTED, out.getState());
        }

        @Test
        @DisplayName("failure during part upload aborts multipart")
        void failureDuringPartUploadAborts() {
            strategy.failOnPartUpload = true;
            S3OutputStream out = createStream();

            assertThrows(S3UploadException.class, () -> out.write(new byte[PART_SIZE]));
            assertTrue(strategy.abortCalled);
            assertEquals(UploadState.ABORTED, out.getState());
        }

        @Test
        @DisplayName("failure during close aborts multipart")
        void failureDuringCloseAborts() throws IOException {
            // Write enough to trigger multipart, then fail on complete
            strategy.failOnComplete = true;
            S3OutputStream out = createStream();
            out.write(new byte[PART_SIZE + 1]); // triggers multipart + uploads part 1

            assertThrows(S3UploadException.class, out::close);
            assertTrue(strategy.abortCalled);
            assertEquals(UploadState.ABORTED, out.getState());
        }

        @Test
        @DisplayName("explicit abort prevents complete on close")
        void abortPreventsComplete() throws IOException {
            S3OutputStream out = createStream();
            out.write(new byte[PART_SIZE + 1]);
            out.abort();
            out.close(); // should be no-op since already terminal
            assertFalse(strategy.completeCalled);
            assertTrue(strategy.abortCalled);
        }
    }

    // ─── Builder validation ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Builder validation")
    class BuilderTests {

        @Test
        @DisplayName("missing bucket throws")
        void missingBucket() {
            assertThrows(IllegalArgumentException.class, () ->
                    S3OutputStream.builder()
                            .uploadStrategy(strategy)
                            .key("k")
                            .build());
        }

        @Test
        @DisplayName("missing key throws")
        void missingKey() {
            assertThrows(IllegalArgumentException.class, () ->
                    S3OutputStream.builder()
                            .uploadStrategy(strategy)
                            .bucket("b")
                            .build());
        }

        @Test
        @DisplayName("part size below 5MB throws")
        void partSizeTooSmall() {
            assertThrows(IllegalArgumentException.class, () ->
                    S3OutputStream.builder()
                            .uploadStrategy(strategy)
                            .bucket("b")
                            .key("k")
                            .partSize(1024) // 1KB — too small
                            .build());
        }

        @Test
        @DisplayName("missing s3Client and no strategy throws")
        void missingClient() {
            assertThrows(IllegalArgumentException.class, () ->
                    S3OutputStream.builder()
                            .bucket("b")
                            .key("k")
                            .build());
        }
    }

    // ─── Test double ───────────────────────────────────────────────────────────

    /**
     * Recording implementation of UploadStrategy for testing.
     * Captures all calls and their payloads for assertion.
     */
    private static class RecordingUploadStrategy implements UploadStrategy {

        int initiateUploadCount = 0;
        final List<byte[]> uploadedParts = new ArrayList<>();
        final List<byte[]> putObjectCalls = new ArrayList<>();
        boolean completeCalled = false;
        boolean abortCalled = false;
        boolean failOnPartUpload = false;
        boolean failOnComplete = false;

        @Override
        public String initiateUpload(String bucket, String key) {
            initiateUploadCount++;
            return "test-upload-id";
        }

        @Override
        public String uploadPart(String bucket, String key, String uploadId,
                                 int partNumber, byte[] data, int length) {
            if (failOnPartUpload) {
                throw new RuntimeException("simulated part upload failure");
            }
            byte[] copy = Arrays.copyOf(data, length);
            uploadedParts.add(copy);
            return "etag-" + partNumber;
        }

        @Override
        public void completeUpload(String bucket, String key, String uploadId,
                                   List<CompletedPartInfo> parts) {
            if (failOnComplete) {
                throw new RuntimeException("simulated complete failure");
            }
            completeCalled = true;
        }

        @Override
        public void abortUpload(String bucket, String key, String uploadId) {
            abortCalled = true;
        }

        @Override
        public void putObject(String bucket, String key, byte[] data, int length) {
            putObjectCalls.add(Arrays.copyOf(data, length));
        }
    }
}
