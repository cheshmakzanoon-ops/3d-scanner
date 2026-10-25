"""Synthetic receipt unit tests, never physical release evidence."""
import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import pytest
from stdlib_cli_boundary import missing_release_records_cli

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.release.qualification.aggregate import qualify, POLICY_PATH, validate_record, load_json
from tools.release.qualification.aggregate import check_acceptance
from tools.release.qualification.branches import index
from tools.release.qualification.journey import parse_instrumentation, validate_bundle, execute
from tools.release.qualification.journey import EXPECTED_CLASS, EXPECTED_METHOD
from tools.release.qualification.quality_adapter import adapt

NOW = datetime(2026, 10, 20, 2, tzinfo=timezone.utc)


def fixtures():
    candidate = {'schema': 1, 'source_sha': '1'*40, 'artifact_sha256': '2'*64, 'run_id': 'SYNTHETIC-UNIT-RECEIPTS', 'created_at': '2026-10-20T00:00:00Z', 'policy_sha256': hashlib.sha256(POLICY_PATH.read_bytes()).hexdigest(), 'protocols': {g: '6'*64 for g in ('geometry', 'texture', 'resources')}, 'scenes': [{'id': 'analytic-' + str(i), 'kind': 'physical', 'sha256': str(i)*64, 'reference_sha256': '5'*64, 'authorization_verified': True} for i in (3, 4)], 'devices': [{'id': 'UNIT-FAKE-DEVICE', 'identity': 'UNIT TEST ONLY, NOT REAL HANDSET', 'kind': 'physical'}]}
    records = []
    policy = json.loads(POLICY_PATH.read_text())
    for gate in policy['gates']:
        for scene in candidate['scenes'] if gate.get('per_scene') else [None]:
            record = {'schema': 1, 'agent': gate['agent'], 'gate': gate['id'], 'source_sha': candidate['source_sha'], 'artifact_sha256': candidate['artifact_sha256'], 'run_id': candidate['run_id'], 'recorded_at': '2026-10-20T01:00:00Z', 'command': ['SYNTHETIC', 'unit-test-receipt'], 'environment': {'kind': 'physical' if gate.get('physical') else 'emulator' if gate['id'] == 'instrumentation' else 'host', 'identity': candidate['devices'][0]['identity'], 'device_id': candidate['devices'][0]['id'], 'emulator': not gate.get('physical'), 'abi': 'arm64-v8a', 'api': 35}, 'dataset_identity': {'id': scene['id'], 'kind': 'physical', 'sha256': scene['sha256'], 'reference_sha256': scene['reference_sha256']} if scene else {'id': 'none', 'kind': 'none', 'sha256': '0'*64}, 'status': 'PASS', 'blockers': [], 'metrics': {'executed_tests': 1, 'failed_tests': 0, 'skipped_tests': 0, 'disabled_tests': 0}, 'evidence_sha256': hashlib.sha256(b'SYNTHETIC TEST BLOB').hexdigest(), 'protocol_sha256': '6'*64}
            m = record['metrics']
            m.update({k: 1 for k in ('registration_fraction', 'completeness_fraction', 'supported_face_fraction_original', 'held_out_support_fraction', 'glb_validated', 'glb_roundtrip', 'obj_roundtrip', 'ply_roundtrip', 'stl_roundtrip', 'static_executed', 'secrets_executed', 'dependencies_executed', 'production_signature_verified', 'r8_exercised', 'aab_validated', 'alignment_16k_verified', 'install', 'capture', 'import', 'calibration', 'reconstruction', 'inspection', 'editing', 'export', 'reopen', 'airplane_mode', 'wifi_disabled', 'mobile_data_disabled', 'completed_offline', 'upgrade_preserved_originals', 'process_death_recovered', 'partial_not_published', 'independent_testers')})
            m.update({k: 0 for k in ('accuracy_p95', 'duplicate_triangles', 'nonmanifold_edges', 'self_intersection_pairs', 'held_out_linear_rgb_rmse', 'seam_linear_rgb_abs_p95', 'uv_overlap_pairs', 'opaque_unknown_pixels', 'active_findings', 'unresolved_licenses', 'unresolved_vulnerabilities', 'debuggable', 'unresolved_critical_defects')})
            m['anisotropy_p95'] = 1
            m.update({key:1 for key in ('sanitizers_passed','ctest_executed','pytest_executed','compile_passed','lint_passed','jvm_tests_passed','full_suite_executed','photos_30_completed','photos_100_completed','photos_250_completed','talkback_checked','font_scaling_checked')})
            m.update({key:0 for key in ('budget_violations','blocking_issues','missing_required_deliveries','unwired_required_interfaces','unresolved_ownership_conflicts')})
            records.append(record)
    return candidate, records


