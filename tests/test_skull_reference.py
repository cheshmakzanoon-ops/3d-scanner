"""Synthetic harness tests only; no licensed photos, COLMAP import or scanner run."""
import ast
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
from types import SimpleNamespace as NS

import numpy as np
import pytest

from tools.qa import skull_reference as ref
from skull_reference_fixture import signal_supervisor


@pytest.fixture
def synthetic_inputs(tmp_path, monkeypatch):
    folder = tmp_path / 'originals'
    folder.mkdir()
    data = {'10.JPG': b'not a photograph', '11.JPG': b'also synthetic',
            '12.JPG': b'test only', 'README.md': b'synthetic test license placeholder'}
    monkeypatch.setattr(ref, 'INPUTS', {n: (len(b), hashlib.sha256(b).hexdigest()) for n, b in data.items()})
    for name, contents in data.items():
        (folder / name).write_bytes(contents)
    return folder


def test_frozen_input_hashes_match_existing_reviewed_profile():
    root = Path(__file__).resolve().parents[1]
    path = root / 'docs/development/experiments/eight-view-sparse-runtime-successor/real_three_inputs.py'
    assignments = {}
    for node in ast.parse(path.read_text()).body:
        if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name):
            try:
                assignments[node.targets[0].id] = ast.literal_eval(node.value)
            except (ValueError, TypeError):
                pass
    assert {n: h for n, (_, h) in ref.INPUTS.items() if n.endswith('.JPG')} == assignments['HASHES']
    assert {n: size for n, (size, _) in ref.INPUTS.items()} == assignments['SIZES']
    assert ref.INPUTS['README.md'][1] == assignments['README_SHA256']
    assert ref.SOURCE_COMMIT == assignments['COMMIT']


def test_same_physical_intrinsics_with_explicit_pixel_origin_conversion():
    assert ref.PARAMS == [10442.666666666666] * 2 + [2136, 1424]
    point_colmap = np.array([100.5, 200.5])
    assert np.allclose((point_colmap - ref.PARAMS[2:]) / ref.FOCAL,
                       (point_colmap - .5 - np.array([2135.5, 1423.5])) / ref.FOCAL)


def test_frozen_options_do_not_refine_intrinsics_or_tune_gates():
    def options(**kwargs):
        return NS(**kwargs, sift=NS(), mapper=NS(), triangulation=NS())
    c = NS(**{name: options for name in ['ImageReaderOptions', 'FeatureExtractionOptions',
                                        'FeatureMatchingOptions', 'IncrementalPipelineOptions']})
    reader, extract, matcher, mapper = ref.configure(c)
    assert reader.camera_model == 'PINHOLE'
    assert list(map(float, reader.camera_params.split(','))) == ref.PARAMS
    assert extract.max_image_size == 1600 and extract.sift.max_num_features == 3500
    assert extract.num_threads == matcher.num_threads == mapper.num_threads == mapper.mapper.num_threads == 2
    assert not extract.use_gpu and not matcher.use_gpu and not mapper.ba_use_gpu
    assert not mapper.ba_refine_focal_length and not mapper.ba_refine_principal_point
    assert not mapper.ba_refine_extra_params and not mapper.ba_refine_sensor_from_rig
    assert not mapper.mapper.abs_pose_refine_focal_length and not mapper.mapper.abs_pose_refine_extra_params
    assert mapper.random_seed == mapper.mapper.random_seed == mapper.triangulation.random_seed == 0
    assert mapper.max_runtime_seconds == 120 and ref.TIMEOUT == 300
    assert mapper.min_model_size == 2
    assert not hasattr(mapper.mapper, 'init_min_tri_angle')  # Keep installed-version defaults.
    assert not hasattr(matcher.sift, 'max_ratio')


def test_exact_sources_admit_only_selected_hashes(synthetic_inputs):
    (synthetic_inputs / 'unselected.JPG').write_bytes(b'not admitted')
    assert ref.validate_inputs(synthetic_inputs) == synthetic_inputs


