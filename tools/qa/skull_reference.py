#!/usr/bin/env python3
"""Offline, bounded independent sparse reference; never a scanner acceptance gate."""
from __future__ import annotations
import argparse
import collections
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import signal
import sqlite3
import subprocess
import sys
import time

VERSION = '4.2.1'
NUMPY_VERSION = '2.2.6'
WHEEL_SHA256 = '711cef7bd0d30326a5d75967043371c98667f24db305b8cd5cd19339d02a7073'
SOURCE_COMMIT = '0f31c229ac289a615b3ae981a5f584856bb24c47'
SOURCE_URL = 'https://gitlab.com/photogrammetry-test-sets/skull-cameramoves-flash-no-background'
INPUTS = {
    '10.JPG': (3086546, '9f034fe4bac3fa57ce6df66f949db69709b27e16e25ee31dd4358233d441dc5e'),
    '11.JPG': (3124295, 'dcec8cd3c1036abc0e2e9a1231c207367098bedbddcc39c25d3777861547db6c'),
    '12.JPG': (3138412, 'f771973ad8f3092d6e869d29c4cb88a0ad67d30c6cf03dfe9dce70c98cdd9bb1'),
    'README.md': (1205, '01e8bfdc1aafda11e61fb13fb6b05b3701349f3820a9f04fc0c744700e2363e8'),
}
IMAGES = tuple(name for name in INPUTS if name.endswith('.JPG'))
FOCAL = 88 * math.hypot(4272, 2848) / math.hypot(36, 24)
PARAMS = [FOCAL, FOCAL, 2136.0, 1424.0]
TIMEOUT = 300
CLASSIFICATION = 'INDEPENDENT_CPU_SPARSE_REFERENCE_NOT_SCANNER_PASS_NOT_GROUND_TRUTH'
ATTRIBUTION = ('"Photogrammetry Test Set: Skull - Cameramoves - Flash - No Background" '
               '#10, #11, #12 by alansartlog (https://alansartlog.com), CC BY4.0 '
               '(https://creativecommons.org/licenses/by/4.0/).\n' + SOURCE_URL + '\n'
               'Source commit: ' + SOURCE_COMMIT + '\n'
               'Resampling, keypoints, camera estimates and sparse reconstruction are changed '
               'derivatives, not author-provided models; no endorsement implied.\n')


def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + '\n')


def canonical(path):
    path = Path(os.path.abspath(path))
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('Symlink paths are not admitted')
    return path.resolve()


def validate_inputs(source):
    source = canonical(source)
    if not source.is_dir():
        raise ValueError('Input directory missing')
    for name, (size, expected) in INPUTS.items():
        p = source / name
        if p.is_symlink() or not p.is_file() or p.stat().st_size != size or sha256(p) != expected:
            raise ValueError('Pinned input/license bytes do not match: ' + name)
    return source


def validate_paths(source, output, repo=None, existing_output=False):
    source, output = canonical(source), canonical(output)
    repo = canonical(repo or Path(__file__).resolve().parents[2])
    if any(p == repo or repo in p.parents for p in (source, output)):
        raise ValueError('Original inputs and outputs must stay outside the repository')
    if (output.exists() and not existing_output) or not output.parent.is_dir():
        raise ValueError('Output must be fresh, with an existing parent directory')
    if output == source or source in output.parents:
        raise ValueError('Output cannot modify the original input directory')
    return source, output


def pair_summary(database):
    with sqlite3.connect(f'file:{database}?mode=ro', uri=True) as db:
        names = dict(db.execute('SELECT image_id,name FROM images'))
        features = {names[i]: n for i, n in db.execute('SELECT image_id,rows FROM keypoints')}
        pairs = []
        for pid, n, config in db.execute('SELECT pair_id,rows,config FROM two_view_geometries ORDER BY pair_id'):
            a, b = divmod(pid, 2147483647)
            raw = db.execute('SELECT rows FROM matches WHERE pair_id=?', (pid,)).fetchone()
            pairs.append({'database_image_ids': [a, b], 'images': [names[a], names[b]],
                          'raw_matches': raw[0] if raw else 0, 'verified_matches': n,
                          'configuration': config})
    return {'features': features, 'pairs': pairs}


