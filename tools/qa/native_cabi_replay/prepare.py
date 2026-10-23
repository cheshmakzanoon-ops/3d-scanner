"""Prepare exact existing fixtures without loading or invoking the scanner."""
import hashlib
import importlib.util
import json
import re
import struct
import sys
from pathlib import Path


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class Captured(Exception):
    pass


class Capture:
    def __getattr__(self, method):
        def call(*args, **kwargs):
            self.call = method, args, kwargs
            raise Captured()
        return call


def validate_arrays(mode, arrays):
    import numpy as np
    if mode == 0:
        rows = np.asarray(arrays[0])
        require(rows.ndim == 2 and rows.shape[1] == 7 and 8 <= len(rows) <= 1000,
                'Sparse fixture shape changed')
        require(np.isfinite(rows).all(), 'Nonfinite sparse fixture')
        return
    if mode == 3:
        require(len(arrays) == 3, 'Calibrated fixture buffers changed')
        rows, observed, domains = map(np.asarray, arrays)
        require(rows.shape == (194, 7) and observed.shape == (194, 4) and domains.shape == (2, 11),
                'Calibrated fixture shape changed')
        require(all(np.isfinite(a).all() for a in (rows, observed, domains)), 'Nonfinite calibrated fixture')
        for camera, k in enumerate(domains):
            require(k[0] > 0 and k[1] > 0 and all(3 <= v <= 32768 and v == int(v) for v in k[9:]),
                    'Invalid calibrated domain')
            pixels = observed[:, camera * 2:camera * 2 + 2]
            require(((pixels >= 0) & (pixels < k[9:])).all(), 'Observed pixels outside domain')
        return
    require(mode in (1, 2) and len(arrays) == 3, 'Unknown fixture mode')
    depth, photos, cameras = map(np.asarray, arrays)
    require(depth.ndim == photos.ndim == 4 and depth.shape[-1] == photos.shape[-1] == 4,
            'Photographic fixture requires RGBA arrays')
    require(2 <= len(depth) <= 8 and len(photos) == len(depth), 'Mismatched camera/image count')
    require(depth.dtype == photos.dtype == np.dtype('uint8'), 'Photographic bytes changed type')
    require(cameras.shape == (len(depth), 16) and np.isfinite(cameras).all(), 'Camera fixture shape/values changed')
    require(32 <= depth.shape[1] <= 640 and 32 <= depth.shape[2] <= 640
            and depth.shape[1] * depth.shape[2] <= 320000, 'Depth dimensions changed beyond native cap')
    require(32 <= photos.shape[1] <= 1024 and 32 <= photos.shape[2] <= 1024,
            'Photograph dimensions changed beyond native cap')


def calibrated_arrays(source):
    """Existing synthetic planar anchor; pixels are forward-distorted, never truth geometry."""
    import numpy as np
    text = (source / 'reconstruction/tests/calibrated_initializer_fixtures.hpp').read_text()
    match = re.search(r'inline constexpr std::array<double,776> anchor\{\{(.*?)\}\};', text, re.S)
    require(match is not None, 'Calibrated anchor declaration changed')
    coordinates = np.array([float(value) for value in match[1].split(',') if value.strip()])
    require(coordinates.size == 776, 'Calibrated anchor extent changed')
    rows = np.column_stack((coordinates.reshape(194, 4), np.tile([120., 130., 140.], (194, 1))))
    domains = np.tile([480., 480., 191.5, 143.5, .01, -.001, .0001, -.0002, .00001, 384., 288.], (2, 1))
    domains[1] = [460., 500., 190., 142., .015, -.002, -.00015, .0001, -.00002, 384., 288.]
    observed = np.empty((len(rows), 4))
    for camera, k in enumerate(domains):
        x, y = rows[:, camera * 2:camera * 2 + 2].T
        r2 = x*x + y*y
        radial = 1 + r2 * (k[4] + r2 * (k[5] + r2 * k[8]))
        observed[:, camera * 2] = k[0] * (x*radial + 2*k[6]*x*y + k[7]*(r2 + 2*x*x)) + k[2]
        observed[:, camera * 2 + 1] = k[1] * (y*radial + k[6]*(r2 + 2*y*y) + 2*k[7]*x*y) + k[3]
    return [rows, observed, domains]


