#!/usr/bin/env python3
"""Run the actual debug build/package checks on one immutable candidate."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--repo', type=Path, required=True)
p.add_argument('--source', required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
assert re.fullmatch('[0-9a-f]{40}', a.source)
repo, out = a.repo.resolve(), a.output.resolve()
assert not out.exists(), 'Use a fresh output directory'
out.mkdir(parents=True)

def git(*args):
    return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()

def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def artifact(path):
    return dict(path=str(path), sha256=digest(path), bytes=path.stat().st_size)

assert git('rev-parse', 'HEAD') == a.source and not git('status', '--porcelain')
assert not (repo / 'app/schemas').exists(), 'Preserve pre-existing user schemas'
record = dict(schema=1, source_sha=a.source, source_root=str(repo), status='FAIL',
              started_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
              driver=artifact(Path(__file__).resolve()),
              scope='debug compile,testAPK,actualJVM,lint,ELF+ZIP16KB,packagedprivacy,notices',
              commands=[], artifacts={}, instrumentation='NOT_RUN_IN_BUILD')

def execute(name, argv, timeout=1200):
    started = time.monotonic()
    path = out / (name + '.log')
    with path.open('wb') as log:
        r = subprocess.run(argv, cwd=repo, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
    record['commands'].append(dict(command=argv, exit_code=r.returncode,
                                   seconds=round(time.monotonic()-started, 3), log=artifact(path)))
    if r.returncode:
        raise RuntimeError(name + ' failed; inspect ' + str(path))

try:
    execute('gradle', ['flock', '/workspace/campaign/android.lock', sys.executable,
                      '/workspace/campaign/gradle.py', '--dependency-verification', 'strict', ':app:assembleDebug',
                      ':app:assembleDebugAndroidTest', ':app:testDebugUnitTest', ':app:lintDebug'])
    for key, relative in [('apk', 'app/build/outputs/apk/debug/app-debug.apk'),
                          ('test_apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
        path = out / Path(relative).name
        shutil.copyfile(repo / relative, path)
        record['artifacts'][key] = artifact(path)
    suites = sorted((repo / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    assert suites, 'No actual JVM reports'
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    (out / 'jvm').mkdir()
    for path in suites:
        root = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(root.attrib[key])
        shutil.copyfile(path, out / 'jvm' / path.name)
    assert counts['tests'] > 0 and not any(counts[k] for k in ('failures','errors','skipped')), counts
    record['jvm'] = counts
    apk = record['artifacts']['apk']['path']
    execute('package', [sys.executable, 'tools/check_apk.py', apk, '--sdk', '/workspace/android-sdk',
                        '--report', str(out / 'package.json'), '--require-notices'])
    execute('privacy', [sys.executable, 'tools/device/offline_privacy/audit.py', '--apk', apk,
                        '--sdk', '/workspace/android-sdk', '--source-sha', a.source,
                        '--output', str(out / 'privacy.json')])
    execute('attribution', [sys.executable, 'tools/dependencies/inventory.py', 'audit', apk,
                            '--inventory', 'tools/dependencies/evidence/debug/inventory.json',
                            '--notices', 'app/src/debug/assets/third-party-notices',
                            '--report', str(out / 'attribution.json')])
    record['status'] = 'PASS'
except Exception as e:
    record['error'] = str(e)
finally:
    # Room emits an untracked schema. Keep its actual bytes outside source.
    generated = repo / 'app/schemas'
    if generated.exists():
        if generated.is_symlink() or any(x.is_symlink() for x in generated.rglob('*')):
            record.update(status='FAIL', cleanup_error='Unexpected schema symlink retained')
        else:
            shutil.move(str(generated), str(out / 'generated-room-schemas'))
    record['source_after'] = git('rev-parse', 'HEAD')
    record['finished_at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    record['tracked_status_after'] = git('status', '--porcelain')
    if record['source_after'] != a.source or record['tracked_status_after']:
        record.update(status='FAIL', source_error='Source changed or unexpected outputs remain')
    (out / 'summary.json').write_text(json.dumps(record, indent=2)+'\n')
    print(json.dumps({k:record[k] for k in ('source_sha','status','artifacts')}, indent=2), flush=True)
sys.exit(0 if record['status'] == 'PASS' else 1)
