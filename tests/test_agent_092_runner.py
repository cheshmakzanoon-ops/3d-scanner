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
        if key.startswith('pm path '): return 'package:/data/app/synthetic.apk'
        if key.startswith('sha256sum '):
            import hashlib
            return hashlib.sha256(apk.read_bytes()).hexdigest() + ' /data/app/synthetic.apk'
        return {'getprop ro.build.fingerprint': 'synthetic', 'getprop ro.build.version.sdk': '36',
                'getprop ro.product.cpu.abi': 'arm64-v8a', 'getconf PAGESIZE': '16384',
                'getprop ro.kernel.qemu': '0'}.get(key, 'OK (32 tests)')
    assert m.run(args, execute) == 1
    import json
    receipt = json.loads((args.output / 'compatibility.json').read_text())
    assert receipt['status'] == 'FAIL'
    assert receipt['runtime16kb'] == 'NOT_RUN'
    assert commands[-1][-3:] == ['rm', '-f', m.SCREEN]


def test_complete_candidate_run_records_actual_runtime16k_and_decoded_screenshot(tmp_path):
    import hashlib,json,struct,zlib
    apk=tmp_path/'candidate.apk';apk.write_bytes(b'synthetic APK input for controller testing')
    args=SimpleNamespace(output=tmp_path/'output',app_apk=apk,test_apk=apk,serial='fixture',expect_api=36,expect_abi='arm64-v8a',expect_page_size=16384)
    def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',128,128,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\x10\x20\x30'*128)*128))+chunk(b'IEND',b'')
    def execute(cmd):
        if cmd==['adb','devices','-l']:return 'List of devices attached\nfixture device\n'
        assert cmd[:3]==['adb','-s','fixture']
        if cmd[3]=='pull':Path(cmd[-1]).write_bytes(png);return ''
        key=' '.join(cmd[4:])
        if key.startswith('pm path '):return 'package:/data/app/fixture.apk'
        if key.startswith('sha256sum '):return hashlib.sha256(apk.read_bytes()).hexdigest()+' /data/app/fixture.apk'
        return {'getprop ro.build.fingerprint':'synthetic parser fixture','getprop ro.build.version.sdk':'36','getprop ro.product.cpu.abi':'arm64-v8a','getconf PAGESIZE':'16384','getprop ro.kernel.qemu':'0'}.get(key,'OK (32 tests)')
    assert m.run(args,execute)==0
    receipt=json.loads((args.output/'compatibility.json').read_text())
    assert receipt['runtime16kb']=='PASS' and receipt['physicalCapture']=='NOT_RUN'
    assert receipt['screenshotSize']==[128,128]
    def wrong(cmd):return 'b'*64+' bad.apk' if 'sha256sum' in cmd else execute(cmd)
    assert m.run(args,wrong)==1
    receipt=json.loads((args.output/'compatibility.json').read_text())
    assert 'Installed package hash differs' in receipt['error'] and receipt['runtime16kb']=='NOT_RUN'
