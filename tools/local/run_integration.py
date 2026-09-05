#!/usr/bin/env python3
"""Starts a private fixture, runs Maven integration tests, always stops the fixture."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='s3os-local-') as temp:
        ready = Path(temp) / 'ready.json'
        with (args.output / 'service.txt').open('w') as service_log:
            service = subprocess.Popen([sys.executable, str(ROOT / 'tools/local/service.py'), '--ready-file', str(ready)],
                                       stdout=service_log, stderr=service_log, cwd=ROOT)
            try:
                deadline = time.monotonic() + 30
                while not ready.exists():
                    if service.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError('Local fixture did not become ready')
                    time.sleep(.1)
                port = json.loads(ready.read_text())['port']
                with (args.output / 'maven.txt').open('w') as output:
                    command = [str(ROOT / 'mvnw'), 'verify', '-Plocal-it', '--no-transfer-progress',
                               f'-Ds3.localEndpoint=http://127.0.0.1:{port}']
                    result = subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
                import shutil
                for directory in ('surefire-reports', 'failsafe-reports'):
                    path = ROOT / 'target' / directory
                    if path.exists():
                        shutil.copytree(path, args.output / directory, dirs_exist_ok=True)
                # The actual ephemeral endpoint is not needed in evidence.
                for path in args.output.rglob('*.xml'):
                    path.write_text(path.read_text().replace(f'http://127.0.0.1:{port}', 'http://LOCAL_FIXTURE'))
                print('Local integration Maven exit:', result.returncode)
                return result.returncode
            finally:
                service.terminate()
                try:
                    service.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    service.kill(); service.wait()

if __name__ == '__main__':
    sys.exit(main())
