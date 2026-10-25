"""Synthetic controls for optional bounded timing, not sanitizer qualification."""
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[1]
PLUGIN = 'tools.qa.native_regression.pytest_progress'


def plugin():
    spec = importlib.util.spec_from_file_location('progress_control', ROOT / 'tools/qa/native_regression/pytest_progress.py')
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def records(text):
    return [json.loads(line) for line in re.findall(r'RF_PYTEST_PROGRESS (\{[^\n]*\})', text)]


def child(tmp_path, source):
    (tmp_path / 'tests').mkdir()
    test = tmp_path / 'tests/test_synthetic_progress.py'
    test.write_text(source)
    env = dict(os.environ, PYTEST_DISABLE_PLUGIN_AUTOLOAD='1')
    env.pop('PYTEST_ADDOPTS', None)
    env['PYTHONPATH'] = str(ROOT) + os.pathsep + env.get('PYTHONPATH', '')
    return ([sys.executable, '-m', 'pytest', '-c', '/dev/null', str(test), '-q',
             '--rootdir=' + str(tmp_path), '--confcutdir=' + str(tmp_path),
             '--tb=no', '-p', PLUGIN], env)


@pytest.mark.parametrize('nodeid', [None, 42, '/private/test_x.py::test_x',
    'tests/../secret/test_x.py::test_x', 'C:\\secret\\test_x.py::test_x',
    'tests/test_x.py::test_x\nSECRET', 'tests/test_x.py::/private/name',
    'tests/test_x.py::test_' + 'x' * 9000, 'tests/test_x.py::test_é'],
    ids=['none', 'integer', 'absolute', 'traversal', 'windows', 'newline',
         'absolute-member', 'oversized', 'unicode'])
def test_unsafe_ids_are_redacted(nodeid):
    assert plugin().safe_nodeid(nodeid) == '<redacted>'


def test_parameters_never_leave_safe_identity():
    mod = plugin()
    assert mod.safe_nodeid('tests/test_x.py::Class::test_x[/private/SECRET\n]') == 'tests/test_x.py::Class::test_x'
    assert mod.safe_nodeid('tests/sub/test_x.py::test_x') == 'tests/sub/test_x.py::test_x'


@pytest.mark.parametrize('budget', ['bytes', 'records'])
def test_budget_emits_one_incomplete_marker_then_stops(monkeypatch, budget):
    mod = plugin()
    monkeypatch.setattr(mod, 'MAX_BYTES' if budget == 'bytes' else 'MAX_RECORDS', 400 if budget == 'bytes' else 2)
    sink = io.StringIO()
    monkeypatch.setattr(sys, '__stdout__', sink)
    observer = mod.Progress()
    for _ in range(1000):
        identity, started = observer.begin('tests/test_x.py::test_x[SECRET]', 'call')
        observer.end(identity, started)
    events = records(sink.getvalue())
    assert events[-1] == dict(event='limit', incomplete=True)
    assert sum(row['event'] == 'limit' for row in events) == 1
    assert len(sink.getvalue().encode()) <= mod.MAX_BYTES
    assert len(events) <= mod.MAX_RECORDS + 1
    assert 'SECRET' not in sink.getvalue()


def test_each_record_flushes_and_timings_pair(monkeypatch):
    mod = plugin()
    class Sink(io.StringIO):
        flushes = 0
        def flush(self):
            self.flushes += 1
    sink = Sink()
    monkeypatch.setattr(sys, '__stdout__', sink)
    observer = mod.Progress()
    identity, started = observer.begin('tests/test_x.py::test_x', 'call')
    assert sink.flushes == 1
    observer.end(identity, started)
    assert sink.flushes == 2
    start, end = records(sink.getvalue())
    assert start['phase_id'] == end['phase_id']
    assert end['duration_s'] >= 0 and end['elapsed_s'] >= start['elapsed_s']


