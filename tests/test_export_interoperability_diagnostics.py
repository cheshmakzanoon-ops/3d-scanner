"""Mocked diagnostic contracts: no external tools or sanitizer-policy changes."""
import importlib
import json
from pathlib import Path
import runpy
import subprocess

import pytest

m = importlib.import_module('tools.qa.export_interoperability.validate')
ROOT = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize('code', [1, -11, 0])
def test_tool_summary_preserves_exit_signal_and_empty_output(code):
    run = subprocess.CompletedProcess(['unused'], code, '', '')
    detail = json.loads(m.tool_failure('blender blender_roundtrip.py', run, target_exists=False))
    assert detail['command'] == 'blender blender_roundtrip.py'
    assert detail['exit_code'] == code
    assert detail['signal'] == (11 if code == -11 else None)
    assert detail['target_exists'] is False
    for stream in ('stdout', 'stderr'):
        assert detail[stream] == {'utf8_bytes': 0, 'head': '', 'tail': '', 'truncated': False}


@pytest.mark.parametrize('text', ['hello', 'é' * 512, 'HEAD' + '界' * 10000 + 'TAIL'],
                         ids=['short-ascii', 'utf8-at-budget', 'utf8-over-budget-head-tail'])
def test_tool_summary_bounds_utf8_snippets_and_labels_truncation(text):
    run = subprocess.CompletedProcess([], 1, text, text)
    encoded = m.tool_failure('node khronos.cjs', run)
    detail = json.loads(encoded)
    for stream in ('stdout', 'stderr'):
        item = detail[stream]
        assert item['utf8_bytes'] == len(text.encode())
        assert len((item['head'] + item['tail']).encode()) <= 1024
        assert item['truncated'] == (len(text.encode()) > 1024)
        if item['truncated']:
            assert item['head'].startswith('HEAD') and item['tail'].endswith('TAIL')
        else:
            assert item['head'] + item['tail'] == text
    assert len(encoded.encode()) < 14000


@pytest.mark.parametrize('code', [1, -11])
def test_khronos_failure_identifies_process_with_artifact_environment(monkeypatch, tmp_path, code):
    monkeypatch.setenv('RF_GLTF_VALIDATOR', 'fixture')
    monkeypatch.setattr(m.shutil, 'which', lambda _: '/node')
    calls = []
    def fake_run(command, **kwargs):
        calls.append((command, kwargs))
        return subprocess.CompletedProcess(command, code, 'first output', 'first error\nlast error')
    monkeypatch.setattr(m.subprocess, 'run', fake_run)
    with pytest.raises(m.Invalid, match='Khronos execution: ') as caught:
        m.khronos(tmp_path / 'fixture.glb')
    detail = json.loads(str(caught.value).split(': ', 1)[1])
    assert detail['exit_code'] == code and detail['stderr']['head'].startswith('first error')
    assert calls == [(['node', str(m.HERE / 'khronos.cjs'), str(tmp_path / 'fixture.glb')],
                      {'capture_output': True, 'text': True, 'timeout': 45, 'env': m._artifact_consumer_env()})]


def test_khronos_success_retains_result(monkeypatch, tmp_path):
    monkeypatch.setenv('RF_GLTF_VALIDATOR', 'fixture')
    monkeypatch.setattr(m.shutil, 'which', lambda _: '/node')
    monkeypatch.setattr(m.subprocess, 'run', lambda *a, **k: subprocess.CompletedProcess(
        a[0], 0, json.dumps({'issues': {'numErrors': 0, 'numWarnings': 2}}), ''))
    assert m.khronos(tmp_path / 'fixture.glb') == {'version': '2.0.0-dev.3.10', 'errors': 0, 'warnings': 2}


@pytest.mark.parametrize('code', [0, 1, -11])
def test_blender_missing_target_and_empty_output_remain_failure(monkeypatch, tmp_path, code):
    monkeypatch.setattr(m.shutil, 'which', lambda _: '/blender')
    calls = []
    def fake_run(command, **kwargs):
        calls.append((command, kwargs))
        return subprocess.CompletedProcess(command, code, '', '')
    monkeypatch.setattr(m.subprocess, 'run', fake_run)
    with pytest.raises(m.Invalid, match='Blender failed: ') as caught:
        m.roundtrip(tmp_path / 'fixture.obj', {'triangles': 1}, {})
    detail = json.loads(str(caught.value).split(': ', 1)[1])
    assert detail['exit_code'] == code and detail['target_exists'] is False
    command, kwargs = calls[0]
    assert command[:8] == ['blender', '--background', '--factory-startup', '--threads', '1', '--python-exit-code', '1', '--python']
    assert kwargs == {'capture_output': True, 'text': True, 'timeout': 120, 'env': m._artifact_consumer_env()}


