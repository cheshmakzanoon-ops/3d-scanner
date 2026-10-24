"""Mocked same-runtime JVM control contracts; not actual JVM qualification."""
import ast
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys

import pytest
from tools.native_process import native_compile_env
from tools import native_test_build

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / 'tools/qa/native_regression'
sys.path.insert(0, str(ROOT / 'tools'))
sys.path.insert(0, str(QA))
from jvm_control import run_baseline, runtime_environment
import jvm_control as control


@pytest.fixture
def fixture(tmp_path):
    jdk = tmp_path / 'jdk'
    (jdk / 'bin').mkdir(parents=True)
    for name in ('javac', 'java'):
        (jdk / 'bin' / name).write_text(name)
    (jdk / 'release').write_text('JAVA_VERSION="17-fixture"')
    link = tmp_path / 'javac'
    link.symlink_to(jdk / 'bin/javac')
    candidate = tmp_path / 'native/librealityforge.so'
    candidate.parent.mkdir()
    candidate.write_bytes(b'not executed')
    env = dict(PATH='/selected/path', RF_NATIVE_LIBRARY=str(candidate),
               LD_LIBRARY_PATH='/existing/runtime', LD_PRELOAD='/runtime/asan.so:/runtime/cxx.so',
               LD_AUDIT='/audit.so', ASAN_OPTIONS='halt_on_error=1:detect_leaks=1',
               LSAN_OPTIONS='verbosity=1', UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1')
    return jdk, link, candidate, env


@pytest.mark.parametrize('previous', [None, '', '/existing/runtime'])
def test_runtime_environment_matches_actual_jni_helper(monkeypatch, fixture, previous):
    _, _, candidate, env = fixture
    env = dict(env)
    env.pop('LD_LIBRARY_PATH')
    if previous is not None:
        env['LD_LIBRARY_PATH'] = previous
    monkeypatch.setattr(os, 'environ', env)
    monkeypatch.setattr(native_test_build, '_selected_library', lambda: candidate.resolve())
    original = dict(env)
    assert runtime_environment(env) == native_test_build.native_run_env()
    assert env == original


@pytest.mark.parametrize('compile_result,run_result,timeout', [
    ('PASS', 'PASS', 600), ('PASS', 'FAIL', 600), ('FAIL', 'PASS', 600), ('PASS', 'PASS', 7),
])
def test_distinct_compile_and_runtime_receipts_keep_environment_and_status(
        monkeypatch, tmp_path, fixture, compile_result, run_result, timeout):
    jdk, javac, _, env = fixture
    lookups = []
    def which(name, path):
        lookups.append((name, path))
        return str(javac)
    monkeypatch.setattr(control.shutil, 'which', which)
    calls = []
    def command(argv, cwd, folder, label, environment, deadline):
        calls.append((list(map(str, argv)), label, dict(environment), deadline))
        if label == 'jvm-baseline-compile' and compile_result == 'PASS':
            (Path(argv[2]) / 'EmptyJvmControl.class').write_bytes(b'compiled-fixture')
        result = compile_result if label.endswith('compile') else run_result
        return dict(result=result, exit_code=0 if result == 'PASS' else 1,
                    log=label + '.log', log_sha256='retained', command=list(map(str, argv)))
    original = dict(env)
    record = run_baseline(ROOT, tmp_path / 'out', env, timeout, command)
    assert lookups == [('javac', env['PATH'])]
    assert env == original
    assert calls[0][0] == [str(javac), '-d', str(tmp_path / 'out/jvm-baseline-classes'), str(control.SOURCE)]
    assert calls[0][2] == native_compile_env(env)
    assert calls[0][3] == min(timeout, 60)
    assert record['inputs']['java']['resolved_path'] == str(jdk / 'bin/java')
    assert record['inputs']['source']['sha256'] == hashlib.sha256(control.SOURCE.read_bytes()).hexdigest()
    assert record['inputs']['javac']['sha256'] == hashlib.sha256(b'javac').hexdigest()
    if compile_result == 'PASS':
        assert calls[1][0] == [str(jdk / 'bin/java'), '-cp', str(tmp_path / 'out/jvm-baseline-classes'), 'EmptyJvmControl']
        assert calls[1][2] == runtime_environment(env)
        assert calls[1][3] == min(timeout, 60)
        assert record['commands'][1]['result'] == run_result
        assert record['class']['sha256'] == hashlib.sha256(b'compiled-fixture').hexdigest()
    else:
        assert len(calls) == 1
        assert record['commands'][1]['result'] == 'NOT_RUN'
    assert record['commands'][0]['result'] == compile_result


@pytest.mark.parametrize('missing', ['javac', 'java', 'release', 'candidate'])
def test_missing_control_inputs_are_not_run_not_pass(monkeypatch, tmp_path, fixture, missing):
    jdk, javac, candidate, env = fixture
    monkeypatch.setattr(control.shutil, 'which', lambda *a, **k: None if missing == 'javac' else str(javac))
    if missing != 'javac':
        {'java': jdk / 'bin/java', 'release': jdk / 'release', 'candidate': candidate}[missing].unlink()
    calls = []
    record = run_baseline(ROOT, tmp_path / 'out', env, 60, lambda *a: calls.append(a))
    assert not calls
    assert record['commands'][-1]['result'] == 'NOT_RUN'


@pytest.mark.parametrize('baseline_status', ['PASS', 'FAIL', 'NOT_RUN'])
def test_failed_control_does_not_skip_candidate_phases(monkeypatch, tmp_path, baseline_status):
    spec = importlib.util.spec_from_file_location('jvm_control_harness_test', QA / 'run.py')
    harness = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(harness)
    monkeypatch.setattr(sys, 'argv', ['run.py', '--configs', 'gcc-sanitize', '--output', str(tmp_path / 'out')])
    monkeypatch.setattr(harness.shutil, 'which', lambda _: '/fixture/g++')
    monkeypatch.setattr(harness.subprocess, 'check_output', lambda argv, **kwargs:
                        '' if argv[1:3] == ['status', '--porcelain'] else 'fixture-sha-or-version')
    monkeypatch.setattr(harness, 'sha', lambda _: 'fixture-hash')
    monkeypatch.setattr(harness, 'sanitizer_python_env', lambda *a: (dict(a[2], LD_PRELOAD='preserved'), []))
    calls = []
    def command(argv, cwd, folder, label, env, timeout):
        folder.mkdir(parents=True, exist_ok=True)
        calls.append(label)
        return dict(result='PASS', command=list(map(str, argv)), exit_code=0)
    monkeypatch.setattr(harness, 'command', command)
    monkeypatch.setattr(harness, 'jvm_baseline', lambda *a: dict(commands=[dict(result=baseline_status)]))
    assert harness.main() == (0 if baseline_status == 'PASS' else 1)
    assert calls[:5] == ['configure', 'build', 'ctest', 'python-baseline-empty', 'python-baseline-numpy']
    assert calls[5:] == ['numerical', 'pytest', 'bundle-three-view', 'benchmark_multiview_coverage',
                         'benchmark_middlebury', 'benchmark_dense_middlebury',
                         'benchmark_surface_middlebury', 'benchmark_texture_middlebury']
    record = json.loads((tmp_path / 'out/evidence.json').read_text())['configurations'][0]
    assert record['commands'][5]['result'] == baseline_status
    assert record['result'] == ('PASS' if baseline_status == 'PASS' else 'FAIL')


def test_java_control_source_has_snapshot_and_ordinary_exit_without_native_load():
    source = control.SOURCE.read_text()
    assert 'scannerMappings(Path.of("/proc/self/maps")' in source
    assert 'Files.readAllLines(maps)' in source
    assert 'Files.readAttributes(selected, "unix:dev,ino")' in source
    assert 'sameIdentity || path.equals(candidate)' in source
    assert 'path.equals(candidate)' in source
    assert 'path.contains("librealityforge")' in source
    assert 'path.contains("libagent003_jni")' in source
    assert 'if (!scanner.isEmpty()) throw new IllegalStateException' in source
    assert 'scanner_mappings' in source and 'Snapshot only' in source
    # Check code, not explanatory comments mentioning forbidden mechanisms.
    code = '\n'.join(line.split('//', 1)[0] for line in source.splitlines())
    for forbidden in ('System.load', 'System.exit', '.halt(', 'System.gc(', 'addShutdownHook', '__lsan'):
        assert forbidden not in code
    assert 'public static void main(String[] args) throws Exception' in code


def test_control_jdk_resolution_matches_actual_jni_source_contract():
    tree = ast.parse((ROOT / 'tests/test_agent_003_jni_boundary.py').read_text())
    function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'test_actual_jvm_native_boundary')
    source = ast.unparse(function)
    assert "javac = shutil.which('javac')" in source
    assert 'jdk = Path(javac).resolve().parents[1]' in source
    assert "str(jdk / 'bin/java')" in source
    assert 'env=native_run_env()' in source and 'timeout=60' in source


