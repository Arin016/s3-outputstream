package io.github.arinmallanna.s3outputstream;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Regression: resource cleanup must not turn producer failure into publication. */
class ProducerFailureRegressionTest {
    @Test
    void producerFailureBeforeMultipartDoesNotPublish() {
        verifyProducerFailure(3);
    }

    @Test
    void producerFailureAfterMultipartDoesNotPublish() {
        verifyProducerFailure(5 * 1024 * 1024 + 1);
    }

    private void verifyProducerFailure(int size) {
        PublicationRecorder store = new PublicationRecorder();
        IOException producerFailure = new IOException("producer failed");
        IOException actual = assertThrows(IOException.class, () -> {
            try (S3OutputStream out = S3OutputStream.builder().uploadStrategy(store)
                    .bucket("test-bucket").key("test-key").build()) {
                out.write(new byte[size]);
                throw producerFailure;
            }
        });
        assertSame(producerFailure, actual);
        assertFalse(store.published, "close() published a failed producer's partial output");
        assertEquals(size > 5 * 1024 * 1024 ? 1 : 0, store.aborts);
    }

    private static final class PublicationRecorder implements UploadStrategy {
        boolean published;
        int aborts;
        public String initiateUpload(String bucket, String key) { return "test-upload"; }
        public String uploadPart(String bucket, String key, String id, int n, byte[] data, int length) {
            return "etag-" + n;
        }
        public void completeUpload(String bucket, String key, String id, List<CompletedPartInfo> parts) {
            published = true;
        }
        public void abortUpload(String bucket, String key, String id) { aborts++; }
        public void putObject(String bucket, String key, byte[] data, int length) { published = true; }
    }
}