@pytest.mark.parametrize('mutation', ['missing', 'size', 'hash', 'symlink'])
def test_source_mutations_rejected(synthetic_inputs, mutation):
    p = synthetic_inputs / '10.JPG'
    old = p.read_bytes()
    if mutation == 'missing':
        p.unlink()
    elif mutation == 'size':
        p.write_bytes(old + b'x')
    elif mutation == 'hash':
        p.write_bytes(b'x' * len(old))
    else:
        other = p.with_name('hidden-source')
        p.rename(other)
        p.symlink_to(other)
    with pytest.raises(ValueError):
        ref.validate_inputs(synthetic_inputs)


def test_root_symlink_rejected(synthetic_inputs, tmp_path):
    alias = tmp_path / 'alias'
    alias.symlink_to(synthetic_inputs, target_is_directory=True)
    with pytest.raises(ValueError, match='Symlink'):
        ref.validate_inputs(alias)


@pytest.mark.parametrize('kind', ['input_repo', 'output_repo', 'existing', 'inside_input', 'missing_parent'])
def test_output_and_repository_boundaries(tmp_path, kind):
    repo, source = tmp_path / 'repo', tmp_path / 'originals'
    repo.mkdir(); source.mkdir()
    output = tmp_path / 'new'
    if kind == 'input_repo':
        source = repo / 'photos'
    elif kind == 'output_repo':
        output = repo / 'result'
    elif kind == 'existing':
        output.mkdir()
    elif kind == 'inside_input':
        output = source / 'result'
    else:
        output = tmp_path / 'absent' / 'result'
    with pytest.raises(ValueError):
        ref.validate_paths(source, output, repo)


def test_external_fresh_output_is_admitted(tmp_path):
    source, out = tmp_path / 'input', tmp_path / 'new'
    source.mkdir()
    assert ref.validate_paths(source, out, tmp_path / 'repo') == (source, out)
    assert not out.exists()


def test_json_refuses_nonfinite_measurement(tmp_path):
    with pytest.raises(ValueError):
        ref.save(tmp_path / 'bad.json', {'measurement': float('nan')})


def test_pair_summary_preserves_database_order_not_filename_order(tmp_path):
    p = tmp_path / 'db'
    with sqlite3.connect(p) as db:
        db.executescript('CREATE TABLE images(image_id INTEGER,name TEXT);'
                         'CREATE TABLE keypoints(image_id INTEGER, rows INTEGER);'
                         'CREATE TABLE matches(pair_id INTEGER, rows INTEGER);'
                         'CREATE TABLE two_view_geometries(pair_id INTEGER, rows INTEGER, config INTEGER);')
        db.executemany('INSERT INTO images VALUES (?,?)', [(1, '11.JPG'), (2, '10.JPG')])
        db.executemany('INSERT INTO keypoints VALUES (?,?)', [(1, 17), (2, 19)])
        pid = 2147483647 + 2
        db.execute('INSERT INTO matches VALUES (?,?)', (pid, 8))
        db.execute('INSERT INTO two_view_geometries VALUES (?,?,?)', (pid, 7, 2))
    result = ref.pair_summary(p)
    assert result['features'] == {'11.JPG': 17, '10.JPG': 19}
    assert result['pairs'] == [{'database_image_ids': [1, 2], 'images': ['11.JPG', '10.JPG'],
                                'raw_matches': 8, 'verified_matches': 7, 'configuration': 2}]


def test_track_connectivity_does_not_call_chains_cycles():
    edges = {('a-b', 0, 1): {(1, 2), (4, 5)}, ('a-c', 0, 2): {(1, 3)}, ('b-c', 1, 2): {(2, 3), (5, 6)}}
    assert ref.connectivity([[1, 2, 3], [4, 5, 6]], edges) == {
        'verified_edges_per_final_track': {3: 1, 2: 1}, 'missing_direct_edges': {'a-c': 1}}


