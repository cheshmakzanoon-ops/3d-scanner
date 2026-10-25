"""Real CLI parsing needs no optional runtime; execution still requires it.

No loader/sanitizer environment is filtered. The import-blocking child is a
negative dependency-availability control, never an alternate execution backend.
"""
import json
from pathlib import Path
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


@pytest.mark.parametrize('script', SCRIPTS)
@pytest.mark.parametrize('arguments,code', [(['--help'], 0), (['--unrecognized-runtime-option'], 2)])
def test_cli_parse_exits_before_optional_imports(script, arguments, code):
    result = blocked_run(script, arguments, ['cv2', 'numpy', 'native_api', 'release'])
    direct = subprocess.run([sys.executable, str(ROOT/'tools'/script), *arguments],
                            capture_output=True, text=True, timeout=30)
    assert result.returncode == direct.returncode == code, result.stderr
    assert result.stdout == direct.stdout
    assert result.stderr == direct.stderr
    assert 'usage:' in (result.stdout if code == 0 else result.stderr).lower()
    assert 'OPTIONAL_RUNTIME_IMPORT_BLOCKED' not in result.stderr
    if code == 0:
        assert result.stderr == ''
    else:
        assert result.stdout == ''


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
