import hashlib
import json
from pathlib import Path
import subprocess
import yaml

BASE = Path('/workspace/campaign-061-080/final-validation/e290ff563293')
SOURCE = 'e290ff56329322b4365298b97bba7df8b16d2470'

def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def load(name):
    return json.loads((BASE / name).read_text())

android, pytest, native, snapshot = map(load, ['result.json', 'pytest-result.json', 'native-proof-reference.json', 'tracked-source.json'])
proof = json.loads(Path(native['native_assertion_proof']['path']).read_text())
old_snapshot = json.loads(Path(native['native_assertion_proof']['tested_native_source_snapshot']).read_text())
assert android['source_commit'] == pytest['source_commit'] == native['source_commit'] == SOURCE
assert android['state'] == 'PASS' and pytest['state'] == 'FAIL'
assert android['tracked_source_unchanged_after_validation'] and pytest['tracked_source_and_host_library_unchanged']
assert {k: android['jvm_tests'][k] for k in ['tests', 'errors', 'failures', 'skipped']} == dict(tests=236, errors=0, failures=0, skipped=0)
assert pytest['totals'] == dict(tests=194, errors=0, failures=7, skipped=0) and pytest['passed'] == 187
assert pytest['scope_is_full_pytest_suite']
assert native['schema'] == 2 and native['status'] == 'PASS'
assert native['tested_native_source_commit'] == proof['source_sha'] == old_snapshot['commit'] == 'c5d6ad0180e9afec6833996cd9c3bb1e0e62a717'
assert native['ctest_count'] == proof['ctest_count'] == len(proof['assertion_enabled_test_compiles']) == 17
for row in proof['assertion_enabled_test_compiles']:
    assert row['assertion_flags_in_order'] == ['-DNDEBUG', '-UNDEBUG']
assert digest(native['native_assertion_proof']['path']) == native['native_assertion_proof']['sha256']
assert digest(native['ctest_log']['path']) == native['ctest_log']['sha256'] == proof['ctest_log_sha256']
assert digest(native['current_source_snapshot']) == native['current_source_snapshot_sha256']
assert digest(native['native_assertion_proof']['tested_native_source_snapshot']) == native['native_assertion_proof']['tested_native_source_snapshot_sha256']
assert digest(native['host_library']['copied_path']) == native['host_library']['sha256'] == proof['library_sha256']
assert digest(native['host_library']['original_path']) == proof['library_sha256']
native_set = lambda s: {p for p in s['files'] if p == 'CMakeLists.txt' or p.startswith('reconstruction/')}
assert native_set(snapshot) == native_set(old_snapshot) == set(native['native_input_sha256'])
assert len(native['native_input_sha256']) == 55
for path, sha in native['native_input_sha256'].items():
    assert snapshot['files'][path]['sha256'] == old_snapshot['files'][path]['sha256'] == sha
    assert digest(BASE / 'source' / path) == sha
for row in android['artifacts'].values():
    assert digest(row['path']) == row['sha256'] and Path(row['path']).stat().st_size == row['bytes']
for row in android['commands']:
    assert row['exit_code'] == 0 and digest(row['log']) == row['log_sha256']
for row in android['verification_adapters'].values():
    assert digest(row['path']) == row['sha256']
assert android['apk_checks']['internetPermission'] is False and android['apk_checks']['zipAlignment16k']
assert len(android['apk_checks']['elfLibrariesChecked']) == 16
assert all(len(row['verified_exported_symbols']) == 7 for row in android['new_native_packaging']['libraries'])
assert digest(pytest['command']['log']) == pytest['command']['log_sha256'] and pytest['command']['exit_code'] == 1

