#!/usr/bin/env python3
"""Run final release cases after a coordinated, source-bound emulator handoff."""
import argparse
import datetime
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def strict_native(text, returncode, selector):
    """Full AndroidJUnitRunner one-case protocol, including fields and summary."""
    if returncode or len(text.encode()) > 4 * 1024 * 1024:
        raise ValueError('Native instrumentation failed or exceeded output budget')
    if any(marker in text for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'AssumptionFailure', 'AssumptionViolatedException', 'Process crashed', 'shortMsg=')):
        raise ValueError('Native instrumentation contains failure or skip evidence')
    required = {'class', 'current', 'id', 'numtests', 'stream', 'test'}
    fields, events, results = {}, [], {}
    phase, continuation = 'tests', None
    class_name, method = selector.split('#')
    for line in text.splitlines():
        status = re.fullmatch(r'INSTRUMENTATION_STATUS: ([A-Za-z][A-Za-z0-9_]*)=(.*)', line)
        code = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        result = re.fullmatch(r'INSTRUMENTATION_RESULT: ([A-Za-z][A-Za-z0-9_]*)=(.*)', line)
        if status:
            key, value = status.groups()
            if phase != 'tests' or key not in required or key in fields:
                raise ValueError('Malformed or unknown native status field')
            fields[key] = value
            continuation = fields if key == 'stream' else None
        elif code:
            expected = {'class': class_name, 'test': method, 'current': '1', 'numtests': '1', 'id': 'AndroidJUnitRunner'}
            if (phase != 'tests' or len(events) >= 2 or fields.keys() != required or
                    code[1] != ('1' if not events else '0') or
                    any(fields[key] != value for key, value in expected.items())):
                raise ValueError('Native test identity/start/end mismatch or skipped/failed case')
            events.append(fields)
            fields, continuation = {}, None
        elif result:
            key, value = result.groups()
            if phase != 'tests' or len(events) != 2 or fields or results or key != 'stream':
                raise ValueError('Malformed or unknown native result')
            results[key] = value
            phase, continuation = 'summary', results
        elif line == 'INSTRUMENTATION_CODE: -1':
            if phase != 'summary' or not results:
                raise ValueError('Native final code out of order')
            phase, continuation = 'done', None
        elif line.startswith('INSTRUMENTATION_'):
            raise ValueError('Unknown native instrumentation record')
        elif continuation is not None:
            continuation['stream'] += '\n' + line
        elif line.strip():
            raise ValueError('Unexpected native output outside stream')
    if (phase != 'done' or len(events) != 2 or
            re.fullmatch(r'\s*Time: (?:0|[1-9]\d*)(?:\.\d+)?\s+OK \(1 tests?\)\s*', results.get('stream', '')) is None or
            len(re.findall(r'^OK \(', text, re.M)) != 1):
        raise ValueError('Native final summary incomplete')
    return {'status': 'PASS', 'test_identity': selector, 'started_count': 1, 'completed_count': 1, 'passed_count': 1, 'skipped_count': 0}


