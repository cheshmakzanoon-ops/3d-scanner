"""Behavioral failure-path tests for the QA gate, independent of native geometry."""
import json
import os
import sys
from pathlib import Path
import pytest

QA = Path(__file__).resolve().parents[1] / 'tools/qa/native_regression'
sys.path.insert(0, str(QA))
from numerical import compare, validate
from run import command


def scene():
    return dict(seed=1, noise=0., outliers=0, point_count=180, rotation_error=0.,
                median_position_error=0., points=[[0., 1., 2.]], rotation=[1., 0., 0.], translation=[-1., 0., 0.])


def test_failing_subprocess_preserves_exit_and_diagnostics(tmp_path):
    result = command([sys.executable, '-c', "import sys; print('repro-seed=81'); sys.exit(17)"], tmp_path, tmp_path, 'failure')
    assert result['exit_code'] == 17 and result['result'] == 'FAIL'
    assert 'repro-seed=81' in Path(result['log']).read_text()
    assert len(result['log_sha256']) == 64


def test_deliberately_bad_numerical_fixture_is_nonzero(tmp_path):
    bad = scene()
    bad['rotation_error'] = .1
    fixture = tmp_path / 'bad.json'
    fixture.write_text(json.dumps(dict(scenes=[bad])))
    result = command([sys.executable, QA / 'numerical.py', '--verify', fixture,
                      '--output', tmp_path / 'output.json'], tmp_path, tmp_path, 'numerical-failure')
    assert result['exit_code'] != 0 and result['result'] == 'FAIL'
    assert 'Rotation contract failed' in Path(result['log']).read_text()


@pytest.mark.parametrize('field', ['rotation_error', 'median_position_error', 'points'])
def test_nonfinite_receipts_cannot_pass(field):
    bad = scene()
    bad[field] = [[float('nan')]] if field == 'points' else float('inf')
    with pytest.raises(ValueError, match='Nonfinite'):
        validate(bad)


def test_parity_retains_scene_identity_and_tolerances():
    a, b = scene(), scene()
    b['points'][0][0] = 1e-8
    compare(dict(scenes=[a]), dict(scenes=[b]))
    b['points'][0][0] = .01
    with pytest.raises(ValueError, match='seed=1'):
        compare(dict(scenes=[a]), dict(scenes=[b]))


def test_timeout_preserves_failure_record(tmp_path):
    result = command([sys.executable, '-c', 'import time; time.sleep(10)'], tmp_path, tmp_path, 'timeout', timeout=.1)
    assert result['result'] == 'FAIL' and result['reason'] == 'TIMEOUT'
