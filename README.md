# S3 OutputStream

[![CI](https://github.com/Arin016/s3-outputstream/actions/workflows/ci.yml/badge.svg)](https://github.com/Arin016/s3-outputstream/actions/workflows/ci.yml)
[![Java 11+](https://img.shields.io/badge/Java-11%2B-blue)](https://openjdk.org/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A `java.io.OutputStream` that writes directly to S3 via multipart upload. Fixed 5 MB of memory whether you're uploading 1 KB or 10 GB.

This exists because the AWS SDK for Java v2 doesn't have one. [They know.](https://github.com/aws/aws-sdk-java-v2/issues/3128) It's been an open request since 2022.

## Why this exists

Every Java library that generates output gives you an `OutputStream`:

```java
workbook.write(outputStream);    // Apache POI
document.save(outputStream);     // PDFBox
new ZipOutputStream(outputStream); // JDK
```

But S3 wants one of: `byte[]`, `InputStream` with known length, or `RequestBody`. You can't hand it an `OutputStream`.

So every team that needs to write POI/ZIP/PDF output to S3 ends up choosing between:

- **Buffer it all in memory** → works until it doesn't (OOM at 200 MB+)
- **Write to temp file, then upload** → double I/O, needs disk, adds latency
- **Roll your own multipart wrapper** → 100-200 lines of plumbing, usually missing abort-on-failure

I've done option 3 enough times in production that I extracted it into something reusable.

## Usage

```java
try (S3OutputStream out = S3OutputStream.builder()
        .s3Client(s3Client)
        .bucket("my-bucket")
        .key("exports/report.xlsx")
        .build()) {

    workbook.write(out);  // done. 5 MB peak heap.
}
```

It's an `OutputStream`. Wrap it in `ZipOutputStream`, `GZIPOutputStream`, whatever — it just works.

### Stream a ZIP to S3

```java
try (S3OutputStream s3Out = S3OutputStream.builder()
        .s3Client(s3).bucket("b").key("archive.zip").build();
     ZipOutputStream zip = new ZipOutputStream(s3Out)) {

    for (Path file : files) {
        zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
        Files.copy(file, zip);
        zip.closeEntry();
    }
}
```

### Bigger parts = fewer API calls

```java
// 50 MB buffer: 20 API calls for 1 GB instead of 200
S3OutputStream.builder()
    .s3Client(s3).bucket("b").key("k")
    .partSize(50 * 1024 * 1024)
    .build();
```

## How it works

```
write(bytes) ──► fills 5 MB buffer
                      │
                  full? ──► uploadPart #N ──► S3
                      │
                  reset buffer, repeat
                      │
close() ──────► flush remainder ──► uploadPart (final)
                      │
                  completeMultipartUpload ──► done
```

If total data never exceeds one part, it skips multipart entirely and does a single `PutObject`. No overhead for small files.

If anything fails at any point: `AbortMultipartUpload`. No orphaned parts, no silent storage costs.

## Memory

One `byte[]` of size `partSize` (default 5 MB). Allocated once, reused for every part, released on close. That's it.

| Upload size | Heap used | S3 API calls |
|-------------|-----------|--------------|
| 100 bytes   | 5 MB      | 1 (PutObject) |
| 50 MB       | 5 MB      | 10 + complete |
| 1 GB        | 5 MB      | 200 + complete |

## What it handles that ad-hoc implementations usually don't

- **Auto-abort on failure.** If `completeMultipartUpload` throws (or anything before it), we abort. Orphaned multipart parts cost money until lifecycle rules clean them up. Most wrappers I've seen in the wild don't do this.
- **Idempotent close/abort.** Call `close()` twice, nothing breaks. Call `abort()` after close, nothing breaks.
- **Write-after-close throws immediately.** Not silently drops bytes.
- **Single-put optimization.** A 500-byte config file shouldn't pay the 3-call multipart tax.
- **Strategy pattern.** All 20 unit tests run in <100ms with zero network calls. The S3 SDK is injected via an interface — swap it out, test the buffering logic in isolation.

## Installation

```xml
<dependency>
    <groupId>io.github.arinmallanna</groupId>
    <artifactId>s3-outputstream</artifactId>
    <version>1.0.0</version>
</dependency>
```

Requires AWS SDK for Java v2 on your classpath (it's `provided` scope — bring your own version).

## Building

```bash
./mvnw verify  # compiles + runs all 20 unit tests
```

Integration tests (needs a real S3 bucket + creds):
```bash
S3_TEST_BUCKET=your-bucket AWS_PROFILE=your-profile ./mvnw test-compile -q && \
java -ea -cp "target/classes:target/test-classes:$(cat target/cp.txt)" \
  io.github.arinmallanna.s3outputstream.S3OutputStreamIntegrationTest
```

## Background

I built this while working on a production export pipeline that generates 500K+ row Excel files and streams them to S3 as ZIPs. The pipeline maintains ~7 MB peak memory regardless of export size (the 5 MB S3 write buffer + a 2 MB read buffer for streaming source data into the ZIP). Without an OutputStream-to-S3 bridge, you're stuck buffering entire workbooks in memory before upload — which blows up at scale.

The AWS SDK's `BlockingOutputStreamAsyncRequestBody` exists for the async client but has different semantics (requires async S3 client, different threading model). A synchronous `S3OutputStream` for the sync client — the one most Java backends actually use — is genuinely missing from the ecosystem.

## License

Apache 2.0
