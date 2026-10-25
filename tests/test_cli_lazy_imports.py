"""CLI parser/import order needs no optional runtime; execution still requires it.

The six parser cases inspect SystemExit in-process, not OS-process shutdown.
The independent runbook smoke retains real process exit checks. No loader or
sanitizer environment is filtered. Runtime dependency controls remain children,
never alternate execution backends.
"""
import builtins
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ('capture_audit.py', 'check_apk.py', 'benchmark_multiview_coverage.py')
DRIVER = r'''
import builtins,json,pathlib,runpy,sys
script=pathlib.Path(sys.argv[1])
blocked=set(json.loads(sys.argv[2]))
sys.argv=[str(script),*sys.argv[3:]]
sys.path.insert(0,str(script.parent))
original=builtins.__import__
def guarded(name,*args,**kwargs):
    if name.split('.')[0] in blocked:
        raise ImportError('OPTIONAL_RUNTIME_IMPORT_BLOCKED:'+name)
    return original(name,*args,**kwargs)
builtins.__import__=guarded
runpy.run_path(str(script),run_name='__main__')
'''


def blocked_run(script, arguments, blocked):
    return subprocess.run([sys.executable, '-c', DRIVER, str(ROOT/'tools'/script),
                           json.dumps(blocked), *arguments],
                          capture_output=True, text=True, timeout=30)


def parse_in_process(script, arguments, blocked=()):
    """Inspect actual parser exit intent, restoring process-global test state."""
    original_import = builtins.__import__
    original_argv, original_path = sys.argv, sys.path
    original_meta_path, original_path_hooks = sys.meta_path, sys.path_hooks
    missing = object()
    original_main = sys.modules.get('__main__', missing)
    out, err = io.StringIO(), io.StringIO()
    path = ROOT/'tools'/script
    def guarded(name, *args, **kwargs):
        if name.split('.')[0] in blocked:
            raise ImportError('OPTIONAL_RUNTIME_IMPORT_BLOCKED:' + name)
        return original_import(name, *args, **kwargs)
    try:
        sys.argv = [str(path), *arguments]
        sys.path = [str(path.parent), *original_path]
        sys.meta_path, sys.path_hooks = list(original_meta_path), list(original_path_hooks)
        builtins.__import__ = guarded
        with redirect_stdout(out), redirect_stderr(err):
            try:
                runpy.run_path(str(path), run_name='__main__')
            except SystemExit as exited:
                code = exited.code
            else:
                raise AssertionError('Parser must terminate through SystemExit')
        return code, out.getvalue(), err.getvalue()
    finally:
        builtins.__import__ = original_import
        sys.argv, sys.path = original_argv, original_path
        sys.meta_path, sys.path_hooks = original_meta_path, original_path_hooks
        if original_main is missing:
            sys.modules.pop('__main__', None)
        else:
            sys.modules['__main__'] = original_main


@pytest.mark.parametrize('script', SCRIPTS)
@pytest.mark.parametrize('arguments,code', [(['--help'], 0), (['--unrecognized-runtime-option'], 2)])
def test_cli_parse_exits_before_optional_imports(script, arguments, code):
    environment = dict(os.environ)
    result = parse_in_process(script, arguments, ['cv2', 'numpy', 'native_api', 'release'])
    ordinary = parse_in_process(script, arguments)
    assert result == ordinary
    exit_code, stdout, stderr = result
    assert exit_code == code
    assert 'usage:' in (stdout if code == 0 else stderr).lower()
    assert 'OPTIONAL_RUNTIME_IMPORT_BLOCKED' not in stderr
    if code == 0:
        assert stderr == ''
    else:
        assert stdout == ''
    assert dict(os.environ) == environment


