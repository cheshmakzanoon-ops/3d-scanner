"""The compiler probes must use the same explicit candidate as ctypes tests."""
from tools.native_process import native_compile_env
import os
from pathlib import Path
import subprocess

import pytest
from tools import native_api
from tools.native_test_build import native_link_args, native_run_env


def test_missing_override_never_uses_existing_host_build(tmp_path, monkeypatch):
    host = tmp_path / 'build/host/librealityforge.so'
    host.parent.mkdir(parents=True)
    host.write_bytes(b'old build')
    monkeypatch.setattr(native_api, 'ROOT', tmp_path)
    monkeypatch.setenv('RF_NATIVE_LIBRARY', str(tmp_path / 'missing.so'))
    with pytest.raises(FileNotFoundError):
        native_link_args()


def test_relative_override_resolves_exact_file_and_runtime_path(tmp_path, monkeypatch):
    candidate = tmp_path / 'alternate build,comma/candidate.so'
    candidate.parent.mkdir()
    source = tmp_path / 'library.cpp'
    source.write_text('extern "C" int candidate_value() { return 42; }')
    subprocess.run(['c++', '-shared', '-fPIC', str(source), '-o', str(candidate)], check=True, env=native_compile_env())
    # A directory/name-based -lrealityforge implementation would select this
    # stale neighbor instead of the explicitly configured candidate filename.
    source.write_text('extern "C" int candidate_value() { return 7; }')
    subprocess.run(['c++', '-shared', '-fPIC', str(source), '-o', str(candidate.parent / 'librealityforge.so')], check=True, env=native_compile_env())
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv('RF_NATIVE_LIBRARY', str(candidate.relative_to(tmp_path)))
    args = native_link_args()
    assert args == [str(candidate), '-Xlinker', '-rpath', '-Xlinker', str(candidate.parent)]
    source.write_text('extern "C" int candidate_value(); int main() { return candidate_value() == 42 ? 0 : 1; }')
    binary = tmp_path / 'probe'
    subprocess.run(['c++', str(source), *args, '-o', str(binary)], check=True, env=native_compile_env())
    environment = dict(os.environ)
    environment.pop('LD_LIBRARY_PATH', None)
    subprocess.run([str(binary)], cwd='/', env=environment, check=True)


def test_directory_override_is_not_a_library(tmp_path, monkeypatch):
    monkeypatch.setenv('RF_NATIVE_LIBRARY', str(tmp_path))
    with pytest.raises(FileNotFoundError, match='not a file'):
        native_link_args()


def test_soname_runtime_prefers_candidate_over_inherited_library_path(tmp_path, monkeypatch):
    candidate_dir = tmp_path / 'candidate build,comma'
    stale_dir = tmp_path / 'stale build'
    source = tmp_path / 'probe.cpp'
    for directory, value in [(candidate_dir, 42), (stale_dir, 7)]:
        directory.mkdir()
        source.write_text(f'extern "C" int candidate_value() {{ return {value}; }}')
        subprocess.run(['c++', '-shared', '-fPIC', '-Wl,-soname,librealityforge.so', str(source), '-o', str(directory / 'librealityforge.so')], check=True, env=native_compile_env())
    monkeypatch.setenv('RF_NATIVE_LIBRARY', str(candidate_dir / 'librealityforge.so'))
    monkeypatch.setenv('LD_LIBRARY_PATH', str(stale_dir))
    source.write_text('extern "C" int candidate_value(); int main() { return candidate_value() == 42 ? 0 : 1; }')
    binary = tmp_path / 'probe'
    subprocess.run(['c++', str(source), *native_link_args(), '-o', str(binary)], check=True, env=native_compile_env())
    # Demonstrate the stale SONAME trap before verifying the corrected runtime.
    assert subprocess.run([str(binary)], env=dict(os.environ)).returncode == 1
    environment = native_run_env()
    assert environment['LD_LIBRARY_PATH'] == str(candidate_dir) + os.pathsep + str(stale_dir)
    subprocess.run([str(binary)], cwd='/', env=environment, check=True)


def test_unconfigured_legacy_host_library_is_preserved(tmp_path, monkeypatch):
    host = tmp_path / 'build/host/librealityforge.so'
    host.parent.mkdir(parents=True)
    source = tmp_path / 'host.cpp'
    source.write_text('extern "C" int candidate_value() { return 42; }')
    subprocess.run(['c++', '-shared', '-fPIC', str(source), '-o', str(host)], check=True, env=native_compile_env())
    monkeypatch.delenv('RF_NATIVE_LIBRARY', raising=False)
    monkeypatch.setattr(native_api, 'ROOT', tmp_path)
    monkeypatch.setattr(native_api.platform, 'system', lambda: 'Linux')
    assert native_link_args()[0] == str(host)


def _build_soname_library(tmp_path, filename, value, soname):
    source = tmp_path / (filename + '.cpp')
    source.write_text(f'extern "C" int candidate_value() {{ return {value}; }}')
    output = tmp_path / filename
    subprocess.run(['c++', '-shared', '-fPIC', '-Xlinker', '-soname', '-Xlinker', soname,
                    str(source), '-o', str(output)], check=True, env=native_compile_env())
    return output


