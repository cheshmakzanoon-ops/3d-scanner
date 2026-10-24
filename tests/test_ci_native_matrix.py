"""Synthetic producer/transport regressions, never native qualification evidence."""
import copy
import json
import os
from pathlib import Path
import shutil

import pytest
import test_ci_evidence_validation as fixtures
from tools.ci.validation import evidence as ci
from tools.ci.validation import native_matrix as matrix

candidate = fixtures.candidate


def prepare(candidate):
    markers = {}
    for profile in matrix.PROFILES:
        marker = candidate[2] / profile / 'start.json'
        matrix.begin(candidate[0], candidate[1], 'test-run:1', profile, marker)
        markers[profile] = marker
    return markers


def split_and_seal(candidate, markers, record):
    value = ci.load(record)
    incoming = candidate[2] / 'download'
    incoming.mkdir()
    for profile in matrix.PROFILES:
        shard = copy.deepcopy(value)
        shard['configurations'] = [row for row in shard['configurations'] if row['name'] == profile]
        shard['comparisons'] = []
        # Independent synthetic oracle; real numerical.compare still executes.
        numeric = next(row for row in shard['configurations'][0]['artifacts'] if Path(row['path']).name == 'numerical.json')
        fixtures.dump(Path(numeric['path']), dict(scenes=[dict(seed=1, noise=0., outliers=0, point_count=180,
            rotation_error=0., median_position_error=0., points=[[0., 1., 2.]], rotation=[1., 0., 0.], translation=[-1., 0., 0.])]))
        numeric['sha256'] = ci.sha(Path(numeric['path']))
        path = fixtures.dump(candidate[2] / profile / 'runner.json', shard)
        sealed = candidate[2] / profile / 'sealed'
        qa = [fixtures.write(candidate[2] / profile / 'qa' / name, 'synthetic QA evidence')
              for name in ('installation.json', 'blender-publisher.sha256', 'blender-install.log', 'blender-check.log')]
        matrix.seal(markers[profile], path, sealed, list(map(str, qa)))
        shutil.copytree(sealed, incoming / ('download-' + profile))
    return incoming


@pytest.fixture
def transport(candidate):
    markers = prepare(candidate)
    incoming = split_and_seal(candidate, markers, fixtures.native(candidate))
    return candidate, incoming


def aggregate(transport):
    candidate, incoming = transport
    output = candidate[3] / 'native'
    matrix.aggregate(incoming, candidate[1], 'test-run:1', output, root=candidate[0])
    return output


def test_original_provenance_survives_transport(transport):
    candidate, incoming = transport
    # Downloaded mtime is not execution freshness; immutable seals remain valid.
    for path in incoming.rglob('*'):
        if path.is_file():
            os.utime(path, ns=(1, 1))
    output = aggregate(transport)
    result = ci.verify_bundle(candidate[3], candidate[1], 'test-run:1', required=('native',))
    assert result['status'] == 'PASS'
    assert result['physical_device'] == 'NOT_RUN'
    for profile in matrix.PROFILES:
        assert (output / 'files' / profile / 'shard.json').read_bytes() == (incoming / ('download-' + profile) / 'shard.json').read_bytes()