def bounded(command, output_path, timeout):
    """Kill the entire local process group on timeout, preserving partial output."""
    with output_path.open('x') as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
            # The leader may exit while its adb child ignores TERM.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
            raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--handoff-confirmed', action='store_true', required=True)
    parser.add_argument('--package-evidence', type=Path, required=True)
    parser.add_argument('--expected-campaign-source', required=True)
    parser.add_argument('--serial', required=True, help='Explicit handed-off software emulator serial')
    args = parser.parse_args()
    root = Path('/workspace/campaign/test100-release')
    package = args.package_evidence.resolve()
    evidence = package.parent / (package.name + '-runtime')
    evidence.mkdir(parents=True, exist_ok=True)
    # Callers may have created only their raw execution log and exact runner snapshot.
    if any(path.name not in {'execution.log', 'run-final-release-runtime.py'} for path in evidence.iterdir()):
        raise ValueError('Refusing to overwrite or mix a preserved runtime attempt')
    with (evidence / 'run-started.json').open('x') as started:
        started.write(json.dumps({'expectedCampaignSource': args.expected_campaign_source, 'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()}) + '\n')
    summary = json.loads((package / 'release-integration-summary.json').read_text())
    source = summary['source']['testedOverlayCommit']
    if summary['status'] != 'PASS' or summary['source']['campaignSourceSha'] != args.expected_campaign_source:
        raise ValueError('Explicit candidate identity disagrees with passing package evidence')
    names = {'app': 'app/build/outputs/apk/release/app-release.apk', 'test': 'app/build/outputs/apk/androidTest/release/app-release-androidTest.apk'}
    artifacts = {kind: package / 'artifacts' / name for kind, name in names.items()}
    expected_hashes = {kind: summary['artifactSha256'][name] for kind, name in names.items()}
    helper = root / 'tools/device/offline_privacy/journey.py'
    def check_source():
        if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip() != source:
            raise ValueError('Runtime helper source differs from the tested overlay')
        if subprocess.check_output(['git', 'status', '--porcelain'], cwd=root, text=True).strip():
            raise ValueError('Runtime helper source checkout must be clean')
        if any(sha(path) != expected_hashes[kind] for kind, path in artifacts.items()):
            raise ValueError('Frozen APK snapshot changed')
    serial = args.serial
    match = re.fullmatch(r'emulator-([0-9]{4,5})', serial)
    if not match or not 1024 <= int(match[1]) <= 65535:
        raise ValueError('Explicit emulator serial/port required')
    device_lock_path = Path('/workspace/campaign') / ('device-' + match[1] + '.lock')
    def adb(*parts, timeout=30):
        return subprocess.check_output(['adb', '-s', serial, *parts], text=True, stderr=subprocess.STDOUT, timeout=timeout).strip()
    record = {'schemaVersion': 1, 'sourceSha': source, 'campaignSourceSha': args.expected_campaign_source,
              'packageSummarySha256': sha(package / 'release-integration-summary.json'), 'artifactSha256': expected_hashes,
              'device': {'serial': serial, 'emulator': None}, 'physicalQualification': 'NOT_RUN',
              'productionSigning': 'NOT_RUN', 'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'status': 'NOT_RUN'}
    with device_lock_path.open('a') as device_lock:
        fcntl.flock(device_lock, fcntl.LOCK_EX)
        try:
            check_source()
            spec = importlib.util.spec_from_file_location('final_offline_journey', helper)
            journey = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(journey)
            record['helperSourceSha256'] = {str(helper.relative_to(root)): sha(helper)}
            if adb('get-state') != 'device' or adb('shell', 'getprop', 'sys.boot_completed') != '1' or adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
                raise ValueError('Handed-off emulator is not a ready booted software test target')
            record['device'].update(emulator=True, physical=False, fingerprint=adb('shell', 'getprop', 'ro.build.fingerprint'), api=adb('shell', 'getprop', 'ro.build.version.sdk'), abi=adb('shell', 'getprop', 'ro.product.cpu.abi'))
            keys = ('airplane_mode_on', 'wifi_on', 'mobile_data')
            before = {key: adb('shell', 'settings', 'get', 'global', key) for key in keys}
            if any(value not in {'0', '1'} for value in before.values()):
                raise ValueError('Original radio settings must be stable known0/1 values for exact restoration')
            record['radioOriginal'] = before
        except (ValueError, OSError, subprocess.SubprocessError) as error:
            record['blockers'] = [str(error)]
            (evidence / 'summary.json').write_text(json.dumps(record, indent=2) + '\n')
            print(json.dumps(record, indent=2))
            return 2
        try:
            adb('shell', 'settings', 'put', 'global', 'airplane_mode_on', '1')
            adb('shell', 'am', 'broadcast', '-a', 'android.intent.action.AIRPLANE_MODE', '--ez', 'state', 'true')
            adb('shell', 'svc', 'wifi', 'disable')
            adb('shell', 'svc', 'data', 'disable')
            for key in ('wifi_on', 'mobile_data'):
                adb('shell', 'settings', 'put', 'global', key, '0')
            command = [sys.executable, str(helper), '--serial', serial, '--source-sha', source, '--apk', str(artifacts['app']), '--test-apk', str(artifacts['test']), '--install', '--output', str(evidence / 'offline-journey.json')]
            offline_code = bounded(command, evidence / 'offline-controller.log', 1200)
            offline = json.loads((evidence / 'offline-journey.json').read_text())
            record.update(offlineControllerExitCode=offline_code, offlineJourney=offline)
            for key, expected in [('source_sha', source), ('artifact_sha256', expected_hashes['app']), ('test_apk_sha256', expected_hashes['test']), ('installed_apk_sha256', expected_hashes['app']), ('installed_test_apk_sha256', expected_hashes['test'])]:
                if offline.get(key) != expected:
                    raise ValueError('Offline controller identity mismatch: ' + key)
            transcript = (evidence / 'offline-journey.instrumentation.log').read_text()
            if offline_code or offline['status'] != 'PASS' or not journey.completed(transcript):
                raise ValueError('Offline journey failed its independent full transcript gate')
            record['installedAppSha256'] = journey.installed_hash(adb, 'com.realityforge.scanner')
            record['installedTestSha256'] = journey.installed_hash(adb, 'com.realityforge.scanner.test')
            if record['installedAppSha256'] != expected_hashes['app'] or record['installedTestSha256'] != expected_hashes['test']:
                raise ValueError('Installed release app/test identity changed')
            adb('shell', 'am', 'force-stop', 'com.realityforge.scanner')
            record['separateNativeInvocation'] = True
            selector = 'com.realityforge.scanner.qa.ReleaseNativeContractTest#installedCandidateIsMinifiedReleaseAndNativeExportsReopen'
            command = ['adb', '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', selector, 'com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner']
            record['releaseNativeCommand'] = command
            code = bounded(command, evidence / 'release-native.instrumentation.log', 900)
            record['releaseNative'] = strict_native((evidence / 'release-native.instrumentation.log').read_text(), code, selector)
            record['status'] = 'PASS'
        except (ValueError, OSError, subprocess.SubprocessError) as error:
            record.update(status='FAIL', error=str(error))
        finally:
            # Terminate candidate processes before restoring radios or handing off.
            try:
                for name in ('com.realityforge.scanner.test', 'com.realityforge.scanner'):
                    adb('shell', 'am', 'force-stop', name)
                processes = adb('shell', 'ps', '-A', '-o', 'NAME').splitlines()[1:]
                if any(line.strip() in {'com.realityforge.scanner', 'com.realityforge.scanner.test'} or line.strip().startswith(('com.realityforge.scanner:', 'com.realityforge.scanner.test:')) for line in processes):
                    raise ValueError('Candidate processes remain active after force-stop')
                record['candidateProcessesStopped'] = True
            except (ValueError, OSError, subprocess.SubprocessError) as error:
                record.setdefault('cleanupErrors', []).append(str(error))
            for key, value in before.items():
                try:
                    adb('shell', 'settings', 'put', 'global', key, value)
                except (ValueError, OSError, subprocess.SubprocessError) as error:
                    record.setdefault('restoreErrors', []).append(str(error))
            for name, key in [('wifi', 'wifi_on'), ('data', 'mobile_data')]:
                try:
                    adb('shell', 'svc', name, 'enable' if before[key] == '1' else 'disable')
                except (ValueError, OSError, subprocess.SubprocessError) as error:
                    record.setdefault('restoreErrors', []).append(str(error))
            try:
                adb('shell', 'am', 'broadcast', '-a', 'android.intent.action.AIRPLANE_MODE', '--ez', 'state', 'true' if before['airplane_mode_on'] == '1' else 'false')
                record['radioAfterRestore'] = {key: adb('shell', 'settings', 'get', 'global', key) for key in keys}
                if record['radioAfterRestore'] != before:
                    raise ValueError('Actual radio settings differ after restoration')
            except (ValueError, OSError, subprocess.SubprocessError) as error:
                record.setdefault('restoreErrors', []).append(str(error))
            if record.get('cleanupErrors') or record.get('restoreErrors'):
                record['status'] = 'FAIL'
        record['finishedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        record['evidenceSha256'] = {str(path.relative_to(evidence)): sha(path) for path in evidence.iterdir() if path.is_file() and path.name not in {'summary.json', 'execution.log'}}
        (evidence / 'summary.json').write_text(json.dumps(record, indent=2) + '\n')
        print(json.dumps(record, indent=2))
        return 0 if record['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
