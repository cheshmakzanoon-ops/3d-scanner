#!/usr/bin/env python3
"""Bounded installed synthetic UI journey plus one real Filament lifecycle check."""
from __future__ import annotations
import hashlib
import importlib.util
import json
import os
import signal
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
UI_PATH = ROOT / 'tools/android-ui-e2e/run.py'
spec = importlib.util.spec_from_file_location('installed_ui_evidence', UI_PATH)
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)
APP = 'com.realityforge.scanner'
RUNNER = APP + '.test/androidx.test.runner.AndroidJUnitRunner'
SERIAL = 'emulator-5554'
FILAMENT_METHOD = 'com.realityforge.scanner.PhotographicViewerTest#filamentRestoresCameraPausesAndRejectsOversizedResize'


def verify_installed(commands, candidate):
    for package, key in ((APP, 'apk_sha256'), (APP + '.test', 'test_apk_sha256')):
        result = commands.shell('pm', 'path', package)
        lines = result.stdout.strip().splitlines()
        if result.returncode or len(lines) != 1 or not lines[0].startswith('package:/'):
            raise ValueError('Expected exactly one installed base APK for ' + package)
        installed = commands.shell('sha256sum', lines[0][len('package:'):])
        fields = installed.stdout.split()
        if installed.returncode or not fields or fields[0] != candidate[key]:
            raise ValueError('Installed APK hash differs from built candidate: ' + package)


def run_filament(commands, candidate, output):
    receipt = {'status': 'FAIL', 'test_identity': FILAMENT_METHOD, 'candidate': candidate,
               'scope': 'ONE_INSTALLED_FILAMENT_LIFECYCLE_TEST_NOT_FULL_DEVICE_QUALIFICATION'}
    try:
        verify_installed(commands, candidate)
        # Stop only these disposable test packages, including a timed-out prior instrumentation.
        for package in (APP + '.test', APP):
            stopped = commands.shell('am', 'force-stop', package)
            if stopped.returncode:
                raise ValueError('Could not isolate lifecycle test from prior instrumentation')
        result = commands.shell('am', 'instrument', '-w', '-r', '-e', 'class', FILAMENT_METHOD, RUNNER,
                                timeout=300)
        (output / 'filament.stdout.txt').write_text(result.stdout)
        (output / 'filament.stderr.txt').write_text(result.stderr)
        receipt.update(ui.parse_instrumentation(result.stdout, result.returncode, FILAMENT_METHOD))
        receipt['stdout_sha256'] = hashlib.sha256(result.stdout.encode()).hexdigest()
    except Exception as error:
        receipt['error'] = str(error)
    (output / 'filament.json').write_text(json.dumps(receipt, indent=2))
    return receipt['status'] == 'PASS'


def main():
    if len(sys.argv) != 1 or not ui.CLASS.fullmatch(FILAMENT_METHOD) or '#' not in FILAMENT_METHOD:
        raise ValueError('Only the fixed reviewed installed tests may execute')
    temp = Path(os.environ['RUNNER_TEMP'])
    output = temp / 'rf-installed-journey'
    output.mkdir(exist_ok=False)
    app = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    test = ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    build = temp / 'rf-installed-build.json'
    source = os.environ['GITHUB_SHA']
    receipt = {'status': 'FAIL', 'scope': 'SYNTHETIC_INSTALLED_UI_AND_FILAMENT_NOT_PHYSICAL_QUALITY'}
    commands = ui.Commands('adb', SERIAL, 60)
    try:
        candidate = ui.source_binding(ROOT, source, app, test, build)
        device = ui.identify_device(commands)
        if (device['api'] != 35 or not device['emulator']
                or device['properties']['ro.product.cpu.abi'] != 'x86_64'):
            raise ValueError('Expected the approved API35 x86_64 emulator')
        receipt.update(candidate=candidate, device=device)
        invocation = [sys.executable, str(UI_PATH), '--source-sha', source, '--apk', str(app),
            '--test-apk', str(test), '--build-receipt', str(build), '--serial', SERIAL,
            '--scenario', 'synthetic_import_reconstruct_export_reopen', '--timeout', '900',
            '--output', str(output / 'ui')]
        with (output / 'ui-runner.stdout.txt').open('w') as stdout, (output / 'ui-runner.stderr.txt').open('w') as stderr:
            try:
                process = subprocess.Popen(invocation, stdout=stdout, stderr=stderr, start_new_session=True)
                receipt['ui_returncode'] = process.wait(timeout=1200)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait(timeout=10)
                receipt['ui_returncode'] = None
                receipt['ui_error'] = 'UI runner exceeded its 1200-second outer deadline'
        # The lifecycle test is independent of UI assertions, but must use installed candidate bytes.
        receipt['filament_passed'] = run_filament(commands, candidate, output)
        if receipt['ui_returncode'] == 0 and receipt['filament_passed']:
            receipt['status'] = 'PASS'
    except Exception as error:
        receipt['error'] = str(error)
    receipt['commands'] = commands.records
    (output / 'summary.json').write_text(json.dumps(receipt, indent=2))
    print(json.dumps({'status': receipt['status'], 'scope': receipt['scope']}))
    return 0 if receipt['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
