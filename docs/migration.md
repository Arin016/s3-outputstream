# Migrating from the original 1.0.0 source API

The 2.0.0-SNAPSHOT branch is unreleased. Review every upload call site before adopting
it: **close no longer means success by default**.

| Original behavior | New default / migration |
| --- | --- |
| close publishes, including during producer exception unwinding | close aborts; commit only after successful generation |
| wrappers can close the upload | use `S3OutputStream.upload(builder, producer)` so wrappers close a shielded view |
| abort silently swallows cleanup errors | abort declares `S3UploadException` (an IOException); handle/report it |
| exactly P bytes starts multipart | exactly P stays buffered and uses PutObject on commit |
| flush inherits a no-op, even after close | flush is a no-upload state/interruption check and rejects terminal streams |
| no part-count/length guard | fixed capacity is P × 10,000; optional exact expectedLength is enforced |
| part metadata survives close; getter is a live view | getter is a snapshot while open, empty after terminal cleanup; scalar count remains |
| failure uses ABORTED even when publication is uncertain | FAILED represents a fatal local operation; remote outcome can be unknown |
| bucket/key included in exception message | identifiers are available through explicit getters; causes still need log hygiene |

Preferred migration:

```java
S3OutputStream.upload(builder, out -> {
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
        generate(gzip);
    }
});
```

Closing a producer view only closes that view. Complete all formatting/compression
before returning; do not return early with unfinished wrappers. Do not catch and
swallow a producer error. The helper commits only after normal return and always
forces safe mode without changing the passed builder's legacy setting.

For manual ownership, call commit after success and before the outer stream closes.
If a wrapper closes the raw S3OutputStream first, it aborts and subsequent commit
fails. The callback helper avoids this ownership trap.

A temporary compatibility option is `builder.autoCommitOnClose(true)`. This restores
the old lifecycle behavior, including the producer-failure hazard. It does not
restore the old exact-boundary timing, receipt retention, failure reporting, or
unbounded part numbering. Do not silently add it to all call sites as a migration.

`expectedLength` is strict: use only the exact final byte length. Leave it unset for
unknown-length ZIP/document generation and choose a fixed part size/capacity suited
to the application's bounded maximum. Part-size changes affect heap, request count,
first-request latency and retry granularity.

The caller continues to own the S3 client. These streams are sequential and not
thread-safe. Use one independent stream per concurrently generated object.
