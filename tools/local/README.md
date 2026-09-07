# Local S3-compatible integration

From the repository root, with Python 3.13 and a Java 11, 17 or 21 JDK:

```bash
python3 -m venv .local-tools/venv
.local-tools/venv/bin/python -m pip install -r tools/local/requirements.lock
.local-tools/venv/bin/python tools/local/run_integration.py --output target/local-evidence
```

The runner launches Moto 5.1.12 on a randomly assigned loopback port, runs
`./mvnw verify -Plocal-it` with that endpoint, archives unit/integration reports,
and always stops its fixture. It never reads AWS credentials or profiles. Client
construction requires a loopback URL plus the dedicated fixture's health marker.
Use `JAVA_HOME` to select the JDK. Avoid simultaneous Maven builds in one checkout.

The fixture supports deterministic failures at create, part, complete and abort,
plus a lost-completion-response mode. It also accepts request delay settings for
benchmarks. It is a test service, not a production S3 implementation. Its control
routes, relaxed credentials and loopback binding are deliberate test-only choices.
The benchmark/local clients disable Expect: 100-continue and optional checksum
calculation for fixture compatibility; SHA-256 verification is independent.

Integration coverage includes empty/small/exact-boundary/multipart objects,
metadata/content type, producer errors before/after multipart, replay on a retried
part, failed abort with a visible orphan, ambiguous completion, and a generated
128 MiB upload downloaded through a streaming hash. Tests do not allocate the full
large object in the Java producer or verifier. The emulator's own storage/memory
behavior is separate.

A direct `./mvnw verify -Plocal-it` without the dedicated endpoint fails instead of
silently skipping or falling back to real AWS. The ordinary `./mvnw verify` runs
only deterministic unit tests and packaging.

The exact Python dependency resolution for archived runs is in each benchmark
manifest's `pip_freeze`. The committed direct pins specify the fixture API version;
use the archived full freeze to reconstruct those transitive versions exactly.
