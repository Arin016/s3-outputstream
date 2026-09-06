package io.github.arinmallanna.s3outputstream;

import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * Separately authorized real-S3 manual program; never discovered as a JUnit test.
 * Requires S3_REAL_TEST_AUTHORIZED=YES, S3_TEST_BUCKET, S3_TEST_PREFIX (starting
 * s3-outputstream-test/), AWS_PROFILE and AWS_REGION. It refuses versioned buckets,
 * uses a unique run prefix, streams synthetic content and always attempts cleanup.
 * Do not execute without explicit owner authorization. Console output is sanitized.
 */
public final class S3OutputStreamIntegrationTest {
    private S3OutputStreamIntegrationTest() { }

    public static void main(String[] args) {
        try {
            run();
            System.out.println("Real-S3 manual verification: PASS; exact test objects and multipart uploads removed.");
        } catch (Throwable error) {
            // SDK messages/stack traces may contain destinations or credential context.
            System.err.println("Real-S3 manual verification: FAIL (" + error.getClass().getSimpleName()
                    + "); secondary failures=" + error.getSuppressed().length
                    + ". Check the explicit gate/configuration and inspect cleanup privately.");
            System.exit(1);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing explicit test configuration");
        return value;
    }

    private static void run() throws Throwable {
        if (!"YES".equals(System.getenv("S3_REAL_TEST_AUTHORIZED"))) {
            throw new IllegalStateException("Explicit real-S3 authorization gate is closed");
        }
        String bucket = required("S3_TEST_BUCKET");
        String prefix = required("S3_TEST_PREFIX");
        if (!prefix.startsWith("s3-outputstream-test/") || !prefix.endsWith("/") || prefix.length() > 800) {
            throw new IllegalArgumentException("A dedicated disposable test prefix is required");
        }
        String runPrefix = prefix + UUID.randomUUID() + "/";
        try (ProfileCredentialsProvider credentials = ProfileCredentialsProvider.create(required("AWS_PROFILE"));
             S3Client client = S3Client.builder().region(Region.of(required("AWS_REGION")))
                     .credentialsProvider(credentials)
                     .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60))
                             .apiCallAttemptTimeout(Duration.ofSeconds(20))
                             .retryPolicy(RetryPolicy.builder().numRetries(2).build())).build()) {
            if (client.getBucketVersioning(r -> r.bucket(bucket)).status() != null) {
                throw new IllegalStateException("Manual test requires a never-versioned disposable bucket");
            }
            List<String> keys = new ArrayList<>();
            Throwable failure = null;
            try {
                for (long size : new long[]{0, 1024, 11L * 1024 * 1024}) {
                    String key = runPrefix + size + ".bin"; keys.add(key);
                    MessageDigest expected = MessageDigest.getInstance("SHA-256");
                    generate(new DigestOutputStream(OutputStream.nullOutputStream(), expected), size);
                    S3OutputStream.upload(S3OutputStream.builder().s3Client(client).bucket(bucket).key(key)
                            .expectedLength(size).contentType("application/octet-stream"), out -> generate(out, size));
                    MessageDigest actual = MessageDigest.getInstance("SHA-256"); long received = 0;
                    try (InputStream in = client.getObject(r -> r.bucket(bucket).key(key))) {
                        byte[] chunk = new byte[65536]; int n;
                        while ((n = in.read(chunk)) != -1) { actual.update(chunk, 0, n); received += n; }
                    }
                    if (received != size || !Arrays.equals(expected.digest(), actual.digest())) {
                        throw new AssertionError("Synthetic object length/hash mismatch");
                    }
                }
            } catch (Throwable error) { failure = error; }
            finally {
                // Only this run's generated keys/prefix are eligible for cleanup.
                for (String key : keys) {
                    try { client.deleteObject(r -> r.bucket(bucket).key(key)); }
                    catch (Throwable cleanup) { failure = append(failure, cleanup); }
                }
                try {
                    for (ListMultipartUploadsResponse page : client.listMultipartUploadsPaginator(
                            r -> r.bucket(bucket).prefix(runPrefix))) {
                        for (MultipartUpload upload : page.uploads()) {
                            client.abortMultipartUpload(r -> r.bucket(bucket).key(upload.key()).uploadId(upload.uploadId()));
                        }
                    }
                    if (!client.listObjectsV2(r -> r.bucket(bucket).prefix(runPrefix)).contents().isEmpty()
                            || !client.listMultipartUploads(r -> r.bucket(bucket).prefix(runPrefix)).uploads().isEmpty()) {
                        throw new AssertionError("Test cleanup incomplete");
                    }
                } catch (Throwable cleanup) { failure = append(failure, cleanup); }
            }
            if (failure != null) throw failure;
        }
    }

    private static Throwable append(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != primary) primary.addSuppressed(secondary);
        return primary;
    }

    private static void generate(OutputStream out, long size) throws java.io.IOException {
        Random random = new Random(42); byte[] chunk = new byte[65536];
        for (long written = 0; written < size;) {
            random.nextBytes(chunk); int n = (int) Math.min(chunk.length, size - written);
            out.write(chunk, 0, n); written += n;
        }
    }
}