def test_all_explicit_unit_receipts_pass_logical_aggregation(tmp_path):
    candidate, records = fixtures()
    blob = b'SYNTHETIC TEST BLOB'
    (tmp_path / hashlib.sha256(blob).hexdigest()).write_bytes(blob)
    report = qualify(candidate, records, now=NOW, evidence_root=tmp_path)
    assert report['status'] == 'PASS', report
    assert len(report['gates']) > 18
    assert 'percentage' not in report


@pytest.mark.parametrize('mutation', ['source', 'artifact', 'run', 'old', 'future', 'owner', 'unknown_gate', 'status', 'nan', 'infinite', 'zero_tests', 'failed', 'skipped', 'disabled', 'extra_field', 'empty_command', 'physical_emulator', 'synthetic_scene', 'wrong_scene_hash', 'wrong_reference', 'wrong_device', 'wrong_protocol', 'bad_geometry', 'missing_material_roundtrip', 'unsigned', 'beta_missing'])
def test_known_stale_incomplete_or_invalid_receipts_fail_closed(mutation):
    candidate, records = fixtures()
    record = next(r for r in records if r['gate'] == 'geometry')
    if mutation == 'source': record['source_sha'] = '9'*40
    elif mutation == 'artifact': record['artifact_sha256'] = '9'*64
    elif mutation == 'run': record['run_id'] = 'old-run'
    elif mutation == 'old': record['recorded_at'] = '2026-10-01T01:00:00Z'
    elif mutation == 'future': record['recorded_at'] = '2026-10-21T01:00:00Z'
    elif mutation == 'owner': record['agent'] = '085'
    elif mutation == 'unknown_gate': record['gate'] = 'magic'
    elif mutation == 'status': record['status'] = 'SKIP'
    elif mutation == 'nan': record['metrics']['accuracy_p95'] = float('nan')
    elif mutation == 'infinite': record['metrics']['accuracy_p95'] = float('inf')
    elif mutation == 'zero_tests': record['metrics']['executed_tests'] = 0
    elif mutation in ('failed', 'skipped', 'disabled'): record['metrics'][mutation+'_tests'] = 1
    elif mutation == 'extra_field': record['trust_me'] = True
    elif mutation == 'empty_command': record['command'] = []
    elif mutation == 'physical_emulator': record['environment']['emulator'] = True
    elif mutation == 'synthetic_scene': record['dataset_identity']['kind'] = 'synthetic'
    elif mutation == 'wrong_scene_hash': record['dataset_identity']['sha256'] = '8'*64
    elif mutation == 'wrong_reference': record['dataset_identity']['reference_sha256'] = '8'*64
    elif mutation == 'wrong_device': record['environment']['identity'] = 'other handset'
    elif mutation == 'wrong_protocol': record['protocol_sha256'] = '9'*64
    elif mutation == 'bad_geometry': record['metrics']['accuracy_p95'] = .010001
    elif mutation == 'missing_material_roundtrip': next(r for r in records if r['gate'] == 'interoperability')['metrics']['obj_roundtrip'] = 0
    elif mutation == 'unsigned': next(r for r in records if r['gate'] == 'signing')['metrics']['production_signature_verified'] = 0
    elif mutation == 'beta_missing': next(r for r in records if r['gate'] == 'external_beta')['metrics']['independent_testers'] = 0
    assert qualify(candidate, records, now=NOW)['status'] == 'FAIL'