@pytest.mark.parametrize('silent', [False, True])
def test_actual_runner_orchestration_to_sealed_matrix(candidate, monkeypatch, silent):
    """Four independent --configs invocations; no receipt splitting or rewriting."""
    import sys
    sys.path.insert(0, str(fixtures.ROOT / 'tools/qa/native_regression'))
    import run
    compiler = fixtures.write(candidate[2] / 'compiler', 'synthetic compiler')
    javac = fixtures.write(candidate[2] / 'jdk/bin/javac', 'synthetic javac')
    fixtures.write(javac.with_name('java'), 'synthetic java')
    fixtures.write(javac.parents[1] / 'release', 'synthetic release')
    incoming = candidate[2] / 'download'
    incoming.mkdir()
    def query(argv, **kwargs):
        if argv[:2] == ['git', 'rev-parse']: return candidate[1] + '\n'
        if argv[:2] == ['git', 'status']: return b''
        assert argv[1] == '--version'
        return 'synthetic compiler version'
    def phase(argv, cwd, folder, label, env, timeout):
        if label == 'build':
            fixtures.write(folder / 'build/librealityforge.so', 'synthetic candidate')
        elif label == 'jvm-baseline-compile':
            fixtures.write(folder / 'jvm-baseline-classes/EmptyJvmControl.class', 'synthetic class')
        elif label == 'numerical':
            fixtures.dump(folder / 'numerical.json', dict(scenes=[dict(seed=1, noise=0., outliers=0,
                point_count=180, rotation_error=0., median_position_error=0., points=[[0., 1., 2.]],
                rotation=[1., 0., 0.], translation=[-1., 0., 0.])]))
        elif label == 'pytest': fixtures.xml(folder / 'pytest.xml')
        elif label.startswith('benchmark_'): fixtures.dump(folder / (label + '.json'), {'synthetic': True})
        log = fixtures.write(folder / (label + '.log'),
            '100% tests passed, 0 tests failed out of 48\n' if label == 'ctest' else
            '' if silent and label == 'numerical' else 'synthetic output\n')
        return dict(command=list(map(str, argv)), cwd=str(cwd), exit_code=0, reason=None,
                    result='PASS', log=str(log), log_sha256=ci.sha(log))
    for profile in matrix.PROFILES:
        marker = candidate[2] / profile / 'start.json'
        matrix.begin(candidate[0], candidate[1], 'test-run:1', profile, marker)
        output = candidate[2] / profile / 'runner'
        with monkeypatch.context() as patch:
            patch.setattr(sys, 'argv', ['run.py', '--configs', profile, '--output', str(output)])
            patch.setattr(run.shutil, 'which', lambda name, **kwargs: str(javac if name == 'javac' else compiler))
            patch.setattr(run.subprocess, 'check_output', query)
            patch.setattr(run.platform, 'platform', lambda: 'synthetic platform')
            patch.setattr(run, 'sanitizer_python_env', lambda compiler, name, env: (env, []))
            patch.setattr(run, 'command', phase)
            assert run.main() == 0
        record = output / 'evidence.json'
        before = record.read_bytes()
        qa = [fixtures.write(candidate[2] / profile / 'qa' / name, 'synthetic QA evidence')
              for name in ('installation.json', 'blender-publisher.sha256', 'blender-install.log', 'blender-check.log')]
        matrix.seal(marker, record, incoming / profile, list(map(str, qa)))
        sealed = matrix.verify_shard(incoming / profile, candidate[1], 'test-run:1', profile)
        assert len(sealed['record']['configurations']) == 1
        assert sealed['record']['comparisons'] == []
        assert sealed['mapped'][str(record)].read_bytes() == before
        if silent:
            log = next(row for row in sealed['envelope']['artifacts'] if row['original_path'].endswith('/numerical.log'))
            assert log['role'] == 'command-log' and log['size_bytes'] == 0
            assert log['sha256'] == 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
    output = aggregate((candidate, incoming))
    assert matrix.verify_matrix(output, candidate[1], 'test-run:1')['tests']['passed'] == 8
    if silent:
        for profile in matrix.PROFILES:
            envelope = ci.load(output / 'files' / profile / 'shard.json')
            log = next(row for row in envelope['artifacts'] if row['original_path'].endswith('/numerical.log'))
            assert log['role'] == 'command-log' and log['size_bytes'] == 0
            assert (output / 'files' / profile / log['path']).read_bytes() == b''
    ci.verify_bundle(candidate[3], candidate[1], 'test-run:1', required=('native',))


