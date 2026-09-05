package io.github.arinmallanna.s3outputstream;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Test/benchmark endpoint policy. Never consults AWS profiles or default credentials. */
public final class LocalS3Support {
    public static final String BUCKET = "s3os-disposable-local-fixture";
    private LocalS3Support() { }

    public static URI endpoint(String value) throws IOException {
        URI uri = URI.create(value);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() <= 0
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("Local fixtures require an explicit loopback HTTP endpoint");
        }
        if (!control(uri, "/__s3os_health", null).contains("s3os-local-only-v1")) {
            throw new IllegalArgumentException("Endpoint is not the dedicated local fixture");
        }
        return uri;
    }

    public static S3Client client(URI endpoint, int retries) {
        return S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local-only", "local-only")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).expectContinueEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60))
                        .apiCallAttemptTimeout(Duration.ofSeconds(20)).retryPolicy(RetryPolicy.builder().numRetries(retries).build()))
                .build();
    }

    public static String configure(URI endpoint, String json) throws IOException {
        return control(endpoint, "/__s3os_control", json);
    }
    public static String stats(URI endpoint) throws IOException {
        return control(endpoint, "/__s3os_control", null);
    }
    private static String control(URI endpoint, String path, String json) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) endpoint.resolve(path).toURL().openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
        try {
            if (json != null) {
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(data.length);
                connection.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(data); }
            }
            if (connection.getResponseCode() != 200) throw new IOException("Local fixture control failed");
            try (java.io.InputStream in = connection.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        } finally { connection.disconnect(); }
    }
}