def connectivity(rows, edges):
    histogram = collections.Counter()
    missing = collections.Counter()
    for row in rows:
        found = {pair: (row[a], row[b]) in pairs for (pair, a, b), pairs in edges.items()}
        histogram[sum(found.values())] += 1
        missing.update(pair for pair, present in found.items() if not present)
    return {'verified_edges_per_final_track': dict(histogram), 'missing_direct_edges': dict(missing)}


def measure(rec, database, np):
    errors, depths, poses, unique = [], [], [], {}
    ids = sorted(rec.reg_image_ids(), key=lambda i: rec.image(i).name)
    for iid in ids:
        im = rec.image(iid)
        camera, pose = rec.camera(im.camera_id), im.cam_from_world()
        R, t = pose.rotation.matrix(), pose.translation
        if not np.array_equal(camera.params, PARAMS):
            raise ValueError('Fixed camera parameters changed')
        if not np.allclose(R.T @ R, np.eye(3), atol=1e-12) or abs(np.linalg.det(R) - 1) > 1e-12:
            raise ValueError('Invalid camera rotation')
        xy = []
        for p in im.points2D:
            if not p.has_point3D():
                continue
            x = R @ rec.point3D(p.point3D_id).xyz + t
            depths.append(float(x[2]))
            predicted = x[:2] / x[2] * np.array(PARAMS[:2]) + np.array(PARAMS[2:])
            errors.append(float(np.linalg.norm(predicted - p.xy)))
            xy.append(tuple(p.xy))
        unique[im.name] = {'observations': len(xy), 'unique_xy': len(set(xy))}
        poses.append({'image': im.name, 'image_id': iid, 'camera_params': camera.params.tolist(),
                      'world_to_camera_R': R.tolist(), 'world_to_camera_t': t.tolist(),
                      'camera_center': im.projection_center().tolist()})
    tracks = collections.Counter(p.track.length() for p in rec.points3D.values())
    result = {'registered_images': [rec.image(i).name for i in ids], 'num_points3D': rec.num_points3D(),
              'track_length_histogram': dict(tracks), 'per_image': unique, 'poses': poses,
              'observations': len(errors), 'positive_depth_observations': sum(z > 0 for z in depths),
              'mean_reprojection_px': float(np.mean(errors)) if errors else None,
              'median_reprojection_px': float(np.median(errors)) if errors else None,
              'p95_reprojection_px': float(np.quantile(errors, .95)) if errors else None}
    if len(ids) == 3:
        edges = {}
        with sqlite3.connect(f'file:{database}?mode=ro', uri=True) as db:
            for a, b in ((0, 1), (0, 2), (1, 2)):
                lo, hi = sorted((ids[a], ids[b]))
                row = db.execute('SELECT rows,cols,data FROM two_view_geometries WHERE pair_id=?',
                                 (lo * 2147483647 + hi,)).fetchone()
                indices = np.frombuffer(row[2], np.uint32).reshape(row[:2]) if row else np.empty((0, 2))
                if ids[a] != lo:
                    indices = indices[:, ::-1]
                edges[(rec.image(ids[a]).name + '-' + rec.image(ids[b]).name, a, b)] = set(map(tuple, indices))
        rows = []
        for p in rec.points3D.values():
            by_image = {e.image_id: e.point2D_idx for e in p.track.elements}
            if all(i in by_image for i in ids):
                rows.append([by_image[i] for i in ids])
        result['three_view_connectivity'] = connectivity(rows, edges)
    return result


