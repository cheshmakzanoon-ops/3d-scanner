"""Immediate failure reporting never replaces full-suite execution or gate status."""
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / 'tools/qa/native_regression'
PLUGIN = 'tools.qa.native_regression.pytest_diagnostics'
MARKER = '=== first pytest failure (immediate diagnostic; suite continues) ==='


def plugin():
    spec = importlib.util.spec_from_file_location('rf_immediate_failure_test', QA / 'pytest_diagnostics.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def child_args(tmp_path, source):
    test = tmp_path / 'test_synthetic_failures.py'
    test.write_text(source)
    return [sys.executable, '-m', 'pytest', '-c', '/dev/null', str(test), '-q',
            '--tb=no', '-p', PLUGIN]


def child_env():
    env = dict(os.environ)
    # Isolate only pytest options/plugins for this synthetic observer control;
    # retain sanitizer settings and preloads exactly as inherited.
    env.pop('PYTEST_ADDOPTS', None)
    env['PYTEST_DISABLE_PLUGIN_AUTOLOAD'] = '1'
    env['PYTHONPATH'] = str(ROOT) + os.pathsep + env.get('PYTHONPATH', '')
    return env


@pytest.mark.parametrize('phase,source,detail', [
    ('setup', 'import pytest\n@pytest.fixture\ndef bad():\n    raise RuntimeError("SETUP_DETAIL")\ndef test_failure(bad):\n    pass\n', 'SETUP_DETAIL'),
    ('call', 'def test_failure():\n    assert False, "CALL_DETAIL"\n', 'CALL_DETAIL'),
    ('teardown', 'import pytest\n@pytest.fixture\ndef bad():\n    yield\n    raise RuntimeError("TEARDOWN_DETAIL")\ndef test_failure(bad):\n    pass\n', 'TEARDOWN_DETAIL'),
])
def test_real_pytest_first_failure_in_each_phase(tmp_path, phase, source, detail):
    result = subprocess.run(child_args(tmp_path, source), cwd=ROOT, env=child_env(),
                            capture_output=True, text=True, timeout=15)
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stdout.count(MARKER) == 1
    assert '[' + phase + ']' in result.stdout and detail in result.stdout
    assert 'INTERNALERROR' not in result.stdout


def test_multiple_failures_do_not_stop_remaining_tests(tmp_path):
    seen = tmp_path / 'last-ran'
    source = 'def test_a():\n    assert False, "FIRST_DETAIL"\ndef test_b():\n    assert False, "SECOND_DETAIL"\ndef test_c():\n    from pathlib import Path\n    Path(' + repr(str(seen)) + ').write_text("ran")\n'
    result = subprocess.run(child_args(tmp_path, source), cwd=ROOT, env=child_env(),
                            capture_output=True, text=True, timeout=15)
    assert result.returncode == 1
    assert result.stdout.count(MARKER) == 1
    diagnostic = result.stdout.split(MARKER, 1)[1].split('=== end first pytest failure ===', 1)[0]
    assert 'FIRST_DETAIL' in diagnostic and 'SECOND_DETAIL' not in diagnostic
    assert seen.read_text() == 'ran' and '2 failed, 1 passed' in result.stdout


def test_passing_suite_has_no_diagnostic_and_executes_all_tests(tmp_path):
    seen = tmp_path / 'passes'
    source = 'from pathlib import Path\ndef test_a():\n    Path(' + repr(str(seen)) + ').write_text("a")\ndef test_b():\n    p = Path(' + repr(str(seen)) + ')\n    p.write_text(p.read_text() + "b")\n'
    result = subprocess.run(child_args(tmp_path, source), cwd=ROOT, env=child_env(),
                            capture_output=True, text=True, timeout=15)
    assert result.returncode == 0, result.stdout + result.stderr
    assert MARKER not in result.stdout and '2 passed' in result.stdout
    assert seen.read_text() == 'ab'


def test_first_failure_is_flushed_before_later_gate_timeout(tmp_path):
    sys.path.insert(0, str(QA))
    from run import command
    result = command(child_args(tmp_path, 'def test_a():\n    assert False, "BEFORE_TIMEOUT"\ndef test_b():\n    import time\n    time.sleep(20)\n'),
                     ROOT, tmp_path, 'timeout-after-failure', child_env(), timeout=3)
    assert result['result'] == 'FAIL' and result['reason'] == 'TIMEOUT'
    log = Path(result['log']).read_text()
    assert log.count(MARKER) == 1 and 'BEFORE_TIMEOUT' in log
    assert 'end first pytest failure' in log


def test_diagnostic_copy_is_byte_bounded_and_original_unchanged(monkeypatch):
    mod = plugin()
    sink = io.StringIO()
    monkeypatch.setattr(sys, '__stdout__', sink)
    original = 'é' * mod.MAX_REPORT_BYTES
    report = SimpleNamespace(failed=True, nodeid='node', when='call', longreprtext=original)
    mod.pytest_runtest_logreport(report)
    assert len(sink.getvalue().encode('utf-8')) <= mod.MAX_REPORT_BYTES
    assert 'diagnostic truncated' in sink.getvalue()
    assert report.longreprtext == original and report.failed is True
    first = sink.getvalue()
    mod.pytest_runtest_logreport(report)
    assert sink.getvalue() == first
    mod.pytest_configure(None)
    mod.pytest_runtest_logreport(report)
    assert sink.getvalue() == first * 2


@pytest.mark.parametrize('operation', ['write', 'flush'])
def test_logging_failure_does_not_change_failed_report(monkeypatch, operation):
    mod = plugin()
    class Broken:
        def write(self, text):
            if operation == 'write':
                raise OSError('closed output')
        def flush(self):
            if operation == 'flush':
                raise OSError('closed output')
    monkeypatch.setattr(sys, '__stdout__', Broken())
    report = SimpleNamespace(failed=True, nodeid='node', when='call', longreprtext='original failure')
    mod.pytest_runtest_logreport(report)
    assert report.failed and report.longreprtext == 'original failure'


def test_runner_loads_observer_without_fail_fast_or_limit_changes():
    source = (QA / 'run.py').read_text()
    assert "'-p', '" + PLUGIN + "'" in source
    assert "'--maxfail" not in source and "'-x'" not in source
    assert "parser.add_argument('--timeout', type=int, default=600)" in source
    assert 'log.stat().st_size > 32 * 1024**2' in source
    assert "ASAN_OPTIONS='halt_on_error=1:detect_leaks=1'" in source
