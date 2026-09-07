#!/usr/bin/env python3
"""Derive descriptive tables and plots from archived rows; never impute successes."""
import argparse
import csv
import hashlib
import json
from collections import defaultdict
from pathlib import Path
from statistics import median, quantiles

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

MIB = 1024 ** 2
ADAPTERS = ['whole-buffer', 'temp-file', 'aws-async', 'ci-cmg', 'original-939f2bd', 'improved']
LABELS = ['Whole buffer', 'Temporary file', 'AWS async', 'CI-CMG 1.2.2', 'Original', 'Improved']
COLORS = ['#64748b', '#a16207', '#7c3aed', '#db2777', '#d97706', '#047857']


def read_rows(folder):
    return [json.loads(line) for line in (folder / 'raw.jsonl').read_text().splitlines()]


def write_csv(path, rows):
    if not rows:
        path.write_text('')
        return
    fields = list(dict.fromkeys(k for row in rows for k in row))
    with path.open('w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)


def dist(values):
    if not values:
        return {'median': '', 'q1': '', 'q3': '', 'min': '', 'max': '', 'n': 0}
    q = quantiles(values, n=4, method='inclusive') if len(values) > 1 else [values[0]] * 3
    return dict(median=median(values), q1=q[0], q3=q[2], min=min(values), max=max(values), n=len(values))


def enrich(row):
    row = dict(row)
    row['seconds'] = row['elapsed_ns'] / 1e9
    row['throughput_mib_s'] = row['expected_object_bytes'] / MIB / row['seconds']
    row['heap_mib'] = row['heap_used_peak_sampled_bytes'] / MIB
    row['committed_heap_mib'] = row['heap_committed_peak_sampled_bytes'] / MIB
    row['rss_mib'] = row['rss_peak_sampled_bytes'] / MIB if row['rss_samples'] else None
    row['producer_allocation_mib'] = row['producer_thread_allocated_bytes'] / MIB
    row['cpu_seconds'] = row['process_cpu_ns'] / 1e9
    for src, dest in [('first_storage_request_ns', 'first_request_ms'), ('first_payload_request_ns', 'first_payload_ms')]:
        row[dest] = row[src] / 1e6 if row[src] >= 0 else None
    for name, count in json.loads(row['service_requests_json'])['counts'].items():
        row['requests_' + name] = count
    return row


def summarize(rows):
    grouped = defaultdict(list)
    for row in rows:
        if row['phase'] == 'measurement':
            grouped[(row['case_id'], row['adapter'])].append(enrich(row))
    summaries = []
    metrics = ['seconds', 'throughput_mib_s', 'heap_mib', 'committed_heap_mib', 'rss_mib',
               'producer_allocation_mib', 'cpu_seconds', 'gc_count', 'gc_collection_millis',
               'first_request_ms', 'first_payload_ms', 'temporary_payload_file_bytes',
               'sink_retained_buffer_bytes_after_terminal', 'sink_retained_receipts_after_terminal']
    for (case, adapter), group in sorted(grouped.items()):
        valid = [r for r in group if r['successful_correct_upload']]
        row = {'case_id': case, 'adapter': adapter, 'measured_runs': len(group), 'correct_successes': len(valid),
               'failed_runs': len(group) - len(valid), 'expected_object_bytes': group[0]['expected_object_bytes']}
        for metric in metrics:
            values = [r[metric] for r in valid if r[metric] is not None and r[metric] >= 0]
            row.update({metric + '_' + k: v for k, v in dist(values).items()})
        # Missing operations mean zero requests, unlike missing RSS samples.
        for op in ['put', 'create', 'part', 'complete', 'abort']:
            row['requests_' + op + '_median'] = median([r.get('requests_' + op, 0) for r in valid]) if valid else ''
        summaries.append(row)
    return summaries


def format_metric(row, name, digits=2):
    value = row[name + '_median']
    return 'NA' if value == '' else f'{value:.{digits}f} [{row[name + "_q1"]:.{digits}f}, {row[name + "_q3"]:.{digits}f}]'


def table(summaries, cases):
    lines = ['| Case | Adapter | Correct / measured | Seconds, median [Q1, Q3] | MiB/s | Peak used heap MiB | Peak RSS MiB |',
             '|---|---|---:|---:|---:|---:|---:|']
    lookup = {(r['case_id'], r['adapter']): r for r in summaries}
    for case in cases:
        for adapter in ADAPTERS:
            r = lookup.get((case, adapter))
            if r:
                lines.append(f'| {case} | {adapter} | {r["correct_successes"]}/{r["measured_runs"]} | '
                             + ' | '.join(format_metric(r, m) for m in ['seconds', 'throughput_mib_s', 'heap_mib', 'rss_mib']) + ' |')
    return '\n'.join(lines)


def fault_summary(folder):
    rows = read_rows(folder)
    outcomes = json.loads((folder / 'outcomes.json').read_text())
    by_job = defaultdict(list)
    for row in rows:
        by_job[row['raw_case_path']].append(row)
    groups = defaultdict(list)
    for o in outcomes:
        groups[(o['case_id'], o['adapter'])].append(o)
    result = []
    fault_ops = {'create': 'create', 'permanent-part': 'part', 'transient-part': 'part',
                 'complete': 'complete', 'part-and-abort': 'part'}
    for (case, adapter), jobs in sorted(groups.items()):
        rs = [r for o in jobs for r in by_job[o['raw_case_path']] if r['phase'] == 'measurement']
        faults_exercised = 0
        for r in rs:
            stats = json.loads(r['service_requests_json'])
            op = fault_ops.get(r['fault'])
            if r['fault'] == 'producer':
                faults_exercised += int(not r['upload_returned_success'])
            elif op:
                initial = 1 if r['fault'] == 'transient-part' else 100
                faults_exercised += int(stats['remaining_failures'].get(op, initial) < initial)
        result.append({'case_id': case, 'adapter': adapter, 'forks': len(jobs),
                       'result_rows': len(rs), 'timeouts': sum(o['timed_out'] for o in jobs),
                       'fault_observed_in_result_rows': faults_exercised,
                       'returned_success': sum(r['upload_returned_success'] for r in rs),
                       'correct_full_objects': sum(r['hash_matches_expected'] for r in rs),
                       'partial_objects': sum(r['object_present'] and not r['hash_matches_expected'] for r in rs),
                       'absent_objects': sum(not r['object_present'] for r in rs),
                       'orphan_uploads': sum(r['orphan_uploads_before_harness_cleanup'] for r in rs),
                       'verified_harness_cleanups': sum(r['harness_cleanup_verified'] for r in rs),
                       'note': 'Timeout visibility and cleanup unknown; isolated fixture destroyed. Multipart faults do not exercise single-PUT adapters.'})
    return result


def plots(output, summaries):
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 10, 'axes.spines.top': False,
                         'axes.spines.right': False, 'svg.fonttype': 'none'})
    lookup = {(r['case_id'], r['adapter']): r for r in summaries}
    fig, axes = plt.subplots(1, 3, figsize=(13, 4.5), layout='constrained')
    for axis, metric, label in zip(axes, ['seconds', 'heap_mib', 'rss_mib'],
                                   ['Upload time (seconds)', 'Sampled used heap peak (MiB)', 'Sampled process RSS peak (MiB)']):
        for i, adapter in enumerate(ADAPTERS):
            r = lookup.get(('128m-unknown', adapter))
            if r and r[metric + '_median'] != '':
                v, lo, hi = (r[metric + '_' + k] for k in ['median', 'q1', 'q3'])
                axis.barh(i, v, color=COLORS[i], alpha=.9)
                axis.errorbar(v, i, xerr=[[v-lo], [hi-v]], color='#111827', capsize=3, fmt='none')
        axis.set_yticks(range(6), LABELS if axis is axes[0] else [''] * 6)
        axis.invert_yaxis(); axis.set_xlabel(label); axis.grid(axis='x', alpha=.15)
    fig.suptitle('128 MiB generated stream, unknown length • local Moto • median and IQR, n=5', fontsize=13)
    for ext in ['svg', 'png']:
        fig.savefig(output / ('128m-comparison.' + ext), dpi=180)
    plt.close(fig)
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.5), layout='constrained')
    for axis, metric, label in zip(axes, ['heap_mib', 'rss_mib'], ['Used heap peak (MiB)', 'Process RSS peak (MiB)']):
        for adapter, label_name, color in zip(ADAPTERS, LABELS, COLORS):
            rs = [lookup.get((case, adapter)) for case in ['1m-unknown', '32m-unknown', '128m-unknown']]
            if all(r and r[metric + '_median'] != '' for r in rs):
                axis.plot([1, 32, 128], [r[metric + '_median'] for r in rs], 'o-', label=label_name, color=color)
        axis.set_xlabel('Generated object size (MiB)'); axis.set_ylabel(label)
        axis.set_xticks([1, 32, 128]); axis.grid(alpha=.15)
    axes[1].legend(fontsize=8)
    fig.suptitle('Observed memory, not an exact process bound • unknown length • local fixture', fontsize=12)
    for ext in ['svg', 'png']:
        fig.savefig(output / ('memory-by-size.' + ext), dpi=180)
    plt.close(fig)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--success', type=Path, required=True)
    p.add_argument('--failures', type=Path)
    p.add_argument('--supplement', type=Path)
    p.add_argument('--retry-success', type=Path)
    p.add_argument('--retry-failures', type=Path)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    rows = read_rows(args.success)
    summaries = summarize(rows)
    write_csv(args.output / 'success-summary.csv', summaries)
    rejected = [r for r in rows if not r['successful_correct_upload']]
    write_csv(args.output / 'unsuccessful-runs.csv', rejected)
    plots(args.output, summaries)
    text = ['# Local benchmark results', '',
            'Generated from archived raw rows. Performance tables include only hash-correct successful uploads.',
            'Each cell is the median and inclusive interquartile range of five measured repetitions in one JVM block.',
            'This is a single-host loopback experiment with Moto 5.1.12 and AWS SDK 2.47.3, not real-S3 evidence.',
            'Heap/RSS are sampled lower bounds; RSS excludes the service. Empty objects have zero byte-throughput.', '',
            f'Archived success rows: {len(rows)}; measured: {sum(r["phase"] == "measurement" for r in rows)}; '
            f'unsuccessful rows (including warmup): {len(rejected)}.', '',
            table(summaries, ['32m-unknown', '128m-unknown', '128m-known', '64m-zip-csv']), '',
            'All cases, request counts, CPU, GC, allocation and first-request distributions are in `success-summary.csv`.',
            'Allocation covers only the producer thread; GC collection time is not a pause-duration sum.',
            'Raw errors and unsupported cases remain in `unsuccessful-runs.csv` and the source evidence directories.', '',
            '![128 MiB comparison](128m-comparison.png)', '', '![Memory by size](memory-by-size.png)', '']
    if args.failures:
        faults = fault_summary(args.failures)
        write_csv(args.output / 'failure-summary.csv', faults)
        text += ['## Fault outcomes', '', '| Case | Adapter | Forks | Timeouts | Full / partial / absent objects | Orphan uploads |',
                 '|---|---|---:|---:|---:|---:|']
        for r in faults:
            visibility = (f'{r["correct_full_objects"]} / {r["partial_objects"]} / {r["absent_objects"]}'
                          if r['result_rows'] else 'NA (no result)')
            orphans = r['orphan_uploads'] if r['result_rows'] else 'NA'
            text.append(f'| {r["case_id"]} | {r["adapter"]} | {r["forks"]} | {r["timeouts"]} | '
                        f'{visibility} | {orphans} |')
        text += ['', 'Visibility is unknown for timed-out runs. A destroyed disposable fixture is harness cleanup, '
                 'not adapter cleanup. Multipart-operation faults are unexercised for whole-buffer/temp-file single PUTs.', '']
    if args.supplement:
        supplement = summarize(read_rows(args.supplement))
        write_csv(args.output / 'supplement-summary.csv', supplement)
        text += ['## Separately configured supplement', '',
                 'These measurements use the supplemental manifest configuration and are not pooled with the original protocol.', '',
                 table(supplement, list(dict.fromkeys(r['case_id'] for r in supplement))), '']
    if args.retry_success:
        retry = summarize(read_rows(args.retry_success))
        write_csv(args.output / 'aws-retry-success-summary.csv', retry)
        text += ['## AWS buffered retry option: separate success supplement', '',
                 'BufferedSplittableAsyncRequestBody with bufferBeforeSend=true, same four-part API buffer.', '',
                 table(retry, list(dict.fromkeys(r['case_id'] for r in retry))), '']
    if args.retry_failures:
        retry_faults = fault_summary(args.retry_failures)
        write_csv(args.output / 'aws-retry-failure-summary.csv', retry_faults)
        text += ['## AWS buffered retry option: separate fault supplement', '',
                 '| Case | Forks | Timeouts | Full / partial / absent objects | Orphan uploads |',
                 '|---|---:|---:|---:|---:|']
        for r in retry_faults:
            visibility = (f'{r["correct_full_objects"]} / {r["partial_objects"]} / {r["absent_objects"]}'
                          if r['result_rows'] else 'NA')
            text.append(f'| {r["case_id"]} | {r["forks"]} | {r["timeouts"]} | {visibility} | '
                        f'{r["orphan_uploads"] if r["result_rows"] else "NA"} |')
        text += ['', 'No pooling with the unwrapped AWS body. Keep the final fault-observation protocol active through inspection.', '']
    (args.output / 'REPORT.md').write_text('\n'.join(text))
    sources = [args.success] + ([args.failures] if args.failures else []) + ([args.supplement] if args.supplement else [])
    sources += ([args.retry_success] if args.retry_success else []) + ([args.retry_failures] if args.retry_failures else [])
    manifest = {'analysis_script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                'method': 'median; inclusive quartiles; only correct successes in performance summaries; no pooling of supplement',
                'inputs': {str(path): hashlib.sha256(path.read_bytes()).hexdigest()
                           for folder in sources for path in [folder / 'manifest.json', folder / 'raw.jsonl', folder / 'outcomes.json']}}
    (args.output / 'analysis-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__':
    main()
