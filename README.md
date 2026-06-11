# S3 OutputStream

[![CI](https://github.com/arinmallanna/s3-outputstream/actions/workflows/ci.yml/badge.svg)](https://github.com/arinmallanna/s3-outputstream/actions/workflows/ci.yml)
[![Java 11+](https://img.shields.io/badge/Java-11%2B-blue)](https://openjdk.org/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**A `java.io.OutputStream` that streams data directly to Amazon S3 using multipart upload — with bounded, predictable memory usage.**

This fills a fundamental gap in the [AWS SDK for Java v2](https://github.com/aws/aws-sdk-java-v2): there is no `OutputStream`-based upload API. This has been an [open feature request since 2022](https://github.com/aws/aws-sdk-java-v2/issues/3128), raised by the Spring Cloud AWS maintainer, with multiple duplicates.

---

## Table of Contents

- [The Problem](#the-problem)
- [The Solution](#the-solution)
- [Architecture](#architecture)
- [Usage Examples](#usage-examples)
- [Memory Model](#memory-model)
- [Design Decisions](#design-decisions)
- [API Reference](#api-reference)
- [Building & Testing](#building--testing)
- [Motivation & Background](#motivation--background)

---

## The Problem

The AWS SDK for Java v2 only accepts uploads as:
- `byte[]` (entire content in memory)
- `InputStream` with a **known content length** (must know size upfront)
- `RequestBody` (same constraints)

But most Java libraries **produce** output via `OutputStream` — where you don't know the final size upfront:

| Library | API | The Conflict |
|---------|-----|--------------|
| Apache POI (Excel) | `workbook.write(outputStream)` | Generates .xlsx — size unknown until done |
| PDFBox | `document.save(outputStream)` | PDF structure finalized at write time |
| `ZipOutputStream` | wraps an `OutputStream` | Compressed size unknowable in advance |
| `GZIPOutputStream` | wraps an `OutputStream` | Same — compression ratio varies |
| ImageIO | `ImageIO.write(img, fmt, outputStream)` | Encoded size depends on content |

**Without this library, you're forced to choose:**

```
Option A: Buffer everything in memory
├── Simple code
└── OOM crash on large files (100MB+ Excel, multi-GB ZIPs)

Option B: Write to a temp file, then upload
├── Works for any size
├── Doubles total I/O (write to disk, read back, upload)
├── Requires available disk space
└── Adds latency (two sequential I/O passes)

Option C: Roll your own multipart upload plumbing  ← what everyone does
├── ~100-200 lines of boilerplate per project
├── Easy to get wrong (orphaned parts, no abort on failure)
└── Duplicated across thousands of codebases
```

**This library is Option C, done once, correctly.**

---

## The Solution

```java
try (S3OutputStream out = S3OutputStream.builder()
        .s3Client(s3Client)
        .bucket("my-bucket")
        .key("exports/report.xlsx")
        .build()) {

    workbook.write(out);  // Streams directly to S3. Done.
}
```

**What happens under the hood:**

```
Your code          S3OutputStream             Amazon S3
─────────          ──────────────             ─────────
write(bytes) ────► buffer (5 MB)
                       │
                   buffer full? ──────────► UploadPart #1 ──► stored
                       │
write(bytes) ────► buffer (5 MB)
                       │
                   buffer full? ──────────► UploadPart #2 ──► stored
                       │
close() ──────────► flush remainder ──────► UploadPart #3 ──► stored
                       │
                   CompleteMultipartUpload ─► parts assembled into final object
```

**If anything fails:**

```
exception ────► AbortMultipartUpload ────► orphaned parts deleted (no storage cost)
```

---

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                        S3OutputStream                           │
│  (java.io.OutputStream)                                        │
│                                                                │
│  Responsibilities:                                             │
│  • Buffer management (fill → flush → reuse)                   │
│  • State machine (BUFFERING → MULTIPART → COMPLETED/ABORTED)  │
│  • Smart path selection (PutObject vs Multipart)              │
│  • Error handling (auto-abort on failure)                      │
├────────────────────────────────────────────────────────────────┤
│                           │                                    │
│                    UploadStrategy (interface)                   │
│                           │                                    │
│              ┌────────────┴────────────┐                      │
│              │                         │                      │
│   S3ClientUploadStrategy      (your mock for tests)           │
│   (translates to AWS SDK)                                     │
└────────────────────────────────────────────────────────────────┘
```

**Class responsibilities (Single Responsibility Principle):**

| Class | One Job |
|-------|---------|
| `S3OutputStream` | Buffer management + state machine + OutputStream contract |
| `UploadStrategy` | Abstract interface for upload operations (Strategy pattern) |
| `S3ClientUploadStrategy` | Translate domain calls → AWS SDK S3Client calls |
| `UploadState` | Type-safe lifecycle states (replaces boolean flags) |
| `CompletedPartInfo` | Immutable value object for completed part metadata |
| `S3UploadException` | Contextual error with bucket/key for debugging |

---

## Usage Examples

### 1. Stream an Excel workbook to S3 (Apache POI)

```java
S3Client s3 = S3Client.create();

try (S3OutputStream out = S3OutputStream.builder()
        .s3Client(s3)
        .bucket("reports")
        .key("monthly/2024-Q4.xlsx")
        .build()) {

    SXSSFWorkbook workbook = generateLargeReport(); // 500K rows
    workbook.write(out);  // Only 5 MB in heap — not 200 MB
    workbook.dispose();
}
```

### 2. Stream a ZIP archive to S3

```java
try (S3OutputStream s3Out = S3OutputStream.builder()
        .s3Client(s3)
        .bucket("data-lake")
        .key("exports/2024-Q4.zip")
        .build();
     ZipOutputStream zip = new ZipOutputStream(s3Out)) {

    for (String fileName : filesToArchive) {
        zip.putNextEntry(new ZipEntry(fileName));
        try (InputStream in = readFileStream(fileName)) {
            in.transferTo(zip);
        }
        zip.closeEntry();
    }
}
// ZIP is complete in S3 — peak memory was 5 MB regardless of archive size
```

### 3. Stream a PDF to S3 (PDFBox)

```java
try (S3OutputStream out = S3OutputStream.builder()
        .s3Client(s3)
        .bucket("documents")
        .key("invoices/INV-2024-1234.pdf")
        .build()) {

    PDDocument document = generateInvoice();
    document.save(out);
    document.close();
}
```

### 4. Custom part size (trade memory for fewer API calls)

```java
// 50 MB parts: uses 50 MB heap, but only 20 API calls for a 1 GB file
// (vs default 5 MB parts = 5 MB heap, 200 API calls)
try (S3OutputStream out = S3OutputStream.builder()
        .s3Client(s3)
        .bucket("big-data")
        .key("dumps/full-export.bin")
        .partSize(50 * 1024 * 1024)
        .build()) {

    exportEntireDatabase(out);
}
```

### 5. Explicit abort on error

```java
S3OutputStream out = S3OutputStream.builder()
        .s3Client(s3).bucket("b").key("k").build();
try {
    riskyOperation(out);
    out.close(); // completes the upload
} catch (Exception e) {
    out.abort(); // explicitly cancel — no partial/corrupt object in S3
    throw e;
}
```

---

## Memory Model

**Guarantee:** peak heap usage = `partSize` bytes (default 5 MB) + negligible overhead.

| Scenario | Part Size | Heap Used | S3 API Calls | Total Data |
|----------|-----------|-----------|--------------|------------|
| Small file (1 MB) | 5 MB | 5 MB | 1 (PutObject) | 1 MB |
| Medium file (50 MB) | 5 MB | 5 MB | 10 UploadPart + Complete | 50 MB |
| Large file (1 GB) | 5 MB | 5 MB | 200 UploadPart + Complete | 1 GB |
| Large file (1 GB) | 50 MB | 50 MB | 20 UploadPart + Complete | 1 GB |

The buffer is **reused** (not reallocated) across parts — zero GC pressure during streaming. After `close()` or `abort()`, the buffer reference is released for GC.

---

## Design Decisions

| Decision | Why |
|----------|-----|
| **Strategy pattern** for S3 interaction | Decouples SDK calls from stream logic. Unit tests use a `RecordingUploadStrategy` — no SDK mocking, no Mockito, no network. Future: swap in an async strategy without touching S3OutputStream. |
| **State enum** (`UploadState`) over boolean flags | A stream has exactly 4 possible states. An enum makes illegal transitions compile-time errors. Three booleans (`closed`, `aborted`, `multipartStarted`) create 8 combinations — 4 of which are nonsensical. |
| **Single PutObject** for data ≤ part size | Multipart has overhead (3 API calls minimum). Small writes (a 100-byte JSON config) shouldn't pay that cost. |
| **Auto-abort on failure** | S3 charges for orphaned incomplete multipart parts. Forgetting to abort is a silent money leak. We abort automatically on any exception in `close()`. |
| **Builder pattern** with validation | Required fields checked at construction time, not at first write. Fail fast, fail clearly. |
| **Buffer reuse** (not reallocate per part) | Zero GC pressure. One allocation for the stream's entire lifetime. |
| **`provided` scope** for AWS SDK dependency | Users bring their own SDK version. No transitive version conflicts. |

---

## API Reference

### `S3OutputStream.builder()`

| Method | Required | Default | Description |
|--------|----------|---------|-------------|
| `.s3Client(S3Client)` | Yes | — | The AWS S3Client instance |
| `.bucket(String)` | Yes | — | Target S3 bucket |
| `.key(String)` | Yes | — | Target S3 object key |
| `.partSize(int)` | No | 5 MB | Part size in bytes (min 5 MB per S3 rules) |
| `.build()` | — | — | Validates and constructs the stream |

### Instance methods

| Method | Description |
|--------|-------------|
| `write(int)` / `write(byte[], int, int)` | Standard OutputStream writes |
| `close()` | Completes the upload (or aborts on failure) |
| `abort()` | Explicitly cancels the upload (idempotent) |
| `getTotalBytesWritten()` | Bytes written so far |
| `getPartsUploaded()` | Parts uploaded (0 while in single-put mode) |
| `getState()` | Current lifecycle state |

---

## Building & Testing

```bash
# Build and run all tests
./mvnw verify

# Tests only
./mvnw test
```

**Test output:**
```
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Tests cover:
- Single PutObject path (empty, small, boundary)
- Multipart path (2-part, 3-part, incremental byte writes, exact boundaries)
- Error semantics (write-after-close, write-after-abort, failure propagation, auto-abort)
- Builder validation (missing params, invalid part size)

All tests run in <100ms with zero network I/O (Strategy pattern enables pure unit testing).

---

## Motivation & Background

I built this while designing a production export pipeline that streams 500K–5M records into Excel/ZIP files and uploads them directly to S3 with bounded memory (64 MB heap for multi-GB exports). The pipeline needed to:

1. Write Apache POI `SXSSFWorkbook` output to S3 (POI only offers `workbook.write(OutputStream)`)
2. Stream `ZipOutputStream` directly to S3 (wrapping multiple workbooks into a ZIP)
3. Hold memory constant regardless of export size (the server handles concurrent exports)

The AWS SDK's lack of an `OutputStream` forced every user of POI/ZipOutputStream in the ecosystem to independently solve this problem — typically with ad-hoc, untested multipart upload wrappers that don't handle abort-on-failure. A [GitHub code search](https://github.com/search?l=Java&q=s3outputstream&type=Code) shows thousands of duplicated implementations.

This library extracts that plumbing into a clean, tested, reusable primitive with proper error semantics.

**Related AWS SDK issues:**
- [aws/aws-sdk-java-v2#3128](https://github.com/aws/aws-sdk-java-v2/issues/3128) — "Add S3 compatible OutputStream" (open since Mar 2022)
- [aws/aws-sdk-java-v2#3131](https://github.com/aws/aws-sdk-java-v2/issues/3131) — "Support Uploading to S3 using an OutputStream" (duplicate)
- [aws/aws-sdk-java#1268](https://github.com/aws/aws-sdk-java/issues/1268) — Same request for SDK v1

---

## Requirements

- **Java 11+** (tested on 11, 17, 21)
- **AWS SDK for Java v2** (`software.amazon.awssdk:s3`) — provided scope, bring your own version

## Installation

```xml
<dependency>
    <groupId>io.github.arinmallanna</groupId>
    <artifactId>s3-outputstream</artifactId>
    <version>1.0.0</version>
</dependency>
```

## License

[Apache 2.0](LICENSE)
