#!/usr/bin/env python3
"""Run the real synthetic Android journey with independently checked disabled radios."""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess

TEST_CLASS = 'com.realityforge.scanner.qa.OfflinePrivacyJourneyTest'
TEST_METHODS = frozenset({
    'packagedAppHasNoNetworkPermissionAndOptionalCamera',
    'deletingAProjectPreservesExternalOriginalsExportsAndOtherProjects',
    'explicitOfflineSyntheticImportReconstructionInspectionReopenExportJourney',
})


def network_disabled(settings: dict) -> bool:
    return settings == {'airplane_mode_on': '1', 'wifi_on': '0', 'mobile_data': '0'}


def completed(text: str) -> bool:
    """Require complete AndroidJUnitRunner start/end records and final summary."""
    if len(text) > 2_000_000 or any(marker in text for marker in (
            'FAILURES!!!', 'INSTRUMENTATION_FAILED', 'AssumptionFailure',
            'AssumptionViolatedException', 'Process crashed', 'shortMsg=')):
        return False
    required = {'class', 'current', 'id', 'numtests', 'stream', 'test'}
    fields, events, results = {}, [], {}
    phase, continuation = 'tests', None
    for line in text.splitlines():
        status = re.fullmatch(r'INSTRUMENTATION_STATUS: ([A-Za-z][A-Za-z0-9_]*)=(.*)', line)
        code = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        result = re.fullmatch(r'INSTRUMENTATION_RESULT: ([A-Za-z][A-Za-z0-9_]*)=(.*)', line)
        if status:
            key, value = status.groups()
            if phase != 'tests' or key not in required or key in fields:
                return False
            fields[key] = value
            continuation = fields if key == 'stream' else None
        elif code:
            if phase != 'tests' or len(events) >= 6 or fields.keys() != required:
                return False
            if (code[1] != ('1' if len(events) % 2 == 0 else '0') or
                    fields['class'] != TEST_CLASS or fields['test'] not in TEST_METHODS or
                    fields['id'] != 'AndroidJUnitRunner' or fields['numtests'] != '3' or
                    fields['current'] != str(len(events) // 2 + 1)):
                return False
            if len(events) % 2 and fields['test'] != events[-1]['test']:
                return False
            events.append(fields)
            fields, continuation = {}, None
        elif result:
            key, value = result.groups()
            if phase != 'tests' or len(events) != 6 or fields or key != 'stream' or results:
                return False
            results[key] = value
            phase, continuation = 'summary', results
        elif line == 'INSTRUMENTATION_CODE: -1':
            if phase != 'summary' or fields or not results:
                return False
            phase, continuation = 'done', None
        elif line.startswith('INSTRUMENTATION_'):
            return False
        elif continuation is not None:
            continuation['stream'] += '\n' + line
        elif line.strip():
            return False
    return (phase == 'done' and len(events) == 6 and
            {event['test'] for event in events[1::2]} == TEST_METHODS and
            re.fullmatch(r'\s*Time: (?:0|[1-9]\d*)(?:\.\d+)?\s+OK \(3 tests\)\s*',
                         results.get('stream', '')) is not None and
            len(re.findall(r'^OK \(', text, re.M)) == 1)


def installed_hash(adb, package: str) -> str:
    rows = adb('shell', 'pm', 'path', package).splitlines()
    if len(rows) != 1 or not rows[0].startswith('package:'):
        raise ValueError('Exact single-APK installed package required; splits need their own package validation')
    path = rows[0][len('package:'):]
    if not re.fullmatch(r'/(?:data/app|mnt/expand)/[A-Za-z0-9._/~=+/-]+\.apk', path):
        raise ValueError('Unexpected installed candidate path')
    digest = adb('shell', 'sha256sum', path).split()[0]
    if not re.fullmatch('[0-9a-f]{64}', digest):
        raise ValueError('Installed APK hash unavailable')
    return digest


def run(args) -> dict:
    if not re.fullmatch('[0-9a-f]{40}', args.source_sha):
        raise ValueError('Full candidate source SHA required')
    if not re.fullmatch('[A-Za-z0-9._:-]+', args.serial):
        raise ValueError('Invalid explicit device serial')
    def adb(*parts, timeout=30):
        return subprocess.check_output([args.adb, '-s', args.serial, *parts], text=True,
                                       stderr=subprocess.STDOUT, timeout=timeout).strip()
    def settings():
        return {k: adb('shell', 'settings', 'get', 'global', k)
                for k in ('airplane_mode_on', 'wifi_on', 'mobile_data')}
    record = {'schema': 1, 'agent': '089', 'source_sha': args.source_sha,
              'artifact_sha256': hashlib.file_digest(args.apk.open('rb'), 'sha256').hexdigest(),
              'test_apk_sha256': hashlib.file_digest(args.test_apk.open('rb'), 'sha256').hexdigest(),
              'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'status': 'NOT_RUN', 'physical_backup_transfer': 'NOT_RUN'}
    record['device'] = {'serial': args.serial, 'fingerprint': adb('shell', 'getprop', 'ro.build.fingerprint'),
                        'abi': adb('shell', 'getprop', 'ro.product.cpu.abi'),
                        'emulator': adb('shell', 'getprop', 'ro.kernel.qemu') == '1'}
    record['network_before'] = settings()
    if not network_disabled(record['network_before']):
        record['blockers'] = ['Operator must disable mobile data and Wi-Fi and enable airplane mode on disposable test device']
        return record
    if not args.install:
        record['blockers'] = ['Use --install for the identified disposable test target; installed-package identity cannot be assumed']
        return record
    # The operator explicitly selected installation; no uninstall/data erasure or global radio changes.
    for artifact in (args.apk, args.test_apk):
        text = adb('install', '-r', str(artifact), timeout=180)
        if 'Success' not in text:
            raise ValueError('Candidate installation did not complete')
    record['installed_apk_sha256'] = installed_hash(adb, 'com.realityforge.scanner')
    record['installed_test_apk_sha256'] = installed_hash(adb, 'com.realityforge.scanner.test')
    if (record['installed_apk_sha256'] != record['artifact_sha256'] or
            record['installed_test_apk_sha256'] != record['test_apk_sha256']):
        raise ValueError('Supplied and installed candidate APKs differ')
    command = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'offlinePrivacy', 'true', '-e', 'class',
               'com.realityforge.scanner.qa.OfflinePrivacyJourneyTest',
               'com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner']
    output = adb(*command, timeout=900)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    log = args.output.with_suffix('.instrumentation.log')
    log.write_text(output + '\n')
    record['command'] = [args.adb, '-s', args.serial, *command]
    record['instrumentation_sha256'] = hashlib.sha256(output.encode()).hexdigest()
    record['network_after'] = settings()
    record['status'] = 'PASS' if completed(output) and network_disabled(record['network_after']) else 'FAIL'
    record['synthetic'] = True
    record['physical_capture_quality'] = 'NOT_RUN'
    return record


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('serial', 'source-sha'):
        p.add_argument('--' + name, required=True)
    p.add_argument('--adb', default='adb')
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--test-apk', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--install', action='store_true')
    a = p.parse_args()
    try:
        result = run(a)
    except (ValueError, OSError, subprocess.SubprocessError) as exc:
        result = {'schema': 1, 'agent': '089', 'status': 'FAIL', 'error': str(exc),
                  'physical_backup_transfer': 'NOT_RUN'}
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
