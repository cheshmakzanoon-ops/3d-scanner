"""Exact help-only child admission; never an alternate native execution route."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

import pytest
import stdlib_cli_boundary as boundary

SCRIPTS = ('capture_audit.py', 'check_apk.py', 'benchmark_multiview_coverage.py')


@pytest.fixture(params=SCRIPTS)
def script(request, tmp_path, monkeypatch):
    target = tmp_path/'tools'/request.param
    target.parent.mkdir()
    shutil.copyfile(boundary.ROOT/'tools'/request.param, target)
    monkeypatch.setattr(boundary, 'ROOT', tmp_path)
    return target


def parent_env(**extra):
    return dict(os.environ, PATH=str(Path(sys.executable).parent)+':/usr/bin:/bin', **extra)


def test_help_real_exit_and_startup_isolation(script):
    marker = script.parent/'poison-ran'
    poison = 'from pathlib import Path\nPath('+repr(str(marker))+').touch()\nraise RuntimeError("startup executed")\n'
    for name in ('sitecustomize.py', 'usercustomize.py', 'argparse.py', 'json.py',
                 'pathlib.py', 'cv2.py', 'numpy.py', 'native_api.py'):
        (script.parent/name).write_text(poison)
    parent = parent_env(PYTHONPATH=str(script.parent), PYTHONHOME='/not-a-python-home',
                        PYTHONSTARTUP=str(script.parent/'sitecustomize.py'))
    before = dict(parent)
    process_before = dict(os.environ)
    command, env = boundary.runbook_help_cli([sys.executable, script, '--help'], parent)
    assert command == [sys.executable, '-I', '-S', str(script), '--help']
    result = subprocess.run(command, env=env, cwd=script.parent, capture_output=True,
                            text=True, timeout=30)
    assert result.returncode == 0 and 'usage:' in result.stdout.lower()
    assert result.stderr == '' and not marker.exists()
    assert parent == before and dict(os.environ) == process_before


@pytest.mark.parametrize('tail', [(), ('--unknown',), ('--help', '--gate'), ('--help', '--'),
    ('--output', 'output.json'), ('--gate',), ('capture.zip',), ('candidate.apk',),
    ('-h',), ('-c', 'import ctypes'), ('--help=1',)])
def test_help_rejects_valid_native_and_other_arguments(script, tail):
    with pytest.raises(ValueError, match='argv'):
        boundary.runbook_help_cli([sys.executable, script, *tail], parent_env())


@pytest.mark.parametrize('change', ['source', 'path', 'relative', 'symlink', 'parent-symlink',
                                    'interpreter', 'module'])
def test_help_rejects_unreviewed_execution(script, change):
    executable = sys.executable
    if change == 'source':
        script.write_text(script.read_text()+'\nimport ctypes; ctypes.CDLL("unexecuted-native.so")\n')
    elif change == 'path':
        other = script.with_name('unreviewed.py')
        shutil.copyfile(script, other)
        script = other
    elif change == 'relative':
        script = Path('tools')/script.name
    elif change == 'symlink':
        saved = script.with_suffix('.saved')
        script.rename(saved)
        script.symlink_to(saved)
    elif change == 'parent-symlink':
        saved = script.parent.with_name('saved-tools')
        script.parent.rename(saved)
        script.parent.symlink_to(saved, target_is_directory=True)
    elif change == 'interpreter':
        executable = '/usr/bin/false'
    elif change == 'module':
        script = '-m'
    with pytest.raises(ValueError, match='identity|path|symlink|interpreter'):
        boundary.runbook_help_cli([executable, script, '--help'], parent_env())


def test_help_environment_copy_preserves_parent_instrumentation(script):
    injected = dict(LD_PRELOAD='/unexecuted/asan.so', LD_AUDIT='/unexecuted/audit.so',
                    LD_LIBRARY_PATH='/unexecuted/libs', PYTHONPATH='/unexecuted/python',
                    PYTEST_PLUGINS='unexecuted', RF_NATIVE_LIBRARY='/unexecuted/native.so',
                    RF_CABI_LIBRARY='/unexecuted/cabi.so')
    parent = parent_env(**injected, ASAN_OPTIONS='detect_leaks=1:halt_on_error=1',
                        LSAN_OPTIONS='exitcode=23', UBSAN_OPTIONS='halt_on_error=1')
    before = dict(parent)
    process_before = dict(os.environ)
    command, env = boundary.runbook_help_cli([sys.executable, script, '--help'], parent)
    assert parent == before and dict(os.environ) == process_before and env is not parent
    assert not set(injected).intersection(env)
    for key in ('ASAN_OPTIONS', 'LSAN_OPTIONS', 'UBSAN_OPTIONS'):
        assert env[key] == parent[key]


@pytest.mark.parametrize('name', ['git', 'python3'])
def test_help_path_shim_is_rejected_without_execution(script, name):
    marker = script.parent/'poison-ran'
    shim = script.parent/name
    shim.write_text('#!/bin/sh\ntouch '+str(marker)+'\n')
    shim.chmod(0o755)
    parent = parent_env()
    parent['PATH'] = str(script.parent)+':'+parent['PATH']
    executable = 'python3' if name == 'python3' else sys.executable
    with pytest.raises(ValueError, match='interpreter|Git'):
        boundary.runbook_help_cli([executable, script, '--help'], parent)
    assert not marker.exists()
