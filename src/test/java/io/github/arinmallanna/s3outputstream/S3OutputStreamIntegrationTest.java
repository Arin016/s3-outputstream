package io.github.arinmallanna.s3outputstream;

import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

/**
 * Integration test — uploads to real S3 and verifies correctness.
 *
 * <p>Requires environment variables:
 * <ul>
 *   <li>{@code S3_TEST_BUCKET} — the S3 bucket to upload to</li>
 *   <li>{@code AWS_PROFILE} (optional) — AWS profile name (defaults to "default")</li>
 *   <li>{@code AWS_REGION} (optional) — AWS region (defaults to "us-east-1")</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * S3_TEST_BUCKET=my-bucket AWS_PROFILE=my-profile \
 *   java -cp target/classes:target/test-classes:... S3OutputStreamIntegrationTest
 * </pre>
 */
public class S3OutputStreamIntegrationTest {

    private static final String BUCKET = requireEnv("S3_TEST_BUCKET");
    private static final String PROFILE = System.getenv().getOrDefault("AWS_PROFILE", "default");
    private static final String REGION = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
    private static final String KEY_PREFIX = "test/s3-outputstream-integ/";
    private static final int PART_SIZE = 5 * 1024 * 1024; // 5 MB

    private static String requireEnv(String name) {
        String val = System.getenv(name);
        if (val == null || val.isEmpty()) {
            System.err.println("ERROR: environment variable " + name + " is required.");
            System.err.println("Usage: S3_TEST_BUCKET=my-bucket java ... S3OutputStreamIntegrationTest");
            System.exit(1);
        }
        return val;
    }

    public static void main(String[] args) throws Exception {
        S3Client s3 = S3Client.builder()
                .region(Region.of(REGION))
                .credentialsProvider(ProfileCredentialsProvider.create(PROFILE))
                .build();

        System.out.println("=== S3OutputStream Integration Test ===");
        System.out.println("Bucket: " + BUCKET);
        System.out.println("Profile: " + PROFILE);
        System.out.println("Region: " + REGION);
        System.out.println();

        testSmallUpload(s3);
        testMultipartUpload(s3);
        testEmptyUpload(s3);

        System.out.println("\n✅ ALL INTEGRATION TESTS PASSED");
        s3.close();
    }

    /**
     * Test 1: Small file — should use single PutObject path.
     */
    private static void testSmallUpload(S3Client s3) throws Exception {
        String key = KEY_PREFIX + "small-" + System.currentTimeMillis() + ".bin";
        byte[] data = "Hello from S3OutputStream integration test!".getBytes();

        System.out.print("Test 1: Small upload (PutObject path, " + data.length + " bytes)... ");

        try (S3OutputStream out = S3OutputStream.builder()
                .s3Client(s3).bucket(BUCKET).key(key).build()) {
            out.write(data);
        }

        // Verify
        byte[] downloaded = download(s3, key);
        assertBytesEqual(data, downloaded, "small upload");

        // Cleanup
        delete(s3, key);
        System.out.println("PASS ✓");
    }

    /**
     * Test 2: Large file — should use multipart upload path (>5MB).
     */
    private static void testMultipartUpload(S3Client s3) throws Exception {
        String key = KEY_PREFIX + "multipart-" + System.currentTimeMillis() + ".bin";
        // 11 MB — triggers 2 full parts + 1 MB remainder
        int size = 11 * 1024 * 1024;
        byte[] data = new byte[size];
        new Random(42).nextBytes(data); // deterministic random content
        String expectedMd5 = md5Hex(data);

        System.out.print("Test 2: Multipart upload (" + (size / 1024 / 1024) + " MB, 3 parts)... ");

        try (S3OutputStream out = S3OutputStream.builder()
                .s3Client(s3).bucket(BUCKET).key(key).partSize(PART_SIZE).build()) {
            // Write in chunks to simulate real streaming (not one big write)
            int offset = 0;
            int chunkSize = 64 * 1024; // 64 KB writes
            while (offset < data.length) {
                int len = Math.min(chunkSize, data.length - offset);
                out.write(data, offset, len);
                offset += len;
            }
            // Verify state before close
            assert out.getPartsUploaded() == 2 : "expected 2 parts uploaded before close, got " + out.getPartsUploaded();
        }

        // Download and verify byte-for-byte
        byte[] downloaded = download(s3, key);
        String actualMd5 = md5Hex(downloaded);
        if (!expectedMd5.equals(actualMd5)) {
            throw new AssertionError("MD5 mismatch! expected=" + expectedMd5 + " actual=" + actualMd5);
        }
        assertBytesEqual(data, downloaded, "multipart upload");

        // Cleanup
        delete(s3, key);
        System.out.println("PASS ✓ (MD5: " + expectedMd5.substring(0, 8) + "...)");
    }

    /**
     * Test 3: Empty write — should produce a 0-byte object.
     */
    private static void testEmptyUpload(S3Client s3) throws Exception {
        String key = KEY_PREFIX + "empty-" + System.currentTimeMillis() + ".bin";

        System.out.print("Test 3: Empty upload (0 bytes)... ");

        try (S3OutputStream out = S3OutputStream.builder()
                .s3Client(s3).bucket(BUCKET).key(key).build()) {
            // write nothing
        }

        byte[] downloaded = download(s3, key);
        if (downloaded.length != 0) {
            throw new AssertionError("expected empty object, got " + downloaded.length + " bytes");
        }

        delete(s3, key);
        System.out.println("PASS ✓");
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private static byte[] download(S3Client s3, String key) {
        ResponseBytes<GetObjectResponse> resp = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET).key(key).build());
        return resp.asByteArray();
    }

    private static void delete(S3Client s3, String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).build());
    }

    private static void assertBytesEqual(byte[] expected, byte[] actual, String testName) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(testName + ": byte mismatch! expected.length=" +
                    expected.length + " actual.length=" + actual.length);
        }
    }

    private static String md5Hex(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("MD5").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
