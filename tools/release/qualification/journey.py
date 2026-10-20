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


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def parse_instrumentation(text):
    parts = re.split(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$', text, flags=re.M)
    statuses = []
    for offset in range(0, len(parts)-1, 2):
        block, code = parts[offset], int(parts[offset+1])
        # Custom sendStatus bundles also use code0; count actual JUnit cases only.
        if re.search(r'^INSTRUMENTATION_STATUS: class\s*=\s*\S+', block, re.M) and re.search(r'^INSTRUMENTATION_STATUS: test\s*=\s*\S+', block, re.M):
            statuses.append(code)
    passed = statuses.count(0)
    skipped = statuses.count(-3) + statuses.count(-4)
    failed = statuses.count(-1) + statuses.count(-2)
    total = re.search(r'OK \((\d+) tests?\)', text)
    complete = 'INSTRUMENTATION_CODE: -1' in text and total is not None and int(total[1]) == passed+failed+skipped
    receipt = re.findall(r'^INSTRUMENTATION_STATUS: rf099_evidence=(.*)$', text, flags=re.M)
    # Android's field printer may use spaces around '=' depending on runner.
    if not receipt:
        receipt = re.findall(r'^INSTRUMENTATION_STATUS: rf099_evidence = (.*)$', text, flags=re.M)
    return {'status': 'PASS' if complete and passed >= 1 and not failed and not skipped and len(receipt) == 1 else 'FAIL',
            'executed_tests': passed + failed + skipped, 'failed_tests': failed,
            'skipped_tests': skipped, 'disabled_tests': 0,
            'app_receipt': json.loads(receipt[0]) if len(receipt) == 1 else None}


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
        adb('install', '-r', str(apk))
        adb('install', '-r', str(test_apk))
        argv = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.realityforge.scanner.qa.ReleaseJourneyTest', '-e', 'rf099_source_sha', candidate['source_sha'], '-e', 'rf099_apk_sha256', candidate['artifact_sha256']]
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
        result['test_apk_sha256'] = sha256(test_apk)
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