def fake_model(*, focal=None, depth=2., rotation=None):
    xyz = np.array([.1, .2, depth])
    camera = NS(params=np.array(ref.PARAMS if focal is None else [focal, focal, 2136, 1424]))
    pose = NS(rotation=NS(matrix=lambda: np.eye(3) if rotation is None else rotation), translation=np.zeros(3))
    xy = xyz[:2] / xyz[2] * ref.FOCAL + np.array(ref.PARAMS[2:])
    point2 = NS(has_point3D=lambda: True, point3D_id=1, xy=xy)
    im = NS(name='10.JPG', camera_id=1, cam_from_world=lambda: pose,
            points2D=[point2, point2], projection_center=lambda: np.zeros(3))
    p3 = NS(xyz=xyz, track=NS(length=lambda: 2))
    return NS(reg_image_ids=lambda: [1], image=lambda _: im, camera=lambda _: camera,
              point3D=lambda _: p3, points3D={1: p3}, num_points3D=lambda: 1)


def test_independent_projection_and_duplicate_xy_accounting(tmp_path):
    result = ref.measure(fake_model(), tmp_path / 'unused', np)
    assert result['mean_reprojection_px'] == 0
    assert result['observations'] == result['positive_depth_observations'] == 2
    assert result['per_image'] == {'10.JPG': {'observations': 2, 'unique_xy': 1}}
    assert result['poses'][0]['world_to_camera_t'] == result['poses'][0]['camera_center'] == [0, 0, 0]


def test_negative_depth_is_not_hidden_by_small_reprojection(tmp_path):
    result = ref.measure(fake_model(depth=-2), tmp_path / 'unused', np)
    assert result['mean_reprojection_px'] == 0
    assert result['positive_depth_observations'] == 0


@pytest.mark.parametrize('model', [fake_model(focal=999), fake_model(rotation=np.zeros((3, 3)))])
def test_changed_intrinsics_or_invalid_rotations_fail(model, tmp_path):
    with pytest.raises(ValueError):
        ref.measure(model, tmp_path / 'unused', np)


@pytest.mark.parametrize('exit_code', [0, 1, -9])
def test_supervisor_preserves_failure_exit(tmp_path, monkeypatch, exit_code):
    captured = {}
    def popen(command, **kwargs):
        captured.update(command=command, **kwargs)
        return NS(wait=lambda **_: exit_code, pid=123)
    monkeypatch.setattr(ref.subprocess, 'Popen', popen)
    assert ref.run_child(tmp_path / 'input', tmp_path) == exit_code
    result = json.loads((tmp_path / 'execution.json').read_text())
    assert result['status'] == ('COMPLETED' if exit_code == 0 else 'FAILED')
    assert captured['start_new_session'] is True
    assert captured['env']['OMP_NUM_THREADS'] == '2'
    assert captured['env']['OPENBLAS_NUM_THREADS'] == '1'


def test_timeout_kills_process_group_and_cannot_pass(tmp_path, monkeypatch):
    calls = []
    clock = [0.]
    monkeypatch.setattr(ref.time, 'monotonic', lambda: clock[0])
    def wait(**kwargs):
        if kwargs:
            assert kwargs['timeout'] == .25
            clock[0] = 301.
            raise subprocess.TimeoutExpired('synthetic', .25)
        return -9
    monkeypatch.setattr(ref.subprocess, 'Popen', lambda *_, **__: NS(wait=wait, pid=1234, returncode=None))
    monkeypatch.setattr(ref.os, 'killpg', lambda pid, sig: calls.append((pid, sig)))
    assert ref.run_child(tmp_path / 'input', tmp_path) == 124
    assert calls == [(1234, ref.signal.SIGKILL)]
    assert json.loads((tmp_path / 'execution.json').read_text())['status'] == 'TIMEOUT'


@pytest.mark.parametrize('version,cuda,numpy_version', [
    ('4.2.0', False, '2.2.6'), ('4.2.1', True, '2.2.6'), ('4.2.1', False, '2.3.0')])
