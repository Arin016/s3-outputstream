# Local comparison protocol (version 1)

Frozen before final measurement: 2026-09-06. Executable scenarios are in
`tools/benchmarks/cases.json`; each run archives that file, its hash, all source
hashes, exact Git revision, dependency hashes, environment and randomized order.
Exploratory smoke runs are separate and excluded from final tables.

Six adapters use AWS SDK 2.47.3, synthetic data, the same loopback Moto 5.1.12
fixture and caller-owned reusable clients. All clients disable Expect: 100-continue
and chunked encoding for this local HTTP fixture, and set optional request/response
checksum handling to WHEN_REQUIRED. Integrity is independently verified with SHA-256.
These are fixture settings, not suggested production security configuration:

- Whole-object buffering: an exposed ByteArrayOutputStream with a replayable
  request-body view, avoiding extra toByteArray/fromBytes copies. Known-length
  cases preallocate the exact size. Unknown-length cases start at up to 64 KiB.
- Buffered temporary-file staging and RequestBody.fromFile. Payload disk bytes
  are measured; OS page cache and service disk are outside this metric.
- AWS BlockingOutputStreamAsyncRequestBody with standard Java multipart enabled,
  the same threshold/minimum part size, a four-part API buffer, four HTTP
  connections and four Netty event-loop threads. Unknown length is passed as null;
  producer failure calls cancel, waits for failure, then cancels a stuck future.
- CI-CMG 1.2.2 with documented safe autoComplete(false)/done(), the default queue
  size of one, and the same part size. It has one upload consumer; queue size
  is not the number of parallel part-upload workers.
- Original library source at 939f2bd, compiled unchanged in an isolated class
  loader against the comparison SDK. This isolates implementation differences;
  it is not the historical 2.25.60 dependency configuration. Legacy close behavior
  is retained and tested as observed, including partial publication on failure.
- Improved serial library with explicit commit and exact expectedLength for
  known-length byte workloads. No new parallel/adaptive mode is claimed.

`bytes` uses seeded generated chunks with no full object allocation. `zip-csv`
serializes synthetic rows into a deterministic JDK ZIP entry. Input-byte count
and final compressed-object size are recorded separately. Hashes are computed
from an independent prepass and streamed download; generation/hash prepass and
verification download are outside upload timing. All adapters pay producer
serialization costs inside the upload timer.

Success cases vary size, known length, part size, write granularity, producer
pacing and request delay. Cases include empty/boundary objects, up to 128 MiB,
and CSV-in-ZIP output. Limits beyond these measured sizes are tested by arithmetic
and state-machine tests, not described as huge real-object experiments.

Failure cases vary producer failure before/after multipart, creation, transient
and permanent part errors, completion, and part plus abort failure. An injected
multipart failure cannot affect a baseline that only performs PutObject; report
that as an unexercised fault, not superior reliability. Every baseline uses its
recommended explicit success/cancellation protocol where available. No adapter
is intentionally given an avoidably unsafe option except the historical baseline
whose behavior is the subject of the regression comparison.

For success, each case/adapter runs in one fresh JVM with two same-workload warmup
runs and five measured repetitions. JVM blocks are shuffled with seed 20260906.
For faults, three independent fresh JVMs run one repetition each, without warmup.
Timed-out processes are killed after 45 seconds, and their dedicated disposable
fixture is stopped; outcomes are retained, never converted into a throughput
number. The success block timeout is 180 seconds, with any exceptions documented.

JVM flags: -Xms128m -Xmx512m -XX:+UseG1GC -Duser.timezone=UTC and
-Dio.netty.eventLoopThreads=4. An explicit GC request occurs before each timed
run. Client construction is outside the timer; sink construction, production,
upload and its in-process completion/abort are inside. Administratively removing
objects/orphans after outcome inspection is outside. Each block gets a new service
process; successful repeated runs reuse clients and the fixture.

Measurements:

- Nanosecond wall time and process CPU time for upload interval.
- Time to first storage request (including create) and first payload request
  (PutObject/UploadPart), measured by AWS beforeTransmission hooks. Not time to
  the first physical byte on a socket.
- JVM used/committed heap sampled every 5 ms and boundary samples; process RSS
  externally sampled every 5 ms during START/END markers. Both observed peaks
  are lower bounds; service process memory is excluded. Missing samples are NA.
- Current producer-thread allocated bytes only. This excludes async/worker
  allocations and must not be compared as whole-process allocation efficiency.
- GC count and aggregate collection time from MXBeans; not exact pause totals.
- Temporary payload-file size, part-buffer/receipt retention where introspection
  is available, service request attempts (including retries), hash equality,
  final object presence, orphan uploads and verified harness cleanup.

Report medians and interquartile ranges over the five measured repetitions,
plus per-run raw data and all failures. These are repeated runs in one JVM block,
not independent host replications. Do not infer statistical significance or a
universal performance ranking. RSS/heap reflect JIT, SDK and transport allocations;
application buffer size is a separate quantity.

The fixture can add a fixed delay per request and a delay proportional to request
body length. The latter is a per-request service-delay model, not a shared network
bandwidth cap. Parallel requests may overlap their delays. Loopback Python service
CPU, HTTP without TLS, local disk/cache, shared-host load and omitted geographic
latency limit external validity. No monetary cost estimate or real-S3 performance
claim follows from these measurements.

Reproduction commands and results are in `tools/benchmarks/README.md` and the
canonical graduate-workspace S3 workstream. Baseline source provenance is kept
separate from internal export pipelines and production anecdotes.

Exploratory correction: SDK 2.47.3 requires explicit multipartEnabled(true),
verified in its S3AsyncClientDecorator source/bytecode. Relying on the current
online guide's implicit enablement yielded a misconfigured smoke case. After
enablement, the local HTTP fixture's interim 100 response was interpreted as an
error by the async path. Disabling Expect consistently is part of the frozen
fixture configuration; initial diagnostic runs are retained and excluded.