@pytest.mark.parametrize('mutation', ['source', 'run', 'profile', 'status', 'time', 'missing', 'duplicate', 'foreign', 'symlink', 'traversal', 'hash', 'duplicate-artifact', 'stale-original', 'missing-qa'])
def test_transport_rejects_invalid_shards(transport, mutation):
    candidate, incoming = transport
    folder = incoming / 'download-gcc-sanitize'
    path = folder / 'shard.json'
    data = ci.load(path)
    if mutation == 'source': data['source_sha'] = '0' * 40
    elif mutation == 'run': data['run_id'] = 'test-run:2'
    elif mutation == 'profile': data['profile'] = 'gcc-release'
    elif mutation == 'status': data['status'] = 'FAIL'
    elif mutation == 'time': data['completed_ns'] = data['started_ns'] - 1
    elif mutation == 'missing': shutil.rmtree(folder)
    elif mutation == 'duplicate': shutil.copytree(folder, incoming / 'duplicate')
    elif mutation == 'foreign': (folder / 'foreign.txt').write_text('foreign')
    elif mutation == 'symlink': (folder / 'linked').symlink_to(folder / 'files', target_is_directory=True)
    elif mutation == 'traversal': data['artifacts'][0]['path'] = '../outside'
    elif mutation == 'hash': (folder / data['artifacts'][0]['path']).write_text('tampered')
    elif mutation == 'duplicate-artifact': data['artifacts'].append(data['artifacts'][0])
    elif mutation == 'stale-original': data['artifacts'][0]['produced_mtime_ns'] = 1
    elif mutation == 'missing-qa':
        qa = next(row for row in data['artifacts'] if row['role'] == 'qa')
        data['artifacts'].remove(qa)
        (folder / qa['path']).unlink()
    if path.exists(): fixtures.dump(path, data)
    with pytest.raises((ci.EvidenceError, ValueError)):
        aggregate(transport)


@pytest.mark.parametrize('mutation', ['missing-baseline', 'failed-baseline', 'unknown-command', 'duplicate-command', 'divergent-numerics'])
def test_rehashed_producer_record_still_cannot_waive_contract(transport, mutation):
    candidate, incoming = transport
    folder = incoming / 'download-gcc-sanitize'
    envelope = ci.load(folder / 'shard.json')
    record_artifact = next(row for row in envelope['artifacts'] if row['role'] == 'native-record')
    record_path = folder / record_artifact['path']
    record = ci.load(record_path)
    config = record['configurations'][0]
    commands = config['commands']
    baseline = next(row for row in commands if Path(row['log']).stem == 'jvm-baseline-empty')
    if mutation == 'missing-baseline': commands.remove(baseline)
    elif mutation == 'failed-baseline': baseline.update(result='FAIL', exit_code=1)
    elif mutation == 'unknown-command': baseline['log'] += '-unknown'
    elif mutation == 'duplicate-command': commands.append(copy.deepcopy(baseline))
    else:
        numeric = next(row for row in config['artifacts'] if Path(row['path']).name == 'numerical.json')
        artifact = next(row for row in envelope['artifacts'] if row['original_path'] == numeric['path'])
        path = folder / artifact['path']
        value = ci.load(path)
        value['scenes'][0]['points'][0][0] = 10.
        fixtures.dump(path, value)
        numeric['sha256'] = ci.sha(path)
        artifact.update(sha256=ci.sha(path), size_bytes=path.stat().st_size)
    fixtures.dump(record_path, record)
    record_artifact.update(sha256=ci.sha(record_path), size_bytes=record_path.stat().st_size)
    fixtures.dump(folder / 'shard.json', envelope)
    with pytest.raises((ci.EvidenceError, ValueError)):
        aggregate(transport)


@pytest.mark.parametrize('mutation', ['comparison', 'source', 'counts', 'foreign', 'symlink', 'manifest', 'unknown-format', 'missing-shard'])
def test_final_verifier_rejects_mutated_aggregate(transport, mutation):
    candidate, _ = transport
    output = aggregate(transport)
    data = ci.load(output / 'evidence.json')
    if mutation == 'comparison':
        manifest = ci.load(output / 'matrix.json')
        manifest['comparisons'][0]['result'] = 'FAIL'
        fixtures.dump(output / 'matrix.json', manifest)
        data['artifacts'] = [matrix.identity(output / 'matrix.json', 'matrix.json', 'matrix.json', 'native-matrix')]
    elif mutation == 'source': data['source_sha'] = '0' * 40
    elif mutation == 'counts': data['tests']['passed'] += 1
    elif mutation == 'foreign': (output / 'foreign.txt').write_text('foreign')
    elif mutation == 'symlink': (output / 'files/linked').symlink_to(output / 'files/gcc-release', target_is_directory=True)
    elif mutation == 'unknown-format': data['native_format'] = 'future-unknown'
    elif mutation == 'missing-shard': (output / 'files/gcc-release/shard.json').unlink()
    else: (output / 'matrix.json').write_text('{}')
    fixtures.dump(output / 'evidence.json', data)
    with pytest.raises((ci.EvidenceError, ValueError)):
        ci.verify_bundle(candidate[3], candidate[1], 'test-run:1', required=('native',))


