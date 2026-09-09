#!/usr/bin/env python3
"""Run the explicitly authorized real-S3 conformance program and seal a safe receipt."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
MAIN_CLASS = "io.github.arinmallanna.s3outputstream.S3OutputStreamIntegrationTest"
REQUIRED = (
    "S3_REAL_TEST_AUTHORIZED",
    "S3_TEST_BUCKET",
    "S3_TEST_PREFIX",
    "AWS_PROFILE",
    "AWS_REGION",
)


def command(args: list[str], *, timeout: int = 240, capture: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=ROOT,
        check=False,
        text=True,
        capture_output=capture,
        timeout=timeout,
    )


def tracked_source_is_clean() -> bool:
    return command(["git", "diff", "--quiet", "HEAD", "--"], capture=False).returncode == 0


def source_identity() -> str:
    result = command(["git", "rev-parse", "HEAD"])
    if result.returncode != 0:
        raise RuntimeError("cannot determine source identity")
    return result.stdout.strip()


def sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def validate_gate() -> None:
    if os.environ.get("S3_REAL_TEST_AUTHORIZED") != "YES":
        raise RuntimeError("explicit authorization gate is closed")
    if any(not os.environ.get(name) for name in REQUIRED[1:]):
        raise RuntimeError("required real-S3 configuration is incomplete")
    prefix = os.environ["S3_TEST_PREFIX"]
    if not prefix.startswith("s3-outputstream-test/") or not prefix.endswith("/"):
        raise RuntimeError("test prefix must be dedicated and disposable")
    if not tracked_source_is_clean():
        raise RuntimeError("tracked source differs from HEAD")


def run(output: Path) -> int:
    validate_gate()
    output.mkdir(parents=True, exist_ok=False)
    commit = source_identity()
    classpath_file = output / "classpath.txt"
    build = command([
        str(ROOT / "mvnw"), "-q", "-DskipTests", "test-compile",
        "dependency:build-classpath", f"-Dmdep.outputFile={classpath_file}",
    ])
    if build.returncode != 0:
        raise RuntimeError("test compilation or classpath generation failed")
    java = shutil.which("java")
    if not java:
        raise RuntimeError("java executable not found")
    classpath = os.pathsep.join((
        str(ROOT / "target" / "test-classes"),
        str(ROOT / "target" / "classes"),
        classpath_file.read_text().strip(),
    ))
    started = datetime.now(timezone.utc)
    result = command([java, "-cp", classpath, MAIN_CLASS], timeout=300)
    ended = datetime.now(timezone.utc)
    # The Java program intentionally emits only a standardized, destination-free line.
    public_message = (result.stdout if result.returncode == 0 else result.stderr).strip()
    receipt = {
        "schema": "s3-outputstream-real-conformance-receipt-v1",
        "source_commit": commit,
        "started_at": started.isoformat(),
        "ended_at": ended.isoformat(),
        "status": "PASS" if result.returncode == 0 else "FAIL",
        "exit_code": result.returncode,
        "synthetic_sizes_bytes": [0, 1024, 11 * 1024 * 1024],
        "checks": ["length", "sha256", "object_cleanup", "multipart_cleanup"],
        "destination_identifiers_recorded": False,
        "message": public_message,
    }
    receipt_path = output / "receipt.json"
    receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    (output / "receipt.sha256").write_text(sha256(receipt_path) + "  receipt.json\n")
    classpath_file.unlink(missing_ok=True)
    print(public_message)
    print(f"Sanitized receipt: {receipt_path}")
    return result.returncode


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True,
                        help="new directory for the sanitized receipt")
    args = parser.parse_args()
    try:
        return run(args.output.resolve())
    except (RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"Real-S3 runner refused or failed safely: {type(error).__name__}: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
