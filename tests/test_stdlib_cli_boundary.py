"""Adversarial checks for explicitly admitted real-CLI functional tests only."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

import pytest
import stdlib_cli_boundary as boundary


@pytest.fixture
def copied_manifest(tmp_path):
    script = tmp_path / 'tools/source_manifest.py'
    script.parent.mkdir()
    shutil.copyfile(boundary.ROOT / 'tools/source_manifest.py', script)
    return tmp_path, script


def parent_env(**extra):
    return dict(os.environ, PATH=str(Path(sys.executable).parent) + ':/usr/bin:/bin', **extra)


def test_parent_native_instrumentation_and_options_stay_intact(copied_manifest):
    repo, script = copied_manifest
    injected = dict(LD_PRELOAD='/unexecuted/asan.so', LD_AUDIT='/unexecuted/audit.so',
                    LD_LIBRARY_PATH='/unexecuted/libs', PYTHONPATH='/unexecuted/python',
                    PYTEST_PLUGINS='unexecuted_plugin', RF_NATIVE_LIBRARY='/unexecuted/engine.so',
                    RF_CABI_LIBRARY='/unexecuted/cabi.so', GIT_EXTERNAL_DIFF='/unexecuted/tool')
    parent = parent_env(**injected, ASAN_OPTIONS='detect_leaks=1:halt_on_error=1',
                        LSAN_OPTIONS='exitcode=23', UBSAN_OPTIONS='halt_on_error=1')
    before = dict(parent)
    args, env = boundary.manifest_cli(repo, [sys.executable, script, 'generate'], parent)
    assert args == [sys.executable, '-I', '-S', str(script), 'generate']
    assert parent == before and env is not parent
    assert not set(injected).intersection(env)
    for key in ('ASAN_OPTIONS', 'LSAN_OPTIONS', 'UBSAN_OPTIONS'):
        assert env[key] == parent[key]
    assert env['GIT_CONFIG_VALUE_0'] == 'false'
    assert env['GIT_CONFIG_VALUE_1'] == os.devnull


@pytest.mark.parametrize('tail', [('-c', 'import ctypes'), ('--help',),
    ('generate', '--source', 'worktree'), ('verify', '--source', 'HEAD', '--check', 'native'),
    ('verify', '--source', 'worktree')])
def test_unknown_manifest_payload_is_not_isolated(copied_manifest, tail):
    repo, script = copied_manifest
    with pytest.raises(ValueError, match='argv'):
        boundary.manifest_cli(repo, [sys.executable, script, *tail], parent_env())


def test_altered_or_native_source_and_alternate_path_rejected(copied_manifest):
    repo, script = copied_manifest
    alternate = repo / 'native.py'
    shutil.copyfile(script, alternate)
    with pytest.raises(ValueError, match='path'):
        boundary.manifest_cli(repo, [sys.executable, alternate, 'generate'], parent_env())
    script.write_text(script.read_text() + '\nimport ctypes; ctypes.CDLL("unexecuted.so")\n')
    with pytest.raises(ValueError, match='identity'):
        boundary.manifest_cli(repo, [sys.executable, script, 'generate'], parent_env())


def test_symlinked_source_rejected(copied_manifest):
    repo, script = copied_manifest
    saved = script.with_suffix('.saved')
    script.rename(saved)
    script.symlink_to(saved)
    with pytest.raises(ValueError, match='symlink'):
        boundary.manifest_cli(repo, [sys.executable, script, 'generate'], parent_env())


@pytest.mark.parametrize('name', ['git', 'python3'])
def test_path_poison_is_refused_without_running_it(copied_manifest, name):
    repo, script = copied_manifest
    marker = repo / 'poison-ran'
    poison = repo / name
    poison.write_text('#!/bin/sh\ntouch ' + str(marker) + '\n')
    poison.chmod(0o755)
    env = parent_env()
    env['PATH'] = str(repo) + ':' + env['PATH']
    executable = 'python3' if name == 'python3' else sys.executable
    with pytest.raises(ValueError, match='interpreter|Git'):
        boundary.manifest_cli(repo, [executable, script, 'generate'], env)
    assert not marker.exists()


def test_real_manifest_ignores_startup_and_cwd_shadows_and_checks_bytes(copied_manifest):
    repo, script = copied_manifest
    # These files would attempt an engine load if Python imported them. They must
    # neither execute nor contaminate the manifest CLI's stdlib import closure.
    marker = repo / 'poison-ran'
    poison = 'from pathlib import Path\nPath(' + repr(str(marker)) + ').touch()\nraise RuntimeError("startup executed")\n'
    for directory in (repo, script.parent):
        for name in ('sitecustomize.py', 'usercustomize.py', 'subprocess.py', 'ctypes.py'):
            (directory / name).write_text(poison)
    env = parent_env(PYTHONPATH=str(repo), PYTHONSTARTUP=str(repo / 'sitecustomize.py'),
                     PYTHONUSERBASE=str(repo), RF_NATIVE_LIBRARY='/unexecuted/engine.so')
    for tail in (['init', '-q'], ['add', '.']):
        subprocess.run(['/usr/bin/git', '-C', str(repo), *tail], check=True, capture_output=True)
    args, child_env = boundary.manifest_cli(repo, [sys.executable, script, 'generate'], env)
    result = subprocess.run(args, env=child_env, cwd=repo, capture_output=True, timeout=10)
    assert result.returncode == 0 and b'Generated manifest from staged Git blobs' in result.stdout
    assert not marker.exists()
    subprocess.run(['/usr/bin/git', '-C', str(repo), 'add', 'SOURCE_MANIFEST.sha256'], check=True)
    subprocess.run(['/usr/bin/git', '-C', str(repo), '-c', 'user.name=Fixture', '-c',
                    'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture'], check=True)
    args, child_env = boundary.manifest_cli(repo, [sys.executable, script, 'verify', '--source', 'HEAD'], env)
    passed = subprocess.run(args, env=child_env, cwd=repo, capture_output=True, timeout=10)
    assert passed.returncode == 0 and b'"status": "PASS"' in passed.stdout
    (repo / 'SOURCE_MANIFEST.sha256').write_text('forged\n')
    subprocess.run(['/usr/bin/git', '-C', str(repo), 'add', 'SOURCE_MANIFEST.sha256'], check=True)
    subprocess.run(['/usr/bin/git', '-C', str(repo), '-c', 'user.name=Fixture', '-c',
                    'user.email=fixture@example.invalid', 'commit', '-qm', 'forged'], check=True)
    refused = subprocess.run(args, env=child_env, cwd=repo, capture_output=True, timeout=10)
    assert refused.returncode == 1 and b'Malformed manifest row' in refused.stderr
    assert not marker.exists()


def test_archive_added_stdlib_shadow_is_rejected(tmp_path, monkeypatch):
    for name in ('tools/source_manifest.py', 'tools/ci/source_snapshot/archive.py'):
        target = tmp_path / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(boundary.ROOT / name, target)
    (tmp_path / 'tools/subprocess.py').write_text('raise RuntimeError("must not execute")')
    monkeypatch.setattr(boundary, 'ROOT', tmp_path)
    with pytest.raises(ValueError, match='shadows'):
        boundary.archive_cli(tmp_path, 'create', ['--output', 'out', '--record', 'record'], parent_env())


def test_unknown_archive_and_receipt_options_are_refused(tmp_path):
    with pytest.raises(ValueError, match='argv'):
        boundary.archive_cli(tmp_path, 'create', ['--output', 'a', '--record', 'b', '--check', 'native'])
    with pytest.raises(ValueError, match='argv'):
        boundary.receipt_cli(tmp_path, tmp_path, extra=('--native-library', 'native.so'))


def test_header_runner_keeps_unknown_native_commands_instrumented(tmp_path, monkeypatch):
    import test_eight_view_header_diagnostic as header_test
    calls = []
    native = ['python3', '-c', 'import ctypes; ctypes.CDLL("unexecuted-native.so")']
    def record(args, **kwargs):
        calls.append((args, kwargs))
        return subprocess.CompletedProcess(args, 0, b'')
    monkeypatch.setattr(header_test.subprocess, 'run', record)
    runner = header_test.GitRunner(tmp_path)
    runner.command('native-control', native)
    assert calls[0][0] == native and 'env' not in calls[0][1]
    with pytest.raises(ValueError, match='Unreviewed'):
        runner.command('header-source-manifest', native)
    assert len(calls) == 1


@pytest.mark.parametrize('poison', ['bytecode', 'extension', 'package'])
def test_archive_import_override_is_not_hidden_by_valid_source_hash(tmp_path, monkeypatch, poison):
    import marshal
    for name in ('tools/source_manifest.py', 'tools/ci/source_snapshot/archive.py'):
        target = tmp_path / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(boundary.ROOT / name, target)
    if poison == 'bytecode':
        cache = tmp_path / 'tools/__pycache__' / ('source_manifest.' + sys.implementation.cache_tag + '.pyc')
        cache.parent.mkdir()
        # Never deserialize or execute this payload in the admission check.
        cache.write_bytes(b'\x00' * 16 + marshal.dumps(compile('import ctypes', 'poison', 'exec')))
    elif poison == 'extension':
        (tmp_path / 'tools/source_manifest.so').write_bytes(b'not a loadable library')
    else:
        (tmp_path / 'tools/source_manifest').mkdir()
    monkeypatch.setattr(boundary, 'ROOT', tmp_path)
    with pytest.raises(ValueError, match='bytecode|shadows'):
        boundary.archive_cli(tmp_path, 'create', ['--output', 'out', '--record', 'record'], parent_env())
