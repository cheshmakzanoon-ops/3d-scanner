#!/usr/bin/env python3
"""Show APK entry payload differences for same-source O0 and O2 debug builds."""
from pathlib import Path
import hashlib
import json
import sys
import zipfile

baseline = Path('/workspace/agents/final-artifacts/2f18a4f5a87139c38ee388f6c70af33e0fb247f7-20261020T100506Z')
optimized = Path(sys.argv[1])
report = {'source_sha': '2f18a4f5a87139c38ee388f6c70af33e0fb247f7',
          'baseline_directory': str(baseline), 'optimized_directory': str(optimized), 'artifacts': {}}
for name in ('app-debug.apk', 'app-debug-androidTest.apk'):
    with zipfile.ZipFile(baseline/name) as before, zipfile.ZipFile(optimized/name) as after:
        names = set(before.namelist()) | set(after.namelist())
        changes = []
        for entry in sorted(names):
            a = before.read(entry) if entry in before.namelist() else None
            b = after.read(entry) if entry in after.namelist() else None
            if a != b:
                changes.append({'entry': entry,
                                'before_sha256': hashlib.sha256(a).hexdigest() if a is not None else None,
                                'after_sha256': hashlib.sha256(b).hexdigest() if b is not None else None})
        permitted = lambda entry: entry in ('lib/arm64-v8a/librealityforge.so', 'lib/x86_64/librealityforge.so') or entry.startswith('META-INF/')
        assert all(permitted(c['entry']) for c in changes), changes
        if name == 'app-debug.apk':
            assert all(any(c['entry'] == f'lib/{abi}/librealityforge.so' for c in changes) for abi in ('arm64-v8a', 'x86_64'))
        else:
            assert not changes, changes
        report['artifacts'][name] = {'entry_count_before': len(before.namelist()), 'entry_count_after': len(after.namelist()),
                                    'changed_entry_payloads': changes,
                                    'unchanged_application_code_resources_and_prebuilt_dependency_payloads': True}
(optimized/'same-source-apk-entry-comparison.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps({name: [x['entry'] for x in item['changed_entry_payloads']] for name, item in report['artifacts'].items()}, indent=2))
