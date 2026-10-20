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


@pytest.fixture
def candidate(tmp_path):
    repo = tmp_path / 'repo'
    repo.mkdir()
    subprocess.run(['git', 'init', '-q', str(repo)], check=True)
    write(repo / 'README', 'synthetic test repository')
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
        numeric = dump(folder / 'numerical.json', {'fixture': 'synthetic'})
        result['configurations'].append(dict(name=name, result='PASS', commands=commands, binaries=[dict(path=str(lib), sha256=ci.sha(lib))], artifacts=[dict(path=str(numeric), sha256=ci.sha(numeric))]))
    result['comparisons'] = [dict(config=row['name'], result='PASS') for row in result['configurations'][1:]]
    return dump(execution / 'native' / 'evidence.json', result)


def android_files(candidate):
    execution = candidate[2]
    app = write(execution / 'android' / 'app-debug.apk', 'synthetic application fixture')
    test = write(execution / 'android' / 'app-debug-androidTest.apk', 'synthetic instrumentation fixture')
    report = dump(execution / 'android' / 'apk-validation.json', dict(internetPermission=False, zipAlignment16k=True, elfLibrariesChecked=['lib/x86_64/lib.so']))
    return app, test, report


@pytest.fixture
def bundle(candidate):
    _, _, execution, bundles = candidate
    marker = start(candidate, 'native')
    ci.collect(marker, bundles / 'native', native=native(candidate))
    marker = start(candidate, 'android')
    app, test, report = android_files(candidate)
    ci.collect(marker, bundles / 'android', [str(xml(execution / 'android' / 'unit.xml'))], [str(app), str(test)], apk_report=report)
    marker = start(candidate, 'device')
    device = dict(schema=1, status='PASS', tests=2, device=dict(serial='emulator-test', fingerprint='synthetic emulator fixture', api=35, abi='x86_64', pageSize=4096, emulator=True), appSha256=ci.sha(app), testSha256=ci.sha(test), physicalCapture='NOT_RUN')
    record = dump(execution / 'device' / 'compatibility.json', device)
    write(record.with_name('instrumentation.txt'), 'OK (2 tests)\n')
    ci.collect(marker, bundles / 'device', artifact_patterns=[str(app), str(test)], screenshot=png(execution / 'device' / 'photographic-viewer.png'), device=record)
    return candidate


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


@pytest.mark.parametrize('mutation', ['missing-config', 'failed-command', 'missing-ctest', 'failed-parity', 'wrong-source', 'dirty-tree'])
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
    dump(path, value)
    with pytest.raises(ci.EvidenceError): ci.native_record(path, candidate[1])