def test_duplicate_receipt_rejected():
    candidate, records = fixtures()
    records.append(copy.deepcopy(records[0]))
    assert qualify(candidate, records, now=NOW)['status'] == 'FAIL'


def test_missing_gate_remains_not_run():
    candidate, records = fixtures()
    report = qualify(candidate, [r for r in records if r['gate'] != 'signing'], now=NOW)
    assert report['status'] == 'NOT_RUN'
    assert next(g for g in report['gates'] if g['gate'] == 'signing')['status'] == 'NOT_RUN'


def test_unavailable_real_inputs_block_all_physical_gates():
    candidate, _ = fixtures()
    candidate['scenes'], candidate['devices'] = [], []
    report = qualify(candidate, [], now=NOW)
    assert report['status'] == 'BLOCKED_EXTERNAL'
    assert len(report['gates']) == 18
    assert len(report['blockers']) == 2


def test_failed_scene_retained_not_averaged():
    candidate, records = fixtures()
    record = next(r for r in records if r['gate'] == 'texture')
    record.update(status='FAIL', blockers=['UV overlap found'])
    report = qualify(candidate, records, now=NOW)
    assert report['status'] == 'FAIL'
    assert any(g['status'] == 'PASS' and g['gate'] == 'texture' for g in report['gates'])
    assert any(g['status'] == 'FAIL' and g['gate'] == 'texture' for g in report['gates'])


def test_missing_backing_evidence_rejected(tmp_path):
    candidate, records = fixtures()
    assert qualify(candidate, records, now=NOW, evidence_root=tmp_path)['status'] == 'FAIL'


@pytest.mark.parametrize('payload', ['{"schema":1,"schema":1}', '{"metric":NaN}', '{"metric":Infinity}'])
def test_duplicate_or_nonfinite_json_rejected(tmp_path, payload):
    path = tmp_path / 'bad.json'
    path.write_text(payload)
    with pytest.raises(ValueError): load_json(path)


def test_all_remote_roles_indexed_without_success_inference():
    report = index(ROOT)
    assert len(report['agents']) == 100
    assert {row['agent'] for row in report['agents']} == {f'{n:03}' for n in range(1, 101)}
    assert all(row['status'] == 'NOT_RUN' for row in report['agents'])