def configure(c):
    reader = c.ImageReaderOptions(camera_model='PINHOLE', camera_params=','.join(map(str, PARAMS)))
    extract = c.FeatureExtractionOptions(num_threads=2, max_image_size=1600, use_gpu=False)
    extract.sift.max_num_features = 3500
    matching = c.FeatureMatchingOptions(num_threads=2, use_gpu=False)
    mapper = c.IncrementalPipelineOptions(num_threads=2, random_seed=0, max_runtime_seconds=120,
        ba_use_gpu=False, ba_refine_focal_length=False, ba_refine_principal_point=False,
        ba_refine_extra_params=False, ba_refine_sensor_from_rig=False, min_model_size=2, max_num_models=3)
    mapper.mapper.num_threads = 2
    mapper.mapper.random_seed = 0
    mapper.mapper.abs_pose_refine_focal_length = False
    mapper.mapper.abs_pose_refine_extra_params = False
    mapper.triangulation.random_seed = 0
    return reader, extract, matching, mapper


def require_tool_versions(c, np):
    if c.__version__ != VERSION or c.has_cuda or np.__version__ != NUMPY_VERSION:
        raise ValueError('Requires separately installed CPU PyCOLMAP4.2.1 and NumPy2.2.6')


def worker(source, output):
    import resource  # Linux-only accounting and process-group supervisor.
    resource.setrlimit(resource.RLIMIT_AS, (4 * 1024**3, 4 * 1024**3))
    resource.setrlimit(resource.RLIMIT_FSIZE, (32 * 1024**2, 32 * 1024**2))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    import numpy as np
    import pycolmap as c
    require_tool_versions(c, np)
    source, output = validate_paths(source, output, existing_output=True)
    if not output.is_dir() or {p.name for p in output.iterdir()} != {'private.log'}:
        raise ValueError('Worker requires a fresh supervisor-owned output directory')
    source = validate_inputs(source)
    images = output / 'images'
    images.mkdir(mode=0o700)
    for name in IMAGES:
        shutil.copyfile(source / name, images / name)
    shutil.copyfile(source / 'README.md', output / 'source-license-readme.md')
    (output / 'attribution.txt').write_text(ATTRIBUTION)
    reader, extract, matching, mapper = configure(c)
    save(output / 'plan.json', {'classification': CLASSIFICATION, 'version': VERSION,
        'numpy_version': np.__version__, 'input_hashes': {n: h for n, (_, h) in INPUTS.items()},
        'source_commit': SOURCE_COMMIT, 'runner_sha256': sha256(Path(__file__)),
        'camera_params_colmap_corner_coordinates': PARAMS, 'image_size': [4272, 2848],
        'estimated_focal35_mm': 88, 'camera_mode': 'SINGLE', 'reader': reader.summary(),
        'extraction': extract.summary(), 'matching': matching.summary(), 'mapper': mapper.summary(),
        'outer_timeout_seconds': TIMEOUT, 'address_limit_bytes': 4 * 1024**3,
        'file_limit_bytes': 32 * 1024**2, 'wheel_sha256_for_provenance_check': WHEEL_SHA256})
    database = output / 'database.db'
    started = time.monotonic()
    c.extract_features(database, images, image_names=list(IMAGES), camera_mode=c.CameraMode.SINGLE,
                       reader_options=reader, extraction_options=extract, device=c.Device.cpu)
    with sqlite3.connect(database) as db:
        camera_ids = {r[0] for r in db.execute('SELECT camera_id FROM cameras')}
    mapper.constant_cameras = camera_ids
    mapper.mapper.constant_cameras = camera_ids
    save(output / 'effective-mapper.json', {'options': mapper.summary()})
    c.match_exhaustive(database, matching_options=matching, device=c.Device.cpu)
    pairs = pair_summary(database)
    save(output / 'matching.json', pairs)
    models = c.incremental_mapping(database, images, output / 'sparse', options=mapper)
    measured = []
    for idx, rec in sorted(models.items()):
        rec.write_text(output / 'sparse' / str(idx))
        rec.export_PLY(output / 'sparse' / str(idx) / 'sparse.ply')
        measured.append(measure(c.Reconstruction(output / 'sparse' / str(idx)), database, np))
    validate_inputs(source)
    for name in IMAGES:
        if sha256(images / name) != INPUTS[name][1]:
            raise ValueError('Staged original changed')
    complete = any(sorted(m['registered_images']) == sorted(IMAGES)
                   and m['observations'] > 0 and m['positive_depth_observations'] == m['observations']
                   for m in measured)
    save(output / 'receipt.json', {'classification': CLASSIFICATION, 'models': measured, 'matching': pairs,
         'seconds': time.monotonic() - started, 'peak_rss_kib_linux': resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
         'original_hashes_verified_before_after': True, 'three_view_reference_registered': complete,
         'dense_mesh_not_run': True, 'scanner_not_run': True})
    return 0 if complete else 1


