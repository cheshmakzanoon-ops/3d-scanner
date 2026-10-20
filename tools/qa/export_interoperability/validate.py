"""Fail-closed independent export QA. Exit 0 PASS, 1 FAIL, 2 NOT_RUN.

Trimesh and Khronos are external, pinned desktop dependencies, not app parsers.
Blender imports then writes the original format in a temporary private directory.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import numpy as np

MAX_BYTES = 64 * 1024 * 1024
HERE = Path(__file__).resolve().parent


class Invalid(ValueError):
    pass


def require(value, message):
    if not value:
        raise Invalid(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate JSON key: ' + key)
        result[key] = value
    return result


def bounded(path):
    require(path.is_file() and 0 < path.stat().st_size <= MAX_BYTES, 'missing/oversized/empty file')
    return path.read_bytes()


def glb_document(path):
    raw = bounded(path)
    require(len(raw) >= 20, 'truncated GLB')
    magic, version, total = struct.unpack_from('<4sII', raw)
    require((magic, version, total) == (b'glTF', 2, len(raw)), 'GLB header/length')
    chunks, offset = [], 12
    while offset < len(raw):
        require(offset + 8 <= len(raw), 'truncated chunk header')
        length, kind = struct.unpack_from('<II', raw, offset)
        offset += 8
        require(length % 4 == 0 and length <= len(raw) - offset, 'chunk bounds/alignment')
        chunks.append((kind, raw[offset:offset + length]))
        offset += length
    require([c[0] for c in chunks] in ([0x4e4f534a], [0x4e4f534a, 0x004e4942]), 'unsupported chunk order')
    doc = json.loads(chunks[0][1], object_pairs_hook=unique_object,
                     parse_constant=lambda x: (_ for _ in ()).throw(Invalid('nonfinite JSON')))
    require(doc.get('asset', {}).get('version') == '2.0', 'asset version')
    require(not any('uri' in item for key in ('buffers', 'images') for item in doc.get(key, [])), 'external GLB resource')
    modes = [p.get('mode', 4) for mesh in doc.get('meshes', []) for p in mesh.get('primitives', [])]
    require(modes and all(m in (0, 4) for m in modes), 'unsupported/nonexistent representation')
    extras = doc.get('extras', {})
    if 'points' in extras.get('representation', ''):
        require(all(m == 0 for m in modes), 'point metadata on triangles')
    if extras.get('scale') in ('metres', 'meters', 'metric'):
        require(bool(extras.get('scaleReference')), 'metric claim without measurement provenance')
    return doc


def sidecar(root, text):
    require('\\' not in text and ':' not in text and not text.startswith('/'), 'unsafe sidecar')
    target = (root / text).resolve()
    require(target.is_relative_to(root.resolve()), 'sidecar escapes package')
    bounded(target)
    return target


def preflight(path):
    suffix = path.suffix.lower()
    bounded(path)
    if suffix == '.glb':
        return glb_document(path)
    require(suffix in ('.obj', '.ply', '.stl'), 'unsupported export format')
    if suffix == '.obj':
        for line in path.read_text().splitlines():
            parts = line.split()
            if parts and parts[0] == 'mtllib':
                require(len(parts) == 2, 'unsupported material filename')
                material = sidecar(path.parent, parts[1])
                for mline in material.read_text().splitlines():
                    mp = mline.split()
                    if mp and mp[0].lower().startswith('map_'):
                        require(len(mp) == 2, 'unsupported texture options')
                        sidecar(material.parent, mp[1])
    return None


def khronos(path):
    root = os.environ.get('RF_GLTF_VALIDATOR')
    if not root or not shutil.which('node'):
        raise ModuleNotFoundError('node and RF_GLTF_VALIDATOR package required')
    run = subprocess.run(['node', str(HERE / 'khronos.cjs'), str(path)],
                         capture_output=True, text=True, timeout=45)
    require(run.returncode == 0, 'Khronos execution: ' + run.stderr[-1000:])
    report = json.loads(run.stdout)
    require(report['issues']['numErrors'] == 0, 'Khronos: ' + json.dumps(report['issues']))
    return {'version': '2.0.0-dev.3.10', 'errors': 0,
            'warnings': report['issues']['numWarnings']}


def read_independent(path):
    import trimesh
    if trimesh.__version__ != '4.5.3':
        raise ModuleNotFoundError('trimesh==4.5.3 required')
    scene = trimesh.load(str(path), process=False, force='scene')
    vertices, faces, textured, colors, samples = [], 0, 0, 0, []
    for node in scene.graph.nodes_geometry:
        transform, name = scene.graph[node]
        geometry = scene.geometry[name]
        points = trimesh.transform_points(geometry.vertices, transform)
        require(len(points) > 0 and np.isfinite(points).all(), 'empty/nonfinite geometry')
        vertices.append(points)
        indices = getattr(geometry, 'faces', np.empty((0, 3), dtype=int))
        require(indices.size == 0 or (indices.min() >= 0 and indices.max() < len(points)), 'index bounds')
        if len(indices):
            triangles = points[indices]
            cross = np.cross(triangles[:, 1] - triangles[:, 0], triangles[:, 2] - triangles[:, 0])
            require(np.all(np.linalg.norm(cross, axis=1) > 1e-12), 'degenerate triangle')
            normals = np.asarray(geometry.vertex_normals)
            require(normals.shape == (len(points), 3) and np.isfinite(normals).all(), 'invalid normals')
            normals = normals @ np.linalg.inv(transform[:3, :3])
            dots = np.einsum('fi,fvi->fv', cross, normals[indices])
            require(np.all(dots > 0), 'normal/winding disagreement')
            faces += len(indices)
        visual = getattr(geometry, 'visual', None)
        if visual is not None and visual.kind in ('vertex', 'face'):
            colors += len(points)
        if visual is not None and visual.kind == 'texture':
            uv = np.asarray(visual.uv)
            require(uv.shape == (len(points), 2) and np.isfinite(uv).all(), 'invalid UVs')
            material = visual.material
            image = getattr(material, 'baseColorTexture', None)
            if image is None:
                image = getattr(material, 'image', None)
            require(image is not None, 'missing decoded texture')
            image = np.asarray(image.convert('RGB'))
            # Trimesh UV origin is bottom-left; decoded image origin is top-left.
            xy = np.rint(np.clip(uv, 0, 1) * [image.shape[1]-1, image.shape[0]-1]).astype(int)
            for point, (x, y) in zip(points, xy):
                samples.append({'point': point.tolist(), 'rgb': image[image.shape[0]-1-y, x].tolist()})
            textured += len(indices)
    require(vertices, 'reader produced no geometry')
    points = np.concatenate(vertices)
    return {'reader': 'trimesh 4.5.3', 'vertices': len(points), 'triangles': faces,
            'representation': 'triangles' if faces else 'points',
            'bounds': [points.min(axis=0).tolist(), points.max(axis=0).tolist()],
            'textured_triangles': textured, 'colored_vertices': colors,
            'texture_samples': samples, '_points': points}


def check_expectations(summary, expected):
    for key in ('representation', 'triangles', 'textured_triangles'):
        if key in expected:
            require(summary[key] == expected[key], 'expected ' + key)
    if 'bounds' in expected:
        require(np.allclose(summary['bounds'], expected['bounds'], atol=1e-5, rtol=1e-6), 'bounds/scale/axes mismatch')
    for sample in expected.get('texture_samples', []):
        candidates = [s for s in summary['texture_samples'] if np.allclose(s['point'], sample['point'], atol=1e-5)]
        require(candidates and any(np.allclose(s['rgb'], sample['rgb'], atol=2) for s in candidates), 'texture orientation/color mismatch')


def roundtrip(path, before, expected):
    if not shutil.which('blender'):
        raise ModuleNotFoundError('Blender 4.3 required')
    require(before['triangles'] > 0, 'Blender mesh round-trip does not qualify point clouds')
    with tempfile.TemporaryDirectory(prefix='rf-interop-') as tmp:
        target = Path(tmp) / ('roundtrip' + path.suffix.lower())
        run = subprocess.run(['blender', '--background', '--factory-startup', '--threads', '1',
                              '--python-exit-code', '1', '--python', str(HERE / 'blender_roundtrip.py'),
                              '--', str(path.resolve()), str(target)], capture_output=True, text=True, timeout=120)
        require(run.returncode == 0 and target.is_file(), 'Blender failed: ' + run.stdout[-1500:] + run.stderr[-500:])
        preflight(target)
        if path.suffix.lower() == '.glb':
            khronos(target)
        after = read_independent(target)
        require(after['representation'] == before['representation'] and after['triangles'] == before['triangles'], 'round-trip representation/count')
        # Compare every unique location, so importer vertex welding is permitted.
        from scipy.spatial import cKDTree
        a, b = before['_points'], after['_points']
        error = max(cKDTree(a).query(b)[0].max(), cKDTree(b).query(a)[0].max())
        require(error <= 1e-5, 'round-trip coordinate error')
        check_expectations(after, expected)
        return {'tool': 'Blender 4.3', 'status': 'PASS', 'max_coordinate_error': float(error),
                'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}


def validate(path, expected=None, target_tool=False):
    expected = expected or {}
    report = {'schema': 1, 'status': 'FAIL', 'path': str(path), 'target_tool': {'status': 'NOT_RUN'}}
    try:
        preflight(path)
        report['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        if path.suffix.lower() == '.glb':
            report['khronos'] = khronos(path)
        summary = read_independent(path)
        check_expectations(summary, expected)
        if target_tool:
            report['target_tool'] = roundtrip(path, summary, expected)
        report['geometry'] = {k: v for k, v in summary.items() if not k.startswith('_')}
        report['status'] = 'PASS'
    except (ModuleNotFoundError, FileNotFoundError) as exc:
        report.update(status='NOT_RUN', error=str(exc))
    except (ValueError, KeyError, IndexError, TypeError, OSError, struct.error, subprocess.SubprocessError) as exc:
        report['error'] = str(exc)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('asset', type=Path)
    parser.add_argument('--expect', type=Path)
    parser.add_argument('--roundtrip', action='store_true')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = validate(args.asset, json.loads(args.expect.read_text()) if args.expect else None, args.roundtrip)
    serialized = json.dumps(report, indent=2, allow_nan=False) + '\n'
    if args.output:
        args.output.write_text(serialized)
    print(serialized, end='')
    return {'PASS': 0, 'FAIL': 1, 'NOT_RUN': 2}[report['status']]


if __name__ == '__main__':
    raise SystemExit(main())