def test_renamed_soname_cannot_select_stale_sibling(tmp_path, monkeypatch):
    candidate = _build_soname_library(tmp_path, 'candidate.so', 42, 'librealityforge.so')
    _build_soname_library(tmp_path, 'librealityforge.so', 7, 'librealityforge.so')
    monkeypatch.setenv('RF_NATIVE_LIBRARY', str(candidate))
    source = tmp_path / 'probe.cpp'
    source.write_text('extern "C" int candidate_value(); int main() { return candidate_value() == 42 ? 0 : 1; }')
    binary = tmp_path / 'probe'
    # Reproduce the previous absolute-file + candidate-first-directory defect.
    subprocess.run(['c++', str(source), str(candidate), '-Xlinker', '-rpath',
                    '-Xlinker', str(tmp_path), '-o', str(binary)], check=True, env=native_compile_env())
    environment = dict(os.environ, LD_LIBRARY_PATH=str(tmp_path))
    assert subprocess.run([str(binary)], env=environment).returncode == 1
    for resolve in (native_link_args, native_run_env):
        with pytest.raises(ValueError, match='does not resolve to selected file'):
            resolve()


def test_renamed_soname_accepts_alias_to_selected_file(tmp_path, monkeypatch):
    candidate = _build_soname_library(tmp_path, 'candidate.so', 42, 'librealityforge.so')
    (tmp_path / 'librealityforge.so').symlink_to(candidate.name)
    monkeypatch.setenv('RF_NATIVE_LIBRARY', str(candidate))
    source = tmp_path / 'probe.cpp'
    source.write_text('extern "C" int candidate_value(); int main() { return candidate_value() == 42 ? 0 : 1; }')
    binary = tmp_path / 'probe'
    subprocess.run(['c++', str(source), *native_link_args(), '-o', str(binary)], check=True, env=native_compile_env())
    subprocess.run([str(binary)], env=native_run_env(), check=True)


@pytest.mark.parametrize('soname', ['/tmp/librealityforge.so', '../librealityforge.so', 'sub/librealityforge.so'])
def test_unsafe_soname_is_rejected(tmp_path, monkeypatch, soname):
    candidate = _build_soname_library(tmp_path, 'candidate.so', 42, soname)
    monkeypatch.setenv('RF_NATIVE_LIBRARY', str(candidate))
    with pytest.raises(ValueError, match='Unsafe native library SONAME'):
        native_link_args()


def test_readelf_scope_does_not_remove_native_runtime_preload(tmp_path, monkeypatch):
    candidate = _build_soname_library(tmp_path, 'librealityforge.so', 42, 'librealityforge.so')
    monkeypatch.setenv('RF_NATIVE_LIBRARY', str(candidate))
    monkeypatch.setenv('LD_PRELOAD', '/runtime/libasan.so')
    monkeypatch.setenv('LD_AUDIT', '/runtime/audit.so')
    original = subprocess.check_output
    calls = []
    def inspect_readelf(command, **kwargs):
        assert command[0] == 'readelf'
        assert 'LD_PRELOAD' not in kwargs['env'] and 'LD_AUDIT' not in kwargs['env']
        calls.append(command)
        return original(command, **kwargs)
    monkeypatch.setattr(subprocess, 'check_output', inspect_readelf)
    environment = native_run_env()
    assert calls
    assert environment['LD_PRELOAD'] == '/runtime/libasan.so'
    assert environment['LD_AUDIT'] == '/runtime/audit.so'


@pytest.mark.parametrize('token', ['$ORIGIN', '$LIB', '$PLATFORM', 'name:part', 'name;part'])
def test_loader_tokens_and_separators_in_soname_are_rejected(tmp_path, monkeypatch, token):
    soname = token + '.so'
    candidate = _build_soname_library(tmp_path, 'candidate.so', 42, soname)
    # Even a literal alias must not satisfy validation: the ELF loader would
    # interpret tokens rather than use the same literal path as pathlib.
    (tmp_path / soname).symlink_to(candidate.name)
    monkeypatch.setenv('RF_NATIVE_LIBRARY', str(candidate))
    with pytest.raises(ValueError, match='Unsafe native library SONAME'):
        native_link_args()


@pytest.mark.parametrize('component', ['$ORIGIN', '$LIB', '$PLATFORM', 'name:part', 'name;part'])
def test_loader_tokens_and_separators_in_candidate_path_are_rejected(tmp_path, monkeypatch, component):
    directory = tmp_path / component
    directory.mkdir()
    candidate = _build_soname_library(directory, 'librealityforge.so', 42, 'librealityforge.so')
    monkeypatch.setenv('RF_NATIVE_LIBRARY', str(candidate))
    for resolve in (native_link_args, native_run_env):
        with pytest.raises(ValueError, match='Unsupported loader token or separator'):
            resolve()
