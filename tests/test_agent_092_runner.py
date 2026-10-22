"""Synthetic adb/controller regressions, never Android runtime evidence."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import pytest

spec = importlib.util.spec_from_file_location('compatibility_runner', Path(__file__).resolve().parents[1] / 'tools/device/compatibility/runner.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def transcript(codes=(0,)*32, selectors=None):
    text=[]
    selectors = selectors or m.suite_scope('debug')['requiredPassedSelectors']
    for index,code in enumerate(codes,1):
        owner, method = selectors[index-1].split('#') if index <= len(selectors) else ('synthetic.ParserFixture',f'case{index}')
        fields=f'INSTRUMENTATION_STATUS: class={owner}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS: current={index}\nINSTRUMENTATION_STATUS: numtests={len(codes)}\n'
        text.extend([fields+'INSTRUMENTATION_STATUS_CODE: 1\n',fields+f'INSTRUMENTATION_STATUS_CODE: {code}\n'])
    text.append(f"OK ({len(codes)-sum(code==-3 for code in codes)} tests)\nINSTRUMENTATION_CODE: -1")
    return ''.join(text)


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
                'getprop ro.kernel.qemu': '0'}.get(key, transcript())
    assert m.run(args, execute) == 1
    import json
    receipt = json.loads((args.output / 'compatibility.json').read_text())
    assert receipt['status'] == 'FAIL'
    assert receipt['runtime16kb'] == 'NOT_RUN'
    assert commands[-1][-3:] == ['rm', '-f', m.SCREEN]


@pytest.mark.parametrize('partial',[b'INSTRUMENTATION_STATUS: class=synthetic.Slow\n', 'INSTRUMENTATION_STATUS: class=synthetic.Slow\n'])
def test_timeout_preserves_partial_raw_output_without_counts_or_gate_promotion(tmp_path, partial):
    import hashlib,json,subprocess
    apk=tmp_path/'candidate.apk';apk.write_bytes(b'synthetic timeout fixture')
    args=SimpleNamespace(output=tmp_path/'output',app_apk=apk,test_apk=apk,serial='fixture',expect_api=29,expect_abi='x86_64',expect_page_size=4096)
    commands=[]
    def execute(cmd):
        commands.append(cmd)
        if cmd==['adb','devices','-l']:return 'List of devices attached\nfixture device\n'
        assert cmd[:3]==['adb','-s','fixture']
        if 'instrument' in cmd:raise subprocess.TimeoutExpired(cmd,900,output=partial,stderr=b'bounded diagnostic\n')
        key=' '.join(cmd[4:])
        if key.startswith('pm path '):return 'package:/data/app/fixture.apk'
        if key.startswith('sha256sum '):return hashlib.sha256(apk.read_bytes()).hexdigest()+' /data/app/fixture.apk'
        return {'getprop ro.build.fingerprint':'synthetic timeout fixture','getprop ro.build.version.sdk':'29','getprop ro.product.cpu.abi':'x86_64','getconf PAGESIZE':'4096','getprop ro.kernel.qemu':'1'}.get(key,'')
    assert m.run(args,execute)==1
    assert (args.output/'instrumentation.txt').read_bytes()==(partial.encode() if isinstance(partial,str) else partial)
    assert (args.output/'instrumentation.stderr.txt').read_bytes()==b'bounded diagnostic\n'
    receipt=json.loads((args.output/'compatibility.json').read_text())
    assert receipt['status']=='FAIL' and receipt['instrumentationTimedOut'] is True
    assert 'counts' not in receipt and 'tests' not in receipt
    assert receipt['runtime16kb']=='NOT_RUN' and receipt['suite']['fullQualification']=='NOT_RUN'
    assert all(row['status']=='NOT_RUN' for row in receipt['suite']['excludedGates'])
    assert commands[-1][-3:]==['rm','-f',m.SCREEN]


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
        if 'instrument' in cmd:
            assert cmd[cmd.index('notClass')+1]==','.join(m.suite_scope('debug')['notClass'])
        key=' '.join(cmd[4:])
        if key.startswith('pm path '):return 'package:/data/app/fixture.apk'
        if key.startswith('sha256sum '):return hashlib.sha256(apk.read_bytes()).hexdigest()+' /data/app/fixture.apk'
        return {'getprop ro.build.fingerprint':'synthetic parser fixture','getprop ro.build.version.sdk':'36','getprop ro.product.cpu.abi':'arm64-v8a','getconf PAGESIZE':'16384','getprop ro.kernel.qemu':'0'}.get(key,transcript())
    assert m.run(args,execute)==0
    receipt=json.loads((args.output/'compatibility.json').read_text())
    assert receipt['runtime16kb']=='PASS' and receipt['physicalCapture']=='NOT_RUN'
    assert receipt['screenshotSize']==[128,128]
    assert receipt['suite']['scope']=='debug'
    assert receipt['suite']['manifestSha256']==hashlib.sha256(m.SUITES.read_bytes()).hexdigest()
    assert receipt['suite']['fullQualification']=='NOT_RUN'
    assert all(row['status']=='NOT_RUN' and row['reason'] for row in receipt['suite']['excludedGates'])
    def wrong(cmd):return 'b'*64+' bad.apk' if 'sha256sum' in cmd else execute(cmd)
    assert m.run(args,wrong)==1
    receipt=json.loads((args.output/'compatibility.json').read_text())
    assert 'Installed package hash differs' in receipt['error'] and receipt['runtime16kb']=='NOT_RUN'



def test_raw_android_statuses_keep_assumptions_and_ignored_separate_from_passes():
    assert m.instrumentation_counts(transcript((0,-4,-3)))=={'tests':3,'passed':1,'skipped':2,'failed':0}

@pytest.mark.parametrize('text',[
    transcript((0,)).replace('INSTRUMENTATION_STATUS_CODE: 0','INSTRUMENTATION_STATUS_CODE: 1'),
    transcript((0,)).replace('numtests=1','numtests=2'),
    transcript((0,)).replace('OK (1 tests)','OK (2 tests)'),
    transcript((-4,)),transcript((-3,)),transcript((0,-2)),
    'OK (1 test)',
])
def test_incomplete_zero_pass_failed_or_forged_status_events_fail(text):
    with pytest.raises(ValueError):m.instrumentation_counts(text)


def test_different_names_cannot_reuse_current_and_mask_a_missing_test_index():
    text=transcript((0,0)).replace('current=2','current=1')
    with pytest.raises(ValueError,match='current indices'):m.instrumentation_counts(text)


@pytest.mark.parametrize('text',[transcript((0,)).replace('INSTRUMENTATION_CODE: -1',''),transcript((0,)).replace('INSTRUMENTATION_CODE: -1','INSTRUMENTATION_CODE: 0'),transcript((0,)).replace('OK (1 tests)','INSTRUMENTATION_STATUS: stream=OK (1 tests)')])
def test_missing_failed_or_embedded_completion_cannot_qualify(text):
    with pytest.raises(ValueError):m.instrumentation_counts(text)


def test_debug_suite_excludes_only_named_specialist_contracts_and_routes_release_separately():
    debug=m.suite_scope('debug');release=m.suite_scope('release')
    assert {row['gate'] for row in debug['excludedGates']}=={'physical_capture','external_recovery','physical_resource_profile','offline_radios_journey','minified_release','dedicated_candidate_journey','physical_capture_ui','camera_permission_ui'}
    assert 'com.realityforge.scanner.qa.ReleaseNativeContractTest' in debug['notClass']
    assert 'com.realityforge.scanner.qa.ReleaseNativeContractTest' not in release['notClass']
    assert any(s.startswith('com.realityforge.scanner.qa.ReleaseNativeContractTest#') for s in release['requiredPassedSelectors'])
    assert 'com.realityforge.scanner.agents.a087.ResourceProbeContractTest' in release['notClass']
    for scope in (debug,release):
        assert m.suite_arguments(scope)==['-e','notClass',','.join(scope['notClass'])]
        # No positive class filter: ordinary discovery still runs baseline and new tests.
        assert 'class' not in m.suite_arguments(scope)
        assert not any(s.startswith('com.realityforge.scanner.NativeSmokeTest') or s.startswith('com.realityforge.scanner.qa.CompatibilityContractTest') or s.startswith('com.realityforge.scanner.SessionReliabilityTest') for s in scope['notClass'])
        assert all(row['status']=='NOT_RUN' and row['reason'] for row in scope['excludedGates'])
        assert scope['fullQualification']=='NOT_RUN'


def test_required_baseline_and_new_compatibility_contract_must_really_pass():
    scope=m.suite_scope('debug')
    with pytest.raises(ValueError,match='did not actually pass'):m.scoped_counts(transcript((0,-4)),scope)
    with pytest.raises(ValueError,match='did not actually pass'):m.scoped_counts(transcript((0,),['synthetic.MissingBaseline#case']),scope)
    assert m.scoped_counts(transcript((0,0,-4)),scope)=={'tests':3,'passed':2,'skipped':1,'failed':0}


@pytest.mark.parametrize('scope_name,selector,code',[
    ('debug','com.realityforge.scanner.qa.PhysicalCaptureContractTest#savedPhysicalOriginalsDecodeWithSelectedCameraAndExactHashes',0),
    ('debug','com.realityforge.scanner.qa.ReleaseNativeContractTest#installedCandidateIsMinifiedReleaseAndNativeExportsReopen',0),
    ('release','com.realityforge.scanner.agents.a087.ResourceProbeContractTest#debugProbeRecordsActualSameProcessCounters',-4),
    ('debug','com.realityforge.scanner.qa.ReleaseJourneyTest#dedicatedCandidateJourney',0),
    ('release','com.realityforge.scanner.qa.ReleaseJourneyTest#dedicatedCandidateJourney',0),
])
def test_excluded_gate_execution_or_skip_cannot_become_a_scope_pass(scope_name,selector,code):
    scope=m.suite_scope(scope_name);required=scope['requiredPassedSelectors']
    with pytest.raises(ValueError,match='excluded specialist'):
        m.scoped_counts(transcript((0,)*len(required)+(code,),required+[selector]),scope)


def test_release_scope_requires_actual_minified_release_contract_and_keeps_physical_unrun():
    release=m.suite_scope('release');required=release['requiredPassedSelectors']
    with pytest.raises(ValueError,match='ReleaseNativeContractTest'):
        m.scoped_counts(transcript((0,0)),release)
    assert m.scoped_counts(transcript((0,)*len(required),required),release)['passed']==3
    assert {row['gate']:row['status'] for row in release['excludedGates']}=={'physical_capture':'NOT_RUN','external_recovery':'NOT_RUN','physical_resource_profile':'NOT_RUN','offline_radios_journey':'NOT_RUN','dedicated_candidate_journey':'NOT_RUN','physical_capture_ui':'NOT_RUN','camera_permission_ui':'NOT_RUN'}


def test_dedicated_candidate_metadata_is_excluded_without_relaxing_junit_accounting():
    for name in ('debug','release'):
        scope=m.suite_scope(name)
        row=next(row for row in scope['excludedGates'] if row['gate']=='dedicated_candidate_journey')
        assert row['selector']=='com.realityforge.scanner.qa.ReleaseJourneyTest'
        assert row['status']=='NOT_RUN' and 'source/APK/installed-package' in row['reason'] and 'physical-bundle' in row['reason']
        assert row['selector'] in scope['notClass']
    metadata='INSTRUMENTATION_STATUS: rf099_evidence={"source":"synthetic-parser-fixture"}\nINSTRUMENTATION_STATUS_CODE: 0\n'
    text=metadata+transcript((0,0))
    with pytest.raises(ValueError,match='Incomplete raw instrumentation'):m.instrumentation_counts(text)
    with pytest.raises(ValueError,match='Incomplete raw instrumentation'):m.scoped_counts(text,m.suite_scope('debug'))


@pytest.mark.parametrize('scope_name', ['debug', 'release'])
@pytest.mark.parametrize('selector', [
    'com.realityforge.scanner.uiflow.PhysicalCaptureJourneyTest#capturesWithRealCameraAndReopensTheSameOriginal',
    'com.realityforge.scanner.uiflow.CameraPermissionJourneyTest#deniedThenGrantedCameraFlowKeepsTheInitiatingProjectAndOriginals',
])
@pytest.mark.parametrize('code', [0, -3, -4])
def test_controller_owned_ui_gates_cannot_qualify_ordinary_scope(scope_name, selector, code):
    scope = m.suite_scope(scope_name)
    required = scope['requiredPassedSelectors']
    with pytest.raises(ValueError, match='excluded specialist'):
        m.scoped_counts(transcript((0,) * len(required) + (code,), required + [selector]), scope)


def test_ordinary_ui_and_memory_checks_remain_discoverable():
    for name in ('debug', 'release'):
        scope = m.suite_scope(name)
        for owner in ('uiflow.ActualUiJourneyTest', 'uiflow.InterruptionUiJourneyTest',
                      'agents.a032.FeatureMemoryDeviceTest', 'agents.a071.NavigationLifecycleDeviceTest'):
            assert not any(selector.startswith('com.realityforge.scanner.' + owner) for selector in scope['notClass'])
