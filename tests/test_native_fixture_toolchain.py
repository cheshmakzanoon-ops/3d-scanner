"""Candidate fixture compile/link coverage; runtime gates remain separate and strict."""
import ast
import os
from pathlib import Path
import shutil
import subprocess
import sys

import pytest
from tools.native_process import native_compile_env
from tools.native_test_build import native_compile_prefix, native_link_args

ROOT = Path(__file__).resolve().parents[1]
SANITIZERS = '-fsanitize=address,undefined,float-cast-overflow'


@pytest.mark.parametrize('config', ['gcc-release', 'clang-release', 'gcc-sanitize', 'clang-sanitize'])
def test_fixture_prefix_uses_exact_matrix_compiler_and_runtime(monkeypatch, config):
    compiler = '/selected toolchain/compiler'
    monkeypatch.setenv('RF_NATIVE_CXX', compiler)
    monkeypatch.setenv('RF_NATIVE_CONFIG', config)
    prefix = native_compile_prefix()
    assert prefix[0] == compiler
    assert (SANITIZERS in prefix) == config.endswith('-sanitize')
    assert ('-fno-omit-frame-pointer' in prefix) == config.endswith('-sanitize')
    assert ('-shared-libasan' in prefix) == (config == 'clang-sanitize')


def test_unconfigured_fixture_keeps_default_compiler(monkeypatch):
    monkeypatch.delenv('RF_NATIVE_CXX', raising=False)
    monkeypatch.delenv('RF_NATIVE_CONFIG', raising=False)
    assert native_compile_prefix() == ['c++']


@pytest.mark.parametrize('config,compiler', [(None, '/compiler'), ('bad-config', '/compiler'),
                                           ('clang-sanitize', None), ('gcc-release', '')])
def test_incomplete_or_unknown_matrix_never_falls_back(monkeypatch, config, compiler):
    for key, value in [('RF_NATIVE_CONFIG', config), ('RF_NATIVE_CXX', compiler)]:
        if value is None:
            monkeypatch.delenv(key, raising=False)
        else:
            monkeypatch.setenv(key, value)
    with pytest.raises(RuntimeError):
        native_compile_prefix()


def test_all_candidate_linked_agent_fixtures_use_matching_compile_prefix():
    count = 0
    for path in (ROOT / 'tests').glob('test_agent_*.py'):
        tree = ast.parse(path.read_text())
        for node in ast.walk(tree):
            if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                    and isinstance(node.func.value, ast.Name) and node.func.value.id == 'subprocess'
                    and node.args and isinstance(node.args[0], ast.List)):
                continue
            argv = node.args[0]
            if not any(isinstance(child, ast.Call) and isinstance(child.func, ast.Name)
                       and child.func.id == 'native_link_args' for child in ast.walk(argv)):
                continue
            assert isinstance(argv.elts[0], ast.Starred), path
            prefix = argv.elts[0].value
            assert isinstance(prefix, ast.Call) and isinstance(prefix.func, ast.Name), path
            assert prefix.func.id == 'native_compile_prefix', path
            env = next((kw.value for kw in node.keywords if kw.arg == 'env'), None)
            assert isinstance(env, ast.Call) and isinstance(env.func, ast.Name), path
            assert env.func.id == 'native_compile_env', path
            count += 1
    assert count >= 11