def test_tool_version_or_cuda_drift_fails(version, cuda, numpy_version):
    with pytest.raises(ValueError):
        ref.require_tool_versions(NS(__version__=version, has_cuda=cuda), NS(__version__=numpy_version))


def test_frozen_runtime_versions_admitted():
    ref.require_tool_versions(NS(__version__='4.2.1', has_cuda=False), NS(__version__='2.2.6'))


@pytest.mark.parametrize('kind', ['keyboard', 'sigint', 'sigterm', 'during_spawn'])
def test_interruption_only_kills_owned_group_reaps_and_restores_handlers(tmp_path, monkeypatch, kind):
    handlers = {s: ref.signal.getsignal(s) for s in (ref.signal.SIGINT, ref.signal.SIGTERM)}
    calls = []
    child = NS(pid=2468, returncode=None)
    def wait(**kwargs):
        calls.append(('wait', bool(kwargs)))
        if kwargs:
            if kind == 'keyboard':
                raise KeyboardInterrupt
            signum = ref.signal.SIGINT if kind == 'sigint' else ref.signal.SIGTERM
            ref.signal.getsignal(signum)(signum, None)
            raise subprocess.TimeoutExpired('synthetic', .25)
        # A repeat signal during cleanup must not prevent reaping.
        ref.signal.getsignal(ref.signal.SIGTERM)(ref.signal.SIGTERM, None)
        child.returncode = -9
        return -9
    child.wait = wait
    def popen(*args, **kwargs):
        assert kwargs['start_new_session'] is True
        if kind == 'during_spawn':
            ref.signal.getsignal(ref.signal.SIGTERM)(ref.signal.SIGTERM, None)
        return child
    monkeypatch.setattr(ref.subprocess, 'Popen', popen)
    monkeypatch.setattr(ref.os, 'killpg', lambda pid, sig: calls.append(('killpg', pid, sig)))
    expected = 130 if kind in ('keyboard', 'sigint') else 143
    assert ref.run_child(tmp_path / 'input', tmp_path) == expected
    assert [x for x in calls if x[0] == 'killpg'] == [('killpg', child.pid, ref.signal.SIGKILL)]
    assert child.returncode == -9 and calls[-1] == ('wait', False)
    assert {s: ref.signal.getsignal(s) for s in handlers} == handlers
    assert json.loads((tmp_path / 'execution.json').read_text())['status'] == 'INTERRUPTED'


def test_cleanup_does_not_signal_an_already_reaped_pid(monkeypatch):
    calls = []
    monkeypatch.setattr(ref.os, 'killpg', lambda *args: pytest.fail('Reaped PID must not be signaled'))
    ref.stop_owned_child(NS(pid=2468, returncode=0, wait=lambda: calls.append('reaped')))
    assert calls == ['reaped']


def test_disappearing_owned_child_is_still_reaped(monkeypatch):
    calls = []
    def gone(*_):
        raise ProcessLookupError
    monkeypatch.setattr(ref.os, 'killpg', gone)
    ref.stop_owned_child(NS(pid=2468, returncode=None, wait=lambda: calls.append('reaped')))
    assert calls == ['reaped']


def test_spawn_failure_restores_handlers_without_signaling_any_pid(tmp_path, monkeypatch):
    handlers = {s: ref.signal.getsignal(s) for s in (ref.signal.SIGINT, ref.signal.SIGTERM)}
    def fail(*args, **kwargs):
        raise OSError('synthetic spawn failure')
    monkeypatch.setattr(ref.subprocess, 'Popen', fail)
    monkeypatch.setattr(ref.os, 'killpg', lambda *args: pytest.fail('No owned PID exists'))
    with pytest.raises(OSError, match='synthetic spawn failure'):
        ref.run_child(tmp_path / 'input', tmp_path)
    assert {s: ref.signal.getsignal(s) for s in handlers} == handlers


