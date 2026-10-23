"""Prepare exact existing fixtures without loading or invoking the scanner."""
import hashlib
import importlib.util
import json
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
                                   'settings': {'threshold': .002} if mode == 0 else {'near': 6., 'far': 80., 'planes': 64, 'stride': 3}}

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
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(args.source.resolve(), args.output.resolve())
