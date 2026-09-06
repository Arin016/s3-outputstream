#!/usr/bin/env python3
"""Inspect the local binary/source/Javadoc candidate. No credentials or publishing."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    pom=ET.parse(ROOT/'pom.xml').getroot();ns={'m':'http://maven.apache.org/POM/4.0.0'}
    artifact=pom.find('m:artifactId',ns).text;version=pom.find('m:version',ns).text
    result={'version':version,'artifacts':{}}
    for suffix in ['', '-sources', '-javadoc']:
        path=ROOT/'target'/f'{artifact}-{version}{suffix}.jar'
        with zipfile.ZipFile(path) as z:
            names=z.namelist()
            forbidden=[n for n in names if any(x in n.lower() for x in ['benchmarkmain','localitsupport','locals3support','recordingstore','.env','credentials','blog.md','surefire'])
                       or n.endswith('Test.class') or n.endswith('IT.class')]
            assert not forbidden, forbidden
            majors=[]
            if not suffix:
                for name in names:
                    if name.endswith('.class'):
                        magic,minor,major=struct.unpack('>IHH',z.read(name)[:8]);assert magic==0xcafebabe and major==55,(name,major);majors.append(major)
                assert majors and 'META-INF/LICENSE' in names and 'META-INF/NOTICE' in names
                assert z.read('META-INF/LICENSE')==(ROOT/'LICENSE').read_bytes()
            elif suffix=='-sources':
                for src in (ROOT/'src/main/java').rglob('*.java'):
                    name=str(src.relative_to(ROOT/'src/main/java'));assert z.read(name)==src.read_bytes(),name
                assert not any(n.endswith('.class') for n in names)
            else:
                assert 'index.html' in names
                assert any(n.endswith('/S3OutputStream.html') for n in names)
            result['artifacts'][path.name]={'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'bytes':path.stat().st_size,
                                          'entries':names,'classfile_major_versions':sorted(set(majors))}
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2)+'\n')
    print('Package inspection passed: binary, sources, Javadoc; Java 11 bytecode; no test or benchmark payloads.')

if __name__=='__main__':main()