@pytest.mark.parametrize('name,args', [
    ('test_valid_independent_readers', ('corpus', 'glb')),
    ('test_blender_import_export_roundtrip', ('corpus', 'obj')),
    ('test_tangent_smooth_vertex_normal_is_legal', ('corpus',)),
    ('test_real066_plain_pbr_material_remains_untextured_surface', ()),
    ('test_baseline_sparse_is_points', ()),
])
def test_acceptance_assertion_exposes_structured_report(monkeypatch, tmp_path, name, args):
    module = runpy.run_path(str(ROOT / 'tests/test_export_interoperability.py'))
    function = module[name]
    report = {'status': 'FAIL', 'error': 'diagnostic-start ' + 'x' * 2000 + ' diagnostic-end',
              'path': 'fixture.glb', 'target_tool': {'status': 'NOT_RUN'}}
    monkeypatch.setitem(function.__globals__, 'validate', lambda *a, **k: report)
    (tmp_path / 'textured.expected.json').write_text('{}')
    with pytest.raises(AssertionError) as caught:
        function(*(tmp_path if a == 'corpus' else a for a in args))
    assert json.loads(str(caught.value)) == report


def test_smoke_assertion_identifies_commands_and_calls_once(monkeypatch):
    module = runpy.run_path(str(ROOT / 'tests/test_runbook_consistency.py'))
    function = module['test_documented_command_smoke']
    results = [{'script': 'tools/capture_audit.py', 'status': 'PASS'},
               {'script': 'tools/check_apk.py', 'status': 'FAIL'}]
    calls = []
    def smoke(root):
        calls.append(root)
        return results
    monkeypatch.setitem(function.__globals__, 'smoke', smoke)
    with pytest.raises(AssertionError) as caught:
        function()
    assert json.loads(str(caught.value)) == results
    assert len(calls) == 1


def test_blender_existing_target_does_not_hide_nonzero_exit(monkeypatch, tmp_path):
    monkeypatch.setattr(m.shutil, 'which', lambda _: '/blender')
    def fake_run(command, **kwargs):
        Path(command[-1]).write_text('fixture')
        return subprocess.CompletedProcess(command, 7, 'output', 'error')
    monkeypatch.setattr(m.subprocess, 'run', fake_run)
    with pytest.raises(m.Invalid) as caught:
        m.roundtrip(tmp_path / 'fixture.obj', {'triangles': 1}, {})
    detail = json.loads(str(caught.value).split(': ', 1)[1])
    assert detail['exit_code'] == 7 and detail['target_exists'] is True


def test_khronos_validation_error_remains_failure(monkeypatch, tmp_path):
    monkeypatch.setenv('RF_GLTF_VALIDATOR', 'fixture')
    monkeypatch.setattr(m.shutil, 'which', lambda _: '/node')
    monkeypatch.setattr(m.subprocess, 'run', lambda *a, **k: subprocess.CompletedProcess(
        a[0], 0, json.dumps({'issues': {'numErrors': 1, 'numWarnings': 0}}), ''))
    with pytest.raises(m.Invalid, match='Khronos: '):
        m.khronos(tmp_path / 'fixture.glb')


def test_validate_retains_failed_status_and_tool_diagnostic(monkeypatch, tmp_path):
    asset = tmp_path / 'fixture.glb'
    asset.write_text('fixture')
    monkeypatch.setattr(m, 'preflight', lambda _: None)
    def fail(_):
        raise m.Invalid('Khronos execution: fixed diagnostic')
    monkeypatch.setattr(m, 'khronos', fail)
    report = m.validate(asset)
    assert report['status'] == 'FAIL'
    assert report['error'] == 'Khronos execution: fixed diagnostic'
    assert report['target_tool']['status'] == 'NOT_RUN'


@pytest.mark.parametrize('consumer', ['khronos', 'blender'])
def test_only_artifact_children_drop_loader_injection(monkeypatch, tmp_path, consumer):
    import os
    settings = dict(LD_PRELOAD='/runtime/asan.so:/runtime/cxx.so', LD_AUDIT='/runtime/audit.so',
                    ASAN_OPTIONS='halt_on_error=1:detect_leaks=1', LSAN_OPTIONS='exitcode=23',
                    UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1',
                    RF_NATIVE_LIBRARY='/candidate/librealityforge.so', RF_NATIVE_CONFIG='gcc-sanitize',
                    LD_LIBRARY_PATH='/candidate:/dependencies', RF_GLTF_VALIDATOR='/pinned/validator')
    for key, value in settings.items():
        monkeypatch.setenv(key, value)
    before = dict(os.environ)
    calls = []
    monkeypatch.setattr(m.shutil, 'which', lambda name: '/external/' + name)
    def run(argv, **kwargs):
        calls.append((argv, kwargs))
        assert kwargs['env'] == {k: v for k, v in before.items() if k not in ('LD_PRELOAD', 'LD_AUDIT')}
        return subprocess.CompletedProcess(argv, 19, '', 'external tool still failed')
    monkeypatch.setattr(m.subprocess, 'run', run)
    with pytest.raises(m.Invalid) as caught:
        if consumer == 'khronos':
            m.khronos(tmp_path / 'fixture.glb')
        else:
            m.roundtrip(tmp_path / 'fixture.obj', {'triangles': 1}, {})
    assert json.loads(str(caught.value).split(': ', 1)[1])['exit_code'] == 19
    assert len(calls) == 1
    assert calls[0][0][0] == ('node' if consumer == 'khronos' else 'blender')
    assert os.environ == before
