# Benchmark reproduction

Read [the protocol](../../docs/benchmark-protocol.md) before interpreting results.
This benchmark uses synthetic bytes and CSV-in-ZIP content with a loopback Moto
service. It never contacts real AWS, even if a local AWS profile exists.

Prepare an isolated Python environment and compile the test-only adapters:

```bash
python3 -m venv .local-tools/venv
.local-tools/venv/bin/python -m pip install -r tools/local/requirements.lock
./mvnw -Pbenchmarks test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/benchmark-classpath.txt --no-transfer-progress
```

Smoke-test all six baselines, then collect separate success and failure sweeps:

```bash
.local-tools/venv/bin/python tools/benchmarks/run.py --suite smoke --output benchmark-results/smoke
.local-tools/venv/bin/python tools/benchmarks/run.py --suite success --output benchmark-results/success
.local-tools/venv/bin/python tools/benchmarks/run.py --suite failures --output benchmark-results/failures
.local-tools/venv/bin/python tools/benchmarks/analyze.py \
  --success benchmark-results/success --failures benchmark-results/failures \
  --output benchmark-results/analysis
```

Use a fresh output directory; the runner refuses to overwrite an existing run.
Use `--java-home /path/to/jdk` to pin the JVM, `--cases case-id,...` or
`--adapters improved,aws-async,...` for an explicitly recorded subset. Do not mix
subset/tuning runs into the full tables without labeling them.

The original source baseline is checked out file-by-file from the fixed Git commit
into `.local-tools/baseline-939f2bd`, compiled against the comparison SDK, and loaded
in an isolated class loader. It never overwrites the current source tree. Baseline
source/artifact hashes are archived. Its historical dependency set is a separate
baseline build record, not the comparison dependency environment.

Each output contains:

- `manifest.json`: source/dependency/protocol hashes, Git revision, JVM, host,
  Python dependency freeze, configuration, and randomized run order;
- `protocol.json`: frozen scenario definitions;
- `raw.jsonl` and `raw.csv`: all warmup and measurement rows;
- `outcomes.json`: process exit codes and timeouts, including missing-result runs;
- per-case configuration, stdout/stderr, service log, outcome and result rows.

Process timeouts, hash mismatches, unsupported empty-object behavior and failed
cleanup are outcomes, not throughput observations. Failure injection at a multipart
operation is marked unexercised for single-PUT baselines. Discarded objects and
orphans are inspected before administrative harness cleanup. A timeout ends the
whole disposable fixture; it is never evidence that the adapter cleaned itself up.

RSS and heap are sampled peaks, not exact maxima. Process RSS excludes the service.
Producer-thread allocation excludes async workers. GC collection time is not a
pause sum. Throughput uses final stored bytes; ZIP input size is also recorded.
First-request timings are SDK transmission hooks, not packet timestamps.

The archived evaluation also includes labeled configuration supplements. To
reproduce the corrected known/unknown subset and AWS's retry-capable body:

```bash
.local-tools/venv/bin/python tools/benchmarks/run.py --suite success \
  --cases 32m-unknown,128m-unknown,128m-known --output benchmark-results/corrected
.local-tools/venv/bin/python tools/benchmarks/run.py --suite success \
  --cases 32m-unknown,128m-unknown,128m-known --adapters aws-async \
  --aws-retry-buffer --output benchmark-results/aws-retry-success
.local-tools/venv/bin/python tools/benchmarks/run.py --suite failures \
  --adapters aws-async --aws-retry-buffer --output benchmark-results/aws-retry-failures
.local-tools/venv/bin/python tools/benchmarks/analyze.py \
  --success benchmark-results/success --failures benchmark-results/failures \
  --supplement benchmark-results/corrected \
  --retry-success benchmark-results/aws-retry-success \
  --retry-failures benchmark-results/aws-retry-failures \
  --output benchmark-results/analysis
```

The current driver enables chunked signing for known-length AWS async requests,
keeps faults active through outcome inspection, and exposes the retry-buffer option.
The original archived success sweep used the earlier signing configuration. Its
invalid known-length block is preserved as a diagnosed harness issue; current
reproduction should produce the corrected behavior. Use the archived source hashes
and revision if deliberately reconstructing that historical diagnostic.

The current POM also includes the September 7 transport updates. The report labels
their validation separately; historical numbers refer to their archived versions.
The review bundle contains `raw/source-snapshots/index.json`, mapping selected
executable/POM inputs to exact source bytes by recorded hash. See the bundle's
`SOURCE_PROVENANCE.md` before reconstructing an earlier configuration. Do this in
a separate checkout; do not overwrite ongoing local work or use historical
dependency pins as production guidance.