@pytest.mark.parametrize('config', ['gcc-release', 'clang-release', 'gcc-sanitize', 'clang-sanitize'])
@pytest.mark.parametrize('failed_phase', [None, 'python-baseline-empty', 'python-baseline-numpy', 'numerical', 'pytest'])
@pytest.mark.parametrize('deadline', [7, 600])
def test_runner_hands_selected_toolchain_to_every_python_phase(tmp_path, monkeypatch, config, failed_phase, deadline):
    sys.path.insert(0, str(ROOT / 'tools/qa/native_regression'))
    import run
    compiler = tmp_path / 'exact compiler'
    compiler.write_bytes(b'synthetic compiler; never executed')
    output = tmp_path / 'evidence'
    monkeypatch.setattr(sys, 'argv', ['run.py', '--configs', config, '--output', str(output), '--timeout', str(deadline)])
    monkeypatch.setattr(run.shutil, 'which', lambda _: str(compiler))
    monkeypatch.setattr(run.platform, 'platform', lambda: 'synthetic platform')
    def query(argv, **kwargs):
        if argv[:2] == ['git', 'rev-parse']:
            return 'synthetic-sha\n'
        if argv[:2] == ['git', 'status']:
            return b''
        assert argv[1] == '--version'
        return 'synthetic compiler version'
    monkeypatch.setattr(run.subprocess, 'check_output', query)
    def preload(selected, name, environment):
        assert selected == str(compiler) and name == config
        return environment | {'LD_PRELOAD': 'synthetic-runtime'}, []
    monkeypatch.setattr(run, 'sanitizer_python_env', preload)
    monkeypatch.setenv('ASAN_OPTIONS', 'detect_leaks=0')
    monkeypatch.setenv('UBSAN_OPTIONS', 'halt_on_error=0')
    monkeypatch.setenv('LSAN_OPTIONS', 'exitcode=23')
    labels, python_envs = [], []
    def phase(argv, cwd, folder, label, env, timeout):
        labels.append(label)
        assert timeout == (min(deadline, 30) if label.startswith('python-baseline-') else deadline)
        if label == 'build':
            build = folder / 'build'
            build.mkdir(parents=True)
            (build / 'librealityforge.so').write_bytes(b'synthetic candidate; never executed')
        if label not in ('configure', 'build', 'ctest'):
            python_envs.append(env.copy())
            assert env['RF_NATIVE_CXX'] == str(compiler)
            assert env['RF_NATIVE_CONFIG'] == config
            assert env['RF_NATIVE_LIBRARY'] == str(folder / 'build/librealityforge.so')
            assert env['ASAN_OPTIONS'] == 'halt_on_error=1:detect_leaks=1'
            assert env['UBSAN_OPTIONS'] == 'halt_on_error=1:print_stacktrace=1'
            assert env['LSAN_OPTIONS'] == 'exitcode=23'
            assert env['PYTHONMALLOC'] == 'malloc'
            assert ('LD_PRELOAD' in env) == config.endswith('-sanitize')
        if label.startswith('python-baseline-'):
            assert argv == [sys.executable, ROOT / 'tools/qa/native_regression/python_control.py', label.rsplit('-', 1)[1]]
        return dict(label=label, exit_code=17 if label == failed_phase else 0, reason=None,
                    result='FAIL' if label == failed_phase else 'PASS')
    monkeypatch.setattr(run, 'command', phase)
    status = run.main()
    controls = ['python-baseline-empty', 'python-baseline-numpy'] if config.endswith('-sanitize') else []
    assert labels == ['configure', 'build', 'ctest', *controls, 'numerical', 'pytest', 'bundle-three-view',
                      'benchmark_multiview_coverage', 'benchmark_middlebury',
                      'benchmark_dense_middlebury', 'benchmark_surface_middlebury',
                      'benchmark_texture_middlebury']
    assert all(env == python_envs[0] for env in python_envs)
    expected_failure = failed_phase in labels
    assert status == int(expected_failure)
    evidence = run.json.loads((output / 'evidence.json').read_text())
    assert evidence['result'] == ('FAIL' if expected_failure else 'PASS')
    record = evidence['configurations'][0]
    assert record['result'] == evidence['result']
    assert [receipt['label'] for receipt in record['commands']] == labels
    assert [receipt['exit_code'] for receipt in record['commands']] == [17 if label == failed_phase else 0 for label in labels]


@pytest.mark.parametrize('family,driver,runtime', [('gcc', 'g++', 'libasan.so'),
                                                ('clang', 'clang++', 'libclang_rt.asan')])
def test_actual_sanitized_fixture_links_matching_runtime(tmp_path, monkeypatch, family, driver, runtime):
    compiler = shutil.which(driver)
    if not compiler:
        pytest.skip(f'{driver} unavailable; compile/link regression NOT_RUN')
    monkeypatch.setenv('RF_NATIVE_CXX', compiler)
    monkeypatch.setenv('RF_NATIVE_CONFIG', family + '-sanitize')
    source = tmp_path / 'candidate.cpp'
    source.write_text('extern "C" int candidate_value(int *p) { return *p; }')
    library = tmp_path / 'candidate.so'
    prefix = native_compile_prefix()
    env = native_compile_env()
    subprocess.run([*prefix, '-shared', '-fPIC', '-g', str(source), '-o', str(library)],
                   check=True, env=env, timeout=30)
    monkeypatch.setenv('RF_NATIVE_LIBRARY', str(library))
    source.write_text('extern "C" int candidate_value(int*); int main() { int v=42; return candidate_value(&v)!=42; }')
    binary = tmp_path / 'fixture'
    subprocess.run([*prefix, '-g', str(source), *native_link_args(), '-o', str(binary)],
                   check=True, env=env, timeout=30)
    # No execution here: this regression establishes compiler/link ownership.
    # Existing candidate fixtures execute with native_run_env and strict LSan.
    dynamic = subprocess.check_output(['readelf', '--dynamic', str(binary)], text=True, env=env, timeout=10)
    assert runtime in dynamic
    symbols = subprocess.check_output(['readelf', '--wide', '--dyn-syms', str(binary)], text=True, env=env, timeout=10)
    assert '__asan_' in symbols
