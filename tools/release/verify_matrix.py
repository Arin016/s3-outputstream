#!/usr/bin/env python3
"""Sequential clean local verification. Supply three explicit JDK installation paths."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def totals(folder):
    result = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in folder.glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for k in result:
            result[k] += int(suite.get(k, 0))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java11', type=Path, required=True)
    parser.add_argument('--java17', type=Path, required=True)
    parser.add_argument('--java21', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() and any(args.output.iterdir()):
        raise SystemExit('Refusing to overwrite matrix evidence')
    args.output.mkdir(parents=True, exist_ok=True)
    result = {'date_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'git_commit': subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
              'git_status': subprocess.check_output(['git','status','--short'],cwd=ROOT,text=True),
              'source_sha256': {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in (ROOT/'src').rglob('*') if p.is_file()},
              'pom_sha256': hashlib.sha256((ROOT/'pom.xml').read_bytes()).hexdigest(), 'jdk_runs': []}
    for version in [11,17,21]:
        home = getattr(args,'java'+str(version)).resolve()
        env = dict(os.environ, JAVA_HOME=str(home), PATH=str(home/'bin')+os.pathsep+os.environ['PATH'])
        folder = args.output / ('java'+str(version)); folder.mkdir()
        jvm = subprocess.run([str(home/'bin/java'),'-version'],capture_output=True,text=True,check=True).stderr
        (folder/'java-version.txt').write_text(jvm)
        with (folder/'clean.txt').open('w') as log:
            subprocess.run([str(ROOT/'mvnw'),'clean','--no-transfer-progress'],cwd=ROOT,env=env,
                           stdout=log,stderr=subprocess.STDOUT,check=True)
        print('Verifying Java',version,flush=True)
        code = subprocess.run([sys.executable,str(ROOT/'tools/local/run_integration.py'),
                               '--output',str(folder)],cwd=ROOT,env=env).returncode
        unit, integration = totals(folder/'surefire-reports'), totals(folder/'failsafe-reports')
        entry = dict(java=version, java_version=jvm.strip(), exit_code=code, unit=unit, local_integration=integration)
        if code == 0:
            subprocess.run([sys.executable,str(ROOT/'tools/release/check_artifacts.py'),
                            '--output',str(folder/'package-inspection.json')],cwd=ROOT,env=env,check=True)
            for jar in (ROOT/'target').glob('s3-outputstream-*.jar'): shutil.copy2(jar,folder/jar.name)
        result['jdk_runs'].append(entry)
        (args.output/'matrix.json').write_text(json.dumps(result,indent=2)+'\n')
        print(entry,flush=True)
        if code != 0 or not unit['tests'] or not integration['tests']:
            return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