@pytest.mark.skipif(ref.sys.platform != 'linux', reason='Linux supervisor/resource contract')
@pytest.mark.parametrize('signum', [ref.signal.SIGINT, ref.signal.SIGTERM])
def test_real_synthetic_worker_is_killed_and_reaped_after_signal(tmp_path, signum):
    import time
    worker_pid_file = tmp_path / 'worker.pid'
    reaped_file = tmp_path / 'reaped.json'
    # The closed generated stdlib fixture loads only hash-verified benchmark
    # bytes and replaces its exact worker command; no production native run is
    # stripped of instrumentation. All interruption/cleanup assertions remain.
    command, fixture_env = signal_supervisor(tmp_path)
    supervisor = subprocess.Popen(command, env=fixture_env, start_new_session=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    worker_pid = None
    try:
        deadline = time.monotonic() + 5
        while not worker_pid_file.exists() and supervisor.poll() is None and time.monotonic() < deadline:
            time.sleep(.01)
        assert worker_pid_file.exists(), supervisor.communicate(timeout=1)
        worker_pid = int(worker_pid_file.read_text())
        supervisor.send_signal(signum)
        stdout, stderr = supervisor.communicate(timeout=5)
        assert supervisor.returncode == 128 + signum, (stdout, stderr)
        assert json.loads(reaped_file.read_text())['returncode'] == -ref.signal.SIGKILL
        assert json.loads((tmp_path / 'execution.json').read_text())['status'] == 'INTERRUPTED'
        assert not Path(f'/proc/{worker_pid}').exists()
    finally:
        if supervisor.poll() is None:
            # Only this held Popen supervisor identity is signaled. The synthetic
            # worker self-exits after three seconds if a regression orphans it.
            supervisor.terminate()
            try:
                supervisor.wait(timeout=5)
            except subprocess.TimeoutExpired:
                supervisor.kill()
                supervisor.wait(timeout=5)
        # Never signal the worker using a pid-file or /proc existence check: its
        # leader may already have been reaped and the numeric PID reused.


def test_signal_during_os_reap_finishes_bookkeeping_before_cleanup(tmp_path, monkeypatch):
    events = []
    child = NS(pid=2468, returncode=None)
    def wait(**kwargs):
        if kwargs:
            events.append('kernel_has_reaped')
            ref.signal.getsignal(ref.signal.SIGTERM)(ref.signal.SIGTERM, None)
            # The flag-only handler must return, not unwind wait() with stale
            # returncode=None after the OS has already released the numeric PID.
            events.append('signal_handler_returned')
            child.returncode = 0
            events.append('returncode_recorded')
        else:
            assert child.returncode == 0
            events.append('already_reaped')
        return child.returncode
    child.wait = wait
    monkeypatch.setattr(ref.subprocess, 'Popen', lambda *_, **__: child)
    monkeypatch.setattr(ref.os, 'killpg', lambda *args: pytest.fail('Reaped PID must never be signaled'))
    assert ref.run_child(tmp_path / 'input', tmp_path) == 143
    assert events == ['kernel_has_reaped', 'signal_handler_returned', 'returncode_recorded', 'already_reaped']
    assert json.loads((tmp_path / 'execution.json').read_text())['status'] == 'INTERRUPTED'


def test_wait_exception_cleans_owned_group_and_restores_handlers(tmp_path, monkeypatch):
    handlers = {s: ref.signal.getsignal(s) for s in (ref.signal.SIGINT, ref.signal.SIGTERM)}
    calls = []
    child = NS(pid=8642, returncode=None)
    def wait(**kwargs):
        if kwargs:
            raise RuntimeError('synthetic wait failure')
        calls.append('reaped')
        child.returncode = -9
        return -9
    child.wait = wait
    monkeypatch.setattr(ref.subprocess, 'Popen', lambda *_, **__: child)
    monkeypatch.setattr(ref.os, 'killpg', lambda pid, sig: calls.append((pid, sig)))
    with pytest.raises(RuntimeError, match='synthetic wait failure'):
        ref.run_child(tmp_path / 'input', tmp_path)
    assert calls == [(8642, ref.signal.SIGKILL), 'reaped']
    assert {s: ref.signal.getsignal(s) for s in handlers} == handlers
