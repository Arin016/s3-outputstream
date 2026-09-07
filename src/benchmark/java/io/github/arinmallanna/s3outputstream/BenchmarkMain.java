package io.github.arinmallanna.s3outputstream;

import edu.colorado.cires.cmg.s3out.AwsS3ClientMultipartUpload;
import edu.colorado.cires.cmg.s3out.MultipartUploadRequest;
import edu.colorado.cires.cmg.s3out.NoContentTypeResolver;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.BlockingOutputStreamAsyncRequestBody;
import software.amazon.awssdk.core.async.BufferedSplittableAsyncRequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.utils.CancellableOutputStream;

import java.io.*;
import java.lang.management.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Controlled local-service experiment driver, isolated from the distributed library JAR. */
public final class BenchmarkMain {
    static final int MIB = 1024 * 1024;
    final Config config;
    final URI endpoint;
    final RequestClock requestClock = new RequestClock();
    S3Client sync;
    S3AsyncClient async;
    URLClassLoader baselineLoader;
    long tempDiskBytes;
    int retainedBufferBytes = -1;
    long retainedMetadataParts = -1;

    BenchmarkMain(Config config) throws Exception {
        this.config = config;
        endpoint = LocalS3Support.endpoint(config.endpoint);
        sync = S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local-only", "local-only")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).expectContinueEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(15))
                        .retryPolicy(RetryPolicy.builder().numRetries(config.retries).build()).addExecutionInterceptor(requestClock))
                .build();
        sync.createBucket(r -> r.bucket(LocalS3Support.BUCKET));
        if (config.adapter.equals("aws-async")) {
            async = S3AsyncClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local-only", "local-only")))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true)
                            .chunkedEncodingEnabled(config.knownLength).expectContinueEnabled(false).build())
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .httpClientBuilder(NettyNioAsyncHttpClient.builder().maxConcurrency(config.asyncConcurrency))
                    .multipartEnabled(true)
                    .multipartConfiguration(m -> m.minimumPartSizeInBytes((long) config.partSize)
                            .thresholdInBytes((long) config.partSize).apiCallBufferSizeInBytes(4L * config.partSize))
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(15))
                            .retryPolicy(RetryPolicy.builder().numRetries(config.retries).build()).addExecutionInterceptor(requestClock))
                    .build();
        }
        if (config.adapter.equals("original-939f2bd")) {
            URL artifact = Path.of(config.baselineJar).toUri().toURL();
            baselineLoader = new URLClassLoader(new URL[]{artifact}, BenchmarkMain.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        if (name.startsWith("io.github.arinmallanna.s3outputstream.")) {
                            Class<?> loaded = findLoadedClass(name);
                            if (loaded == null) loaded = findClass(name);
                            if (resolve) resolveClass(loaded);
                            return loaded;
                        }
                        return super.loadClass(name, resolve);
                    }
                }
            };
        }
    }

    void run() throws Exception {
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        CounterOutput expectedBytes = new CounterOutput(new DigestOutputStream(OutputStream.nullOutputStream(), expected));
        produce(expectedBytes, false, false);
        String expectedHash = hex(expected.digest());
        for (int repetition = -config.warmups; repetition < config.repetitions; repetition++) {
            String id = config.caseId + ":" + config.adapter + ":" + repetition;
            String key = "benchmark/" + UUID.randomUUID();
            LocalS3Support.configure(endpoint, config.fixtureJson());
            tempDiskBytes = 0; retainedBufferBytes = -1; retainedMetadataParts = -1;
            System.gc(); // Prespecified collection request outside each timed interval.
            HeapSampler heap = new HeapSampler();
            long gcCount = gcCount(), gcMillis = gcMillis(), allocated = allocatedMainThread(), cpu = cpuNanos();
            System.out.println("S3OS_RUN_START " + id); System.out.flush();
            requestClock.reset();
            heap.start();
            Throwable failure = null;
            long start = System.nanoTime(); requestClock.start = start;
            try { upload(key); } catch (Throwable e) { failure = unwrap(e); }
            long elapsed = System.nanoTime() - start;
            heap.close();
            long cpuDelta = cpuNanos() - cpu;
            long allocatedDelta = allocatedMainThread() - allocated;
            long gcCountDelta = gcCount() - gcCount, gcMillisDelta = gcMillis() - gcMillis;
            System.out.println("S3OS_RUN_END " + id); System.out.flush();
            if (failure != null) {
                for (Throwable error = failure; error != null; error = error.getCause()) {
                    String detail = String.valueOf(error.getMessage()).replace(endpoint.toString(), "LOCAL_FIXTURE")
                            .replace(LocalS3Support.BUCKET, "LOCAL_BUCKET").replace(key, "LOCAL_KEY");
                    System.err.println("BENCHMARK_ERROR " + error.getClass().getName() + ": " + detail);
                }
            }
            if (failure != null) {
                for (Throwable secondary : failure.getSuppressed()) {
                    String detail = String.valueOf(secondary.getMessage()).replace(endpoint.toString(), "LOCAL_FIXTURE")
                            .replace(LocalS3Support.BUCKET, "LOCAL_BUCKET").replace(key, "LOCAL_KEY");
                    System.err.println("BENCHMARK_SUPPRESSED " + secondary.getClass().getName() + ": " + detail);
                }
            }
            String requestStats = LocalS3Support.stats(endpoint);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("run_id", id); row.put("date_utc", Instant.now().toString());
            row.put("case_id", config.caseId); row.put("adapter", config.adapter);
            row.put("repetition", repetition); row.put("phase", repetition < 0 ? "warmup" : "measurement");
            row.put("workload", config.workload); row.put("input_bytes", config.size);
            row.put("expected_object_bytes", expectedBytes.count); row.put("known_length", config.knownLength);
            row.put("part_size_bytes", config.partSize); row.put("write_chunk_bytes", config.chunk);
            row.put("producer_delay_us_per_chunk", config.producerDelayUs);
            row.put("fixture_latency_ms_per_request", config.latencyMs);
            row.put("fixture_body_delay_mib_per_second_per_request", config.bandwidthMib);
            row.put("ci_cmg_queue_size", config.queue); row.put("async_http_max_concurrency", config.asyncConcurrency);
            row.put("async_api_call_buffer_bytes", 4L * config.partSize); row.put("sdk_retries", config.retries);
            row.put("async_chunked_encoding_enabled", config.adapter.equals("aws-async") && config.knownLength);
            row.put("async_retry_buffer_enabled", config.adapter.equals("aws-async") && config.awsRetryBuffer);
            row.put("fault", config.fault); row.put("failure_after_input_bytes", config.failAfter);
            row.put("elapsed_ns", elapsed);
            row.put("first_storage_request_ns", requestClock.delta(requestClock.firstRequest));
            row.put("first_payload_request_ns", requestClock.delta(requestClock.firstPayload));
            row.put("heap_used_peak_sampled_bytes", heap.peakUsed.get());
            row.put("heap_committed_peak_sampled_bytes", heap.peakCommitted.get());
            row.put("producer_thread_allocated_bytes", allocatedDelta);
            row.put("process_cpu_ns", cpuDelta); row.put("gc_count", gcCountDelta);
            row.put("gc_collection_millis", gcMillisDelta);
            row.put("temporary_payload_file_bytes", tempDiskBytes);
            row.put("sink_retained_buffer_bytes_after_terminal", retainedBufferBytes);
            row.put("sink_retained_receipts_after_terminal", retainedMetadataParts);
            row.put("service_requests_json", requestStats);
            row.put("upload_returned_success", failure == null);
            row.put("error_type", failure == null ? "" : failure.getClass().getName());
            row.put("root_error_type", failure == null ? "" : root(failure).getClass().getName());
            row.put("suppressed_errors", failure == null ? 0 : failure.getSuppressed().length);
            // Verification/administrative cleanup are outside upload time and request counts.
            boolean present = false; String actualHash = ""; long actualSize = -1;
            try {
                HeadObjectResponse head = sync.headObject(r -> r.bucket(LocalS3Support.BUCKET).key(key));
                actualSize = head.contentLength(); present = true;
                MessageDigest hash = MessageDigest.getInstance("SHA-256"); byte[] chunk = new byte[65536];
                try (InputStream in = sync.getObject(r -> r.bucket(LocalS3Support.BUCKET).key(key))) {
                    int n; while ((n = in.read(chunk)) != -1) hash.update(chunk, 0, n);
                }
                actualHash = hex(hash.digest());
            } catch (S3Exception e) { if (e.statusCode() != 404) throw e; }
            List<MultipartUpload> orphans = sync.listMultipartUploads(r -> r.bucket(LocalS3Support.BUCKET)).uploads();
            long ownOrphans = orphans.stream().filter(u -> key.equals(u.key())).count();
            row.put("object_present", present); row.put("actual_object_bytes", actualSize);
            row.put("expected_sha256", expectedHash); row.put("actual_sha256", actualHash);
            row.put("hash_matches_expected", present && expectedHash.equals(actualHash));
            row.put("orphan_uploads_before_harness_cleanup", ownOrphans);
            // Keep faults active through outcome observation: an async adapter may
            // issue its abort after its completion future fails. Clearing faults
            // earlier could accidentally make that delayed abort succeed.
            LocalS3Support.configure(endpoint, "{}");
            for (MultipartUpload orphan : orphans) {
                if (key.equals(orphan.key())) sync.abortMultipartUpload(r -> r.bucket(LocalS3Support.BUCKET).key(key).uploadId(orphan.uploadId()));
            }
            sync.deleteObject(r -> r.bucket(LocalS3Support.BUCKET).key(key));
            boolean gone;
            try { sync.headObject(r -> r.bucket(LocalS3Support.BUCKET).key(key)); gone = false; }
            catch (S3Exception e) { if (e.statusCode() != 404) throw e; gone = true; }
            boolean noOrphans = sync.listMultipartUploads(r -> r.bucket(LocalS3Support.BUCKET)).uploads().stream().noneMatch(u -> key.equals(u.key()));
            row.put("harness_cleanup_verified", gone && noOrphans);
            row.put("successful_correct_upload", failure == null && present && expectedHash.equals(actualHash) && ownOrphans == 0);
            System.out.println("S3OS_RESULT " + json(row)); System.out.flush();
        }
    }

    void upload(String key) throws Exception {
        if (config.adapter.equals("whole-buffer")) {
            // Strong baseline: preallocate known lengths; expose a replayable view
            // of the BAOS buffer instead of adding toByteArray/fromBytes copies.
            int initial = config.knownLength ? Math.toIntExact(config.size) : Math.min(config.chunk, 65536);
            try (WholeBuffer out = new WholeBuffer(initial)) {
                produce(out, true, true); sync.putObject(r -> r.bucket(LocalS3Support.BUCKET).key(key), out.body());
            }
        } else if (config.adapter.equals("temp-file")) {
            Path file = Files.createTempFile("s3os-benchmark-", ".bin");
            try {
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 65536)) { produce(out, true, true); }
                tempDiskBytes = Files.size(file);
                sync.putObject(r -> r.bucket(LocalS3Support.BUCKET).key(key), RequestBody.fromFile(file));
            } finally { tempDiskBytes = Files.size(file); Files.deleteIfExists(file); }
        } else if (config.adapter.equals("improved")) {
            S3OutputStream.Builder builder = S3OutputStream.builder().s3Client(sync).bucket(LocalS3Support.BUCKET).key(key).partSize(config.partSize);
            if (config.knownLength) builder.expectedLength(config.size);
            S3OutputStream out = builder.build();
            try (S3OutputStream sink = out) { produce(sink, true, true); sink.commit(); }
            finally { retainedBufferBytes = out.getRetainedBufferBytes(); retainedMetadataParts = out.getCompletedParts().size(); }
        } else if (config.adapter.equals("original-939f2bd")) {
            Class<?> type = baselineLoader.loadClass("io.github.arinmallanna.s3outputstream.S3OutputStream");
            Object builder = type.getMethod("builder").invoke(null); Class<?> b = builder.getClass();
            b.getMethod("s3Client", S3Client.class).invoke(builder, sync);
            b.getMethod("bucket", String.class).invoke(builder, LocalS3Support.BUCKET);
            b.getMethod("key", String.class).invoke(builder, key);
            b.getMethod("partSize", int.class).invoke(builder, config.partSize);
            OutputStream original = (OutputStream) b.getMethod("build").invoke(builder);
            try (OutputStream out = original) { produce(out, true, true); }
            finally {
                java.lang.reflect.Field buffer = type.getDeclaredField("buffer"); buffer.setAccessible(true);
                byte[] bytes = (byte[]) buffer.get(original); retainedBufferBytes = bytes == null ? 0 : bytes.length;
                retainedMetadataParts = ((List<?>) type.getMethod("getCompletedParts").invoke(original)).size();
            }
        } else if (config.adapter.equals("ci-cmg")) {
            edu.colorado.cires.cmg.s3out.S3OutputStream out = edu.colorado.cires.cmg.s3out.S3OutputStream.builder()
                    .s3(AwsS3ClientMultipartUpload.builder().s3(sync).contentTypeResolver(new NoContentTypeResolver()).build())
                    .uploadRequest(MultipartUploadRequest.builder().bucket(LocalS3Support.BUCKET).key(key).build())
                    .partSizeMib(config.partSize / MIB).uploadQueueSize(config.queue).autoComplete(false).build();
            try (edu.colorado.cires.cmg.s3out.S3OutputStream sink = out) { produce(sink, true, true); sink.done(); }
        } else if (config.adapter.equals("aws-async")) {
            BlockingOutputStreamAsyncRequestBody body = AsyncRequestBody.forBlockingOutputStream(config.knownLength ? config.size : null);
            AsyncRequestBody requestBody = config.awsRetryBuffer ? BufferedSplittableAsyncRequestBody.builder()
                    .asyncRequestBody(body).bufferBeforeSend(true).build() : body;
            CompletableFuture<PutObjectResponse> future = async.putObject(r -> r.bucket(LocalS3Support.BUCKET).key(key), requestBody);
            CancellableOutputStream out = body.outputStream();
            try {
                produce(out, true, true); out.close(); future.get(35, TimeUnit.SECONDS);
            } catch (Throwable primary) {
                out.cancel();
                try { future.get(5, TimeUnit.SECONDS); } catch (Exception secondary) {
                    if (secondary instanceof TimeoutException) future.cancel(true);
                    Throwable underlying = unwrap(secondary);
                    if (underlying != primary) primary.addSuppressed(underlying);
                }
                if (primary instanceof Exception) throw (Exception) primary;
                throw (Error) primary;
            }
        } else throw new IllegalArgumentException("Unknown adapter");
    }

    void produce(OutputStream destination, boolean injectFailure, boolean delay) throws IOException {
        OutputStream shield = new FilterOutputStream(destination) {
            @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
            @Override public void close() throws IOException { flush(); }
        };
        if (config.workload.equals("zip-csv")) {
            try (ZipOutputStream zip = new ZipOutputStream(shield)) {
                ZipEntry entry = new ZipEntry("synthetic.csv"); entry.setTime(0); zip.putNextEntry(entry);
                produceCsv(zip, injectFailure, delay); zip.closeEntry();
            }
        } else {
            byte[] bytes = new byte[config.chunk]; Random random = new Random(20260905);
            long written = 0;
            while (written < config.size) {
                maybeFail(written, injectFailure);
                int n = (int) Math.min(bytes.length, config.size - written);
                if (injectFailure && config.failAfter >= 0) n = (int) Math.min(n, config.failAfter - written);
                random.nextBytes(bytes);
                if (config.chunk == 1) destination.write(bytes[0]); else destination.write(bytes, 0, n);
                written += n;
                if (delay && config.producerDelayUs > 0) LockSupport.parkNanos(config.producerDelayUs * 1000);
            }
            maybeFail(written, injectFailure);
        }
    }

    void produceCsv(OutputStream out, boolean injectFailure, boolean delay) throws IOException {
        long written = 0, row = 0;
        ByteArrayOutputStream chunk = new ByteArrayOutputStream(config.chunk + 128);
        while (written < config.size) {
            maybeFail(written, injectFailure);
            chunk.reset();
            while (chunk.size() < config.chunk) {
                String line = row + ",user-" + (row * 7919 % 1_000_003) + ",group-" + (row % 97) + "," + (row % 2 == 0) + "\n";
                byte[] bytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8); chunk.write(bytes, 0, bytes.length); row++;
            }
            byte[] bytes = chunk.toByteArray(); int n = (int) Math.min(bytes.length, config.size - written);
            out.write(bytes, 0, n); written += n;
            if (delay && config.producerDelayUs > 0) LockSupport.parkNanos(config.producerDelayUs * 1000);
        }
        maybeFail(written, injectFailure);
    }
    void maybeFail(long produced, boolean enabled) throws IOException {
        if (enabled && config.failAfter >= 0 && produced >= config.failAfter) throw new IOException("synthetic producer failure");
    }

    static final class WholeBuffer extends ByteArrayOutputStream {
        WholeBuffer(int size) { super(size); }
        RequestBody body() {
            return RequestBody.fromContentProvider(() -> new ByteArrayInputStream(buf, 0, count), count, "application/octet-stream");
        }
    }
    static final class CounterOutput extends FilterOutputStream {
        long count;
        CounterOutput(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }
    static final class RequestClock implements ExecutionInterceptor {
        volatile long start;
        final AtomicLong firstRequest = new AtomicLong(); final AtomicLong firstPayload = new AtomicLong();
        void reset() { firstRequest.set(0); firstPayload.set(0); }
        @Override public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            long now = System.nanoTime(); firstRequest.compareAndSet(0, now);
            if (context.request() instanceof UploadPartRequest || context.request() instanceof PutObjectRequest) firstPayload.compareAndSet(0, now);
        }
        long delta(AtomicLong value) { return value.get() == 0 ? -1 : value.get() - start; }
    }
    static final class HeapSampler implements AutoCloseable {
        final AtomicLong peakUsed = new AtomicLong(), peakCommitted = new AtomicLong();
        volatile boolean running;
        Thread thread;
        void sample() {
            MemoryUsage usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            peakUsed.accumulateAndGet(usage.getUsed(), Math::max); peakCommitted.accumulateAndGet(usage.getCommitted(), Math::max);
        }
        void start() {
            running = true; sample();
            thread = new Thread(() -> { while (running) { sample(); LockSupport.parkNanos(5_000_000); } }, "benchmark-heap-sampler");
            thread.setDaemon(true); thread.start();
        }
        @Override public void close() throws InterruptedException { sample(); running = false; thread.join(1000); }
    }

    static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionCount())).sum(); }
    static long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum(); }
    static long cpuNanos() { return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime(); }
    static long allocatedMainThread() {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        return bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
    static Throwable unwrap(Throwable error) {
        while ((error instanceof InvocationTargetException || error instanceof ExecutionException || error instanceof CompletionException) && error.getCause() != null) error = error.getCause();
        return error;
    }
    static Throwable root(Throwable error) { while (error.getCause() != null) error = error.getCause(); return error; }
    static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b)); return out.toString(); }
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            StringJoiner out = new StringJoiner(",", "{", "}");
            ((Map<?, ?>) value).forEach((k, v) -> out.add(json(k) + ":" + json(v))); return out.toString();
        }
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
    public static void main(String[] args) throws Exception {
        Config config = new Config(args);
        BenchmarkMain benchmark = new BenchmarkMain(config);
        try { benchmark.run(); }
        finally {
            if (benchmark.async != null) benchmark.async.close();
            benchmark.sync.close();
            if (benchmark.baselineLoader != null) benchmark.baselineLoader.close();
        }
    }

    static final class Config {
        String endpoint, adapter, caseId, workload, baselineJar, fault;
        long size, failAfter, producerDelayUs;
        int partSize, chunk, repetitions, warmups, latencyMs, queue, asyncConcurrency, retries;
        double bandwidthMib;
        boolean knownLength, awsRetryBuffer;
        Config(String[] args) throws IOException {
            Properties p = new Properties(); try (Reader reader = Files.newBufferedReader(Path.of(args[0]))) { p.load(reader); }
            endpoint = p.getProperty("endpoint"); adapter = p.getProperty("adapter"); caseId = p.getProperty("case_id");
            workload = p.getProperty("workload", "bytes"); baselineJar = p.getProperty("baseline_jar"); fault = p.getProperty("fault", "none");
            size = Long.parseLong(p.getProperty("size")); failAfter = Long.parseLong(p.getProperty("fail_after", "-1"));
            producerDelayUs = Long.parseLong(p.getProperty("producer_delay_us", "0"));
            partSize = Integer.parseInt(p.getProperty("part_size", "5242880")); chunk = Integer.parseInt(p.getProperty("chunk", "65536"));
            repetitions = Integer.parseInt(p.getProperty("repetitions", "5")); warmups = Integer.parseInt(p.getProperty("warmups", "2"));
            latencyMs = Integer.parseInt(p.getProperty("latency_ms", "0")); queue = Integer.parseInt(p.getProperty("queue", "1"));
            asyncConcurrency = Integer.parseInt(p.getProperty("async_concurrency", "4")); retries = Integer.parseInt(p.getProperty("retries", "0"));
            bandwidthMib = Double.parseDouble(p.getProperty("bandwidth_mib", "0")); knownLength = Boolean.parseBoolean(p.getProperty("known_length", "false"));
            awsRetryBuffer = Boolean.parseBoolean(p.getProperty("aws_retry_buffer", "false"));
            if (chunk < 1 || size < 0 || partSize < 5 * MIB || partSize % MIB != 0 || (knownLength && !workload.equals("bytes"))) throw new IllegalArgumentException("Invalid benchmark configuration");
        }
        String fixtureJson() {
            String failures = "{}";
            if (fault.equals("permanent-part")) failures = "{\"part\":100}";
            else if (fault.equals("transient-part")) failures = "{\"part\":1}";
            else if (fault.equals("create")) failures = "{\"create\":100}";
            else if (fault.equals("complete")) failures = "{\"complete\":100}";
            else if (fault.equals("part-and-abort")) failures = "{\"part\":100,\"abort\":100}";
            return "{\"failures\":" + failures + ",\"latency_ms\":" + latencyMs + ",\"bandwidth_mib_s\":" + bandwidthMib + "}";
        }
    }
}
