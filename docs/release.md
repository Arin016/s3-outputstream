# Release readiness and operator checklist

This is a local, unreleased 2.0.0-SNAPSHOT candidate. A POM coordinate is not proof
of Maven Central availability. No public push, PR, tag, release, Central upload or
article publication is part of local validation.

## Local candidate checks

1. Run `./mvnw verify --no-transfer-progress` on Java 11/17/21 and archive exact
   revision, JVM/Maven versions, reports and exit status.
2. Run the local fixture suite under each supported JDK; keep its evidence separate
   from any authorized real-S3 run. Run the benchmark protocol and preserve raw data.
3. Build the main, sources and Javadoc JARs. Inspect entries for test/benchmark
   classes, credentials, `.env`, object data and other unintended files.
4. Review breaking changes in `docs/migration.md`, README examples, POM dependency
   scope/BOM, Apache license, notice, and metadata URLs. Compile examples as part
   of the tests. Review dependency/security policy before publication.
5. Check all generated reports against source/raw data and reconcile article claims.
   Keep user BLOG.md and the separate personal-site repository preserved.
6. Confirm the candidate Git tree, package hashes and release version. Local commits
   are reviewable checkpoints, not public release evidence.

## Distribution prerequisites requiring owner action

- Confirm the Central Portal publisher namespace `io.github.arin016` against the
  repository owner's GitHub identity. The Java package and automatic-module name
  remain `io.github.arinmallanna.s3outputstream`; Maven coordinates and Java
  package names are independent.
- Configure a Central Portal publisher account/token, signing key, developer
  contact details, and namespace verification outside Git. Verify current Portal
  requirements and test a candidate upload only after explicit authorization.
- Decide the first released version and supported AWS SDK policy. This branch uses
  2.0.0-SNAPSHOT to make the close-contract break unmistakable even though no
  previous Central release is established.
- Review any internal/public lineage or employer-disclosure statements. This
  candidate's article confines itself to public code and synthetic experiments.
- Obtain human technical review and explicit approval for the exact artifact,
  public repository changes and article text before public distribution.

## Optional real-S3 validation

Real-S3 access requires explicit user authorization and a dedicated disposable
bucket/key prefix. The manual program must be deliberately gated and configured;
local fixtures never fall back to cloud credentials. Use least-privilege test
access, configure timeouts/lifecycle cleanup, verify exact test-key removal and
multipart cleanup, and archive only sanitized results. Do not log credentials,
profiles, endpoints, bucket names, keys, signed URLs or object contents.

The guarded runner, exact environment contract and least-privilege action list are
documented in [`tools/real-s3/README.md`](../tools/real-s3/README.md). It emits a
destination-free receipt tied to the tested commit and refuses modified tracked
source or a closed authorization gate.

### Verified real-S3 conformance (2026-09-09)

The guarded runner passed against AWS S3 using a dedicated, never-versioned,
disposable bucket and a prefix-restricted test principal. It exercised deterministic
0-byte, 1 KiB and 11 MiB payloads, covering single-PUT and multipart paths. Each
download matched the expected length and SHA-256, and the harness verified removal
of its exact objects and multipart uploads. The public
[receipt](../evidence/real-s3/2026-09-09/receipt.json) contains no destination
identifiers and records the exact source commit; its sibling checksum verifies the
receipt bytes.

This run establishes bounded protocol conformance for the recorded revision. It
does not establish latency, availability, crash recovery, compatibility with every
AWS SDK version, production usage or external adoption.

No automated deploy goal is bound to ordinary verify/package. Signing/publishing
configuration is deliberately an operator step after local artifacts are reviewed.

## Verified local matrix (2026-09-07)

Clean Corretto 11.0.30 / 17.0.18 / 21.0.10 builds each passed 75 unit invocations
and 16 loopback integration invocations. Strict Javadocs passed without warnings.
Binary, sources and Javadoc JARs were inspected; binary classes target Java 11
(major 55), and no tests or benchmark classes are included. These are local checks,
not a claim that the revised GitHub Actions workflow has run remotely.

Reproduce with explicit JDK paths and an environment outside `target`:

```bash
.local-tools/venv/bin/python tools/release/verify_matrix.py \
  --java11 /path/to/jdk11 --java17 /path/to/jdk17 --java21 /path/to/jdk21 \
  --output local-matrix-evidence
```

The checker archives per-JDK logs, sanitized XML reports and package hashes. Source
JARs matched across this matrix; binary/Javadoc hashes varied with the JDK. Fixed
archive timestamps do not establish byte-identical output across different JDKs.
The canonical release evidence records the exact tested source/POM hashes.

## Dependency policy and advisory review

The September 7 matrix uses AWS SDK 2.47.3 with Netty 4.1.137.Final, Apache
HttpClient 5.6.3 and HttpCore 5.4.3. These transport updates address advisory
matches found in the September 6 evaluation environment. The OSV batch lookup
on September 7 returned no matches for its 60 comparison-classpath Maven packages;
this is a dated database result, not a guarantee that the dependency set is secure.
See the [Netty release](https://netty.io/news/2026/08/06/4-1-137-Final.html),
[HttpClient release](https://github.com/apache/httpcomponents-client/releases/tag/rel/v5.6.3)
and [HttpCore source release](https://hc.apache.org/httpcomponents-core-5.4.x/current/scm.html).

S3 is `provided`: applications supply their own SDK/client. This library's
dependency management controls its build and evaluation, and is not automatically
inherited by consuming applications. Consumers must align and review their own
AWS SDK and HTTP dependencies. Historical benchmark manifests retain the original
versions; post-update checks are separately labeled. Recheck advisories and current
publication requirements immediately before an authorized release.
