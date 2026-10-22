"""Independent host controls cannot replace or bypass final sanitizer checking."""
import ast
import hashlib
import json
import os
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest
from tools.qa.native_regression import python_control as control


def test_file_identity_is_resolved_and_hashed(tmp_path):
    binary = tmp_path / 'python'
    binary.write_bytes(b'example binary')
    link = tmp_path / 'python-link'
    link.symlink_to(binary)
    assert control.identity(link) == dict(path=str(link), resolved_path=str(binary),
                                        sha256=hashlib.sha256(binary.read_bytes()).hexdigest())


@pytest.mark.parametrize('kind', ['empty', 'numpy'])
def test_control_logs_provenance_and_preserves_environment(monkeypatch, capsys, kind):
    environment = dict(os.environ)
    monkeypatch.setattr(sys, 'argv', ['python_control.py', kind])
    imports = []
    def load(name):
        imports.append(name)
        assert name == 'numpy'
        return SimpleNamespace(__version__='test-version', __file__=__file__)
    monkeypatch.setattr(control.importlib, 'import_module', load)
    monkeypatch.setattr(control, 'scanner_mappings', lambda: [])
    assert control.main() == 0
    rows = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert imports == (['numpy'] if kind == 'numpy' else [])
    assert [row['event'] for row in rows] == (['start', 'numpy_import', 'complete'] if kind == 'numpy' else ['start', 'complete'])
    start = rows[0]
    assert start['python'] == sys.version
    assert start['executable'] == control.identity(sys.executable)
    assert start['probe'] == control.identity(control.__file__)
    assert set(start['build']) == {'Py_DEBUG', 'Py_TRACE_REFS', 'SOABI', 'CONFIG_ARGS'}
    assert start['environment'] == {key: environment.get(key) for key in control.ENVIRONMENT_KEYS}
    assert rows[-1]['scanner_mappings'] == []
    assert os.environ == environment


def test_candidate_mapping_fails_control(monkeypatch, capsys):
    monkeypatch.setattr(sys, 'argv', ['python_control.py', 'empty'])
    monkeypatch.setattr(control, 'scanner_mappings', lambda: ['/candidate/librealityforge.so'])
    with pytest.raises(RuntimeError, match='unexpectedly loaded'):
        control.main()
    rows = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert rows[-1]['scanner_mappings'] == ['/candidate/librealityforge.so']


def test_mapping_evidence_detects_selected_and_renamed_candidate(monkeypatch):
    monkeypatch.setenv('RF_NATIVE_LIBRARY', '/candidate/renamed.so')
    def read(path):
        assert str(path) == '/proc/self/maps'
        return ('0-1 r-xp 0 0:0 0 /candidate/renamed.so\n'
                '0-1 r-xp 0 0:0 0 /other/librealityforge.so (deleted)\n'
                '0-1 r-xp 0 0:0 0 /runtime/libasan.so\n')
    monkeypatch.setattr(Path, 'read_text', read)
    assert control.scanner_mappings() == ['/candidate/renamed.so', '/other/librealityforge.so']


def test_missing_mapping_evidence_fails_closed(monkeypatch):
    def missing(path):
        raise FileNotFoundError('/proc/self/maps')
    monkeypatch.setattr(Path, 'read_text', missing)
    with pytest.raises(FileNotFoundError):
        control.scanner_mappings()


def test_control_only_has_ordinary_exit_and_no_candidate_import():
    tree = ast.parse(Path(control.__file__).read_text())
    imports = {alias.name for node in ast.walk(tree) if isinstance(node, ast.Import) for alias in node.names}
    imports |= {node.module for node in ast.walk(tree) if isinstance(node, ast.ImportFrom)}
    assert imports == {'hashlib', 'importlib', 'json', 'os', 'pathlib', 'sys', 'sysconfig'}
    calls = {ast.unparse(node.func) for node in ast.walk(tree) if isinstance(node, ast.Call)}
    assert not any('lsan' in call or call in {'os._exit', 'os.fork', 'atexit.register', 'gc.collect'} for call in calls)
    assert ast.unparse(tree.body[-1].body[0]) == 'raise SystemExit(main())'
