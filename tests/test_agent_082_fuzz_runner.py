from synthetic_child_env import synthetic_child_env, child_evidence
import importlib.util
import json
import subprocess

import pytest
from pathlib import Path
import struct
import sys
import zlib

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('fuzzrun', ROOT / 'tools/qa/fuzz/run.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_checkpoint_seed_has_real_format_and_crc_and_bad_variant(tmp_path):
    result = module.corpus(tmp_path)
    assert result['synthetic']
    data = (tmp_path / 'checkpoint/valid32').read_bytes()
    assert data[:8] == b'RFDPT018'
    assert struct.unpack('<IIIII', data[8:28]) == (3, 32, 32, 0, 8192)
    assert struct.unpack('<I', data[92:96])[0] == zlib.crc32(data[96:])
    assert (tmp_path / 'checkpoint/bad_crc').read_bytes() != data
    before = result
    assert module.corpus(tmp_path) == before


def test_failing_subprocess_preserves_nonzero_status_and_log(tmp_path):
    log = tmp_path / 'failure.log'
    r = module.invoke([sys.executable, '-I', '-S', '-c', 'print("diagnostic"); raise SystemExit(17)'], log, 5, environment=synthetic_child_env())
    assert r['status'] == 'FAIL' and r['exit_code'] == 17, child_evidence(r['exit_code'], log.read_bytes())
    assert log.read_text().strip() == 'diagnostic'
    assert len(r['log_sha256']) == 64


def test_timeout_is_failure_with_diagnostic_log(tmp_path):
    r = module.invoke([sys.executable, '-c', 'import time; time.sleep(5)'], tmp_path / 'timeout.log', 1)
    assert r['status'] == 'FAIL' and r['exit_code'] is None
    assert 'incomplete run' in (tmp_path / 'timeout.log').read_text()

# These isolated runner fixtures deliberately simulate completion messages and
# build outputs. They validate rejection/provenance; real sanitizer qualification
# runs separately with the actual CMake/Clang targets, never these scripts.
def git_commit(root, message):
    subprocess.run(['git', 'add', '.'], cwd=root, check=True, capture_output=True)
    subprocess.run(['git', '-c', 'user.name=Runner fixture', '-c', 'user.email=fixture@example.invalid',
                    'commit', '-qm', message], cwd=root, check=True, capture_output=True)


@pytest.fixture
def stamped_fixture(tmp_path, monkeypatch):
    root = tmp_path / 'source'
    (root / 'reconstruction/src').mkdir(parents=True)
    (root / 'tools/qa/fuzz').mkdir(parents=True)
    (root / 'CMakeLists.txt').write_text('# isolated source fingerprint fixture\n')
    (root / 'reconstruction/src/core.cpp').write_text('// synthetic source fixture\n')
    (root / 'tools/qa/fuzz/run.py').write_bytes((ROOT / 'tools/qa/fuzz/run.py').read_bytes())
    subprocess.run(['git', 'init', '-q', str(root)], check=True, capture_output=True)
    git_commit(root, 'synthetic baseline')
    monkeypatch.setattr(module, 'ROOT', root)
    build = tmp_path / 'build'
    (build / 'native').mkdir(parents=True)
    library = build / 'native/librealityforge.so'
    library.write_bytes(b'synthetic library provenance fixture; not an ELF qualification artifact')
    compiler = tmp_path / 'compiler'
    compiler.write_text('#!/bin/sh\necho "Synthetic compiler identity fixture"\n# provenance marker=A\n')
    compiler.chmod(0o755)
    for kind in module.KINDS:
        binary = build / ('rf_fuzz_' + kind)
        binary.write_text('#!/bin/sh\necho "Done 10 runs in 0 second(s)"\n')
        binary.chmod(0o755)

    def stamp():
        record = module.build_record(build, module.compiler_record(str(compiler)), module.source_snapshot())
        (build / module.STAMP_NAME).write_text(json.dumps(record))
        return record

    stamp()
    return dict(root=root, build=build, library=library, compiler=compiler, stamp=stamp,
                output=tmp_path / 'evidence')


def run_fixture(fixture, output=None):
    output = output or fixture['output']
    code = module.main(['--build', str(fixture['build']), '--compiler', str(fixture['compiler']),
                        '--output', str(output), '--skip-build', '--runs', '10', '--seconds', '1'])
    return code, json.loads((output / 'evidence.json').read_text())


def test_exact_stamp_records_native_library_and_original_source(stamped_fixture):
    code, evidence = run_fixture(stamped_fixture)
    assert code == 0 and evidence['status'] == 'PASS'
    retained = json.loads(Path(evidence['buildstamp']).read_text())
    assert retained['library'] == evidence['library']
    assert module.sha(Path(evidence['buildstamp'])) == evidence['buildstamp_sha256']
    assert evidence['source_sha'] == evidence['build_source_sha']
    assert evidence['source_fingerprint'] == evidence['build_source_fingerprint']
    assert evidence['library']['sha256'] == module.sha(stamped_fixture['library'])
    assert evidence['compiler_identity']['sha256'] == module.sha(stamped_fixture['compiler'])
    assert [s['completed_runs'] for s in evidence['steps']] == [[10], [10], [10]]
    assert all(s['budget_completion'] == 'runs' for s in evidence['steps'])


@pytest.mark.parametrize('change', ['binary', 'library', 'compiler', 'source', 'new_revision'])
def test_skip_build_rejects_stale_outputs_and_source(stamped_fixture, change):
    f = stamped_fixture
    if change == 'binary':
        binary = f['build'] / 'rf_fuzz_numeric'
        binary.write_bytes(binary.read_bytes().replace(b'10 runs', b'11 runs'))
    elif change == 'library':
        data = f['library'].read_bytes()
        f['library'].write_bytes(bytes([data[0] ^ 1]) + data[1:])
    elif change == 'compiler':
        compiler = f['compiler']
        compiler.write_bytes(compiler.read_bytes().replace(b'marker=A', b'marker=B'))
    elif change == 'source':
        (f['root'] / 'reconstruction/src/core.cpp').write_text('// changed source; same HEAD\n')
    else:
        (f['root'] / 'metadata.txt').write_text('new HEAD with identical native source')
        git_commit(f['root'], 'metadata-only new revision')
    code, evidence = run_fixture(f)
    assert code == 1 and evidence['status'] == 'FAIL'
    assert 'Buildstamp mismatch' in evidence['blocker']
    assert evidence['steps'] == []  # Failure precedes running stale code.
    assert not (f['output'] / 'numeric.log').exists()


@pytest.mark.parametrize('stamp', ['absent', 'invalid_json'])
def test_skip_build_rejects_unstamped_or_invalid_builds(stamped_fixture, stamp):
    path = stamped_fixture['build'] / module.STAMP_NAME
    if stamp == 'absent':
        path.unlink()
    else:
        path.write_text('not a buildstamp')
    code, evidence = run_fixture(stamped_fixture)
    assert code == 1 and evidence['status'] == 'FAIL'
    assert evidence['steps'] == []


@pytest.mark.parametrize('message', ['Done 0 runs in 0 second(s)', 'Done 3 runs in 0 second(s)',
                                    'Done 10 runs in 0 second(s)\nDone 0 runs in 0 second(s)', 'no completion'])
def test_zero_or_incomplete_execution_never_passes(stamped_fixture, message):
    binary = stamped_fixture['build'] / 'rf_fuzz_numeric'
    binary.write_text('#!/bin/sh\nprintf "%s\\n" ' + "'" + message + "'\n")
    stamped_fixture['stamp']()  # The completion check must fail even with matched provenance.
    code, evidence = run_fixture(stamped_fixture)
    assert code == 1 and evidence['status'] == 'FAIL'
    first = evidence['steps'][0]
    assert first['exit_code'] == 0 and first['status'] == 'FAIL'
    assert first['budget_completion'] is None
    assert message in Path(first['log']).read_text()


def test_time_completion_requires_positive_work_and_real_elapsed_budget():
    assert module.completion('Done 3 runs in 30 second(s)\n', 10000, 30, 30.1)['budget_completion'] == 'time'
    assert module.completion('Done 3 runs in 30 second(s)\n', 10000, 30, .1)['budget_completion'] is None
    assert module.completion('Done 0 runs in 30 second(s)\n', 10000, 30, 30.1)['budget_completion'] is None


@pytest.mark.parametrize('existing', ['empty', 'with_stale_corpus', 'symlink', 'symlink_parent'])
def test_reused_or_symlink_evidence_is_rejected_without_overwrite(stamped_fixture, tmp_path, existing):
    output = stamped_fixture['output']
    marker = None
    if existing in ('empty', 'with_stale_corpus'):
        output.mkdir()
        if existing == 'with_stale_corpus':
            marker = output / 'old-corpus'
            marker.write_bytes(b'preserve historical input')
    else:
        target = tmp_path / 'historical'
        target.mkdir()
        marker = target / 'old-evidence'
        marker.write_bytes(b'preserve historical input')
        output.symlink_to(target, target_is_directory=True)
        if existing == 'symlink_parent':
            output = output / 'new-child'
    with pytest.raises(SystemExit) as error:
        run_fixture(stamped_fixture, output)
    assert error.value.code == 2
    if marker:
        assert marker.read_bytes() == b'preserve historical input'
    assert not (output / 'evidence.json').exists()


def test_library_changed_during_execution_invalidates_receipt(stamped_fixture):
    binary = stamped_fixture['build'] / 'rf_fuzz_numeric'
    binary.write_text('#!/bin/sh\nprintf changed > "' + str(stamped_fixture['library']) + '"\necho "Done 10 runs in 0 second(s)"\n')
    stamped_fixture['stamp']()
    code, evidence = run_fixture(stamped_fixture)
    assert code == 1 and evidence['status'] == 'FAIL'
    assert len(evidence['steps']) == 1
    assert 'Buildstamp mismatch' in evidence['steps'][0]['blocker']


def test_fingerprint_catches_changes_when_head_and_dirty_flag_are_unchanged(stamped_fixture):
    f = stamped_fixture
    source = f['root'] / 'reconstruction/src/core.cpp'
    source.write_text('// dirty source version A\n')
    old = f['stamp']()
    assert old['build_source_dirty'] is True
    source.write_text('// dirty source version B\n')
    current = module.source_snapshot()
    assert current['source_sha'] == old['build_source_sha'] and current['source_dirty'] is True
    assert current['source_fingerprint'] != old['source_fingerprint']
    code, evidence = run_fixture(f)
    assert code == 1 and evidence['status'] == 'FAIL'
    assert 'Buildstamp mismatch' in evidence['blocker']
