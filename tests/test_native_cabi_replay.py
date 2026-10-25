"""Supplementary diagnostic guards; these tests are not sanitizer evidence."""
from synthetic_child_env import synthetic_child_env, child_evidence
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

from tools.qa.native_cabi_replay import run
from tools.qa.native_cabi_replay.prepare import require
from tools.qa.native_cabi_replay.verify import parse_glb


def clean(monkeypatch):
    for key in ('LD_PRELOAD', 'LD_AUDIT', 'ASAN_OPTIONS', 'LSAN_OPTIONS', 'UBSAN_OPTIONS'):
        monkeypatch.delenv(key, raising=False)


def test_guard_survives_optimized_python():
    result = subprocess.run([sys.executable, '-O', '-c',
                             'from tools.qa.native_cabi_replay.prepare import require; require(False,"guard")'],
                            capture_output=True, text=True)
    assert result.returncode != 0 and 'guard' in result.stderr


@pytest.mark.parametrize('key,value', [('LD_PRELOAD', 'foreign.so'), ('LD_AUDIT', 'foreign.so'),
    ('ASAN_OPTIONS', 'detect_leaks=0'), ('LSAN_OPTIONS', 'suppressions=anything'), ('UBSAN_OPTIONS', 'halt_on_error=0')])
def test_injected_controller_environment_rejected(monkeypatch, key, value):
    clean(monkeypatch)
    monkeypatch.setenv(key, value)
    with pytest.raises(RuntimeError, match='clean controller'):
        run.native_environment('sanitize')


def test_sanitizer_policy_preserves_ordinary_exit_leaks(monkeypatch):
    clean(monkeypatch)
    env = run.native_environment('sanitize')
    assert 'detect_leaks=1' in env['ASAN_OPTIONS']
    assert env['LSAN_OPTIONS'] == 'exitcode=23'
    assert 'halt_on_error=1' in env['UBSAN_OPTIONS']


LEAK_HEADER = '==123==ERROR: LeakSanitizer: detected memory leaks\n'
LEAK_BLOCK = ('Direct leak of 1237 byte(s) in 1 object(s) allocated from:\n'
              '    #0 0x123 in malloc /compiler-rt/asan_malloc_linux.cpp:69:3\n'
              '    #1 0x456 in planted_leak /source/control.c:4:15\n')
LEAK_SUMMARY = 'SUMMARY: AddressSanitizer: 1237 byte(s) leaked in 1 allocation(s).\n'
EXACT_LEAK = LEAK_HEADER + '\n' + LEAK_BLOCK + '\n' + LEAK_SUMMARY


@pytest.mark.parametrize('text', [
    EXACT_LEAK,
    EXACT_LEAK.replace('==123==', '').replace('SUMMARY: AddressSanitizer:', 'SUMMARY: LeakSanitizer:'),
    EXACT_LEAK.replace('in planted_leak ', 'in planted_leak() ').replace('    #', '  #'),
])
def test_planted_control_accepts_complete_gcc_clang_syntax(text):
    assert run.planted_detected(text, 23)


@pytest.mark.parametrize('text,code', [
    (EXACT_LEAK, 0),
    (EXACT_LEAK.replace('in planted_leak ', 'in unrelated '), 23),
    (EXACT_LEAK.replace('Direct leak of 1237', 'Direct leak of 1238'), 23),
    (EXACT_LEAK.replace('Direct leak', 'Indirect leak'), 23),
    (EXACT_LEAK.replace(LEAK_HEADER, ''), 23),
    (EXACT_LEAK.replace(LEAK_HEADER, LEAK_HEADER * 2), 23),
    (EXACT_LEAK.replace(LEAK_SUMMARY, ''), 23),
    (EXACT_LEAK.replace(LEAK_SUMMARY, 'SUMMARY:\n'), 23),
    (EXACT_LEAK.replace(LEAK_SUMMARY, LEAK_SUMMARY * 2), 23),
    (EXACT_LEAK.replace('1237 byte(s) leaked', '1238 byte(s) leaked'), 23),
    (EXACT_LEAK.replace('1 allocation(s)', '2 allocation(s)'), 23),
    (EXACT_LEAK.replace(LEAK_BLOCK, LEAK_BLOCK * 2), 23),
    (EXACT_LEAK.replace(LEAK_SUMMARY, 'Direct leak of 4 byte(s) in 1 object(s) allocated from:\n #0 in unrelated\n' + LEAK_SUMMARY), 23),
    (EXACT_LEAK.replace(LEAK_SUMMARY, 'Indirect leak of 4 byte(s) in 1 object(s) allocated from:\n #0 in unrelated\n' + LEAK_SUMMARY), 23),
    (EXACT_LEAK + 'ERROR: AddressSanitizer: heap-use-after-free\n', 23),
    (EXACT_LEAK + 'AddressSanitizer:DEADLYSIGNAL\n', 23),
    (EXACT_LEAK + 'LeakSanitizer has encountered a fatal error\n', 23),
    (EXACT_LEAK + 'source.cpp:1: runtime error: overflow\n', 23),
    (EXACT_LEAK + 'UndefinedBehaviorSanitizer: undefined-behavior\n', 23),
    (LEAK_SUMMARY + LEAK_BLOCK + LEAK_HEADER, 23),
    ('Direct leak of 1237 byte(s) in 1 object(s) allocated from:\n #1 planted_leak control.c:4\nSUMMARY:', 23),
])
def test_planted_control_rejects_incomplete_extra_or_other_findings(text, code):
    assert not run.planted_detected(text, code)