def test_final_verifier_recomputes_parity_after_all_envelopes_rehashed(transport):
    candidate, _ = transport
    output = aggregate(transport)
    folder = output / 'files/clang-sanitize'
    envelope = ci.load(folder / 'shard.json')
    record_artifact = next(row for row in envelope['artifacts'] if row['role'] == 'native-record')
    record_path = folder / record_artifact['path']
    record = ci.load(record_path)
    numeric = next(row for row in record['configurations'][0]['artifacts'] if Path(row['path']).name == 'numerical.json')
    artifact = next(row for row in envelope['artifacts'] if row['original_path'] == numeric['path'])
    path = folder / artifact['path']
    value = ci.load(path)
    value['scenes'][0]['points'][0][0] = 20.
    fixtures.dump(path, value)
    numeric['sha256'] = ci.sha(path)
    artifact.update(sha256=ci.sha(path), size_bytes=path.stat().st_size)
    fixtures.dump(record_path, record)
    record_artifact.update(sha256=ci.sha(record_path), size_bytes=record_path.stat().st_size)
    fixtures.dump(folder / 'shard.json', envelope)
    manifest = ci.load(output / 'matrix.json')
    next(row for row in manifest['shards'] if row['profile'] == 'clang-sanitize')['sha256'] = ci.sha(folder / 'shard.json')
    fixtures.dump(output / 'matrix.json', manifest)
    outer = ci.load(output / 'evidence.json')
    outer['artifacts'] = [matrix.identity(output / 'matrix.json', 'matrix.json', 'matrix.json', 'native-matrix')]
    fixtures.dump(output / 'evidence.json', outer)
    with pytest.raises(ValueError, match='Compiler parity failed'):
        ci.verify_bundle(candidate[3], candidate[1], 'test-run:1', required=('native',))


@pytest.mark.parametrize('mutation', ['repository', 'gate', 'kind', 'boolean-time', 'boolean-size', 'original-relative', 'original-parent', 'original-control'])
def test_explicit_shard_identity_and_lexical_metadata(transport, mutation):
    candidate, incoming = transport
    path = incoming / 'download-gcc-release/shard.json'
    data = ci.load(path)
    if mutation == 'repository': data['repository'] = 'foreign/repository'
    elif mutation == 'gate': data['gate'] = 'android'
    elif mutation == 'kind': data['kind'] = 'physical-capture'
    elif mutation == 'boolean-time': data['started_ns'] = True
    elif mutation == 'boolean-size': data['artifacts'][0]['size_bytes'] = True
    elif mutation == 'original-relative': data['artifacts'][0]['original_path'] = 'relative/file'
    elif mutation == 'original-parent': data['artifacts'][0]['original_path'] = '/root/../file'
    else: data['artifacts'][0]['original_path'] = '/root/\nfile'
    fixtures.dump(path, data)
    with pytest.raises(ci.EvidenceError):
        aggregate(transport)


@pytest.mark.parametrize('bound', ['MAX_FILES', 'MAX_ENTRIES', 'MAX_BYTES', 'MAX_FILE_BYTES', 'MAX_DEPTH', 'INVENTORY_SECONDS'])
def test_inventory_limits_fail_closed(transport, monkeypatch, bound):
    monkeypatch.setattr(matrix, bound, 0)
    with pytest.raises(ci.EvidenceError, match='limit'):
        aggregate(transport)


def test_producer_mutation_during_copy_is_rejected(transport, monkeypatch):
    candidate, incoming = transport
    profile = 'gcc-release'
    marker = candidate[2] / profile / 'start.json'
    record = candidate[2] / profile / 'runner.json'
    qa = list((candidate[2] / profile / 'qa').iterdir())
    real_copy = matrix.copy_snapshot
    changed = False
    def mutating_copy(source, target, *args, **kwargs):
        nonlocal changed
        result = real_copy(source, target, *args, **kwargs)
        if not changed:
            changed = True
            Path(source).write_bytes(Path(source).read_bytes() + b' ')
        return result
    monkeypatch.setattr(matrix, 'copy_snapshot', mutating_copy)
    with pytest.raises(ci.EvidenceError, match='changed during sealing'):
        matrix.seal(marker, record, candidate[2] / 'mutation-output', list(map(str, qa)))
    assert not (candidate[2] / 'mutation-output/shard.json').exists()


