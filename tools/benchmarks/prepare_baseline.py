#!/usr/bin/env python3
"""Compile the exact original sources in isolation, using the comparison SDK classpath."""
from pathlib import Path
import hashlib
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]
COMMIT = '939f2bd4350963e9d1486c1abd392ba9e10655b8'

def prepare(java_home=None):
    dest = ROOT / '.local-tools/baseline-939f2bd'
    sources = dest / 'src'
    classes = dest / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    names = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', COMMIT, 'src/main/java'], cwd=ROOT, text=True).splitlines()
    hashes = {}
    paths = []
    for name in names:
        if not name.endswith('.java'):
            continue
        data = subprocess.check_output(['git', 'show', f'{COMMIT}:{name}'], cwd=ROOT)
        path = sources / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        hashes[name] = hashlib.sha256(data).hexdigest()
        paths.append(str(path))
    cp = (ROOT / 'target/benchmark-classpath.txt').read_text().strip()
    java_home = java_home or os.environ.get('JAVA_HOME')
    javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
    jar = str(Path(java_home) / 'bin/jar') if java_home else 'jar'
    subprocess.run([javac, '--release', '11', '-cp', cp, '-d', str(classes), *paths], check=True)
    artifact = dest / 'original.jar'
    subprocess.run([jar, '--create', '--file', str(artifact), '-C', str(classes), '.'], check=True)
    manifest = {'source_commit': COMMIT, 'source_sha256': hashes, 'artifact_sha256': hashlib.sha256(artifact.read_bytes()).hexdigest(),
                'compiled_against_comparison_classpath': True, 'compiler_release': 11}
    (dest / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return artifact

if __name__ == '__main__':
    print(prepare())
