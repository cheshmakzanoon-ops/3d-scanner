"""Execute the real installed import/calibration/render/export/reopen regression.

This produces instrumentation evidence only. Camera/edit/upgrade/recovery/offline
and physical-quality gates remain separate. No device means BLOCKED_EXTERNAL.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid

EXPECTED_CLASS = 'com.realityforge.scanner.qa.ReleaseJourneyTest'
EXPECTED_METHOD = 'importCalibrateReconstructInspectExportReopenPreservesOriginals'
EXPECTED_PACKAGES = ('com.realityforge.scanner', 'com.realityforge.scanner.test')


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def parse_instrumentation(text):
    """One selected AndroidJUnitRunner method, with complete raw event linkage.

    The test's custom sendStatus(0, rf099_evidence) is metadata inside its active
    start/end span. It never counts as an executed or passing JUnit method.
    """
    result = {'status': 'FAIL', 'executed_tests': 0, 'failed_tests': 0,
              'skipped_tests': 0, 'disabled_tests': 0, 'app_receipt': None,
              'events': []}
    def require(value, message):
        if not value:
            raise ValueError(message)
    try:
        require(isinstance(text, str) and len(text) <= 16 * 1024 * 1024, 'missing/oversized instrumentation transcript')
        require(not any(marker in text for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED', 'Process crashed')), 'instrumentation reported failure/abort')
        fields, started, finished, final = {}, False, False, False
        summaries, receipt_count = [], 0
        for line in text.splitlines():
            if not line.strip():
                continue
            require(not final, 'output after final instrumentation code')
            if line.startswith('INSTRUMENTATION_STATUS: '):
                key, separator, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
                key = key.strip()
                require(separator and key and key not in fields, 'malformed/duplicate instrumentation field')
                require(key in {'id', 'class', 'test', 'current', 'numtests', 'stream', 'stack', 'rf099_evidence'}, 'unknown instrumentation field')
                fields[key] = value.strip()
            elif line.startswith('INSTRUMENTATION_STATUS_CODE:'):
                match = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
                require(match is not None, 'malformed instrumentation event code')
                code = int(match[1])
                if 'rf099_evidence' in fields:
                    require(set(fields) == {'rf099_evidence'} and code == 0 and started and not finished and receipt_count == 0, 'unexpected/duplicate app receipt event')
                    def unique(pairs):
                        record = {}
                        for key, value in pairs:
                            require(key not in record, 'duplicate app receipt JSON key')
                            record[key] = value
                        return record
                    receipt = json.loads(fields['rf099_evidence'], object_pairs_hook=unique)
                    require(isinstance(receipt, dict) and type(receipt.get('schema')) is int and receipt['schema'] == 1, 'invalid app receipt schema')
                    require(receipt.get('kind') in ('synthetic', 'physical') and receipt.get('originals_preserved') is True, 'invalid app receipt provenance/preservation')
                    require(all(isinstance(receipt.get(key), str) and re.fullmatch(pattern, receipt[key]) for key, pattern in [('source_sha', '[0-9a-f]{40}'), ('artifact_sha256', '[0-9a-f]{64}'), ('export_sha256', '[0-9a-f]{64}')]), 'invalid app receipt artifact identity')
                    require(type(receipt.get('photo_count')) is int and 3 <= receipt['photo_count'] <= 12 and type(receipt.get('triangle_count')) is int and receipt['triangle_count'] > 0, 'invalid app receipt observed counts')
                    result['app_receipt'], receipt_count = receipt, 1
                else:
                    require({'class', 'test', 'current', 'numtests'} <= set(fields), 'incomplete raw test identity/count')
                    require(fields['class'] == EXPECTED_CLASS and fields['test'] == EXPECTED_METHOD, 'selected required method did not execute')
                    require(fields['current'] == '1' and fields['numtests'] == '1', 'selected-method indices/count are missing, duplicated or inconsistent')
                    require('id' not in fields or fields['id'] == 'AndroidJUnitRunner', 'unexpected instrumentation runner')
                    if code == 1:
                        require(not started and not finished, 'duplicate/out-of-order test start')
                        started = True
                    elif code in (0, -1, -2, -3, -4):
                        require(started and not finished, 'missing start or duplicate terminal test event')
                        finished = True
                        result['executed_tests'] = 1
                        result['failed_tests'] = int(code in (-1, -2))
                        result['skipped_tests'] = int(code in (-3, -4))
                        result['events'].append({'class': fields['class'], 'test': fields['test'], 'current': 1, 'numtests': 1, 'code': code})
                    else:
                        raise ValueError('unknown raw instrumentation event code')
                fields = {}
            elif line.startswith('INSTRUMENTATION_CODE:'):
                require(re.fullmatch(r'INSTRUMENTATION_CODE: -1', line) is not None and not fields and started and finished, 'missing/invalid final instrumentation code')
                require(summaries == [1], 'missing/duplicate/inconsistent JUnit summary')
                final = True
            else:
                summary = re.fullmatch(r'OK \((\d+) tests?\)', line)
                if summary:
                    require(finished and not fields, 'JUnit summary before completed test events')
                    summaries.append(int(summary[1]))
                elif line.startswith('INSTRUMENTATION_'):
                    require(line.startswith('INSTRUMENTATION_RESULT: stream=') and finished and not fields, 'unknown/out-of-order instrumentation control output')
        require(final and not fields and started and finished and receipt_count == 1, 'missing/incomplete instrumentation events or app receipt')
        require(result['executed_tests'] == 1 and not result['failed_tests'] and not result['skipped_tests'], 'required method failed/skipped')
        result['status'] = 'PASS'
    except (ValueError, TypeError, KeyError) as error:
        result['parse_error'] = str(error)
    return result


def verify_installed_package(adb, package, expected_sha256):
    """Read the actual expected package, refusing partial split identities."""
    installed = adb('shell', 'pm', 'path', package).strip().splitlines()
    if len(installed) != 1 or not installed[0].startswith('package:'):
        raise ValueError('Expected exactly one installed base APK for ' + package + '; splits require complete set validation')
    path = installed[0][8:]
    # adb shell joins its arguments remotely; never pass an untrusted shell token.
    if not re.fullmatch(r'/data/app/(?:[A-Za-z0-9_.=+~-]+/)+base\.apk', path):
        raise ValueError('Unsafe or unsupported installed APK path for ' + package)
    checksum = adb('shell', 'sha256sum', path).strip()
    match = re.fullmatch(r'([0-9a-f]{64})\s+\*?' + re.escape(path), checksum)
    if match is None or match[1] != expected_sha256:
        raise ValueError('Installed package hash differs from supplied original: ' + package)
    return {'package': package, 'path': path, 'sha256': match[1]}


def validate_bundle(root):
    if (root / 'manifest.json').stat().st_size > 200_000:
        raise ValueError('physical manifest exceeds bound')
    manifest = json.loads((root / 'manifest.json').read_text())
    if manifest.get('schema') != 1 or manifest.get('kind') != 'physical' or manifest.get('authorization_verified') is not True:
        raise ValueError('authorized physical bundle required')
    photos = manifest.get('photos')
    if not isinstance(photos, list) or not 3 <= len(photos) <= 12:
        raise ValueError('Current app profile requires3–12 physical photos')
    names = set()
    for photo in photos:
        name = photo.get('filename')
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', name) or name in ('.', '..') or name in names:
            raise ValueError('unsafe/duplicate physical filename')
        names.add(name)
        path = root / name
        if not path.resolve().is_relative_to(root.resolve()) or not 1 <= path.stat().st_size <= 42 * 1024 * 1024 or sha256(path) != photo.get('sha256'):
            raise ValueError('physical original identity/budget mismatch')
    if (root / 'calibration.json').stat().st_size > 10_000 or sha256(root / 'calibration.json') != manifest.get('calibration_sha256'):
        raise ValueError('same-lens calibration identity missing/mismatched')
    return manifest


def execute(candidate, apk, test_apk, serial, output, bundle=None):
    result = {'schema': 1, 'status': 'NOT_RUN', 'source_sha': candidate['source_sha'], 'artifact_sha256': candidate['artifact_sha256'], 'gate': 'instrumentation', 'blockers': [], 'not_covered': ['camera capture', 'editing', 'upgrade', 'process death', 'offline radio verification', 'quality', 'resources', 'accessibility', 'signing', 'external beta']}
    output.mkdir(parents=True, exist_ok=True)
    remote = None
    adb_prefix = ['adb'] + (['-s', serial] if serial else [])
    def adb(*args, timeout=60):
        run = subprocess.run(adb_prefix + list(args), capture_output=True, text=True, timeout=timeout)
        if run.returncode:
            raise RuntimeError('ADB command failed: ' + args[0])
        return run.stdout
    try:
        attached = subprocess.run(['adb', 'devices'], capture_output=True, text=True, timeout=15)
        devices = [line.split()[0] for line in attached.stdout.splitlines()[1:] if line.strip().endswith('\tdevice')]
        if attached.returncode or not devices or serial and serial not in devices:
            result.update(status='BLOCKED_EXTERNAL', blockers=['No selected authorized ADB device attached'])
            return result
        if serial is None:
            if len(devices) != 1:
                raise ValueError('Select an exact device serial; multiple devices attached')
            serial = devices[0]
            adb_prefix[:] = ['adb', '-s', serial]
        if sha256(apk) != candidate['artifact_sha256']:
            raise ValueError('APK differs from frozen candidate')
        test_sha256 = sha256(test_apk)
        result['test_apk_sha256'] = test_sha256
        qemu = adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
        environment = {'kind': 'emulator' if qemu else 'physical', 'emulator': qemu, 'identity': adb('shell', 'getprop', 'ro.build.fingerprint').strip(), 'abi': adb('shell', 'getprop', 'ro.product.cpu.abi').strip(), 'api': int(adb('shell', 'getprop', 'ro.build.version.sdk').strip()), 'device_id': hashlib.sha256(serial.encode()).hexdigest()[:16]}
        result['environment'] = environment
        if bundle:
            if qemu:
                raise ValueError('Physical capture execution requires physical handset')
            validate_bundle(bundle)
            remote = '/data/local/tmp/rf099-' + uuid.uuid4().hex[:16]
            adb('shell', 'mkdir', remote)
            for name in ['manifest.json', 'calibration.json'] + [p['filename'] for p in validate_bundle(bundle)['photos']]:
                adb('push', str(bundle / name), remote + '/' + name)
        adb('install', '-r', '-t', str(apk))
        adb('install', '-r', '-t', str(test_apk))
        result['installed_packages'] = [verify_installed_package(adb, package, checksum) for package, checksum in zip(EXPECTED_PACKAGES, (candidate['artifact_sha256'], test_sha256))]
        argv = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', EXPECTED_CLASS + '#' + EXPECTED_METHOD, '-e', 'rf099_source_sha', candidate['source_sha'], '-e', 'rf099_apk_sha256', candidate['artifact_sha256']]
        if remote:
            argv += ['-e', 'rf099_physical_dir', remote]
        argv += ['com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner']
        text = adb(*argv, timeout=300)
        log = output / 'instrumentation.txt'
        log.write_text(text)
        result.update(parse_instrumentation(text))
        if result['app_receipt'] is None or result['app_receipt'].get('artifact_sha256') != candidate['artifact_sha256'] or result['app_receipt'].get('source_sha') != candidate['source_sha']:
            result['status'] = 'FAIL'
        result['command'] = adb_prefix + argv
        result['evidence_sha256'] = sha256(log)
        if result['status'] != 'PASS':
            result['blockers'] = ['Instrumentation failed, skipped, incomplete, or lacked app receipt']
    except FileNotFoundError:
        result.update(status='BLOCKED_EXTERNAL', blockers=['ADB, candidate APK, test APK or physical input unavailable'])
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        result.update(status='FAIL', blockers=[str(error)])
    finally:
        if remote is not None:
            try:
                adb('shell', 'rm', '-rf', remote)
            except (OSError, RuntimeError, subprocess.SubprocessError):
                result['cleanup'] = 'NOT_RUN: remove only the recorded rf099 test staging directory'
        result['recorded_at'] = datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--serial')
    parser.add_argument('--physical-bundle', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = execute(json.loads(args.candidate.read_text()), args.apk, args.test_apk, args.serial, args.output, args.physical_bundle)
    (args.output / 'journey.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'blockers': report['blockers']}))
    return 0 if report['status'] == 'PASS' else 2 if report['status'] in ('NOT_RUN', 'BLOCKED_EXTERNAL') else 1


if __name__ == '__main__':
    raise SystemExit(main())