def test_cli_missing_records_cannot_succeed(tmp_path):
    candidate, _ = fixtures()
    candidate['scenes'], candidate['devices'] = [], []
    candidate['created_at'] = '2026-01-01T00:00:00Z'
    candidate['source_sha'] = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    apk = tmp_path / 'unit-fixture.apk'
    apk.write_bytes(b'SYNTHETIC UNIT ARTIFACT')
    candidate['artifact_sha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
    path = tmp_path / 'candidate.json'
    path.write_text(json.dumps(candidate))
    records = tmp_path / 'records'
    records.mkdir()
    output = tmp_path / 'report.json'
    # Use an independent clean source fixture; current implementation is uncommitted
    # while this test is developed, and dirty trees must never qualify.
    source = tmp_path / 'source'
    source.mkdir()
    subprocess.run(['git','init','-q','-b','fixture-source',str(source)],check=True)
    (source/'source.txt').write_text('SYNTHETIC UNIT SOURCE\n')
    subprocess.run(['git','-C',str(source),'add','source.txt'],check=True)
    subprocess.run(['git','-C',str(source),'-c','user.name=Unit Fixture','-c','user.email=unit@example.invalid','commit','-qm','Synthetic fixture'],check=True)
    candidate['source_sha'] = subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()
    path.write_text(json.dumps(candidate))
    args, env = missing_release_records_cli(tmp_path, [sys.executable, '-m', 'tools.release.qualification.aggregate', '--candidate', str(path), '--records', str(records), '--evidence-root', str(tmp_path), '--candidate-apk',str(apk),'--source-root',str(source),'--output', str(output)])
    run = subprocess.run(args, env=env, cwd=ROOT, capture_output=True, text=True, timeout=10)
    assert run.returncode == 2
    assert run.stderr == ''
    report = json.loads(output.read_text())
    summary = json.loads(run.stdout)
    assert summary['status'] == report['status'] == 'BLOCKED_EXTERNAL'
    assert summary['rejected_records'] == report['rejected_records'] == []
    assert summary['blockers'] == report['blockers'] == [
        'BLOCKED_EXTERNAL: at least two authorized independent physical scene/reference bundles required',
        'BLOCKED_EXTERNAL: physical ARM64 supported-device matrix required']


def instrumentation_fixture(source_sha='1'*40, apk_sha256='2'*64):
    # Actual AndroidJUnitRunner event shape, but entirely synthetic unit data.
    fields = ('INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n'
              'INSTRUMENTATION_STATUS: numtests=1\n'
              'INSTRUMENTATION_STATUS: class=' + EXPECTED_CLASS + '\n'
              'INSTRUMENTATION_STATUS: test=' + EXPECTED_METHOD + '\n'
              'INSTRUMENTATION_STATUS: current=1\n')
    receipt = {'schema':1,'kind':'synthetic','source_sha':source_sha,'artifact_sha256':apk_sha256,'export_sha256':'3'*64,'originals_preserved':True,'photo_count':3,'triangle_count':40}
    start = fields + 'INSTRUMENTATION_STATUS_CODE: 1\n'
    evidence = 'INSTRUMENTATION_STATUS: rf099_evidence=' + json.dumps(receipt) + '\nINSTRUMENTATION_STATUS_CODE: 0\n'
    finish = fields + 'INSTRUMENTATION_STATUS: stream=.\nINSTRUMENTATION_STATUS_CODE: 0\n'
    final = 'INSTRUMENTATION_RESULT: stream=\n\nTime: 0.2\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n'
    return start, evidence, finish, final


def test_real_instrumentation_output_requires_completion_and_app_evidence():
    text = ''.join(instrumentation_fixture())
    report = parse_instrumentation(text)
    assert report['status'] == 'PASS', report
    assert report['executed_tests'] == 1
    assert report['events'] == [{'class':EXPECTED_CLASS,'test':EXPECTED_METHOD,'current':1,'numtests':1,'code':0}]


@pytest.mark.parametrize('mutation', ['missing_start','duplicate_start','missing_end','duplicate_end','missing_receipt','duplicate_receipt','receipt_before_start','receipt_after_end','receipt_unknown_code','summary_missing','summary_duplicate','summary_zero','summary_wrong_count','final_missing','final_duplicate','final_wrong_code','unknown_status','failed','ignored','assumption','missing_current','wrong_current','missing_numtests','inconsistent_numtests','wrong_class','wrong_method','duplicate_identity_field','incomplete_tail','event_after_final','unknown_control','crash','malformed_receipt','duplicate_receipt_key','wrong_receipt_schema','terminal_before_start'])
def test_instrumentation_adversarial_event_sequences_fail_closed(mutation):
    start,evidence,finish,final = instrumentation_fixture()
    text = start+evidence+finish+final
    if mutation == 'missing_start': text=evidence+finish+final
    elif mutation == 'duplicate_start': text=start+start+evidence+finish+final
    elif mutation == 'missing_end': text=start+evidence+final
    elif mutation == 'duplicate_end': text=start+evidence+finish+finish+final
    elif mutation == 'missing_receipt': text=start+finish+final
    elif mutation == 'duplicate_receipt': text=start+evidence+evidence+finish+final
    elif mutation == 'receipt_before_start': text=evidence+start+finish+final
    elif mutation == 'receipt_after_end': text=start+finish+evidence+final
    elif mutation == 'receipt_unknown_code': text=start+evidence.replace('STATUS_CODE: 0','STATUS_CODE: 5')+finish+final
    elif mutation == 'summary_missing': text=text.replace('OK (1 test)','')
    elif mutation == 'summary_duplicate': text=text.replace('OK (1 test)','OK (1 test)\nOK (1 test)')
    elif mutation == 'summary_zero': text=text.replace('OK (1 test)','OK (0 tests)')
    elif mutation == 'summary_wrong_count': text=text.replace('OK (1 test)','OK (2 tests)')
    elif mutation == 'final_missing': text=text.replace('INSTRUMENTATION_CODE: -1','')
    elif mutation == 'final_duplicate': text+='INSTRUMENTATION_CODE: -1\n'
    elif mutation == 'final_wrong_code': text=text.replace('INSTRUMENTATION_CODE: -1','INSTRUMENTATION_CODE: 0')
    elif mutation == 'unknown_status': text=start+evidence+finish.replace('STATUS_CODE: 0','STATUS_CODE: 2')+final
    elif mutation in ('failed','ignored','assumption'):
        text=start+evidence+finish.replace('STATUS_CODE: 0','STATUS_CODE: '+{'failed':'-2','ignored':'-3','assumption':'-4'}[mutation])+final
    elif mutation == 'missing_current': text=text.replace('INSTRUMENTATION_STATUS: current=1\n','',1)
    elif mutation == 'wrong_current': text=start+evidence+finish.replace('current=1','current=2')+final
    elif mutation == 'missing_numtests': text=text.replace('INSTRUMENTATION_STATUS: numtests=1\n','',1)
    elif mutation == 'inconsistent_numtests': text=start+evidence+finish.replace('numtests=1','numtests=2')+final
    elif mutation == 'wrong_class': text=text.replace(EXPECTED_CLASS,'com.example.UnrelatedTest')
    elif mutation == 'wrong_method': text=text.replace(EXPECTED_METHOD,'unrelatedMethod')
    elif mutation == 'duplicate_identity_field': text=text.replace('INSTRUMENTATION_STATUS: current=1','INSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: current=1',1)
    elif mutation == 'incomplete_tail': text=start+evidence+finish+'INSTRUMENTATION_STATUS: current=1\n'+final
    elif mutation == 'event_after_final': text+=finish
    elif mutation == 'unknown_control': text=text.replace('Time: 0.2','INSTRUMENTATION_UNKNOWN: done')
    elif mutation == 'crash': text=text.replace('Time: 0.2','Process crashed')
    elif mutation == 'malformed_receipt': text=start+'INSTRUMENTATION_STATUS: rf099_evidence={\nINSTRUMENTATION_STATUS_CODE: 0\n'+finish+final
    elif mutation == 'duplicate_receipt_key': text=start+evidence.replace('{"schema": 1','{"schema": 1, "schema": 1')+finish+final
    elif mutation == 'wrong_receipt_schema': text=start+evidence.replace('"schema": 1','"schema": true')+finish+final
    elif mutation == 'terminal_before_start': text=finish+start+evidence+final
    report=parse_instrumentation(text)
    assert report['status'] == 'FAIL', (mutation,report)


@pytest.fixture
def mocked_installed_device(tmp_path,monkeypatch):
    """Mock ADB protocol only; never device execution evidence."""
    import tools.release.qualification.journey as journey
    apk,test_apk=tmp_path/'app.apk',tmp_path/'test.apk'
    apk.write_bytes(b'SYNTHETIC APP APK');test_apk.write_bytes(b'SYNTHETIC TEST APK')
    candidate={'source_sha':'1'*40,'artifact_sha256':hashlib.sha256(apk.read_bytes()).hexdigest()}
    digests={'com.realityforge.scanner':candidate['artifact_sha256'],'com.realityforge.scanner.test':hashlib.sha256(test_apk.read_bytes()).hexdigest()}
    paths={name:'/data/app/~~unit-test/'+name+'-unit/base.apk' for name in digests}
    state={'digests':digests,'paths':paths,'calls':[],'pm_overrides':{}}
    def run(argv,**kwargs):
        state['calls'].append(argv)
        if argv==['adb','devices']: out='List of devices attached\nUNIT_FAKE_DEVICE\tdevice\n'
        elif argv[:3]==['adb','-s','UNIT_FAKE_DEVICE']:
            command=argv[3:]
            if command[:2]==['install','-r']: out='Success\n'
            elif command[:2]==['shell','getprop']:
                out={'ro.kernel.qemu':'1','ro.build.fingerprint':'SYNTHETIC UNIT EMULATOR','ro.product.cpu.abi':'x86_64','ro.build.version.sdk':'35'}[command[2]]+'\n'
            elif command[:3]==['shell','pm','path']:
                package=command[3];out=state['pm_overrides'].get(package,'package:'+paths[package]+'\n')
            elif command[:2]==['shell','sha256sum']:
                package=next(name for name,path in paths.items() if path==command[2]);out=digests[package]+'  '+command[2]+'\n'
            elif command[:3]==['shell','am','instrument']:
                assert EXPECTED_CLASS+'#'+EXPECTED_METHOD in command
                out=''.join(instrumentation_fixture(candidate['source_sha'],candidate['artifact_sha256']))
            else: raise AssertionError('Unexpected mock ADB command: '+repr(command))
        else: raise AssertionError('Unexpected mock command: '+repr(argv))
        return subprocess.CompletedProcess(argv,0,stdout=out,stderr='')
    monkeypatch.setattr(journey.subprocess,'run',run)
    return candidate,apk,test_apk,tmp_path/'output',state


def test_controller_hashes_both_actual_expected_packages_before_instrumentation(mocked_installed_device):
    candidate,apk,test_apk,output,state=mocked_installed_device
    report=execute(candidate,apk,test_apk,None,output)
    assert report['status']=='PASS',report
    assert {row['package'] for row in report['installed_packages']}==set(state['digests'])
    hashes=[i for i,args in enumerate(state['calls']) if args[3:5]==['shell','sha256sum']]
    instrumentation=next(i for i,args in enumerate(state['calls']) if args[3:6]==['shell','am','instrument'])
    assert len(hashes)==2 and max(hashes)<instrumentation


@pytest.mark.parametrize('package',['com.realityforge.scanner','com.realityforge.scanner.test'])
def test_wrong_package_install_success_cannot_reuse_stale_expected_package(mocked_installed_device,package):
    candidate,apk,test_apk,output,state=mocked_installed_device
    # Supplied APK install reports Success, but the expected package remains old.
    # Its test can echo correct candidate arguments; it must never be invoked.
    state['digests'][package]='f'*64
    report=execute(candidate,apk,test_apk,None,output)
    assert report['status']=='FAIL' and 'Installed package hash differs' in report['blockers'][0]
    assert not any(args[3:6]==['shell','am','instrument'] for args in state['calls'])


@pytest.mark.parametrize('package',['com.realityforge.scanner','com.realityforge.scanner.test'])
@pytest.mark.parametrize('bad_path',['split','missing','unsafe'])
def test_missing_split_or_unsafe_installed_package_fails_before_test(mocked_installed_device,package,bad_path):
    candidate,apk,test_apk,output,state=mocked_installed_device
    path=state['paths'][package]
    state['pm_overrides'][package]={'split':'package:'+path+'\npackage:'+path.replace('base.apk','split_config.apk')+'\n','missing':'','unsafe':'package:/data/app/unit;touch/base.apk\n'}[bad_path]
    report=execute(candidate,apk,test_apk,None,output)
    assert report['status']=='FAIL'
    assert not any(args[3:6]==['shell','am','instrument'] for args in state['calls'])


def test_physical_bundle_must_resolve_exact_original_bytes(tmp_path):
    photos = []
    for n in range(3):
        name = f'photo-{n}.jpg'
        data = ('SYNTHETIC-PARSER-TEST-' + str(n)).encode()
        (tmp_path / name).write_bytes(data)
        photos.append({'filename': name, 'sha256': hashlib.sha256(data).hexdigest()})
    (tmp_path / 'calibration.json').write_text('{"schema":1}')
    manifest = {'schema':1, 'kind':'physical', 'authorization_verified':True, 'photos':photos, 'calibration_sha256':hashlib.sha256((tmp_path/'calibration.json').read_bytes()).hexdigest()}
    (tmp_path / 'manifest.json').write_text(json.dumps(manifest))
    assert len(validate_bundle(tmp_path)['photos']) == 3
    (tmp_path / photos[0]['filename']).write_bytes(b'tampered')
    with pytest.raises(ValueError): validate_bundle(tmp_path)


def test_quality_adapter_never_promotes_limited_uv_or_synthetic_physical():
    candidate, records = fixtures()
    environment = next(r for r in records if r['gate'] == 'geometry')['environment']
    with pytest.raises(ValueError):
        adapt('texture', {'status':'PASS','independent_uv_gate':'PASS_LIMITED_UV'}, candidate, environment, '2026-10-20T01:00:00Z', 'actual command', '7'*64)
    scene = {'id':candidate['scenes'][0]['id'], 'candidate_source_sha':candidate['source_sha'], 'manifest_sha256':candidate['scenes'][0]['sha256'], 'reference_sha256':candidate['scenes'][0]['reference_sha256'], 'status':'PASS', 'metrics': {}}
    raw = {'source_sha':'8'*40,'protocol_sha256':'6'*64,'physical_gate':'BLOCKED_EXTERNAL','scenes':[scene]}
    assert adapt('geometry', raw, candidate, environment, '2026-10-20T01:00:00Z', 'actual command', '7'*64)[0]['status'] == 'BLOCKED_EXTERNAL'
    raw['physical_gate'] = 'PASS'
    assert adapt('geometry', raw, candidate, environment, '2026-10-20T01:00:00Z', 'actual command', '7'*64)[0]['status'] == 'NOT_RUN'


def test_quality_adapter_consumes_actual084_085_contract_fields():
    candidate, records = fixtures()
    environment = next(r for r in records if r['gate'] == 'geometry')['environment']
    scene = {'id':candidate['scenes'][0]['id'], 'candidate_source_sha':candidate['source_sha'], 'manifest_sha256':candidate['scenes'][0]['sha256'], 'reference_sha256':candidate['scenes'][0]['reference_sha256'], 'status':'PASS', 'metrics': {'accuracy_p95':.001,'completeness_fraction':.99,'cameras':{'registration_fraction':1.0},'topology':{'duplicate_triangles':0,'nonmanifold_edges':0,'self_intersection_pairs':0,'self_intersections_status':'PASS_CHECK_EXECUTED'}}}
    raw = {'source_sha':'8'*40,'protocol_sha256':'6'*64,'physical_gate':'PASS','scenes':[scene]}
    record = adapt('geometry',raw,candidate,environment,'2026-10-20T01:00:00Z','actual command','7'*64)[0]
    assert record['status'] == 'PASS'
    validate_record(record)
    check_acceptance(record)
    scene['metrics'] = {'supported_face_fraction_original':.99,'potential_seam_edges':0,'uv_overlap_pairs':0,'distortion_anisotropy_p95':1,'alpha':{'opaque_unknown_pixels':0}}
    scene['held_out_status'] = 'CHECKED'
    scene['held_out'] = [{'metrics':{'eligible_reference_pixels':100,'compared_pixels':99,'held_out_support_fraction':.99,'linear_rgb_rmse':.001}}]
    record = adapt('texture',raw,candidate,environment,'2026-10-20T01:00:00Z','actual command','7'*64)[0]
    assert record['status'] == 'PASS'
    validate_record(record)
    check_acceptance(record)
    del scene['metrics']['alpha']
    assert adapt('texture',raw,candidate,environment,'2026-10-20T01:00:00Z','actual command','7'*64)[0]['status'] == 'NOT_RUN'
