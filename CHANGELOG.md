# Changelog

## 2.0.0-SNAPSHOT — unreleased

- Breaking: default close aborts; explicit commit publishes. Added a producer helper
  that commits on successful return and permits wrapper finalization through a
  close-shielded OutputStream. Explicit legacy auto-commit remains available.
- Breaking: abort reports cleanup errors; FAILED is distinct from explicit ABORTED.
  Preserve primary causes and suppressed cleanup failures. Document ambiguous
  remote publication and the limits of interruption/cleanup.
- Clear buffer, upload ID and ETag list on every terminal operation; retain scalar
  counters. Snapshot part receipts while open instead of returning a live view.
- Use PutObject for sizes <= partSize, including the exact boundary. Flush validates
  state without sending an undersized part or publishing the object.
- Enforce the 10,000-part limit and capacity before accepting an oversized write;
  choose/validate part size for strict expectedLength. Guard range arithmetic.
- Add synchronous structured events, content type and copied user metadata. Make
  lifecycle state a public API type. Omit destination identifiers from top-level
  exception messages.
- Use replayable streams over the valid buffer prefix for synchronous AWS calls;
  avoid the strategy's previous payload copies. No whole-process memory guarantee.
- Add generated lifecycle tests, a local S3-compatible integration/fault fixture,
  and a reproducible six-adapter comparative benchmark.
- Pin the evaluation SDK to 2.47.3 with a BOM, retain Java 11 release compatibility,
  correct project URLs, and include the full Apache license and wrapper notice.
- Update the local build/evaluation transports to Netty 4.1.137.Final, HttpClient
  5.6.3 and HttpCore 5.4.3 after advisory review; revalidate the Java matrix.
  Consumers supplying the AWS client retain responsibility for their dependencies.

## Original source baseline — 939f2bd (2026-06-12)

Synchronous multipart OutputStream with automatic completion on close, a small-object
path strictly below one part, strategy-based testing, twenty unit tests, Java
11/17/21 CI configuration, and a manually invoked real-S3 integration program.
A POM version of 1.0.0 was present; package publication is not established here.
