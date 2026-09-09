# Real-S3 conformance run

This is a separately authorized manual check. It is not part of ordinary tests or
CI, and it never runs from default Maven goals. It uploads only deterministic
synthetic bytes beneath a unique child of a dedicated prefix, verifies exact
length and SHA-256 after download, and attempts removal of both objects and
multipart uploads.

## Infrastructure required

Provide a dedicated, never-versioned disposable S3 bucket. Configure a local AWS
profile with access only to that bucket and, where IAM conditions permit, the
`s3-outputstream-test/` prefix. The runner needs these S3 actions:

- `s3:GetBucketVersioning`
- `s3:ListBucket`
- `s3:ListBucketMultipartUploads`
- `s3:PutObject`
- `s3:GetObject`
- `s3:DeleteObject`
- `s3:AbortMultipartUpload`

`PutObject` authorizes creation, part upload and multipart completion in standard
S3 IAM evaluation. Add `s3:ListMultipartUploadParts` if an organization policy or
future test requires it. A lifecycle rule that removes incomplete multipart
uploads after one day is recommended as a secondary cleanup boundary.

Do not use a production bucket, a versioned bucket, production credentials or a
prefix containing customer data. Do not commit profile names, account IDs, bucket
names or receipts containing those values.

## Run

Set the five values in the invoking shell; do not place them in the repository:

```bash
export AWS_PROFILE='s3-outputstream-test'
export AWS_REGION='ap-south-1'
export S3_TEST_BUCKET='your-disposable-bucket'
export S3_TEST_PREFIX='s3-outputstream-test/manual/'
export S3_REAL_TEST_AUTHORIZED='YES'

python3 tools/real-s3/run.py \
  --output target/real-s3-evidence-$(date -u +%Y%m%dT%H%M%SZ)
```

The output directory must not already exist. The runner refuses modified tracked
source, does not print destination identifiers and stores only a sanitized JSON
receipt plus its SHA-256. The Java program uses a new UUID child prefix each run
and verifies that its exact objects and multipart uploads are absent after cleanup.

A PASS demonstrates basic single-PUT and multipart conformance against S3 for the
recorded source commit. It is not a latency benchmark, availability guarantee,
proof against process death or evidence of downstream adoption.
