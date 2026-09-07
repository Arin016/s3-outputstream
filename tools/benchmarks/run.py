#!/usr/bin/env python3
"""Reproducible local-only S3 comparison. Each case/adapter has an isolated JVM and fixture."""
import argparse
import csv
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import psutil
from prepare_baseline import prepare, ROOT


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def redact(text):
    return re.sub(r'http://127\.0\.0\.1:\d+', 'http://LOCAL_FIXTURE', text)

def invoke_job(java, cp, params, folder, baseline, timeout):
    folder.mkdir(parents=True, exist_ok=True)
    records, peaks, samples = [], {}, {}
    current = [None]
    with tempfile.TemporaryDirectory(prefix='s3os-bench-') as temp:
        ready = Path(temp) / 'ready.json'
        service_log = (folder / 'service.txt').open('w')
        service = subprocess.Popen([sys.executable, str(ROOT / 'tools/local/service.py'), '--ready-file', str(ready)],
                                   cwd=ROOT, stdout=service_log, stderr=service_log)
        process = None
        try:
            deadline = time.monotonic() + 30
            while not ready.exists():
                if service.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('Fixture did not start')
                time.sleep(.05)
            port = json.loads(ready.read_text())['port']
            config = dict(params, endpoint=f'http://127.0.0.1:{port}', baseline_jar=str(baseline))
            properties = Path(temp) / 'config.properties'
            properties.write_text('\n'.join(f'{k}={str(v).lower() if isinstance(v, bool) else v}' for k, v in config.items()) + '\n')
            (folder / 'config.json').write_text(json.dumps(params, indent=2) + '\n')
            command = [java, '-Xms128m', '-Xmx512m', '-XX:+UseG1GC', '-Duser.timezone=UTC',
                       '-Dio.netty.eventLoopThreads=4', '-cp', cp,
                       'io.github.arinmallanna.s3outputstream.BenchmarkMain', str(properties)]
            with (folder / 'stderr.txt').open('w') as stderr:
                process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=stderr, text=True, bufsize=1)
                handle = psutil.Process(process.pid)
                def capture():
                    with (folder / 'stdout.txt').open('w') as raw:
                        for line in process.stdout:
                            raw.write(redact(line)); raw.flush()
                            if line.startswith('S3OS_RUN_START '):
                                key = line.strip().split(' ', 1)[1]
                                current[0] = key; peaks[key] = 0; samples[key] = 0
                            elif line.startswith('S3OS_RUN_END '):
                                current[0] = None
                            elif line.startswith('S3OS_RESULT '):
                                row = json.loads(line[len('S3OS_RESULT '):])
                                key = row['run_id']
                                row['rss_peak_sampled_bytes'] = peaks.get(key, 0)
                                row['rss_samples'] = samples.get(key, 0)
                                row['rss_sampling_interval_ms'] = 5
                                records.append(row)
                reader = threading.Thread(target=capture, daemon=True); reader.start()
                timed_out = False
                deadline = time.monotonic() + timeout
                while process.poll() is None:
                    key = current[0]
                    if key is not None:
                        try:
                            rss = handle.memory_info().rss
                            peaks[key] = max(peaks.get(key, 0), rss); samples[key] = samples.get(key, 0) + 1
                        except psutil.NoSuchProcess:
                            pass
                    if time.monotonic() > deadline:
                        timed_out = True; process.kill(); break
                    time.sleep(.005)
                process.wait(); reader.join(timeout=10)
                outcome = {'exit_code': process.returncode, 'timed_out': timed_out,
                           'process_timeout_seconds': timeout, 'result_rows': len(records),
                           'incomplete_run_id': current[0], 'incomplete_rss_peak_sampled_bytes': peaks.get(current[0], 0)}
                (folder / 'outcome.json').write_text(json.dumps(outcome, indent=2) + '\n')
            (folder / 'stderr.txt').write_text(redact((folder / 'stderr.txt').read_text()))
        finally:
            if process is not None and process.poll() is None:
                process.kill(); process.wait()
            service.terminate()
            try:
                service.wait(timeout=10)
            except subprocess.TimeoutExpired:
                service.kill(); service.wait()
            service_log.close()
    for row in records:
        row['raw_case_path'] = str(folder.name)
        row['fork'] = params.get('fork', 0)
        row['run_id'] = folder.name + ':' + row['run_id']
    with (folder / 'rows.jsonl').open('w') as output:
        for row in records:
            output.write(json.dumps(row) + '\n')
    return records, outcome


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--suite', choices=['smoke', 'success', 'failures'], default='smoke')
    parser.add_argument('--adapters', default='')
    parser.add_argument('--cases', default='')
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME', ''))
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--aws-retry-buffer', action='store_true',
                        help='Supplement: wrap AWS blocking body in BufferedSplittableAsyncRequestBody, bufferBeforeSend=true')
    args = parser.parse_args()
    if args.output.exists() and any(args.output.iterdir()):
        raise SystemExit('Refusing to overwrite an existing evidence directory')
    args.output.mkdir(parents=True, exist_ok=True)
    protocol = json.loads((ROOT / 'tools/benchmarks/cases.json').read_text())
    if args.suite == 'smoke':
        selected = [c for c in protocol['success'] if c['case_id'] == '32m-unknown'] + [protocol['failures'][1]]
    else:
        selected = protocol[args.suite]
    if args.cases:
        selected = [c for c in selected if c['case_id'] in args.cases.split(',')]
    adapters = args.adapters.split(',') if args.adapters else protocol['adapters']
    if not selected or any(a not in protocol['adapters'] for a in adapters):
        raise SystemExit('Unknown adapter or empty case selection')
    java = str(Path(args.java_home) / 'bin/java') if args.java_home else shutil.which('java')
    baseline = prepare(args.java_home)
    cp = os.pathsep.join([str(ROOT / 'target/classes'), str(ROOT / 'target/test-classes'),
                         (ROOT / 'target/benchmark-classpath.txt').read_text().strip()])
    jobs = []
    for case in selected:
        for adapter in adapters:
            for fork in range(3 if args.suite == 'failures' else 1):
                params = dict(protocol['defaults'], **case, adapter=adapter, fork=fork)
                params['aws_retry_buffer'] = args.aws_retry_buffer
                if args.suite == 'smoke':
                    params.update(warmups=1, repetitions=1)
                elif args.suite == 'failures':
                    params.update(warmups=0, repetitions=1)
                jobs.append(params)
    random.Random(protocol['random_seed']).shuffle(jobs)
    code_files = [p for p in ROOT.rglob('*') if p.is_file() and
                  any(p.is_relative_to(ROOT / scope) for scope in ['src/main', 'src/test', 'src/benchmark', 'tools'])
                  and '__pycache__' not in str(p)]
    dependencies = (ROOT / 'target/benchmark-classpath.txt').read_text().strip().split(os.pathsep)
    manifest = {
        'date_utc': dt.datetime.now(dt.timezone.utc).isoformat(), 'suite': args.suite,
        'git_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'git_status': subprocess.check_output(['git', 'status', '--short'], cwd=ROOT, text=True),
        'source_sha256': {str(p.relative_to(ROOT)): sha(p) for p in sorted(code_files)},
        'pom_sha256': sha(ROOT / 'pom.xml'), 'protocol_sha256': sha(ROOT / 'tools/benchmarks/cases.json'),
        'baseline': json.loads((baseline.parent / 'manifest.json').read_text()),
        'java_version': subprocess.run([java, '-version'], capture_output=True, text=True).stderr.strip(),
        'jvm_flags': ['-Xms128m', '-Xmx512m', '-XX:+UseG1GC', '-Duser.timezone=UTC', '-Dio.netty.eventLoopThreads=4'],
        'host': {'os': platform.platform(), 'architecture': platform.machine(), 'logical_cpus': psutil.cpu_count(),
                 'physical_cpus': psutil.cpu_count(logical=False), 'memory_bytes': psutil.virtual_memory().total},
        'python_version': sys.version, 'pip_freeze': subprocess.check_output([sys.executable, '-m', 'pip', 'freeze'], text=True).splitlines(),
        'dependencies_sha256': {str(Path(p)).split('/repository/')[-1]: sha(p) for p in dependencies},
        'run_order': jobs, 'network_scope': 'isolated loopback Moto fixture; no real S3',
        'sampler_note': 'RSS and heap sampled every 5 ms; peaks are observed lower bounds, not exact maxima. Service is a separate process.',
        'allocation_note': 'Allocation counter covers the Java producer thread only; async worker allocation is excluded.',
        'gc_note': 'Explicit GC requested before each run outside timed interval. Reported collection time is not a pause-duration sum.',
        'order_note': 'Case/adapter JVM blocks randomized; 2 same-workload warmups then 5 sequential measured repetitions per success JVM.'
    }
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    shutil.copy2(ROOT / 'tools/benchmarks/cases.json', args.output / 'protocol.json')
    all_rows, outcomes = [], []
    for index, params in enumerate(jobs):
        name = f'{index:03d}-{params["case_id"]}-{params["adapter"]}-f{params["fork"]}'
        print(f'[{index+1}/{len(jobs)}] {params["case_id"]} / {params["adapter"]}', flush=True)
        rows, outcome = invoke_job(java, cp, params, args.output / name, baseline,
                                   45 if args.suite == 'failures' else args.timeout)
        all_rows.extend(rows); outcomes.append(dict(params, **outcome, raw_case_path=name))
        print(f'  results={len(rows)} exit={outcome["exit_code"]} timeout={outcome["timed_out"]}', flush=True)
        with (args.output / 'raw.jsonl').open('w') as out:
            for row in all_rows:
                out.write(json.dumps(row) + '\n')
        (args.output / 'outcomes.json').write_text(json.dumps(outcomes, indent=2) + '\n')
    if all_rows:
        with (args.output / 'raw.csv').open('w', newline='') as out:
            writer = csv.DictWriter(out, fieldnames=list(all_rows[0]))
            writer.writeheader(); writer.writerows(all_rows)
    print(f'Archived {len(all_rows)} raw run rows and {len(outcomes)} process outcomes.', flush=True)
    return 0

if __name__ == '__main__':
    sys.exit(main())
