"""Adversarial contracts for explicitly generated-only process fixtures."""
import os
from pathlib import Path
import subprocess
import sys

from synthetic_child_env import synthetic_child_env, child_evidence


def test_copy_removes_injection_but_preserves_options_and_parent():
    injected = {key: 'synthetic-injection' for key in (
        'LD_PRELOAD', 'LD_AUDIT', 'LD_LIBRARY_PATH', 'DYLD_INSERT_LIBRARIES',
        'PYTHONPATH', 'PYTHONHOME', 'PYTHONSTARTUP', 'PYTHONINSPECT',
        'PYTEST_ADDOPTS', 'PYTEST_PLUGINS', 'RF_NATIVE_LIBRARY', 'RF_NATIVE_CONFIG',
        'RF_CABI_LIBRARY')}
    options = {'ASAN_OPTIONS': 'detect_leaks=1:halt_on_error=1',
               'LSAN_OPTIONS': 'exitcode=23', 'UBSAN_OPTIONS': 'halt_on_error=1',
               'PATH': '/synthetic/path', 'KEEP_MARKER': 'unchanged'}
    parent = {**injected, **options}
    before = dict(parent)
    child = synthetic_child_env(parent)
    assert child == options
    assert parent == before and child is not parent
    child['KEEP_MARKER'] = 'child-only'
    assert parent['KEEP_MARKER'] == 'unchanged'


def test_synthetic_launch_blocks_startup_and_keeps_nonzero_exit(tmp_path):
    marker = tmp_path / 'unexpected-startup'
    (tmp_path / 'sitecustomize.py').write_text(
        'from pathlib import Path; Path(' + repr(str(marker)) + ').touch()')
    parent = dict(os.environ, PYTHONPATH=str(tmp_path), PYTHONSTARTUP=str(tmp_path / 'sitecustomize.py'),
                  PYTEST_PLUGINS='untrusted_plugin', RF_NATIVE_LIBRARY='/synthetic/native.so')
    before = dict(parent)
    result = subprocess.run([sys.executable, '-I', '-S', '-c',
        "import os,sys; assert sys.flags.isolated and sys.flags.no_site; "
        "assert 'RF_NATIVE_LIBRARY' not in os.environ; print('payload'); raise SystemExit(17)"],
        env=synthetic_child_env(parent), cwd=tmp_path, capture_output=True, timeout=5)
    assert result.returncode == 17, child_evidence(result.returncode, result.stderr)
    assert result.stdout == b'payload\n' and result.stderr == b''
    assert not marker.exists() and parent == before


def test_diagnostics_never_return_raw_child_data():
    evidence = child_evidence(17, b'private-value LeakSanitizer SUMMARY:')
    assert evidence == {'exit': 17, 'bytes': 36, 'markers': {
        'AddressSanitizer': 0, 'LeakSanitizer': 1,
        'UndefinedBehaviorSanitizer': 0, 'SUMMARY:': 1}}
    assert 'private-value' not in str(evidence)