def captured_arrays(mode, call, output):
    method, args, kwargs = call
    if mode == 1:
        require(method == 'surface' and kwargs == {'glb': None, 'ply': None},
                'Surface benchmark invocation changed')
        depth, cameras, near, far, planes, stride = args
        photos = depth
    else:
        require(mode == 2, 'Unknown capture mode')
        expected = {'textured_glb': output / 'preparation-only/middlebury-photographic-texture.glb',
                    'atlas_png': output / 'preparation-only/middlebury-photographic-atlas.png'}
        require(method == 'textured_surface' and kwargs == expected, 'Texture benchmark invocation changed')
        depth, photos, cameras, near, far, planes, stride = args
    require((near, far, planes, stride) == (6., 80., 64, 3), 'Benchmark settings changed')
    arrays = [depth, photos, cameras]
    validate_arrays(mode, arrays)
    return method, arrays


def prepare(source, output):
    import numpy as np
    sys.path.insert(0, str(source / 'tools'))
    output.mkdir()
    manifest = {'schema': 1, 'cases': {}}

    def save(name, mode, arrays):
        validate_arrays(mode, arrays)
        if mode == 0:
            rows = np.ascontiguousarray(arrays[0], dtype='<f8')
            header = [0x52464331, mode, len(rows), 0, 0, 0, 0]
            body = rows.tobytes()
        elif mode == 3:
            rows, observed, domains = [np.ascontiguousarray(a, dtype='<f8') for a in arrays]
            header = [0x52464331, mode, len(rows), 0, 0, 0, 0]
            body = rows.tobytes() + observed.tobytes() + domains.tobytes()
        else:
            depth, photos, cameras = arrays
            depth = np.ascontiguousarray(depth, dtype='u1')
            photos = np.ascontiguousarray(photos, dtype='u1')
            cameras = np.ascontiguousarray(cameras, dtype='<f8')
            header = [0x52464331, mode, len(depth), depth.shape[2], depth.shape[1], photos.shape[2], photos.shape[1]]
            body = cameras.tobytes() + depth.tobytes() + photos.tobytes()
        data = struct.pack('<7I', *header) + body
        path = output / (name + '.bin')
        with path.open('xb') as stream:
            stream.write(data)
        path.chmod(0o444)
        manifest['cases'][name] = {'sha256': digest(path), 'bytes': len(data), 'header': header,
                                   'settings': {'threshold': .003125} if mode == 3 else {'threshold': .002} if mode == 0 else {'near': 6., 'far': 80., 'planes': 64, 'stride': 3}}

    fixture = module('replay_pipeline_fixture', source / 'tests/test_pipeline.py')
    rows, *_ = fixture.observations()
    save('sparse', 0, [rows])
    for name, mode in [('surface', 1), ('texture', 2)]:
        fixture = module('replay_' + name, source / f'tools/benchmark_{name}_middlebury.py')
        capture = Capture()
        fixture.NativeEngine = lambda: capture
        try:
            fixture.run(**({'output_dir': output / 'preparation-only'} if mode == 2 else {}))
        except Captured:
            pass
        else:
            raise RuntimeError('Benchmark did not reach the expected native call')
        method, arrays = captured_arrays(mode, capture.call, output)
        save(name, mode, arrays)
        manifest['cases'][name]['captured_method'] = method
    save('calibrated-planar', 3, calibrated_arrays(source))
    manifest['cases']['calibrated-planar']['provenance'] = 'synthetic existing calibrated_initializer_fixtures.hpp anchor; forward-distorted observed pixels'
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(args.source.resolve(), args.output.resolve())
