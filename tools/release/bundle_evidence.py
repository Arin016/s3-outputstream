#!/usr/bin/env python3
"""Build a deterministic review bundle with machine paths redacted; no object payloads."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile


def main():
    p=argparse.ArgumentParser();p.add_argument('--evidence-root',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    root=a.evidence_root.resolve();files=[]
    for folder in sorted((root/'raw').glob('benchmark-*')):
        # Preserve successful, failed, superseded and interrupted diagnostics alike.
        files.extend(p for p in folder.rglob('*') if p.is_file() and p.suffix in ['.json','.jsonl','.csv','.txt','.py'])
    for folder in sorted((root/'raw').glob('*java-matrix*')):
        files.extend(p for p in folder.rglob('*') if p.is_file() and p.suffix in ['.json','.xml','.txt'])
    files.extend(p for p in (root/'analysis').rglob('*') if p.is_file())
    for name in ['baseline-939f2bd-20260905.txt','baseline-environment.txt','baseline-manifest.json',
                 'producer-regression-red.txt','example-compilation.json','dependency-osv-20260906.json',
                 'dependency-advisory-details-20260906.json','dependency-osv-patched-20260906.json']:
        f=root/'raw'/name
        if f.exists():files.append(f)
    for name in ['BENCHMARK_REGISTRY.csv','CLAIMS_EVIDENCE.csv','BENCHMARK_PROTOCOL.md','RELEASE_EVIDENCE.md']:
        f=root/name
        if f.exists():files.append(f)
    entries={};index={}
    for f in sorted(set(files)):
        original=f.read_bytes();published=original
        if f.suffix not in ['.png']:
            text=original.decode('utf-8')
            text=text.replace(str(root),'$EVIDENCE_ROOT')
            text=text.replace('/Users/arin.mallanna/personal/s3-outputstream','$SDK_CHECKOUT')
            text=text.replace('/Users/arin.mallanna','$USER_HOME')
            text=re.sub(r'http://127\.0\.0\.1:\d+','http://LOCAL_FIXTURE',text)
            published=text.encode('utf-8')
        name=str(f.relative_to(root));entries[name]=published
        index[name]={'canonical_sha256':hashlib.sha256(original).hexdigest(),
                     'bundle_sha256':hashlib.sha256(published).hexdigest(),
                     'path_redaction_changed_bytes':published!=original}
    entries['evidence-index.json']=(json.dumps(index,indent=2)+'\n').encode()
    entries['BUNDLE_README.md']=b'''# Local S3 engineering evidence\n\nNo real-S3 traffic or production object data. Benchmark content was generated.\nFull original and corrected runs are retained; consult BENCHMARK_REGISTRY.csv\nfor inclusion/exclusion rules. Machine home paths and loopback ports are redacted;\nindex entries distinguish canonical and bundled hashes. Source hashes are unchanged.\nUse the SDK tools/benchmarks/analyze.py with the selected raw directories to\nregenerate tables. Compilation, source and dependency identities are in manifests.\nThis archive is a local review artifact, not a release/publication receipt.\n'''
    a.output.parent.mkdir(parents=True,exist_ok=True)
    with a.output.open('wb') as f,gzip.GzipFile(filename='',mode='wb',fileobj=f,mtime=0) as gz,tarfile.open(fileobj=gz,mode='w') as tar:
        for name,data in sorted(entries.items()):
            info=tarfile.TarInfo(name);info.size=len(data);info.mtime=0;info.mode=0o644
            tar.addfile(info,io.BytesIO(data))
    with tarfile.open(a.output,'r:gz') as tar:
        for name,info in index.items():
            assert hashlib.sha256(tar.extractfile(name).read()).hexdigest()==info['bundle_sha256']
    print(json.dumps({'files':len(entries),'bytes':a.output.stat().st_size,'sha256':hashlib.sha256(a.output.read_bytes()).hexdigest()}))

if __name__=='__main__':main()
