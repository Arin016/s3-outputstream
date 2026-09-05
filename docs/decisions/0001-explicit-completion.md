# Explicit completion and migration policy

Date: 2026-09-05. Baseline: `939f2bd4350963e9d1486c1abd392ba9e10655b8`.

The two producer-failure regressions fail against the baseline: closing during
exception unwinding publishes a partial small object or completes a partial
multipart upload. `OutputStream.close()` cannot observe why its owner exited.

## Alternatives considered

| Alternative | Assessment |
| --- | --- |
| Keep automatic completion and document manual abort | Existing examples remain unsafe when a producer throws. |
| Callback helper only; keep implicit completion by default | Helps new call sites but leaves the easiest construction unsafe. |
| Separate session type | Useful for larger APIs; unnecessary extra ownership surface for this small serial adapter. |
| Explicit commit; close aborts; callback helper | Selected. Success is visible at the call site, and wrappers compose through a close-shielded callback view. |

This is a breaking, unreleased `2.0.0-SNAPSHOT` change. `commit()` performs the
publication operation; `close()` aborts unless already committed. The explicitly
named `autoCommitOnClose(true)` restores legacy completion semantics for migration.
The recommended `upload(builder, producer)` helper always uses the safe lifecycle,
and gives the producer only an `OutputStream` view. Closing that view marks the
view closed without closing the upload; wrapper finalization can finish before
the helper commits. A callback exception triggers cleanup, never publication.
The producer must propagate errors and finish/close all wrappers before returning.

`commit()` is idempotent after success and rejects aborted/failed sessions.
`close()` and `abort()` are idempotent after every terminal state. Explicit abort
reports cleanup failure as an IOException. During another failure, cleanup errors
are suppressed on the primary upload exception; try-with-resources preserves a
producer exception and suppresses any close/abort exception. A FAILED state means
a local operation failed, not proof that the object is absent. A lost response to
PutObject/CompleteMultipartUpload can leave a successfully published object.
Abort cannot undo that object. An existing object is never deleted by cleanup.

Terminal transitions clear payload buffer, upload ID and ETag list; scalar
counters remain available. Observers receive identifier-free events; ordinary
listener exceptions are isolated and counted. Observers must not reenter the
stream. No new threads, retry loop, or executor ownership is added.

The first full buffer is retained until another nonempty write arrives, so
objects of size <= partSize use PutObject. `flush()` validates state but does not
publish or send an undersized non-final part. Uploads remain serial; synchronous
part calls provide backpressure.

Part numbers are guarded at 10,000. Unknown-length streams reject a write that
would exceed `partSize * 10,000` before accepting bytes from that call. An exact
`expectedLength` chooses sufficient part size up front unless an explicit size is
too small; overrun fails during write and underrun fails before commit. Arithmetic
is tested without large allocations. Array-backed parts have a Java-specific
maximum below S3's 5 GiB maximum; supported object capacity is documented as a
function of that configured buffer. No adaptive or parallel research claim is made.

Prior art already provides explicit success: CI-CMG documents `autoComplete(false)`
and `done()`. This decision improves this SDK and is not claimed as a new primitive.

Sources checked 2026-09-05:
- https://github.com/CI-CMG/aws-s3-outputstream#auto-completion
- https://docs.aws.amazon.com/AmazonS3/latest/userguide/qfacts.html
- https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/core/async/BlockingOutputStreamAsyncRequestBody.html

Evidence: the grad-apps S3 workstream's `raw/producer-regression-red.txt` records
both failing tests. Full baseline test output and artifact are archived there.
