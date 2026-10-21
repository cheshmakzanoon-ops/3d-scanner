"""Synthetic evidence fixtures exercise rejection; they are not device results."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import zlib

import pytest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("ci_evidence", ROOT / "tools/ci/validation/evidence.py")
ci = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ci)


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value)
    return path


def dump(path, value):
    return write(path, json.dumps(value))


def xml(path):
    return write(path, '<testsuite tests="3" failures="0" errors="0"><testcase name="a"/><testcase name="b"/><testcase name="optional"><skipped/></testcase></testsuite>')


def png(path):
    def chunk(kind, value):
        return struct.pack('>I', len(value)) + kind + value + struct.pack('>I', zlib.crc32(kind + value) & 0xffffffff)
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 64, 64, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress((b'\0' + b'\xff\0\0' * 64) * 64)) + chunk(b'IEND', b''))
    return path


def instrumentation(codes=(0, -4, -3)):
    rows = []
    for current, code in enumerate(codes, 1):
        fields = (f"INSTRUMENTATION_STATUS: class=synthetic.Test\n"
                  f"INSTRUMENTATION_STATUS: test=fixture{current}\n"
                  f"INSTRUMENTATION_STATUS: current={current}\n"
                  f"INSTRUMENTATION_STATUS: numtests={len(codes)}\n")
        rows += [fields + "INSTRUMENTATION_STATUS_CODE: 1\n", fields + f"INSTRUMENTATION_STATUS_CODE: {code}\n"]
    return "".join(rows) + f"OK ({len(codes) - codes.count(-3)} tests)\nINSTRUMENTATION_CODE: -1\n"


def scope_manifest():
    return dict(schema="realityforge.android-suite-scopes.v1", scopes={"debug": dict(
        requiredPassedSelectors=["synthetic.Test#fixture1"],
        excludedGates=[dict(gate=gate, selector=f"synthetic.Excluded{index}", reason="synthetic specialist fixture requires separate controller") for index, gate in enumerate(sorted(ci.DEBUG_SPECIALIST_GATES))])})


def suite_record(repo):
    path = repo / ci.SUITE_PATH
    scope = ci.load(path)["scopes"]["debug"]
    return dict(schema=1, scope="debug", manifestSha256=ci.sha(path),
                requiredPassedSelectors=scope["requiredPassedSelectors"],
                notClass=[row["selector"] for row in scope["excludedGates"]],
                excludedGates=[{**row, "status": "NOT_RUN"} for row in scope["excludedGates"]],
                fullQualification="NOT_RUN")


@pytest.fixture
def candidate(tmp_path):
    repo = tmp_path / 'repo'
    repo.mkdir()
    subprocess.run(['git', 'init', '-q', str(repo)], check=True)
    write(repo / 'README', 'synthetic test repository')
    dump(repo / ci.SUITE_PATH, scope_manifest())
    for args in [('config', 'user.name', 'CI Test'), ('config', 'user.email', 'ci@example.invalid'), ('add', '.'), ('commit', '-qm', 'synthetic fixture')]:
        subprocess.run(['git', '-C', str(repo), *args], check=True)
    commit = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip()
    return repo, commit, tmp_path / 'execution', tmp_path / 'bundles'


def start(candidate, gate):
    repo, commit, execution, bundles = candidate
    path = execution / gate / 'start.json'
    ci.begin(repo, gate, commit, 'test-run:1', path)
    return path


def native(candidate):
    _, commit, execution, _ = candidate
    result = dict(schema='realityforge.native-regression.v1', source_sha=commit, dirty_tree=False, result='PASS', configurations=[], comparisons=[])
    for name in sorted(ci.NATIVE_CONFIGS):
        folder = execution / 'native' / name
        commands = []
        for label in sorted(ci.COMMANDS):
            path = write(folder / (label + '.log'), '100% tests passed, 0 tests failed out of 11\n' if label == 'ctest' else 'synthetic command fixture\n')
            commands.append(dict(log=str(path), log_sha256=ci.sha(path), result='PASS', reason=None, exit_code=0))
        xml(folder / 'pytest.xml')
        lib = write(folder / 'lib.so', 'synthetic binary fixture')
        numerical = [dump(folder / filename, {'fixture': 'synthetic'}) for filename in sorted(ci.NATIVE_REPORTS)]
        result['configurations'].append(dict(name=name, result='PASS', commands=commands, binaries=[dict(path=str(lib), sha256=ci.sha(lib))], artifacts=[dict(path=str(path), sha256=ci.sha(path)) for path in numerical]))
    result['comparisons'] = [dict(config=row['name'], result='PASS') for row in result['configurations'][1:]]
    return dump(execution / 'native' / 'evidence.json', result)


def android_files(candidate):
    execution = candidate[2]
    app = write(execution / 'android' / 'app-debug.apk', 'synthetic application fixture')
    test = write(execution / 'android' / 'app-debug-androidTest.apk', 'synthetic instrumentation fixture')
    report = dump(execution / 'android' / 'apk-validation.json', dict(internetPermission=False, zipAlignment16k=True, elfLibrariesChecked=['lib/x86_64/lib.so']))
    return app, test, report


def build_bundle(candidate):
    _, _, execution, bundles = candidate
    marker = start(candidate, 'native')
    ci.collect(marker, bundles / 'native', native=native(candidate))
    marker = start(candidate, 'android')
    app, test, report = android_files(candidate)
    ci.collect(marker, bundles / 'android', [str(xml(execution / 'android' / 'unit.xml'))], [str(app), str(test)], apk_report=report)
    marker = start(candidate, 'device')
    device = dict(schema=1, suite=suite_record(candidate[0]), status='PASS', tests=3, counts=dict(tests=3, passed=1, skipped=2, failed=0), device=dict(serial='emulator-5554', fingerprint='synthetic emulator fixture', api=35, abi='x86_64', pageSize=4096, emulator=True), appSha256=ci.sha(app), testSha256=ci.sha(test), physicalCapture='NOT_RUN')
    record = dump(execution / 'device' / 'compatibility.json', device)
    write(record.with_name('instrumentation.txt'), instrumentation())
    ci.collect(marker, bundles / 'device', artifact_patterns=[str(app), str(test)], screenshot=png(execution / 'device' / 'photographic-viewer.png'), device=record)
    return candidate


@pytest.fixture
def bundle(candidate):
    return build_bundle(candidate)


def verify(candidate):
    return ci.verify_bundle(candidate[3], candidate[1], 'test-run:1')


def test_complete_sealed_bundle_keeps_evidence_kinds_distinct(bundle):
    result = verify(bundle)
    assert result['status'] == 'PASS'
    assert result['physical_device'] == 'NOT_RUN'
    assert result['gates']['native']['tests']['passed'] == 8
    assert result['gates']['device']['kind'] == 'emulator-synthetic-session'


@pytest.mark.parametrize('mutation', ['failed', 'zero', 'all-skipped', 'declared-failure', 'omitted-cases', 'malformed'])
def test_invalid_junit_rejected(tmp_path, mutation):
    variants = {
        'failed': '<testsuite tests="1"><testcase><failure/></testcase></testsuite>',
        'zero': '<testsuite tests="0"/>',
        'all-skipped': '<testsuite tests="1"><testcase><skipped/></testcase></testsuite>',
        'declared-failure': '<testsuite tests="1" failures="1"><testcase/></testsuite>',
        'omitted-cases': '<testsuite tests="2"><testcase/></testsuite>',
        'malformed': '<not-junit/>',
    }
    with pytest.raises(ci.EvidenceError):
        ci.junit(write(tmp_path / 'bad.xml', variants[mutation]))


@pytest.mark.parametrize('mutation', ['source', 'run-id', 'skipped', 'counts', 'missing-file', 'changed-file', 'missing-screenshot', 'missing-gate', 'apk-mismatch'])
def test_final_gate_rejects_incomplete_stale_or_tampered_evidence(bundle, mutation):
    directory = bundle[3]
    path = directory / 'device/evidence.json'
    value = ci.load(path)
    if mutation == 'source': value['source_sha'] = '0' * 40
    elif mutation == 'run-id': value['run_id'] = 'old-run:1'
    elif mutation == 'skipped': value['status'] = 'SKIPPED'
    elif mutation == 'counts': value['tests']['passed'] += 1
    elif mutation == 'missing-file': (path.parent / value['artifacts'][0]['path']).unlink()
    elif mutation == 'changed-file': (path.parent / value['artifacts'][0]['path']).write_text('tampered')
    elif mutation == 'missing-screenshot': value['artifacts'] = [row for row in value['artifacts'] if row['role'] != 'screenshot']
    elif mutation == 'missing-gate': path.unlink()
    elif mutation == 'apk-mismatch':
        apk = next(row for row in value['artifacts'] if row['path'].endswith('.apk'))
        target = path.parent / apk['path']
        target.write_text('different candidate')
        apk.update(sha256=ci.sha(target), size_bytes=target.stat().st_size)
    if mutation != 'missing-gate': dump(path, value)
    with pytest.raises(ci.EvidenceError): verify(bundle)


def test_start_rejects_wrong_source_and_dirty_tree(candidate):
    repo, commit, execution, _ = candidate
    with pytest.raises(ci.EvidenceError): ci.begin(repo, 'android', '0' * 40, 'run', execution / 'wrong.json')
    write(repo / 'README', 'changed')
    with pytest.raises(subprocess.CalledProcessError): ci.begin(repo, 'android', commit, 'run', execution / 'dirty.json')


def test_capture_rejects_stale_reports_and_missing_globs(candidate):
    marker = start(candidate, 'android')
    app, test, report = android_files(candidate)
    path = xml(candidate[2] / 'android/unit.xml')
    os.utime(path, ns=(1, 1))
    with pytest.raises(ci.EvidenceError, match='Stale'):
        ci.collect(marker, candidate[3] / 'android', [str(path)], [str(app), str(test)], apk_report=report)
    with pytest.raises(ci.EvidenceError, match='Missing expected'):
        ci.expanded([str(path.parent / 'absent*.xml')])


@pytest.mark.parametrize('mutation', ['missing-config', 'failed-command', 'missing-ctest', 'failed-parity', 'wrong-source', 'dirty-tree', 'missing-benchmark'])
def test_native_record_requires_full_executed_matrix(candidate, mutation):
    start(candidate, 'native')
    path = native(candidate)
    value = ci.load(path)
    if mutation == 'missing-config': value['configurations'].pop()
    elif mutation == 'failed-command': value['configurations'][0]['commands'][0]['exit_code'] = 1
    elif mutation == 'missing-ctest':
        command = next(row for row in value['configurations'][0]['commands'] if row['log'].endswith('/ctest.log'))
        write(Path(command['log']), 'No tests were found')
        command['log_sha256'] = ci.sha(command['log'])
    elif mutation == 'failed-parity': value['comparisons'][0]['result'] = 'FAIL'
    elif mutation == 'wrong-source': value['source_sha'] = '0' * 40
    elif mutation == 'dirty-tree': value['dirty_tree'] = True
    elif mutation == 'missing-benchmark': value['configurations'][0]['artifacts'].pop()
    dump(path, value)
    with pytest.raises(ci.EvidenceError): ci.native_record(path, candidate[1])


def test_sealed_evidence_cannot_be_overwritten(bundle):
    with pytest.raises(ci.EvidenceError, match='immutable'):
        ci.collect(bundle[2] / 'android/start.json', bundle[3] / 'android')


def test_instrumentation_preserves_assumptions_and_ignored_tests():
    assert ci.instrumentation_counts(instrumentation()) == dict(tests=3, passed=1, skipped=2, failed=0)


@pytest.mark.parametrize("mutation", ["only-skips", "failed", "duplicate", "incomplete", "missing-start", "wrong-summary", "missing-raw", "duplicate-current", "missing-declared", "embedded-summary", "duplicate-summary", "missing-completion", "wrong-completion"])
def test_instrumentation_requires_actual_nonzero_completed_passes(mutation):
    text = instrumentation((0, 0))
    if mutation == "only-skips": text = instrumentation((-3, -4))
    elif mutation == "failed": text = instrumentation((0, -2))
    elif mutation == "duplicate": text = text.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_STATUS_CODE: 0", 1)
    elif mutation == "incomplete": text = text.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: 1", 1)
    elif mutation == "missing-start": text = text.replace("INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: 0", 1)
    elif mutation == "wrong-summary": text = text.replace("OK (2 tests)", "OK (9 tests)")
    elif mutation == "missing-raw": text = "OK (2 tests)"
    elif mutation == "duplicate-current": text = text.replace("current=2", "current=1")
    elif mutation == "missing-declared": text = text.replace("numtests=2", "numtests=3")
    elif mutation == "embedded-summary": text = text.replace("OK (2 tests)", "INSTRUMENTATION_STATUS: stream=OK (2 tests)")
    elif mutation == "duplicate-summary": text = text.replace("OK (2 tests)", "OK (2 tests)\nOK (2 tests)")
    elif mutation == "missing-completion": text = text.replace("INSTRUMENTATION_CODE: -1\n", "")
    elif mutation == "wrong-completion": text = text.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0")
    with pytest.raises(ci.EvidenceError): ci.instrumentation_counts(text)


def test_android_gate_requires_both_candidate_apks(candidate):
    marker = start(candidate, "android")
    app, _, report = android_files(candidate)
    with pytest.raises(ci.EvidenceError, match="built APKs"):
        ci.collect(marker, candidate[3] / "android", [str(xml(candidate[2] / "android/unit.xml"))], [str(app)], apk_report=report)


@pytest.mark.parametrize("mutation", ["wrong-api", "wrong-serial", "skip-overclaim"])
def test_device_receipt_must_match_measured_environment_and_raw_outcomes(bundle, mutation):
    _, _, execution, _ = bundle
    path = execution / "device/compatibility.json"
    value = ci.load(path)
    if mutation == "wrong-api": value["device"]["api"] = 34
    elif mutation == "wrong-serial": value["device"]["serial"] = "another-emulator"
    else: value["counts"].update(passed=3, skipped=0)
    dump(path, value)
    with pytest.raises(ci.EvidenceError):
        ci.device_record(path, path.with_name("instrumentation.txt"), [execution / "android/app-debug.apk", execution / "android/app-debug-androidTest.apk"], bundle[0] / ci.SUITE_PATH)


def test_compatibility_pass_preserves_all_excluded_required_gates_as_not_run(bundle):
    result = verify(bundle)
    assert result["status"] == "PASS"
    assert result["release_qualification"] == "NOT_RUN"
    assert {row["gate"]: row["status"] for row in result["required_specialist_gates"]} == {gate: "NOT_RUN" for gate in ci.DEBUG_SPECIALIST_GATES}
    assert result["gates"]["device"]["suite"]["scope"] == "debug"
    assert result["gates"]["device"]["suite"]["fullQualification"] == "NOT_RUN"


@pytest.mark.parametrize("mutation", ["claim-specialist-pass", "omit-required-gate", "claim-full-qualification", "wrong-scope", "wrong-manifest", "wrong-selector-routing", "missing-scope-artifact"])
def test_generic_scope_cannot_qualify_or_hide_specialist_gates(bundle, mutation):
    directory = bundle[3] / "device"
    path = directory / "evidence.json"
    sealed = ci.load(path)
    if mutation == "missing-scope-artifact":
        sealed["artifacts"] = [row for row in sealed["artifacts"] if row["role"] != "scope-manifest"]
        dump(path, sealed)
    else:
        row = next(row for row in sealed["artifacts"] if row["role"] == "device-record")
        record_path = directory / row["path"]
        value = ci.load(record_path)
        if mutation == "claim-specialist-pass": value["suite"]["excludedGates"][0]["status"] = "PASS"
        elif mutation == "omit-required-gate": value["suite"]["excludedGates"].pop()
        elif mutation == "claim-full-qualification": value["suite"]["fullQualification"] = "PASS"
        elif mutation == "wrong-scope": value["suite"]["scope"] = "release"
        elif mutation == "wrong-manifest": value["suite"]["manifestSha256"] = "0" * 64
        elif mutation == "wrong-selector-routing": value["suite"]["notClass"].pop()
        dump(record_path, value)
        row.update(sha256=ci.sha(record_path), size_bytes=record_path.stat().st_size)
        sealed["suite"] = value["suite"]
        dump(path, sealed)
    with pytest.raises(ci.EvidenceError): verify(bundle)


@pytest.mark.parametrize("mutation", ["required-selector-skipped", "excluded-selector-executed", "required-selector-retried-after-skip"])
def test_debug_scope_enforces_actual_required_and_excluded_selectors(bundle, mutation):
    repo, _, execution, _ = bundle
    manifest_path = repo / ci.SUITE_PATH
    manifest = ci.load(manifest_path)
    if mutation == "required-selector-skipped":
        manifest["scopes"]["debug"]["requiredPassedSelectors"] = ["synthetic.Test#fixture2"]
    elif mutation == "excluded-selector-executed":
        manifest["scopes"]["debug"]["excludedGates"][0]["selector"] = "synthetic.Test#fixture1"
    else:
        write(execution / "device/instrumentation.txt", instrumentation((-4, 0, -3)).replace("test=fixture2", "test=fixture1"))
    dump(manifest_path, manifest)
    path = execution / "device/compatibility.json"
    value = ci.load(path);value["suite"] = suite_record(repo);dump(path, value)
    with pytest.raises(ci.EvidenceError):
        ci.device_record(path, path.with_name("instrumentation.txt"), [execution / "android/app-debug.apk", execution / "android/app-debug-androidTest.apk"], manifest_path)


def test_additional_source_declared_specialist_is_preserved_as_not_run(candidate):
    repo, _, execution, bundles = candidate
    path = repo / ci.SUITE_PATH
    manifest = ci.load(path)
    manifest["scopes"]["debug"]["excludedGates"].append(dict(
        gate="dedicated_candidate_journey", selector="synthetic.ReleaseJourneyTest",
        reason="synthetic dedicated candidate controller emits its own evidence protocol"))
    dump(path, manifest)
    subprocess.run(["git", "-C", str(repo), "add", ci.SUITE_PATH], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "additional source-declared specialist scope"], check=True)
    commit = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
    updated = build_bundle((repo, commit, execution, bundles))
    result = verify(updated)
    assert result["status"] == "PASS"
    assert result["release_qualification"] == "NOT_RUN"
    gates = {row["gate"]: row["status"] for row in result["required_specialist_gates"]}
    assert gates == {gate: "NOT_RUN" for gate in ci.DEBUG_SPECIALIST_GATES | {"dedicated_candidate_journey"}}
