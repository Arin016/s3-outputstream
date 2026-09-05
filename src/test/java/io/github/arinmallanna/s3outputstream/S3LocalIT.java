package io.github.arinmallanna.s3outputstream;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Wire-level integration with a pinned local Moto service, never real AWS. */
class S3LocalIT {
    static URI endpoint;
    static S3Client client;
    static final int P = 5 * 1024 * 1024;
    String key;

    @BeforeAll static void connect() throws Exception {
        endpoint = LocalS3Support.endpoint(System.getProperty("s3.localEndpoint", ""));
        client = LocalS3Support.client(endpoint, 0);
        client.createBucket(r -> r.bucket(LocalS3Support.BUCKET));
    }
    @AfterAll static void disconnect() { if (client != null) client.close(); }
    @BeforeEach void initialize() throws Exception {
        LocalS3Support.configure(endpoint, "{}"); key = "integration/" + UUID.randomUUID();
    }
    @AfterEach void cleanup() throws Exception {
        LocalS3Support.configure(endpoint, "{}");
        client.deleteObject(r -> r.bucket(LocalS3Support.BUCKET).key(key));
        for (MultipartUpload upload : client.listMultipartUploads(r -> r.bucket(LocalS3Support.BUCKET)).uploads()) {
            if (key.equals(upload.key())) client.abortMultipartUpload(r -> r.bucket(LocalS3Support.BUCKET).key(key).uploadId(upload.uploadId()));
        }
        assertAbsent();
        assertTrue(client.listMultipartUploads(r -> r.bucket(LocalS3Support.BUCKET)).uploads().stream().noneMatch(u -> key.equals(u.key())));
    }
    S3OutputStream.Builder builder(S3Client s3) {
        return S3OutputStream.builder().s3Client(s3).bucket(LocalS3Support.BUCKET).key(key);
    }
    void assertAbsent() {
        S3Exception failure = assertThrows(S3Exception.class, () -> client.headObject(r -> r.bucket(LocalS3Support.BUCKET).key(key)));
        assertEquals(404, failure.statusCode());
    }
    byte[] downloadHash() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] chunk = new byte[65536];
        try (ResponseInputStream<GetObjectResponse> in = client.getObject(r -> r.bucket(LocalS3Support.BUCKET).key(key))) {
            int n; while ((n = in.read(chunk)) != -1) digest.update(chunk, 0, n);
        }
        return digest.digest();
    }
    byte[] uploadGenerated(long size, S3OutputStream.Builder configuration) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] chunk = new byte[65536]; new Random(20260905).nextBytes(chunk);
        S3OutputStream.upload(configuration, out -> {
            long remaining = size;
            while (remaining > 0) {
                int n = (int) Math.min(remaining, chunk.length); out.write(chunk, 0, n); digest.update(chunk, 0, n); remaining -= n;
            }
        });
        return digest.digest();
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, P - 1, P, P + 1, 11 * 1024 * 1024})
    void smallBoundaryAndMultipartSuccess(int size) throws Exception {
        byte[] expected = uploadGenerated(size, builder(client).contentType("application/octet-stream").metadata(Map.of("fixture", "synthetic")));
        String requests = LocalS3Support.stats(endpoint);
        assertArrayEquals(expected, downloadHash());
        HeadObjectResponse head = client.headObject(r -> r.bucket(LocalS3Support.BUCKET).key(key));
        assertEquals(size, head.contentLength()); assertEquals("synthetic", head.metadata().get("fixture"));
        assertEquals("application/octet-stream", head.contentType());
        assertEquals(size <= P ? 1 : 0, count(requests, "put"));
        assertEquals(size <= P ? 0 : 1, count(requests, "create"));
        assertEquals(size <= P ? 0 : (size + P - 1) / P, count(requests, "part"));
        assertEquals(size <= P ? 0 : 1, count(requests, "complete"));
        assertTrue(client.listMultipartUploads(r -> r.bucket(LocalS3Support.BUCKET)).uploads().isEmpty());
    }

    @ParameterizedTest @ValueSource(ints = {3, P + 1})
    void producerFailureLeavesNoFinalObject(int size) throws Exception {
        IOException failure = new IOException("synthetic producer failure");
        assertSame(failure, assertThrows(IOException.class, () -> S3OutputStream.upload(builder(client), out -> {
            byte[] chunk = new byte[65536]; int remaining = size;
            while (remaining > 0) { int n = Math.min(remaining, chunk.length); out.write(chunk, 0, n); remaining -= n; }
            throw failure;
        })));
        String requests = LocalS3Support.stats(endpoint); assertAbsent();
        assertEquals(size > P ? 1 : 0, count(requests, "abort"));
        assertEquals(0, count(requests, "complete")); assertEquals(0, count(requests, "put"));
        assertTrue(client.listMultipartUploads(r -> r.bucket(LocalS3Support.BUCKET)).uploads().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"create", "part", "complete", "put"})
    void serviceFailureDoesNotPublishAndCleansMultipart(String operation) throws Exception {
        LocalS3Support.configure(endpoint, "{\"failures\":{\"" + operation + "\":1}}");
        S3OutputStream out = builder(client).build();
        assertThrows(IOException.class, () -> {
            out.write(new byte[operation.equals("put") ? 1 : P + 1]); out.commit();
        });
        assertEquals(UploadState.FAILED, out.getState()); LifecycleTest.assertTerminalReleased(out); assertAbsent();
        assertTrue(client.listMultipartUploads(r -> r.bucket(LocalS3Support.BUCKET)).uploads().isEmpty());
    }

    @Test void retryReplaysExactBufferAndPreservesHash() throws Exception {
        LocalS3Support.configure(endpoint, "{\"failures\":{\"part\":1}}");
        try (S3Client retrying = LocalS3Support.client(endpoint, 2)) {
            byte[] expected = uploadGenerated(P + 19, builder(retrying));
            assertEquals(3, count(LocalS3Support.stats(endpoint), "part"));
            assertArrayEquals(expected, downloadHash());
        }
    }

    @Test void failedAbortLeavesObservableOrphanUntilExplicitCleanup() throws Exception {
        LocalS3Support.configure(endpoint, "{\"failures\":{\"part\":1,\"abort\":1}}");
        S3OutputStream out = builder(client).build();
        IOException failure = assertThrows(IOException.class, () -> out.write(new byte[P + 1]));
        assertEquals(1, failure.getSuppressed().length); assertAbsent(); LifecycleTest.assertTerminalReleased(out);
        assertEquals(1, client.listMultipartUploads(r -> r.bucket(LocalS3Support.BUCKET)).uploads().size());
        // afterEach restores service and explicitly removes this known orphan.
    }

    @Test void lostCompletionAcknowledgementDoesNotPromiseAbsence() throws Exception {
        LocalS3Support.configure(endpoint, "{\"lose_complete_response\":true}");
        S3OutputStream out = builder(client).build(); out.write(new byte[P + 1]);
        assertThrows(IOException.class, out::commit);
        assertEquals(UploadState.FAILED, out.getState());
        assertEquals(P + 1, client.headObject(r -> r.bucket(LocalS3Support.BUCKET).key(key)).contentLength());
        LifecycleTest.assertTerminalReleased(out);
    }

    @Test void largeLogicalStreamDoesNotAllocateWholePayload() throws Exception {
        long size = 128L * 1024 * 1024;
        byte[] expected = uploadGenerated(size, builder(client).expectedLength(size));
        assertEquals(size, client.headObject(r -> r.bucket(LocalS3Support.BUCKET).key(key)).contentLength());
        assertArrayEquals(expected, downloadHash());
    }

    static int count(String stats, String operation) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("\"" + operation + "\"\\s*:\\s*(\\d+)").matcher(stats);
        return match.find() ? Integer.parseInt(match.group(1)) : 0;
    }
}
