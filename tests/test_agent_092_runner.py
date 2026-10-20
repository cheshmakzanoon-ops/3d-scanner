"""Synthetic adb/controller regressions, never Android runtime evidence."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import pytest

spec = importlib.util.spec_from_file_location('compatibility_runner', Path(__file__).resolve().parents[1] / 'tools/device/compatibility/runner.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def test_selection_rejects_ambiguity_and_unauthorized():
    listing = 'List of devices attached\na device\nb unauthorized\nc device\n'
    assert m.select_serial(listing, 'a') == 'a'
    with pytest.raises(ValueError): m.select_serial(listing)
    with pytest.raises(ValueError): m.select_serial(listing, 'b')

@pytest.mark.parametrize('output', ['OK (0 tests)', 'INSTRUMENTATION_FAILED', 'OK (3 tests)\nProcess crashed', 'Time: 1'])
def test_zero_tests_crashes_and_incomplete_runs_rejected(output):
    with pytest.raises(ValueError): m.test_count(output)


def test_success_counts_real_runner_summary():
    assert m.test_count('Time: 1.29\n\nOK (32 tests)\nINSTRUMENTATION_CODE: -1') == 32


def test_missing_screenshot_fails_and_cleanup_is_serial_bound(tmp_path):
    apk = tmp_path / 'candidate.apk'; apk.write_bytes(b'synthetic controller input')
    args = SimpleNamespace(output=tmp_path / 'evidence', app_apk=apk, test_apk=apk,
                           serial='physical', expect_api=36, expect_abi='arm64-v8a', expect_page_size=16384)
    commands = []
    def execute(cmd):
        commands.append(cmd)
        if cmd == ['adb', 'devices', '-l']: return 'List of devices attached\nphysical device\n'
        assert cmd[:3] == ['adb', '-s', 'physical']
        key = ' '.join(cmd[4:])
        return {'getprop ro.build.fingerprint': 'synthetic', 'getprop ro.build.version.sdk': '36',
                'getprop ro.product.cpu.abi': 'arm64-v8a', 'getconf PAGESIZE': '16384',
                'getprop ro.kernel.qemu': '0'}.get(key, 'OK (32 tests)')
    assert m.run(args, execute) == 1
    import json
    receipt = json.loads((args.output / 'compatibility.json').read_text())
    assert receipt['status'] == 'FAIL'
    assert receipt['runtime16kb'] == 'NOT_RUN'
    assert commands[-1][-3:] == ['rm', '-f', m.SCREEN]