def test_final_repository_binding_uses_expected_workflow_environment(transport, monkeypatch):
    candidate, _ = transport
    output = aggregate(transport)
    monkeypatch.setenv('GITHUB_REPOSITORY', 'other/project')
    with pytest.raises(ci.EvidenceError, match='matrix envelope'):
        ci.verify_bundle(candidate[3], candidate[1], 'test-run:1', required=('native',))


def test_producer_stale_original_never_becomes_fresh_on_copy(transport):
    candidate, _ = transport
    profile = 'gcc-release'
    record = candidate[2] / profile / 'runner.json'
    os.utime(record, ns=(1, 1))
    qa = list((candidate[2] / profile / 'qa').iterdir())
    with pytest.raises(ci.EvidenceError, match='Stale'):
        matrix.seal(candidate[2] / profile / 'start.json', record,
                    candidate[2] / 'stale-output', list(map(str, qa)))



def test_copy_snapshot_refuses_growth_before_copy(tmp_path):
    source = fixtures.write(tmp_path / 'source', 'original')
    snapshot = matrix.stable_snapshot(source)
    source.write_text('original' * 100)
    with pytest.raises(ci.EvidenceError, match='changed before copying'):
        matrix.copy_snapshot(source, tmp_path / 'target', snapshot)
    assert not (tmp_path / 'target').exists()


@pytest.mark.parametrize('relative', ['files/\x00bad', 'files/\nbad', 'files/\x7fbad'])
def test_transport_path_controls_are_rejected(tmp_path, relative):
    with pytest.raises(ci.EvidenceError, match='Unsafe'):
        matrix.safe_file(tmp_path, relative)


def test_inventory_directory_swap_cannot_follow_symlink(tmp_path, monkeypatch):
    root = tmp_path / 'bundle'
    (root / 'files').mkdir(parents=True)
    fixtures.write(root / 'files/original', 'original')
    outside = tmp_path / 'outside'
    fixtures.write(outside / 'foreign', 'must not be traversed')
    original_open = os.open
    swapped = False
    def swapping_open(path, flags, *args, **kwargs):
        nonlocal swapped
        if path == 'files' and kwargs.get('dir_fd') is not None and not swapped:
            swapped = True
            (root / 'files').rename(root / 'prior-files')
            (root / 'files').symlink_to(outside, target_is_directory=True)
        return original_open(path, flags, *args, **kwargs)
    monkeypatch.setattr(matrix.os, 'open', swapping_open)
    with pytest.raises((OSError, ci.EvidenceError)):
        matrix.inventory(root)
    assert swapped


@pytest.mark.parametrize('mutation', ['missing', 'truncated', 'rehash-truncated'])
def test_silent_log_allowance_does_not_accept_lost_output(transport, mutation):
    candidate, incoming = transport
    folder = incoming / 'download-gcc-release'
    envelope = ci.load(folder / 'shard.json')
    artifact = next(row for row in envelope['artifacts'] if row['original_path'].endswith('/numerical.log'))
    path = folder / artifact['path']
    assert path.stat().st_size > 0
    if mutation == 'missing':
        path.unlink()
    else:
        path.write_bytes(b'')
        if mutation == 'rehash-truncated':
            artifact.update(size_bytes=0, sha256=ci.sha(path))
            fixtures.dump(folder / 'shard.json', envelope)
    with pytest.raises(ci.EvidenceError):
        aggregate(transport)


@pytest.mark.parametrize('name', ['numerical.json', 'lib.so', 'pytest.xml', 'installation.json'])
@pytest.mark.parametrize('relabel', [False, True])
def test_non_command_evidence_never_gets_empty_allowance(transport, name, relabel):
    candidate, incoming = transport
    folder = incoming / 'download-gcc-release'
    envelope = ci.load(folder / 'shard.json')
    artifact = next(row for row in envelope['artifacts'] if Path(row['original_path']).name == name)
    path = folder / artifact['path']
    path.write_bytes(b'')
    artifact.update(size_bytes=0, sha256=ci.sha(path))
    if relabel:
        artifact['role'] = 'command-log'
    fixtures.dump(folder / 'shard.json', envelope)
    with pytest.raises((ci.EvidenceError, ValueError)):
        aggregate(transport)


