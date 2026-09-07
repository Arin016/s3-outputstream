#!/usr/bin/env python3
"""Read-only advisory lookup. Sends public Maven coordinates/versions to OSV."""
import argparse
import datetime
import json
import os
from pathlib import Path
import urllib.request


def coordinate(path):
    parts = path.split('/repository/', 1)[-1].split('/')
    if len(parts) < 4:
        return None
    return {'package': {'ecosystem': 'Maven', 'name': '.'.join(parts[:-3]) + ':' + parts[-3]},
            'version': parts[-2]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--classpath', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    artifacts = [item for item in (coordinate(path) for path in
                 args.classpath.read_text().strip().split(os.pathsep)) if item]
    payload = json.dumps({'queries': artifacts}).encode()
    request = urllib.request.Request('https://api.osv.dev/v1/querybatch', data=payload,
                                     headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=45) as response:
        result = json.load(response)
    record = {
        'date_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'source': 'https://api.osv.dev/v1/querybatch',
        'scope': ('All comparison-classpath Maven packages, including provided SDK and '
                  'test/benchmark dependencies; not a proof of absence of vulnerabilities'),
        'queries': artifacts,
        'response': result
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(record, indent=2) + '\n')
    for query, answer in zip(artifacts, result['results']):
        if answer.get('vulns'):
            print(query['package']['name'], query['version'], [v['id'] for v in answer['vulns']])
    print('Packages checked:', len(artifacts))


if __name__ == '__main__':
    main()