def test_fresh_evidence_directory_required(tmp_path):
    with pytest.raises(FileExistsError):
        run.Runner(tmp_path, 1)


def test_timeout_keeps_failed_command_receipt(tmp_path):
    runner = run.Runner(tmp_path / 'evidence', .1)
    with pytest.raises(RuntimeError, match='TIMEOUT'):
        runner.command('timeout', [sys.executable, '-c', 'import time; time.sleep(30)'])
    result = json.loads((runner.output / 'receipt.json').read_text())
    assert result['commands'][0]['status'] == 'TIMEOUT'
    assert result['commands'][0]['returncode'] != 0
    assert result['commands'][0]['log_sha256']


def test_nonzero_scanner_command_not_waived(tmp_path):
    runner = run.Runner(tmp_path / 'evidence', 5)
    with pytest.raises(RuntimeError, match='FAILED'):
        runner.command('scanner', [sys.executable, '-c', 'raise SystemExit(23)'])
    assert runner.receipt['commands'][0]['status'] == 'FAILED'


def test_main_failure_retains_receipt(monkeypatch, tmp_path):
    def fail(*_):
        raise RuntimeError('deliberate preparation failure')
    monkeypatch.setattr(run, 'execute', fail)
    output = tmp_path / 'evidence'
    assert run.main(['--output', str(output), '--config', 'normal']) == 1
    receipt = json.loads((output / 'receipt.json').read_text())
    assert receipt['status'] == 'FAIL' and 'deliberate' in receipt['failure']


def test_glb_parser_rejects_malformed(tmp_path):
    path = tmp_path / 'bad.glb'
    path.write_bytes(b'glTF')
    with pytest.raises(RuntimeError, match='Truncated'):
        parse_glb(path)


def test_log_budget_failure_preserves_receipt(monkeypatch, tmp_path):
    monkeypatch.setattr(run, 'MAX_LOG', 16)
    runner = run.Runner(tmp_path / 'evidence', 5)
    with pytest.raises(RuntimeError):
        runner.command('large-log', [sys.executable, '-c', 'print("x" * 100)'])
    assert runner.receipt['commands'][0]['status'] == 'LOG_LIMIT'
    assert runner.receipt['commands'][0]['log_sha256']


def test_additive_workflow_is_parseable_and_narrow():
    import yaml
    root = Path(__file__).resolve().parents[1]
    workflow = yaml.safe_load((root / '.github/workflows/native-cabi-replay-diagnostic.yml').read_text())
    assert workflow['permissions'] == {'contents': 'read'}
    events = workflow.get('on', workflow.get(True))  # YAML1.1 treats on as a boolean.
    assert set(events) == {'push', 'workflow_dispatch'}
    assert events['push']['branches'] == ['main']
    assert events['push']['paths']
    for step in workflow['jobs']['supplementary-native-replay']['steps']:
        if 'uses' in step:
            assert len(step['uses'].split('@')[1]) == 40


def test_ordinary_parity_child_imports_snapshot_not_working_tree(tmp_path):
    source = tmp_path / 'snapshot'
    package = source / 'tools/qa/native_cabi_replay'
    package.mkdir(parents=True)
    (package / '__init__.py').write_text('')
    # This sentinel implements the child interface, without loading any native code.
    (package / 'run.py').write_text(
        'def require(condition, message):\n'
        '    if not condition: raise RuntimeError(message)\n'
        'def ctypes_parity(source, library, inputs, expected):\n'
        '    require(expected == {"snapshot": True}, "wrong arguments")\n'
        '    print("SNAPSHOT_CONTROLLER_SELECTED")\n')
    expected = tmp_path / 'expected.json'
    expected.write_text('{"snapshot": true}')
    command = list(map(str, run.ordinary_parity_command(source, tmp_path / 'unused.so', tmp_path, expected)))
    # This exact source snapshot contains only the sentinel above, not ctypes.
    command[1:1] = ['-I', '-S']
    result = subprocess.run(command, capture_output=True, text=True, timeout=10,
                            env=synthetic_child_env())
    assert result.returncode == 0, child_evidence(result.returncode, result.stderr)
    assert result.stdout.strip() == 'SNAPSHOT_CONTROLLER_SELECTED'