MAPPING_CHECK = r'''
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
public class MappingCheck {
    static final List<MappedByteBuffer> retained = new ArrayList<>();
    static final Path maps = Path.of("/proc/self/maps");
    static void map(Path file) throws Exception {
        try (FileChannel channel = FileChannel.open(file)) {
            retained.add(channel.map(FileChannel.MapMode.READ_ONLY, 0, 1));
        }
    }
    static Path file(Path root, String name) throws Exception {
        return Files.writeString(root.resolve(name), "fixture data");
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path selected = file(root, "selected");
        map(selected);
        require(!EmptyJvmControl.scannerMappings(maps, selected).isEmpty(), "selected mapping missed");
        Path linked = file(root, "linked-selected");
        Path alias = Files.createLink(root.resolve("different-name"), linked);
        map(alias);
        require(EmptyJvmControl.scannerMappings(maps, linked).contains(alias.toString()), "hardlink missed");
        Path renamed = root.resolve("renamed-alias");
        Files.move(alias, renamed);
        require(EmptyJvmControl.scannerMappings(maps, linked).contains(renamed.toString()), "renamed alias missed");
        Files.delete(renamed);
        require(EmptyJvmControl.scannerMappings(maps, linked).contains(renamed + " (deleted)"), "deleted alias missed");
        Files.writeString(renamed, "replacement data");
        require(!EmptyJvmControl.scannerMappings(maps, linked).isEmpty(), "replaced pathname missed old mapping");
        Path symbolic = file(root, "symbolic-selected");
        Path symlink = Files.createSymbolicLink(root.resolve("symbolic-alias"), symbolic);
        map(symlink);
        require(!EmptyJvmControl.scannerMappings(maps, symbolic).isEmpty(), "symlink missed");
        Path unused = file(root, "unmapped-selected");
        require(EmptyJvmControl.scannerMappings(maps, unused).isEmpty(), "unrelated mapping misattributed");
        Path empty = Files.writeString(root.resolve("empty-map"), "");
        Path badDevice = Files.writeString(root.resolve("bad-device-map"), "0-1 r-xp 0 xx:zz 0 /unrelated\n");
        for (Path missing : List.of(root.resolve("missing-map"), file(root, "malformed-map"), empty, badDevice)) {
            boolean rejected = false;
            try { EmptyJvmControl.scannerMappings(missing, unused); }
            catch (Exception expected) { rejected = true; }
            require(rejected, "missing/malformed map accepted");
        }
        System.out.println("JAVA_MAPPING_CONTROLS_PASSED");
    }
}
'''


def test_actual_java_mapping_detector_aliases_and_missing_evidence(tmp_path):
    import shutil
    import subprocess
    javac = shutil.which('javac')
    if not javac:
        pytest.skip('JDK compiler unavailable; actual JVM mapping detector control NOT_RUN')
    jdk = Path(javac).resolve().parents[1]
    source = tmp_path / 'MappingCheck.java'
    source.write_text(MAPPING_CHECK)
    subprocess.run([javac, '-d', str(tmp_path), str(control.SOURCE), str(source)],
                   check=True, env=native_compile_env(), timeout=30)
    # Preserve inherited sanitizer runtime/options. A shutdown leak still fails.
    result = subprocess.run([str(jdk / 'bin/java'), '-cp', str(tmp_path), 'MappingCheck', str(tmp_path)],
                            capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr
    assert 'JAVA_MAPPING_CONTROLS_PASSED' in result.stdout