def test_previous_nonempty_command_artifact_roles_remain_valid(transport):
    candidate, incoming = transport
    for folder in incoming.iterdir():
        envelope = ci.load(folder / 'shard.json')
        for artifact in envelope['artifacts']:
            if artifact['role'] == 'command-log':
                assert artifact['size_bytes'] > 0
                artifact['role'] = 'artifact'
        fixtures.dump(folder / 'shard.json', envelope)
    aggregate(transport)
    ci.verify_bundle(candidate[3], candidate[1], 'test-run:1', required=('native',))


def test_snapshot_empty_requires_explicit_command_allowance(tmp_path):
    path = fixtures.write(tmp_path / 'empty.log', '')
    with pytest.raises(ci.EvidenceError, match='Invalid/oversized'):
        matrix.stable_snapshot(path)
    snapshot = matrix.stable_snapshot(path, allow_empty=True)
    assert snapshot[0][3] == 0
    assert snapshot[1] == 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'


def test_final_verifier_rejects_rehashed_truncated_command_log(transport):
    candidate, _ = transport
    output = aggregate(transport)
    folder = output / 'files/gcc-release'
    envelope = ci.load(folder / 'shard.json')
    artifact = next(row for row in envelope['artifacts'] if row['original_path'].endswith('/numerical.log'))
    path = folder / artifact['path']
    path.write_bytes(b'')
    artifact.update(size_bytes=0, sha256=ci.sha(path))
    fixtures.dump(folder / 'shard.json', envelope)
    manifest = ci.load(output / 'matrix.json')
    manifest['shards'][0]['sha256'] = ci.sha(folder / 'shard.json')
    fixtures.dump(output / 'matrix.json', manifest)
    outer = ci.load(output / 'evidence.json')
    outer['artifacts'] = [matrix.identity(output / 'matrix.json', 'matrix.json', 'matrix.json', 'native-matrix')]
    fixtures.dump(output / 'evidence.json', outer)
    with pytest.raises(ci.EvidenceError, match='command/log failed integrity'):
        ci.verify_bundle(candidate[3], candidate[1], 'test-run:1', required=('native',))


def test_empty_command_log_does_not_waive_freshness(transport):
    candidate, _ = transport
    profile = 'gcc-release'
    record_path = candidate[2] / profile / 'runner.json'
    record = ci.load(record_path)
    command = next(row for row in record['configurations'][0]['commands'] if row['log'].endswith('/numerical.log'))
    log = Path(command['log'])
    log.write_bytes(b'')
    command['log_sha256'] = ci.sha(log)
    fixtures.dump(record_path, record)
    os.utime(log, ns=(1, 1))
    qa = list((candidate[2] / profile / 'qa').iterdir())
    with pytest.raises(ci.EvidenceError, match='Missing/stale/symlink command log'):
        matrix.seal(candidate[2] / profile / 'start.json', record_path,
                    candidate[2] / 'stale-empty-log-output', list(map(str, qa)))


@pytest.mark.parametrize('mutation', ['failed', 'nonzero', 'timeout', 'not-run'])
def test_empty_command_output_never_waives_execution_failure(transport, mutation):
    candidate, incoming = transport
    folder = incoming / 'download-gcc-release'
    envelope = ci.load(folder / 'shard.json')
    record_artifact = next(row for row in envelope['artifacts'] if row['role'] == 'native-record')
    record_path = folder / record_artifact['path']
    record = ci.load(record_path)
    command = next(row for row in record['configurations'][0]['commands'] if row['log'].endswith('/numerical.log'))
    artifact = next(row for row in envelope['artifacts'] if row['original_path'] == command['log'])
    path = folder / artifact['path']
    path.write_bytes(b'')
    artifact.update(size_bytes=0, sha256=ci.sha(path))
    command['log_sha256'] = ci.sha(path)
    if mutation == 'failed': command['result'] = 'FAIL'
    elif mutation == 'nonzero': command['exit_code'] = 17
    elif mutation == 'timeout': command['reason'] = 'TIMEOUT'
    else: command['result'] = 'NOT_RUN'
    fixtures.dump(record_path, record)
    record_artifact.update(size_bytes=record_path.stat().st_size, sha256=ci.sha(record_path))
    fixtures.dump(folder / 'shard.json', envelope)
    with pytest.raises(ci.EvidenceError, match='command/log failed integrity'):
        aggregate(transport)