@pytest.mark.parametrize('operation', ['write', 'flush'])
def test_broken_output_is_nonfatal_and_disables_observer(monkeypatch, operation):
    mod = plugin()
    class Broken:
        def write(self, text):
            if operation == 'write':
                raise OSError('private path')
        def flush(self):
            if operation == 'flush':
                raise ValueError('closed stream')
    monkeypatch.setattr(sys, '__stdout__', Broken())
    observer = mod.Progress()
    identity, started = observer.begin('tests/test_x.py::test_x', 'call')
    observer.end(identity, started)
    assert observer.stopped


@pytest.mark.parametrize('phase,source', [
    ('setup', 'import pytest\n@pytest.fixture\ndef bad():\n    assert False\ndef test_a(bad):\n    pass\n'),
    ('call', 'def test_a():\n    assert False\n'),
    ('teardown', 'import pytest\n@pytest.fixture\ndef bad():\n    yield\n    assert False\ndef test_a(bad):\n    pass\n'),
])
def test_failed_phases_preserve_failure_and_continue(tmp_path, phase, source):
    argv, env = child(tmp_path, source + '\ndef test_z():\n    pass\n')
    result = subprocess.run(argv, cwd=ROOT, env=env, text=True, capture_output=True, timeout=20)
    assert result.returncode == 1 and 'INTERNALERROR' not in result.stdout
    events = records(result.stdout)
    assert any(row['phase'] == phase and row['event'] == 'end' for row in events)
    assert sum(row['phase'] == 'teardown' and row['event'] == 'end' for row in events) == 2
    assert str(tmp_path) not in ''.join(json.dumps(row) for row in events)


def test_passing_skipped_and_parameterized_tests_keep_status(tmp_path):
    argv, env = child(tmp_path, 'import pytest\n@pytest.mark.parametrize("value", [1, 2], ids=["/private/SECRET", "other"])\ndef test_a(value):\n    pass\n@pytest.mark.skip(reason="synthetic")\ndef test_z():\n    pass\n')
    result = subprocess.run(argv, cwd=ROOT, env=env, text=True, capture_output=True, timeout=20)
    assert result.returncode == 0 and '2 passed, 1 skipped' in result.stdout
    events = records(result.stdout)
    assert len(events) == 16
    assert events[0]['nodeid'] == 'tests/test_synthetic_progress.py::test_a'
    assert 'SECRET' not in ''.join(json.dumps(row) for row in events)
    starts = [row['phase_id'] for row in events if row['event'] == 'start']
    assert len(set(starts)) == 8


def test_sigkill_preserves_started_phase_without_fabricating_end(tmp_path):
    sys.path.insert(0, str(ROOT / 'tools/qa/native_regression'))
    from run import command
    argv, env = child(tmp_path, 'def test_a():\n    import time\n    time.sleep(30)\n')
    result = command(argv, ROOT, tmp_path, 'pytest', env, timeout=3)
    assert result['result'] == 'FAIL' and result['reason'] == 'TIMEOUT'
    assert result['exit_code'] == -9
    events = records(Path(result['log']).read_text())
    assert [(row['phase'], row['event']) for row in events] == [('setup', 'start'), ('setup', 'end'), ('call', 'start')]


def test_existing_failure_upload_and_gate_limits_unchanged():
    runner = (ROOT / 'tools/qa/native_regression/run.py').read_text()
    assert "'-p', '" + PLUGIN + "'" in runner
    assert "parser.add_argument('--timeout', type=int, default=600)" in runner
    assert "ASAN_OPTIONS='halt_on_error=1:detect_leaks=1'" in runner
    assert 'log.stat().st_size > 32 * 1024**2' in runner
    assert "'--maxfail" not in runner and "'-x'" not in runner
    workflow = (ROOT / '.github/workflows/verify.yml').read_text()
    failure = workflow.split('if: failure() || cancelled()', 1)[1].split('  native:', 1)[0]
    assert '${{ runner.temp }}/rf-ci/native/runner/**/*.log' in failure