for num in ['062', '063', '064']:
    root = Path('/workspace/agent-work') / num
    path = root / 'docs/agents/status' / (num + '.md')
    text = path.read_text()
    receipt = yaml.safe_load(text)
    assert receipt['state'] == 'READY_FOR_INTEGRATION'
    assert not subprocess.check_output(['git', '-C', str(root), 'status', '--porcelain'])
    role_head = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
    for owned, sha in receipt['source_files_sha256'].items():
        assert digest(root / owned) == digest(BASE / 'source' / owned) == snapshot['files'][owned]['sha256'] == sha
        assert hashlib.sha256(subprocess.check_output(['git', '-C', str(root), 'show', SOURCE + ':' + owned])).hexdigest() == sha
    common = dict(source_sha=SOURCE, role_input_sha=role_head, agent=num,
        environment='Managed Linux x86_64; JDK17.0.16+8, Gradle8.13, Kotlin2.1.21, SDK36, NDK28.2.13676358, CMake3.22.1; no device execution in these rows',
        device_identity=None, dataset_identity='Committed analytic/synthetic native and JVM/regression fixtures; no physical captures or accuracy measurements')
    fixture = next(row for row in proof['assertion_enabled_test_compiles'] if ('agent_' + num + '_') in row['source'])
    rows = [dict(common,
        criterion='C16 assertion-enabled native regression accepted by exact native input and host-library byte equality',
        result='PASS', gate_status='PASS_SCOPED_NATIVE_EQUIVALENCE', evidence_id='c16-native-equivalence-e290',
        command='ctest --test-dir /workspace/campaign-061-080/native-harness/build --output-on-failure (historical execution at tested_native_source_commit); schema-2 reference checks exact tracked native set, source bytes and actual library bytes for source_sha',
        tested_native_source_commit=native['tested_native_source_commit'],
        artifact_sha256=native['host_library']['sha256'], evidence=str(BASE / 'native-proof-reference.json'),
        result_json_sha256=digest(BASE / 'native-proof-reference.json'),
        measured_result=dict(ctest_passed=17, ctest_failed=0, assertion_enabled_test_compiles=17, exact_native_inputs=55, actual_host_library_bytes=native['host_library']['bytes']),
        native_assertion_proof=native['native_assertion_proof'], ctest_log=native['ctest_log'],
        current_source_snapshot=dict(path=native['current_source_snapshot'], sha256=native['current_source_snapshot_sha256']),
        own_fixture_compile=fixture, native_input_sha256=native['native_input_sha256'],
        host_library=native['host_library'],
        limitations=['All 17 actual CTests executed at c5d6ad0180e9afec6833996cd9c3bb1e0e62a717, with -UNDEBUG after -DNDEBUG. They were not rerun at e290ff56329322b4365298b97bba7df8b16d2470.',
            'The schema-2 reference accepts e290 only through exact 55-file native tracked-set/source-byte equality and actual host-library byte equality to that immutable execution.',
            'Host/native regression does not establish application/JNI invocation, physical hardware or production interoperability.'],
        remaining_blocker='Owners003/004/100 must complete receiving integration and runtime routing; Agent090 target-tool and physical/hardware gates remain open.'),
        dict(common, criterion='C16 frozen combined app/test APK compilation, JVM regression, lint and offline 16 KiB packaging',
            result='PASS', gate_status='PASS_WITH_EXPLICIT_VERIFICATION_ADAPTERS', evidence_id='c16-combined-android-e290',
            command=android['commands'], artifact_sha256=android['artifacts']['app/build/outputs/apk/debug/app-debug.apk']['sha256'],
            test_apk_sha256=android['artifacts']['app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']['sha256'],
            evidence=str(BASE / 'result.json'), result_json_sha256=digest(BASE / 'result.json'),
            measured_result=dict(jvm_tests=236, failures=0, errors=0, skipped=0, lint_errors=0, lint_warnings=28),
            packaging=dict(internet_permission=False, elf_libraries_16kb_checked=16, zip_alignment_16kb=True, native_symbol_families_in_each_of_arm64_v8a_and_x86_64=7),
            native_packaged_library_evidence=android['new_native_packaging']['libraries'],
            verification_adapters=android['verification_adapters'],
            adapter_scope='External CMake registers seven native modules on the actual owner004 realityforge target; external Gradle supplies exact Filament/gltfio1.56.0. No source-set replacement, handwritten stubs or foreign-worktree source injection. Owner004 URI/MessageDigest import fix is now in pinned source71262455888e051fd03a1df06f276db0db461ef7; no external import-fix adapter is used by this candidate. The older a18 unadapted FAIL row remains historical evidence.',
            matched_code_proof=dict(role_input_sha=role_head, all_four_owned_source_and_test_files_equal_frozen_git_blobs_and_validation_checkout=True, source_files_sha256=receipt['source_files_sha256']),
            limitations=android['limitations'],
            remaining_blocker='Receiving-owner build registration/Filament dependency adoption and actual003/067 application routing require100 integration; compilation and packaged symbols do not establish runtime or device success.'),
        dict(common, criterion='C16 full frozen combined Python regression, with all seven source-contract failures retained',
            result='FAIL', gate_status='FAIL_REQUIRED_FULL_REGRESSION', evidence_id='c16-full-pytest-e290',
            command=pytest['command'], artifact_sha256=pytest['host_library']['sha256'],
            evidence=str(BASE / 'pytest-result.json'), result_json_sha256=digest(BASE / 'pytest-result.json'),
            measured_result=dict(tests=194, passed=187, failures=7, errors=0, skipped=0),
            scope=pytest['scope'], scope_is_full_pytest_suite=True, test_environment_overrides=pytest['test_environment_overrides'],
            failed_cases=[dict(nodeid=row['nodeid'], recorded_mapping=row['recorded_mapping']) for row in pytest['failed_cases']],
            owner_requests=['/workspace/campaign-061-080/combined/docs/agents/requests/080-to-094.md', '/workspace/campaign-061-080/combined/docs/agents/requests/080-to-053.md'],
            limitations=['No test was excluded, deselected, marked xfail, skipped or weakened to produce these counts.', 'The M18 Python source-literal expectation still fails despite a separately passing actual JVM legacy-hash parity test. That JVM result does not turn the Python assertion into a pass.', 'This is host regression evidence only; no full UI, device, physical or production success is claimed.'],
            remaining_blocker='Owners094/053 must review all seven protected invariants and implement semantic source-contract migration without weakening behavior;100 must integrate reviewed fixes. Full pytest remains FAIL.')]
    assert 'c16-native-equivalence-e290' not in text
    text = text.replace('\nsource_files_sha256:\n', '\n' + yaml.safe_dump(rows, sort_keys=False, width=110) + 'source_files_sha256:\n', 1)
    text = text.replace('Owner004 must adopt the tested build-script imports/native registration; owner069 Filament dependencies remain external\n  verification adapters in this evidence.', 'Owner004 build-script imports are fixed in pinned71262455888e051fd03a1df06f276db0db461ef7; native registration\n  and owner069 Filament dependencies still use explicit external verification adapters in current e290 evidence.')
    text = text.replace('Owners094/053 must resolve the full combined pytest7 failures, including the unclassified M18 depth identity expectation,\n  without weakening protected behavior.', 'Owners094/053 must resolve all seven full combined pytest source-contract failures without weakening protected\n  behavior. M18 now has a recorded constant-domain mapping and independent actual JVM parity coverage; its Python assertion still fails.')
    scope = 'All four owned source/test files match frozen e290 Git blobs and its validation checkout. Only this receipt and the canonical staged-source manifest change. Earlier reader/unit evidence is retained. Native17 execution belongs to c5d6ad0180e9afec6833996cd9c3bb1e0e62a717 and is accepted for e290 by exact55-native-input and actual-library byte equality; no fresh native run, device or full UI pass is claimed.'
    at = text.index('\nc16_evidence_scope:')
    text = text[:at] + '\n' + yaml.safe_dump(dict(c16_evidence_scope=scope, latest_combined_candidate=SOURCE), sort_keys=False, width=110)
    updated = yaml.safe_load(text)
    assert updated['acceptance_results'][:-3] == receipt['acceptance_results']
    assert updated['source_files_sha256'] == receipt['source_files_sha256']
    assert updated['state'] == 'READY_FOR_INTEGRATION' and len(updated['acceptance_results']) == len(receipt['acceptance_results']) + 3
    path.write_text(text)
    print(f'{num}: retained {len(receipt["acceptance_results"])} historical rows; appended 3 C16 rows; verified4 own files,55 native inputs and actual immutable host-library bytes')
