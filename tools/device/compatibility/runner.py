#!/usr/bin/env python3
"""Serial-bound installed-APK instrumentation; no zero-test or alignment-only pass."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tools'))
from check_screenshot import validate
from device.compatibility.failure_logcat import capture as capture_failure_logcat, utc_now
SCREEN = '/data/local/tmp/realityforge-m12-session.png'
SUITES = Path(__file__).with_name('suites.json')


def select_serial(listing, requested=None):
    rows = [line.split() for line in listing.splitlines()[1:] if line.strip()]
    ready = [row[0] for row in rows if len(row) > 1 and row[1] == 'device']
    if requested:
        if requested not in ready:
            raise ValueError('Requested serial is absent, offline or unauthorized')
        return requested
    if len(ready) != 1:
        raise ValueError('Select --serial explicitly unless exactly one authorized device exists')
    return ready[0]


def test_count(output):
    summaries = re.findall(r'^OK \((\d+) tests?\)\s*$', output, re.MULTILINE)
    completed = re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', output, re.MULTILINE)
    if len(summaries) != 1 or completed != ['-1'] or int(summaries[0]) < 1 or any(x in output for x in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed', 'INSTRUMENTATION_ABORTED')):
        raise ValueError('Instrumentation failed or did not execute any tests')
    return int(summaries[0])


def _instrumentation_report(output):
    """AndroidJUnitRunner1.6.2 raw statuses; -3 ignored, -4 failed assumption.

    JUnit's OK summary excludes ignored tests but includes assumption failures.
    Each ignored test still emits a start/terminal pair from InstrumentationResultPrinter.
    """
    summary = test_count(output)
    fields, started, finished, declared = {}, set(), {}, set()
    for line in output.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, separator, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if separator and key in ('class', 'test', 'current', 'numtests'):
                fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            try:
                code = int(line.split(': ', 1)[1])
                current, total = int(fields['current']), int(fields['numtests'])
                identity = (fields['class'], fields['test'], current)
            except (ValueError, KeyError) as error:
                raise ValueError('Incomplete raw instrumentation test event') from error
            if not identity[0] or not identity[1] or not 1 <= current <= total:
                raise ValueError('Invalid raw instrumentation identity/count')
            declared.add(total)
            if code == 1:
                if identity in started: raise ValueError('Duplicate instrumentation start event')
                started.add(identity)
            elif code in (0, -1, -2, -3, -4):
                if identity not in started or identity in finished: raise ValueError('Missing start or duplicate terminal event')
                finished[identity] = code
            else:
                raise ValueError('Unexpected instrumentation status code')
            fields = {}
    if not finished or started != set(finished) or declared != {len(finished)}:
        raise ValueError('Missing/incomplete raw instrumentation events')
    if {identity[2] for identity in finished} != set(range(1, len(finished)+1)):
        raise ValueError('Instrumentation current indices are incomplete or duplicated')
    ignored = sum(code == -3 for code in finished.values())
    counts = {'tests': len(finished), 'passed': sum(code == 0 for code in finished.values()),
              'skipped': sum(code in (-3, -4) for code in finished.values()),
              'failed': sum(code in (-1, -2) for code in finished.values())}
    if counts['failed'] or counts['passed'] < 1 or summary != counts['tests'] - ignored:
        raise ValueError('Instrumentation summary disagrees with actual pass/skip/failure events')
    return {'counts': counts, 'events': [
        {'class': key[0], 'test': key[1], 'current': key[2], 'code': code}
        for key, code in finished.items()]}


def instrumentation_counts(output):
    return _instrumentation_report(output)['counts']


def suite_scope(name):
    raw = SUITES.read_bytes()
    manifest = json.loads(raw)
    if manifest.get('schema') != 'realityforge.android-suite-scopes.v1' or name not in ('debug', 'release'):
        raise ValueError('Unsupported checked-in instrumentation suite scope')
    scope = manifest['scopes'][name]
    exclusions = scope['excludedGates']
    if (not exclusions or len({row['gate'] for row in exclusions}) != len(exclusions)
            or not scope['requiredPassedSelectors']):
        raise ValueError('Invalid suite manifest gate/selector declarations')
    selectors = [row['selector'] for row in exclusions] + scope['requiredPassedSelectors']
    if any(not re.fullmatch(r'com\.realityforge\.scanner\.[A-Za-z0-9_.]+(?:#[A-Za-z0-9_]+)?', selector) for selector in selectors):
        raise ValueError('Invalid suite selector')
    return {'schema': 1, 'scope': name, 'manifestSha256': hashlib.sha256(raw).hexdigest(),
            'notClass': [row['selector'] for row in exclusions],
            'requiredPassedSelectors': scope['requiredPassedSelectors'],
            'excludedGates': [{**row, 'status': 'NOT_RUN'} for row in exclusions],
            'fullQualification': 'NOT_RUN'}


def suite_arguments(scope):
    return ['-e', 'notClass', ','.join(scope['notClass'])]


def _selector_matches(selector, event):
    owner, separator, method = selector.partition('#')
    return event['class'] == owner and (not separator or event['test'] == method)


def scoped_counts(output, scope):
    report = _instrumentation_report(output)
    for event in report['events']:
        if any(_selector_matches(selector, event) for selector in scope['notClass']):
            raise ValueError('An excluded specialist test executed; suite selection was not honored')
    for selector in scope['requiredPassedSelectors']:
        matching = [event for event in report['events'] if _selector_matches(selector, event)]
        if not matching or any(event['code'] != 0 for event in matching):
            raise ValueError('Required scope test did not actually pass: ' + selector)
    return report['counts']


def command(args, timeout=900):
    result = subprocess.run(args, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed ({result.returncode}): {result.stderr.strip()} {result.stdout[-2000:]}')
    return result.stdout


def run(args, execute=command, capture_logcat=capture_failure_logcat):
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    receipt = {'schema': 1, 'status': 'FAIL', 'runtime16kb': 'NOT_RUN', 'physicalCapture': 'NOT_RUN'}
    selected, identity = None, None
    receipt['startedAtUtc'] = utc_now()
    retain_logcat = getattr(args, 'retain_emulator_failure_logcat', False)
    receipt['logcat'] = {'status': 'NOT_CAPTURED' if retain_logcat else 'NOT_REQUESTED'}
    stale_logcat_error = None
    try:
        (output / 'logcat.txt').unlink(missing_ok=True)
    except OSError as error:
        # A stale artifact must never be labeled as a fresh successful capture.
        stale_logcat_error = str(error)[:512]
    try:
        receipt['suite'] = suite_scope(getattr(args, 'scope', 'debug'))
        for apk in (args.app_apk, args.test_apk):
            if not apk.is_file():
                raise ValueError('APK missing: ' + str(apk))
        selected = select_serial(execute(['adb', 'devices', '-l']), args.serial)
        adb = ['adb', '-s', selected]
        def shell(*parts):
            return execute(adb + ['shell', *parts]).strip()
        identity = {'serial': selected, 'fingerprint': shell('getprop', 'ro.build.fingerprint'),
                    'api': int(shell('getprop', 'ro.build.version.sdk')),
                    'abi': shell('getprop', 'ro.product.cpu.abi'),
                    'pageSize': int(shell('getconf', 'PAGESIZE')),
                    'emulator': shell('getprop', 'ro.kernel.qemu') == '1'}
        if identity['pageSize'] not in (4096, 16384):
            raise ValueError('Unsupported measured page size')
        for name, expected in [('api', args.expect_api), ('abi', args.expect_abi), ('pageSize', args.expect_page_size)]:
            if expected is not None and identity[name] != expected:
                raise ValueError(f'{name} differs from required matrix environment')
        receipt.update(device=identity, appSha256=hashlib.sha256(args.app_apk.read_bytes()).hexdigest(),
                       testSha256=hashlib.sha256(args.test_apk.read_bytes()).hexdigest())
        shell('rm', '-f', SCREEN)
        screenshot = output / 'photographic-viewer.png'
        screenshot.unlink(missing_ok=True)
        for apk in (args.app_apk, args.test_apk):
            execute(adb + ['install', '-r', '-t', str(apk)])
        for package, key in [('com.realityforge.scanner', 'appSha256'), ('com.realityforge.scanner.test', 'testSha256')]:
            installed = shell('pm', 'path', package).splitlines()
            if len(installed) != 1 or not installed[0].startswith('package:'):
                raise ValueError('Expected single installed base APK; candidate splits require complete package identity')
            if shell('sha256sum', installed[0][8:]).split()[0] != receipt[key]:
                raise ValueError('Installed package hash differs from selected candidate')
        try:
            transcript = shell('am', 'instrument', '-w', '-r', *suite_arguments(receipt['suite']),
                               'com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner')
        except subprocess.TimeoutExpired as error:
            # TimeoutExpired retains partial pipes even with text=True. Preserve the
            # original bytes for diagnosis; incomplete output must never earn counts.
            for name, data in [('instrumentation.txt', error.stdout), ('instrumentation.stderr.txt', error.stderr)]:
                (output / name).write_bytes(data.encode() if isinstance(data, str) else data or b'')
            receipt['instrumentationTimedOut'] = True
            raise
        (output / 'instrumentation.txt').write_text(transcript + '\n')
        receipt['counts'] = scoped_counts(transcript, receipt['suite'])
        receipt['tests'] = receipt['counts']['tests']
        execute(adb + ['pull', SCREEN, str(screenshot)])
        receipt['screenshotSize'] = validate(screenshot)
        receipt['status'] = 'PASS'
        receipt['runtime16kb'] = 'PASS' if identity['pageSize'] == 16384 else 'NOT_RUN'
    except Exception as error:
        receipt['error'] = str(error)
    finally:
        if selected:
            try:
                execute(['adb', '-s', selected, 'shell', 'rm', '-f', SCREEN])
            except Exception as error:
                receipt['cleanupError'] = str(error)
                receipt['status'] = 'FAIL'
        if retain_logcat:
            if receipt['status'] == 'PASS':
                receipt['logcat']['reason'] = 'run passed; failure-only collection'
            elif not selected or not identity or not identity['emulator'] or not re.fullmatch(r'emulator-[0-9]+', selected):
                receipt['logcat']['reason'] = 'verified emulator serial required; no physical-device logs collected'
            elif stale_logcat_error:
                receipt['logcat'] = {'status': 'ERROR', 'reason': 'stale log artifact could not be removed'}
            else:
                try:
                    receipt['logcat'] = capture_logcat(selected, identity['emulator'], output / 'logcat.txt')
                except Exception as error:
                    # Diagnostics cannot overwrite the original failure or counts.
                    receipt['logcat'] = {'status': 'ERROR', 'error': str(error)[:512]}
        if stale_logcat_error:
            receipt['logcat']['staleArtifactRemovalError'] = stale_logcat_error
        receipt['finishedAtUtc'] = utc_now()
        (output / 'compatibility.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return 0 if receipt['status'] == 'PASS' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial')
    parser.add_argument('--retain-emulator-failure-logcat', action='store_true',
                        help='Opt in only for disposable synthetic emulators: bounded failure logs; never physical-device logs')
    parser.add_argument('--scope', choices=['debug', 'release'], default='debug',
                        help='Checked-in suite routing; excluded required specialist gates remain NOT_RUN')
    parser.add_argument('--app-apk', type=Path, default=Path('app/build/outputs/apk/debug/app-debug.apk'))
    parser.add_argument('--test-apk', type=Path, default=Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
    parser.add_argument('--output', type=Path, default=Path('reports/device'))
    parser.add_argument('--expect-api', type=int)
    parser.add_argument('--expect-abi', choices=['arm64-v8a', 'x86_64'])
    parser.add_argument('--expect-page-size', type=int, choices=[4096, 16384])
    raise SystemExit(run(parser.parse_args()))

if __name__ == '__main__':
    main()
