"""Ownership/admission controls for one generated stdlib-only signal fixture."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

import pytest
import skull_reference_fixture as fixture


@pytest.fixture
def copied_source(tmp_path, monkeypatch):
    root = tmp_path / 'reviewed-repo'
    source = root / 'tools/qa/skull_reference.py'
    source.parent.mkdir(parents=True)
    shutil.copyfile(fixture.ROOT / 'tools/qa/skull_reference.py', source)
    output = tmp_path / 'output'
    output.mkdir()
    monkeypatch.setattr(fixture, 'ROOT', root)
    return source, output


def test_exact_closure_and_parent_instrumentation_preserved(copied_source):
    source, output = copied_source
    injected = {name: 'unexecuted-injection' for name in (
        'LD_PRELOAD', 'LD_AUDIT', 'LD_LIBRARY_PATH', 'DYLD_INSERT_LIBRARIES',
        'PYTHONPATH', 'PYTHONSTARTUP', 'PYTEST_PLUGINS', 'RF_NATIVE_LIBRARY', 'RF_CABI_LIBRARY')}
    keep = {'ASAN_OPTIONS': 'halt_on_error=1:detect_leaks=1', 'LSAN_OPTIONS': 'exitcode=23',
            'UBSAN_OPTIONS': 'halt_on_error=1:print_stacktrace=1', 'KEEP': 'unchanged'}
    parent = {**injected, **keep}
    before = dict(parent)
    args, env = fixture.signal_supervisor(output, parent)
    assert args == [sys.executable, '-I', '-S', '-c', fixture.SUPERVISOR, str(source), str(output)]
    assert hashlib.sha256(args[4].encode()).hexdigest() == fixture.SUPERVISOR_SHA256
    assert env == keep and parent == before
    assert env is not parent


@pytest.mark.parametrize('mutation', ['native_source', 'source_symlink', 'native_payload'])
def test_unreviewed_code_rejected_before_clean_environment(copied_source, monkeypatch, mutation):
    source, output = copied_source
    if mutation == 'native_source':
        source.write_text(source.read_text() + '\nimport ctypes; ctypes.CDLL("never-loaded.so")\n')
    elif mutation == 'source_symlink':
        saved = source.with_suffix('.saved')
        source.rename(saved)
        source.symlink_to(saved)
    else:
        monkeypatch.setattr(fixture, 'SUPERVISOR', fixture.SUPERVISOR + '\nimport ctypes\n')
    monkeypatch.setattr(fixture, 'synthetic_child_env', lambda *_: pytest.fail('No environment exception before admission'))
    with pytest.raises(ValueError, match='identity|symlink'):
        fixture.signal_supervisor(output)


@pytest.mark.parametrize('mutation', ['altered_source', 'extra_argv'])
def test_child_rechecks_source_and_closed_argv_before_exec(copied_source, mutation):
    source, output = copied_source
    args, env = fixture.signal_supervisor(output)
    marker = output / 'native-control-ran'
    if mutation == 'altered_source':
        source.write_text(source.read_text() + '\nfrom pathlib import Path\nPath(' + repr(str(marker)) + ').touch()\n')
    else:
        args.extend(['--native-library', 'never-loaded.so'])
    result = subprocess.run(args, env=env, capture_output=True, timeout=5)
    assert result.returncode == 1
    expected = b'identity changed' if mutation == 'altered_source' else b'Unreviewed'
    assert expected in result.stderr
    assert not marker.exists() and not (output / 'worker.pid').exists()
    assert b'LeakSanitizer' not in result.stderr  # Expected admission failure, never an accepted leak exit.


def test_repository_local_or_symlink_output_is_refused(copied_source, tmp_path):
    source, output = copied_source
    with pytest.raises(ValueError, match='output'):
        fixture.signal_supervisor(source.parent)
    alias = tmp_path / 'alias'
    alias.symlink_to(output, target_is_directory=True)
    with pytest.raises(ValueError, match='output'):
        fixture.signal_supervisor(alias)


def test_bytecode_extension_and_startup_shadows_cannot_replace_pinned_source(copied_source):
    source, output = copied_source
    marker = output / 'unexpected-loader'
    poison = 'from pathlib import Path; Path(' + repr(str(marker)) + ').touch(); raise RuntimeError("unreviewed")\n'
    for name in ['sitecustomize.py', 'usercustomize.py', 'subprocess.py', 'pathlib.py']:
        (output / name).write_text(poison)
    (source.parent / 'skull_reference.pyc').write_bytes(b'not valid bytecode')
    (source.parent / 'skull_reference.so').write_bytes(b'not an extension')
    shadow = source.parent / 'skull_reference'
    shadow.mkdir(); (shadow / '__init__.py').write_text(poison)
    parent = dict(os.environ, PYTHONPATH=str(output), PYTHONSTARTUP=str(output / 'sitecustomize.py'),
                  RF_NATIVE_LIBRARY='/unexecuted/native.so')
    args, env = fixture.signal_supervisor(output, parent)
    supervisor = subprocess.Popen(args, env=env, cwd=output, start_new_session=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        deadline = time.monotonic() + 5
        while not (output / 'worker.pid').exists() and supervisor.poll() is None and time.monotonic() < deadline:
            time.sleep(.01)
        assert (output / 'worker.pid').exists()
        supervisor.send_signal(signal.SIGTERM)
        stdout, stderr = supervisor.communicate(timeout=5)
        assert supervisor.returncode == 143, (stdout, stderr)
        assert json.loads((output / 'reaped.json').read_text())['returncode'] == -signal.SIGKILL
        assert not marker.exists()
    finally:
        if supervisor.poll() is None:
            supervisor.terminate()
            try:
                supervisor.wait(timeout=5)
            except subprocess.TimeoutExpired:
                supervisor.kill(); supervisor.wait(timeout=5)
        # Nested fixture self-exits after3s; no pid-file based fallback signal.