def test_configure_requires_all_reviewed_providers(monkeypatch, tmp_path):
    clean(monkeypatch)
    runner = run.Runner(tmp_path / 'evidence', 5)
    source = tmp_path / 'snapshot'
    monkeypatch.setattr(run, 'snapshot', lambda _: source)
    class CapturedConfigure(Exception):
        pass
    def command(name, argv, **kwargs):
        if name == 'prepare-inputs':
            inputs = runner.output / 'inputs'
            inputs.mkdir()
            (inputs / 'manifest.json').write_text(json.dumps({'cases': {name: {'header': [0x52464331, mode]} for name, mode in run.CASES.items()}}))
        if name == 'configure':
            assert '-DRF_REQUIRE_CAMPAIGN_031_060=ON' in argv
            assert source in argv
            raise CapturedConfigure()
        return 'test compiler'
    monkeypatch.setattr(runner, 'command', command)
    with pytest.raises(CapturedConfigure):
        run.execute(runner, 'normal', sys.executable)


@pytest.mark.parametrize('message', [
    'LeakSanitizer has encountered a fatal error',
    'AddressSanitizer:DEADLYSIGNAL',
    'UndefinedBehaviorSanitizer: undefined-behavior',
    'runtime error: overflow',
    'WARNING: unexpected runtime message',
])
def test_native_success_and_expected_rejection_never_accept_runtime_output(message):
    with pytest.raises(RuntimeError, match='Unexpected native output'):
        run.require_native_log(message)
    with pytest.raises(RuntimeError, match='Unexpected native output'):
        run.require_native_log('truncated input\n' + message, 'truncated input')
    with pytest.raises(RuntimeError, match='Unexpected native output'):
        run.require_native_log(message, 'truncated input')


def test_native_rejection_requires_exact_driver_reason():
    run.require_native_log('truncated input\n', 'truncated input')
    run.require_native_log('')
    with pytest.raises(RuntimeError):
        run.require_native_log('unrelated failure', 'truncated input')


def capture_fixture():
    import numpy as np
    return np.zeros((2, 32, 32, 4), np.uint8), np.zeros((2, 16))


@pytest.mark.parametrize('method,kwargs', [
    ('dense', {'glb': None, 'ply': None}),
    ('surface', {}),
    ('surface', {'glb': 'unexpected.glb', 'ply': None}),
    ('surface', {'glb': None, 'ply': None, 'new_setting': True}),
])
def test_changed_capture_method_or_keywords_rejected(tmp_path, method, kwargs):
    from tools.qa.native_cabi_replay.prepare import captured_arrays
    depth, cameras = capture_fixture()
    with pytest.raises(RuntimeError, match='invocation changed'):
        captured_arrays(1, (method, (depth, cameras, 6, 80, 64, 3), kwargs), tmp_path)


def test_actual_surface_and_texture_capture_contracts(tmp_path):
    from tools.qa.native_cabi_replay.prepare import captured_arrays
    depth, cameras = capture_fixture()
    assert captured_arrays(1, ('surface', (depth, cameras, 6, 80, 64, 3), {'glb': None, 'ply': None}), tmp_path)[0] == 'surface'
    kwargs = {'textured_glb': tmp_path / 'preparation-only/middlebury-photographic-texture.glb',
              'atlas_png': tmp_path / 'preparation-only/middlebury-photographic-atlas.png'}
    assert captured_arrays(2, ('textured_surface', (depth, depth, cameras, 6, 80, 64, 3), kwargs), tmp_path)[0] == 'textured_surface'
    with pytest.raises(RuntimeError, match='invocation changed'):
        captured_arrays(2, ('textured_surface', (depth, depth, cameras, 6, 80, 64, 3), {}), tmp_path)


