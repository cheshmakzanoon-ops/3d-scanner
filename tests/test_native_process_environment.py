"""Pure-Python boundary checks: no compiler or candidate binary is executed."""
import ast
import os
from pathlib import Path
import sys

import pytest
from tools.native_process import native_compile_env

QA = Path(__file__).resolve().parents[1] / 'tools/qa/native_regression'
sys.path.insert(0, str(QA))
from run import sanitizer_python_env


def test_compile_environment_is_a_copy_and_only_removes_loader_injection():
    original = dict(LD_PRELOAD='asan.so:cxx.so', LD_AUDIT='audit.so',
                    ASAN_OPTIONS='halt_on_error=1:detect_leaks=1',
                    UBSAN_OPTIONS='halt_on_error=1', LD_LIBRARY_PATH='/candidate',
                    RF_NATIVE_LIBRARY='/candidate/lib.so', PATH='/compiler')
    clean = native_compile_env(original)
    assert clean == {k: v for k, v in original.items() if k not in ('LD_PRELOAD', 'LD_AUDIT')}
    assert original['LD_PRELOAD'] == 'asan.so:cxx.so'


def test_compile_environment_does_not_mutate_process(monkeypatch):
    monkeypatch.setenv('LD_PRELOAD', '/runtime/asan.so')
    assert 'LD_PRELOAD' not in native_compile_env()
    assert os.environ['LD_PRELOAD'] == '/runtime/asan.so'


@pytest.mark.parametrize('config,runtime', [('gcc-sanitize', 'libasan.so'),
                                           ('clang-sanitize', 'libclang_rt.asan-x86_64.so')])
def test_runtime_queries_are_clean_and_native_python_retains_checks(tmp_path, monkeypatch, config, runtime):
    import run
    files = {}
    for name in (runtime, 'libstdc++.so'):
        path = tmp_path / name
        path.write_bytes(b'synthetic preload bytes; never executed')
        files[name] = str(path)
    parent = dict(LD_PRELOAD='unrelated.so', LD_AUDIT='audit.so',
                  ASAN_OPTIONS='halt_on_error=1:detect_leaks=1',
                  UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1')
    def query(argv, **kwargs):
        assert kwargs['env'] == native_compile_env(parent)
        return files[argv[1].split('=', 1)[1]] + '\n'
    monkeypatch.setattr(run.subprocess, 'check_output', query)
    result, records = sanitizer_python_env('/compiler', config, parent)
    assert result['LD_PRELOAD'] == files[runtime] + ':' + files['libstdc++.so']
    assert result['ASAN_OPTIONS'] == parent['ASAN_OPTIONS']
    assert result['UBSAN_OPTIONS'] == parent['UBSAN_OPTIONS']
    assert parent['LD_PRELOAD'] == 'unrelated.so'
    assert [record['path'] for record in records] == list(files.values())


@pytest.mark.parametrize('missing', ['libasan.so', 'libstdc++.so'])
def test_missing_preload_rejects_launch(tmp_path, monkeypatch, missing):
    import run
    for name in ('libasan.so', 'libstdc++.so'):
        if name != missing:
            (tmp_path / name).write_bytes(b'synthetic')
    monkeypatch.setattr(run.subprocess, 'check_output',
                        lambda argv, **kwargs: str(tmp_path / argv[1].split('=', 1)[1]))
    with pytest.raises(RuntimeError, match='Host sanitizer preload missing'):
        sanitizer_python_env('/compiler', 'gcc-sanitize', {})


def test_direct_cpp_compilers_have_explicit_tool_boundary():
    # Guard the compile sites, not native execution: executions must continue to
    # inherit preload or use native_run_env() so candidate leaks remain fatal.
    found = 0
    for path in Path(__file__).parent.glob('test*.py'):
        for node in ast.walk(ast.parse(path.read_text())):
            if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                    and isinstance(node.func.value, ast.Name) and node.func.value.id == 'subprocess'
                    and node.args and isinstance(node.args[0], ast.List) and node.args[0].elts):
                continue
            first = node.args[0].elts[0]
            literal_compiler = isinstance(first, ast.Constant) and first.value in ('c++', 'g++', 'clang++', 'gcc', 'cc', 'clang')
            selected_compiler = (isinstance(first, ast.Starred) and isinstance(first.value, ast.Call)
                                 and isinstance(first.value.func, ast.Name)
                                 and first.value.func.id == 'native_compile_prefix')
            if not (literal_compiler or selected_compiler):
                continue
            env = next((k.value for k in node.keywords if k.arg == 'env'), None)
            assert isinstance(env, ast.Call) and isinstance(env.func, ast.Name) and env.func.id == 'native_compile_env', path
            found += 1
    assert found > 0


@pytest.mark.parametrize('config,runtime', [('gcc-sanitize', 'libasan.so'),
                                           ('clang-sanitize', 'libclang_rt.asan-x86_64.so')])
@pytest.mark.parametrize('missing_index', [0, 1])
def test_missing_runtime_records_not_run_and_does_not_launch_python(tmp_path, monkeypatch, config, runtime, missing_index):
    import run
    compiler = tmp_path / 'compiler'
    compiler.write_bytes(b'synthetic compiler; never executed')
    output = tmp_path / 'evidence'
    names = (runtime, 'libstdc++.so')
    for index, name in enumerate(names):
        if index != missing_index:
            (tmp_path / name).write_bytes(b'synthetic preload; never executed')
    monkeypatch.setattr(sys, 'argv', ['run.py', '--configs', config, '--output', str(output)])
    monkeypatch.setattr(run.shutil, 'which', lambda _: str(compiler))
    monkeypatch.setattr(run.platform, 'platform', lambda: 'synthetic platform')
    def query(argv, **kwargs):
        if argv[:2] == ['git', 'rev-parse']:
            return 'synthetic-sha\n'
        if argv[:2] == ['git', 'status']:
            return b''
        if argv[1] == '--version':
            return 'synthetic compiler version'
        return str(tmp_path / argv[1].split('=', 1)[1])
    monkeypatch.setattr(run.subprocess, 'check_output', query)
    labels = []
    def phase(argv, cwd, folder, label, env, timeout):
        assert 'LD_PRELOAD' not in env and 'LD_AUDIT' not in env
        assert env['ASAN_OPTIONS'] == 'halt_on_error=1:detect_leaks=1'
        labels.append(label)
        if label == 'build':
            build = folder / 'build'
            build.mkdir(parents=True)
            (build / 'librealityforge.so').write_bytes(b'synthetic candidate; never executed')
        return dict(result='PASS')
    monkeypatch.setattr(run, 'command', phase)
    monkeypatch.setenv('LD_PRELOAD', '/unrelated/runtime.so')
    monkeypatch.setenv('LD_AUDIT', '/unrelated/audit.so')
    assert run.main() == 1
    import json
    receipt = json.loads((output / 'evidence.json').read_text())
    assert labels == ['configure', 'build', 'ctest']
    assert receipt['result'] == 'FAIL'
    config = receipt['configurations'][0]
    assert config['result'] == 'FAIL'
    assert config['commands'][-1]['result'] == 'NOT_RUN'
    assert names[missing_index] in config['commands'][-1]['reason']
    assert 'python_preloads' not in config