@pytest.mark.parametrize('fails', [False, True])
def test_progress_does_not_replace_or_change_accounting(tmp_path, fails):
    argv, env = child(tmp_path, 'def test_a():\n    assert ' + str(not fails) + '\n')
    receipt = tmp_path / 'receipt.json'
    xml = tmp_path / 'pytest.xml'
    env.update(RF_PYTEST_SOURCE_SHA='a' * 40, RF_NATIVE_CONFIG='gcc-release')
    env.pop('RF_PYTEST_REPOSITORY', None)
    env.pop('RF_PYTEST_RUN_ID', None)
    argv += ['-p', 'tools.qa.native_regression.pytest_accounting',
             '-p', 'tools.qa.native_regression.pytest_diagnostics',
             '--rf-pytest-accounting=' + str(receipt), '--junitxml=' + str(xml)]
    result = subprocess.run(argv, cwd=ROOT, env=env, text=True, capture_output=True, timeout=20)
    assert result.returncode == int(fails), result.stdout + result.stderr
    data = json.loads(receipt.read_text())
    assert data['completed'] is True and data['session_exitstatus'] == int(fails)
    assert [row['outcome'] for row in data['events']] == ['passed', 'failed' if fails else 'passed', 'passed']
    assert len(records(result.stdout)) == 6 and xml.is_file()


@pytest.mark.parametrize('stage', ['init', 'begin', 'end'])
def test_clock_failure_disables_only_optional_diagnostics(monkeypatch, stage):
    mod = plugin()
    sink = io.StringIO()
    monkeypatch.setattr(sys, '__stdout__', sink)
    calls = 0
    fail_at = {'init': 1, 'begin': 2, 'end': 3}[stage]
    def clock():
        nonlocal calls
        calls += 1
        if calls == fail_at:
            raise OSError('synthetic clock failure')
        return float(calls)
    # Inject before construction: the observer owns this callable afterward.
    monkeypatch.setattr(mod, 'time', SimpleNamespace(monotonic=clock))
    observer = mod.Progress()
    identity, started = observer.begin('tests/test_x.py::test_x', 'call')
    observer.end(identity, started)
    assert observer.stopped


def test_configured_sink_survives_test_level_stdout_replacement(monkeypatch):
    mod = plugin()
    sink = io.StringIO()
    monkeypatch.setattr(mod, 'sys', SimpleNamespace(__stdout__=sink))
    observer = mod.Progress()
    monkeypatch.setattr(mod, 'sys', SimpleNamespace(__stdout__=None))
    identity, started = observer.begin('tests/test_x.py::test_x', 'call')
    observer.end(identity, started)
    assert len(records(sink.getvalue())) == 2 and not observer.stopped


def test_real_shared_clock_patch_keeps_later_progress_under_default_capture():
    # This real regression patches the shared time module with a two-value
    # iterator. It must not exhaust the observer clock or hide later tests.
    env = dict(os.environ, PYTEST_DISABLE_PLUGIN_AUTOLOAD='1')
    env.pop('PYTEST_ADDOPTS', None)
    result = subprocess.run([
        sys.executable, '-m', 'pytest', '-q', '-p', PLUGIN,
        'tests/test_eight_view_capability.py::test_snapshot_elapsed_predicate_reports_actual_allocated_budget',
        'tests/test_native_pytest_progress.py::test_parameters_never_leave_safe_identity',
    ], cwd=ROOT, env=env, text=True, capture_output=True, timeout=30)
    assert result.returncode == 0 and '2 passed' in result.stdout, result.stdout + result.stderr
    events = records(result.stdout)
    assert len(events) == 12
    assert [(row['phase'], row['event']) for row in events] == [
        (phase, event) for _ in range(2)
        for phase in ('setup', 'call', 'teardown') for event in ('start', 'end')]
    assert events[-1]['nodeid'].endswith('::test_parameters_never_leave_safe_identity')
    assert all(row.get('duration_s', 0) >= 0 for row in events)