@pytest.mark.parametrize('outcome', ['help', 'usage', 'unexpected', 'returns'])
def test_parser_probe_restores_globals_and_preserves_unexpected_errors(monkeypatch, outcome):
    original = (sys.argv, sys.path, sys.meta_path, sys.path_hooks,
                builtins.__import__, sys.stdout, sys.stderr, sys.modules.get('__main__'))
    contents = [list(value) for value in original[:4]]
    environment = dict(os.environ)
    error = RuntimeError('unexpected parser failure must propagate')
    def probe(path, run_name):
        assert path == str(ROOT/'tools/capture_audit.py') and run_name == '__main__'
        assert sys.argv == [path, '--help']
        assert sys.path[0] == str(ROOT/'tools')
        sys.argv.append('mutation')
        sys.path.append('mutation')
        sys.meta_path.clear()
        sys.path_hooks.clear()
        sys.modules['__main__'] = object()
        print('captured stdout')
        print('captured stderr', file=sys.stderr)
        if outcome == 'unexpected':
            raise error
        if outcome != 'returns':
            raise SystemExit(0 if outcome == 'help' else 2)
    monkeypatch.setattr(runpy, 'run_path', probe)
    if outcome == 'unexpected':
        with pytest.raises(RuntimeError) as caught:
            parse_in_process('capture_audit.py', ['--help'])
        assert caught.value is error
    elif outcome == 'returns':
        with pytest.raises(AssertionError, match='must terminate through SystemExit'):
            parse_in_process('capture_audit.py', ['--help'])
    else:
        assert parse_in_process('capture_audit.py', ['--help']) == (
            0 if outcome == 'help' else 2, 'captured stdout\n', 'captured stderr\n')
    restored = (sys.argv, sys.path, sys.meta_path, sys.path_hooks,
                builtins.__import__, sys.stdout, sys.stderr, sys.modules.get('__main__'))
    assert all(actual is expected for actual, expected in zip(restored, original))
    assert [list(value) for value in restored[:4]] == contents
    assert dict(os.environ) == environment


def test_parser_probe_does_not_swallow_forbidden_runtime_import(monkeypatch):
    original = builtins.__import__
    def import_runtime(*args, **kwargs):
        __import__('native_api')
    monkeypatch.setattr(runpy, 'run_path', import_runtime)
    with pytest.raises(ImportError, match='OPTIONAL_RUNTIME_IMPORT_BLOCKED:native_api'):
        parse_in_process('capture_audit.py', ['--help'], ['native_api'])
    assert builtins.__import__ is original


@pytest.mark.parametrize('script,arguments,dependency', [
    ('capture_audit.py', ['missing-capture.zip'], 'cv2'),
    ('check_apk.py', ['missing.apk'], 'release'),
    ('benchmark_multiview_coverage.py', [], 'native_api'),
])
def test_valid_cli_still_requires_original_runtime(script, arguments, dependency):
    result = blocked_run(script, arguments, [dependency])
    assert result.returncode == 1
    assert result.stdout == ''
    assert 'ImportError: OPTIONAL_RUNTIME_IMPORT_BLOCKED:' + dependency in result.stderr


@pytest.mark.parametrize('script,call,dependency', [
    ('capture_audit.py', "module['_json'](b'1.5')", 'numpy'),
    ('capture_audit.py', "module['_matching_count'](None,None)", 'cv2'),
    ('benchmark_multiview_coverage.py', "module['fixture']()", 'cv2'),
    ('benchmark_multiview_coverage.py', "module['evaluate']([],[])", 'numpy'),
])
def test_imported_function_still_requires_runtime(script, call, dependency):
    # Reuse the exact import guard, but invoke the actual library function rather
    # than __main__; this protects direct callers as well as command execution.
    driver = DRIVER.replace("runpy.run_path(str(script),run_name='__main__')",
                            "module=runpy.run_path(str(script));" + call)
    result = subprocess.run([sys.executable, '-c', driver, str(ROOT/'tools'/script),
                             json.dumps([dependency])],
                            capture_output=True, text=True, timeout=30)
    assert result.returncode == 1
    assert 'ImportError: OPTIONAL_RUNTIME_IMPORT_BLOCKED:' + dependency in result.stderr


def test_valid_benchmark_preserves_explicit_native_override(tmp_path, monkeypatch):
    absent = tmp_path / 'missing-candidate-native.so'
    monkeypatch.setenv('RF_NATIVE_LIBRARY', str(absent))
    output = tmp_path / 'must-not-exist.json'
    result = subprocess.run([sys.executable, str(ROOT/'tools/benchmark_multiview_coverage.py'),
                             '--output', str(output)],
                            capture_output=True, text=True, timeout=30)
    assert result.returncode == 1
    assert str(absent) in result.stderr and 'OSError' in result.stderr
    assert not output.exists()
