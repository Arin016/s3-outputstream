# S3 OutputStream

[![CI](https://github.com/Arin016/s3-outputstream/actions/workflows/ci.yml/badge.svg)](https://github.com/Arin016/s3-outputstream/actions/workflows/ci.yml)
[![Java 11+](https://img.shields.io/badge/Java-11%2B-blue)](https://openjdk.org/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A `java.io.OutputStream` that writes directly to S3 via multipart upload. Fixed 5 MB of memory whether you're uploading 1 KB or 10 GB.

This exists because the AWS SDK for Java v2 doesn't have one. [They know.](https://github.com/aws/aws-sdk-java-v2/issues/3128) It's been an open request since 2022.

---

## The Problem

Every Java library that generates output gives you an `OutputStream`:

```java
workbook.write(outputStream);      // Apache POI
document.save(outputStream);       // PDFBox
new ZipOutputStream(outputStream); // JDK
```

But S3 wants `byte[]` or `InputStream` with known length. No OutputStream. So you're stuck with:

```mermaid
graph LR
    subgraph "❌ Option A: Buffer Everything"
        A1[Generate content] --> A2[Hold ALL bytes in memory] --> A3[Upload to S3]
    end
    style A2 fill:#ff6b6b,color:#fff
```

```mermaid
graph LR
    subgraph "❌ Option B: Temp File"
        B1[Generate content] --> B2[Write to disk] --> B3[Read back from disk] --> B4[Upload to S3]
    end
    style B2 fill:#ffa94d,color:#fff
    style B3 fill:#ffa94d,color:#fff
```

```mermaid
graph LR
    subgraph "✅ This Library"
        C1[Generate content] --> C2[5 MB buffer] --> C3[S3 UploadPart]
        C2 -->|buffer full| C3
        C3 -->|reset buffer| C2
    end
    style C2 fill:#51cf66,color:#fff
    style C3 fill:#339af0,color:#fff
```

---

## Usage

```java
try (S3OutputStream out = S3OutputStream.builder()
        .s3Client(s3Client)
        .bucket("my-bucket")
        .key("exports/report.xlsx")
        .build()) {

    workbook.write(out);  // streams to S3. 5 MB peak heap. done.
}
```

It's a standard `OutputStream`. Wrap it in `ZipOutputStream`, `GZIPOutputStream`, whatever.

---

## How It Works

```mermaid
sequenceDiagram
    participant App as Your Code
    participant S3OS as S3OutputStream
    participant S3 as Amazon S3

    App->>S3OS: write(bytes)
    Note over S3OS: Fills 5 MB buffer

    S3OS->>S3: CreateMultipartUpload
    S3-->>S3OS: uploadId

    loop Every time buffer fills
        App->>S3OS: write(bytes)
        S3OS->>S3: UploadPart (5 MB)
        S3-->>S3OS: eTag
        Note over S3OS: Reset buffer → reuse
    end

    App->>S3OS: close()
    S3OS->>S3: UploadPart (remainder)
    S3OS->>S3: CompleteMultipartUpload
    Note over S3: Object assembled ✓
```

**Small file optimization:** if total data never exceeds one part, skips multipart entirely → single `PutObject`. No overhead for small writes.

**On any failure:**

```mermaid
sequenceDiagram
    participant S3OS as S3OutputStream
    participant S3 as Amazon S3

    S3OS->>S3: UploadPart
    S3--xS3OS: Exception!
    S3OS->>S3: AbortMultipartUpload
    Note over S3: Orphaned parts cleaned up<br/>No storage cost
```

---

## Architecture

```mermaid
classDiagram
    class S3OutputStream {
        -byte[] buffer
        -UploadState state
        -List~CompletedPartInfo~ parts
        +write(byte[] b, int off, int len)
        +close()
        +abort()
        +getTotalBytesWritten() long
        +getPartsUploaded() int
    }

    class UploadStrategy {
        <<interface>>
        +initiateUpload(bucket, key) String
        +uploadPart(bucket, key, uploadId, partNum, data, len) String
        +completeUpload(bucket, key, uploadId, parts)
        +abortUpload(bucket, key, uploadId)
        +putObject(bucket, key, data, len)
    }

    class S3ClientUploadStrategy {
        -S3Client s3Client
    }

    class UploadState {
        <<enum>>
        BUFFERING
        MULTIPART_IN_PROGRESS
        COMPLETED
        ABORTED
        +isTerminal() boolean
        +acceptsWrites() boolean
    }

    S3OutputStream --> UploadStrategy : uses
    S3ClientUploadStrategy ..|> UploadStrategy : implements
    S3OutputStream --> UploadState : tracks
    S3OutputStream --> CompletedPartInfo : collects
```

**Why the Strategy pattern?** Unit tests run in <100ms with zero network I/O. Swap in a recording implementation, test all buffering/state logic in isolation. The S3 SDK is never mocked.

---

## Memory Guarantee

```mermaid
graph LR
    subgraph "Memory: CONSTANT regardless of upload size"
        B[/"5 MB buffer (allocated once, reused)"/]
    end

    subgraph "Upload Size"
        direction TB
        S1["1 KB → 1 API call"]
        S2["50 MB → 10 API calls"]
        S3["1 GB → 200 API calls"]
        S4["10 GB → 2000 API calls"]
    end

    B --- S1
    B --- S2
    B --- S3
    B --- S4
```

| Upload size | Heap used | S3 API calls |
|-------------|-----------|--------------|
| 100 bytes   | 5 MB      | 1 (PutObject) |
| 50 MB       | 5 MB      | 10 + complete |
| 1 GB        | 5 MB      | 200 + complete |
| 10 GB       | 5 MB      | 2,000 + complete |

The buffer is **reused** — one allocation for the stream's entire lifetime. Released on `close()`.

---

## State Machine

```mermaid
stateDiagram-v2
    [*] --> BUFFERING : build()

    BUFFERING --> MULTIPART_IN_PROGRESS : buffer fills (>5MB total)
    BUFFERING --> COMPLETED : close() [data ≤ 1 part → PutObject]

    MULTIPART_IN_PROGRESS --> COMPLETED : close() [CompleteMultipartUpload]

    BUFFERING --> ABORTED : error / abort()
    MULTIPART_IN_PROGRESS --> ABORTED : error / abort() [AbortMultipartUpload]

    COMPLETED --> [*]
    ABORTED --> [*]
```

Four states. No boolean soup. Illegal transitions are compile-time impossible.

---

## Examples

### Stream a ZIP to S3

```java
try (S3OutputStream s3Out = S3OutputStream.builder()
        .s3Client(s3).bucket("data").key("export.zip").build();
     ZipOutputStream zip = new ZipOutputStream(s3Out)) {

    for (Path file : filesToArchive) {
        zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
        Files.copy(file, zip);
        zip.closeEntry();
    }
}
// ZIP in S3. Peak memory: 5 MB. Archive size: doesn't matter.
```

### Stream an Excel workbook (Apache POI)

```java
try (S3OutputStream out = S3OutputStream.builder()
        .s3Client(s3).bucket("reports").key("Q4.xlsx").build()) {

    SXSSFWorkbook workbook = generateLargeReport(); // 500K rows
    workbook.write(out);
    workbook.dispose();
}
```

### Custom part size

```java
// 50 MB parts: fewer API calls, more memory
S3OutputStream.builder()
    .s3Client(s3).bucket("b").key("k")
    .partSize(50 * 1024 * 1024)
    .build();
```

---

## What This Handles That Ad-Hoc Implementations Usually Don't

| Concern | This library | Typical DIY wrapper |
|---------|-------------|-------------------|
| Abort on failure | ✓ Auto-aborts, no orphaned parts | ✗ Often forgotten → silent S3 costs |
| Small file optimization | ✓ Single PutObject if ≤ 1 part | ✗ Always multipart (3 API calls for 100 bytes) |
| Write-after-close | ✓ Throws immediately | ✗ Silently drops bytes or NPE |
| Idempotent close | ✓ Safe to call twice | ✗ Double-complete or NPE |
| Buffer reuse | ✓ Zero GC pressure | ✗ New byte[] per part |
| Testable without S3 | ✓ Strategy pattern | ✗ Needs LocalStack or mocking |

---

## Installation

```xml
<dependency>
    <groupId>io.github.arinmallanna</groupId>
    <artifactId>s3-outputstream</artifactId>
    <version>1.0.0</version>
</dependency>
```

Requires `software.amazon.awssdk:s3` on your classpath (`provided` scope — bring your own version).

## Building & Testing

```bash
./mvnw verify          # 20 unit tests, <100ms, zero network
```

Integration test (real S3):
```bash
S3_TEST_BUCKET=your-bucket AWS_PROFILE=your-profile \
  java -ea -cp "target/classes:target/test-classes:$(./mvnw dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout)" \
  io.github.arinmallanna.s3outputstream.S3OutputStreamIntegrationTest
```

---

## Background

I built this while designing a production export pipeline that streams 500K+ row Excel reports directly to S3 as ZIPs — maintaining ~7 MB peak memory for multi-GB exports (5 MB write buffer + 2 MB read buffer for streaming source data into the ZIP). The pipeline handles 4 concurrent exports on a single 4 GB pod across 300+ enterprise tenants.

The AWS SDK's `BlockingOutputStreamAsyncRequestBody` exists for the async client but requires different threading semantics. A synchronous `S3OutputStream` for the sync client — which most Java backends use — is [genuinely missing](https://github.com/aws/aws-sdk-java-v2/issues/3128).

## License

Apache 2.0
