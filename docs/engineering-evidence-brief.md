# Engineering evidence brief

This is a short, claim-bounded review path through the S3 OutputStream artifact.
It does not replace the API documentation, benchmark protocol or release record.

## Problem framed

A Java producer may need to stream a ZIP, workbook or other generated object to
S3 without holding the complete payload in memory or relying on a temporary file.
The difficult part is failure semantics: Java wrappers call `close()` to finalize
their own format, while S3 multipart completion publishes an object. Treating
those two events as equivalent can expose a partial object after the producer
fails.

## System contribution

The revised library makes publication explicit:

- `commit()` is the success boundary;
- closing an uncommitted stream aborts rather than publishes;
- the producer helper supplies a close-shielded view so a ZIP/GZIP/writer can
  finish without committing the S3 object;
- one reusable part buffer bounds application payload storage;
- fixed-part capacity and exact-length constraints fail before accepting invalid
  writes;
- terminal states, cleanup attempts, ambiguous remote completion and listener
  behavior are explicit and tested.

The design is deliberately synchronous. It does not claim to replace the AWS
async implementation, temporary files, whole-object buffering or existing
third-party streams.

## Evidence available

- Clean local Java 11, 17 and 21 verification passed 75 unit invocations and 16
  loopback integration invocations per JDK on September 7, 2026.
- The comparison harness covers six adapters, success cases, producer failures,
  multipart faults, visibility checks and orphan-upload observations.
- Raw rows, manifests, source identities, selected/excluded outcomes, generated
  tables and hashes are preserved under `evidence/`.
- Binary, source and Javadoc artifacts were inspected; the binary targets Java 11
  and excludes test/benchmark classes.
- A separately authorized least-privilege run against AWS S3 passed on September 9,
  2026 for deterministic 0-byte, 1 KiB and 11 MiB payloads. It verified exact
  length and SHA-256 after download, then verified exact object and multipart
  cleanup. Its destination-free [receipt](../evidence/real-s3/2026-09-09/receipt.json)
  is bound to the tested source commit and protected by a published checksum.

See [benchmark results](benchmark-results.md) and the
[measurement protocol](benchmark-protocol.md). Loopback Moto results are not
presented as real AWS S3 performance.

## Fast verification

```bash
./mvnw verify --no-transfer-progress
```

The gated local integration runner and matrix verifier are documented under
`tools/local/` and in [release.md](release.md).

## What this artifact demonstrates

The artifact shows API-contract design, protocol-limit reasoning, bounded-memory
implementation, state-machine and failure-path engineering, comparative
measurement, adversarial fault injection, dependency review and careful handling
of ambiguous distributed-system outcomes.

## Remaining credibility gates

This branch is an unreleased `2.0.0-SNAPSHOT`. It has established a passing public
CI matrix and a bounded real-S3 conformance result, but not Maven Central
availability, external adoption or independent technical review. Those remaining
gaps are more important than adding another feature. The concrete path is:

1. obtain owner review of the API and article;
2. verify artifact coordinates/signing and make a release candidate;
3. seek external review or a genuine downstream use before claiming adoption.

Exact operational prerequisites and claim boundaries are in
[release.md](release.md).