@pytest.mark.parametrize('fault', ['camera-count', 'camera-width', 'photo-count', 'channels', 'dtype', 'depth-size'])
def test_fixture_shape_relationships_checked_before_serialization(fault):
    import numpy as np
    from tools.qa.native_cabi_replay.prepare import validate_arrays
    depth, cameras = capture_fixture()
    photos = depth.copy()
    if fault == 'camera-count': cameras = cameras[:1]
    if fault == 'camera-width': cameras = cameras[:, :15]
    if fault == 'photo-count': photos = photos[:1]
    if fault == 'channels': depth = depth[..., :3]
    if fault == 'dtype': photos = photos.astype(np.float32)
    if fault == 'depth-size': depth = depth[:, :16]
    with pytest.raises(RuntimeError):
        validate_arrays(2, [depth, photos, cameras])


@pytest.mark.parametrize('number', ['NaN', 'Infinity', '-Infinity', '1e999'])
def test_verifier_rejects_nonfinite_json(number):
    from tools.qa.native_cabi_replay.verify import strict_json
    with pytest.raises(RuntimeError, match='Nonfinite'):
        strict_json('{"value": ' + number + '}')


def test_calibrated_preparation_uses_real_distorted_observed_pixels():
    import numpy as np
    from tools.qa.native_cabi_replay.prepare import calibrated_arrays, validate_arrays
    rows, observed, domains = calibrated_arrays(Path(__file__).resolve().parents[1])
    validate_arrays(3, [rows, observed, domains])
    assert np.any(domains[:, 4:9] != 0)
    assert np.all(domains[0, :9] != domains[1, :9])
    for c, k in enumerate(domains):
        for row, pixels in zip(rows, observed):
            x, y = row[c*2:c*2+2]
            r2 = x*x + y*y
            radial = 1 + r2 * (k[4] + r2 * (k[5] + r2*k[8]))
            px = k[0]*(x*radial + 2*k[6]*x*y + k[7]*(r2+2*x*x)) + k[2]
            py = k[1]*(y*radial + k[6]*(r2+2*y*y) + 2*k[7]*x*y) + k[3]
            assert np.hypot(px-pixels[c*2], py-pixels[c*2+1]) < .01
        virtual = rows[:, c*2:c*2+2] * k[:2] + k[2:4]
        assert np.max(np.abs(virtual-observed[:, c*2:c*2+2])) > .01


@pytest.mark.parametrize('fault', ['rows', 'observed', 'domains', 'nonfinite', 'outside', 'fractional'])
def test_calibrated_preparation_rejects_bad_buffers(fault):
    import numpy as np
    from tools.qa.native_cabi_replay.prepare import calibrated_arrays, validate_arrays
    rows, observed, domains = calibrated_arrays(Path(__file__).resolve().parents[1])
    if fault == 'rows': rows = rows[:-1]
    if fault == 'observed': observed = observed[:, :3]
    if fault == 'domains': domains = domains[:, :10]
    if fault == 'nonfinite': observed[0, 0] = np.nan
    if fault == 'outside': observed[0, 0] = domains[0, 9]
    if fault == 'fractional': domains[0, 9] += .5
    with pytest.raises(RuntimeError):
        validate_arrays(3, [rows, observed, domains])


@pytest.mark.parametrize('fault', ['missing', 'extra', 'replaced'])
def test_replay_workload_coverage_is_exact(fault):
    manifest = {'cases': {name: {'header': [0x52464331, mode]} for name, mode in run.CASES.items()}}
    if fault == 'missing': del manifest['cases']['calibrated-planar']
    if fault == 'extra': manifest['cases']['unexpected'] = {'header': [0x52464331, 3]}
    if fault == 'replaced': manifest['cases']['calibrated-planar']['header'][1] = 0
    with pytest.raises(RuntimeError, match='workload'):
        run.validate_manifest(manifest)


def calibrated_outputs(tmp_path):
    from tools.qa.native_cabi_replay.verify import CALIBRATED_ERRORS
    from tools.qa.native_cabi_replay.prepare import calibrated_arrays
    rows, _, _ = calibrated_arrays(Path(__file__).resolve().parents[1])
    first = {}
    for i, row in enumerate(rows): first.setdefault(tuple(row[:4]), i)
    assert len(rows) == 194 and len(first) == 162
    result = {'representation': 'sparse_points', 'initializerModel': 'HOMOGRAPHY', 'scale': 'arbitrary',
              'registeredCameras': 2, 'inputMatches': 194, 'pointCount': 159, 'homographyInliers': 159,
              'modelInliers': 159, 'inliers': 159,
              'points': [[0, 0, 1, 120, 130, 140, 0, 1, i] for i in list(first.values())[:159]]}
    for i in range(2):
        directory = tmp_path / str(i)
        directory.mkdir()
        for name in ('success', 'recovery', 'undistorted-equivalent'):
            (directory / (name + '.json')).write_text(json.dumps(result))
        for name in CALIBRATED_ERRORS:
            (directory / (name + '.json')).write_text(json.dumps({'errorCode': 'INVALID_INPUT', 'error': 'deliberate'}))
    return result


