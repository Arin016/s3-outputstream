#!/usr/bin/env python3
"""Recover evaluation source bytes by their recorded hashes, without changing Git state."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evaluation', type=Path, action='append', required=True)
    parser.add_argument('--supplemental-source', type=Path, action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    revisions = subprocess.check_output(['git', 'rev-list', 'HEAD'], cwd=ROOT, text=True).splitlines()
    supplied = {sha(p.read_bytes()): (p.read_bytes(), 'hash-verified supplemental reconstruction')
                for p in args.supplemental_source}
    cache, records, missing_code = {}, [], []
    args.output.mkdir(parents=True, exist_ok=True)
    for evaluation in args.evaluation:
        manifest = json.loads((evaluation / 'manifest.json').read_text())
        sources = dict(manifest['source_sha256'], **{'pom.xml': manifest['pom_sha256']})
        for path, digest in sorted(sources.items()):
            key = (path, digest)
            if key not in cache:
                current = ROOT / path
                candidates = [(current.read_bytes(), 'current checkout')] if current.is_file() else []
                if digest in supplied:
                    candidates.append(supplied[digest])
                found = next(((data, origin) for data, origin in candidates if sha(data) == digest), None)
                if found is None:
                    for revision in revisions:
                        result = subprocess.run(['git', 'show', revision + ':' + path], cwd=ROOT, capture_output=True)
                        if result.returncode == 0 and sha(result.stdout) == digest:
                            found = (result.stdout, 'git:' + revision)
                            break
                cache[key] = found
            found = cache[key]
            entry = {'evaluation': evaluation.name, 'original_path': path, 'sha256': digest}
            if found is not None:
                data, origin = found
                destination = args.output / digest / Path(path).name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
                assert sha(destination.read_bytes()) == digest
                entry.update(snapshot=str(destination.relative_to(args.output)), origin=origin, recovered=True)
            else:
                entry.update(recovered=False, note='Historical bytes unavailable; recorded digest retained.')
                if Path(path).suffix != '.md':
                    missing_code.append(entry)
            records.append(entry)
    (args.output / 'index.json').write_text(json.dumps(records, indent=2) + '\n')
    print(json.dumps({'references': len(records), 'recovered': sum(r['recovered'] for r in records),
                      'unrecovered_code': missing_code}, indent=2))
    if missing_code:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