def stop_owned_child(child):
    """Only the Popen-created session leader is eligible; never accept an external PID."""
    if child is None:
        return
    # An unreaped child reserves this PID. Do not signal a potentially reused group
    # after wait() has already reaped the leader. This worker spawns no subprocesses.
    if child.returncode is None:
        try:
            os.killpg(child.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass  # The child exited concurrently; wait still reaps our child.
    child.wait()


def run_child(source, output):
    env = dict(os.environ, OMP_NUM_THREADS='2', OPENBLAS_NUM_THREADS='1', MKL_NUM_THREADS='1')
    command = [sys.executable, str(Path(__file__).resolve()), '--input', str(source), '--output', str(output), '--worker']
    child, pending_signal = None, None
    previous = {}

    def interrupt(signum, _frame):
        nonlocal pending_signal
        pending_signal = pending_signal or signum
        # Never raise inside Popen.wait(): the OS may already have reaped the
        # leader before Popen assigns returncode. Let that bookkeeping finish.

    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous[signum] = signal.signal(signum, interrupt)
        with (output / 'private.log').open('wb') as log:
            try:
                child = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                deadline = time.monotonic() + TIMEOUT
                while True:
                    if pending_signal is not None:
                        stop_owned_child(child)
                        code, status = 128 + pending_signal, 'INTERRUPTED'
                        break
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        stop_owned_child(child)
                        code, status = 124, 'TIMEOUT'
                        break
                    try:
                        code = child.wait(timeout=min(.25, remaining))
                    except subprocess.TimeoutExpired:
                        continue
                    # wait() has now finished its reap/returncode bookkeeping.
                    if pending_signal is not None:
                        stop_owned_child(child)
                        code, status = 128 + pending_signal, 'INTERRUPTED'
                    else:
                        status = 'COMPLETED' if code == 0 else 'FAILED'
                    break
            except KeyboardInterrupt:
                stop_owned_child(child)
                code, status = 130, 'INTERRUPTED'
            except BaseException:
                stop_owned_child(child)
                raise
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)
    save(output / 'execution.json', {'exit_code': code, 'status': status, 'timeout_seconds': TIMEOUT,
                                    'classification': CLASSIFICATION})
    return code


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input', type=Path, required=True, help='External directory with pinned originals and README.md')
    p.add_argument('--output', type=Path, required=True, help='Fresh external private evidence directory')
    p.add_argument('--worker', action='store_true', help=argparse.SUPPRESS)
    args = p.parse_args(argv)
    if sys.platform != 'linux':
        p.error('Linux required for resource accounting and bounded process groups')
    os.umask(0o077)
    if args.worker:
        return worker(args.input, args.output)
    source, output = validate_paths(args.input, args.output)
    validate_inputs(source)
    output.mkdir(mode=0o700)
    return run_child(source, output)


if __name__ == '__main__':
    raise SystemExit(main())