def test_calibrated_verifier_valid_contract(tmp_path):
    from tools.qa.native_cabi_replay.verify import verify
    expected = calibrated_outputs(tmp_path)
    assert verify('calibrated-planar', tmp_path) == expected


@pytest.mark.parametrize('fault', ['missing-call', 'extra-call', 'missing-repeat', 'repeat-error', 'wrong-code',
                                   'partial-error', 'equivalence', 'wrong-model', 'lost-support', 'provenance',
                                   'conflicting-model', 'bool-index', 'success-error-code', 'later-duplicate',
                                   'historical-inflation', 'support-mismatch', 'three-cameras', 'fewer-points', 'colors'])
def test_calibrated_verifier_fails_closed(tmp_path, fault):
    from tools.qa.native_cabi_replay.verify import verify
    result = calibrated_outputs(tmp_path)
    if fault == 'missing-call': (tmp_path / '0/null-domains.json').unlink()
    elif fault == 'extra-call': (tmp_path / '0/extra.json').write_text('{}')
    elif fault == 'missing-repeat': (tmp_path / '1').rename(tmp_path / '2')
    elif fault in ('repeat-error', 'wrong-code', 'partial-error'):
        value = {'errorCode': 'INVALID_INPUT', 'error': 'different' if fault == 'repeat-error' else 'deliberate'}
        if fault == 'wrong-code': value['errorCode'] = 'NATIVE_FAILURE'
        if fault == 'partial-error': value['points'] = []
        (tmp_path / '1/null-rows.json').write_text(json.dumps(value))
    elif fault == 'equivalence': (tmp_path / '0/undistorted-equivalent.json').write_text('{}')
    else:
        if fault == 'later-duplicate':
            from tools.qa.native_cabi_replay.prepare import calibrated_arrays
            rows, _, _ = calibrated_arrays(Path(__file__).resolve().parents[1])
            first = {}
            for i, row in enumerate(rows):
                pair = tuple(row[:4])
                if pair in first:
                    result['points'][0][8] = i
                    break
                first[pair] = i
        if fault == 'historical-inflation':
            result['points'] = [[0, 0, 1, 120, 130, 140, 0, 1, i] for i in range(191)]
            for key in ('pointCount', 'homographyInliers', 'modelInliers', 'inliers'): result[key] = 191
        if fault == 'support-mismatch': result['homographyInliers'] = 160
        if fault == 'three-cameras': result['registeredCameras'] = 3
        if fault == 'colors': result['points'][0][3] = 1
        if fault == 'fewer-points':
            result['points'].pop()
            for key in ('pointCount', 'homographyInliers', 'modelInliers', 'inliers'): result[key] = 158
        if fault == 'wrong-model': result['initializerModel'] = 'ESSENTIAL'
        if fault == 'conflicting-model': result['epipolarInliers'] = 170
        if fault == 'bool-index': result['points'][0][8] = False
        if fault == 'success-error-code': result['errorCode'] = 'INVALID_INPUT'
        if fault == 'lost-support': result['pointCount'] = 0
        if fault == 'provenance': result['points'][0][8] = result['points'][1][8]
        for i in range(2):
            for name in ('success', 'recovery', 'undistorted-equivalent'):
                (tmp_path / str(i) / (name + '.json')).write_text(json.dumps(result))
    with pytest.raises(RuntimeError):
        verify('calibrated-planar', tmp_path)


def test_calibrated_verifier_reads_selected_snapshot_and_rejects_changed_distinct_count(tmp_path):
    import re
    from tools.qa.native_cabi_replay.verify import verify
    output = tmp_path / 'output'
    output.mkdir()
    expected = calibrated_outputs(output)
    source = tmp_path / 'snapshot'
    header = source / 'reconstruction/tests/calibrated_initializer_fixtures.hpp'
    header.parent.mkdir(parents=True)
    original = (Path(__file__).resolve().parents[1] / header.relative_to(source)).read_text()
    header.write_text(original)
    assert verify('calibrated-planar', output, source=source) == expected
    # Preserve the raw buffer shape but collapse geometry. Reading the working
    # tree instead of the selected snapshot would silently pass this mutation.
    changed = re.sub(r'(inline constexpr std::array<double,776> anchor\{\{).*?(\}\};)',
                     lambda m: m[1] + ','.join(['0'] * 776) + m[2], original, flags=re.S)
    assert changed != original
    header.write_text(changed)
    with pytest.raises(RuntimeError, match='distinct-pair identity'):
        verify('calibrated-planar', output, source=source)
