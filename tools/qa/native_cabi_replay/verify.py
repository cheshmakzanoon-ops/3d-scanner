"""Output contract checks; explicit errors also apply under Python -O."""
import json
import math
import struct
from .prepare import require


def strict_json(text):
    def reject_constant(value):
        raise RuntimeError('Nonfinite JSON constant: ' + value)
    def finite_float(value):
        result = float(value)
        require(math.isfinite(result), 'Nonfinite JSON numeric value')
        return result
    return json.loads(text, parse_constant=reject_constant, parse_float=finite_float)


def parse_glb(path):
    raw = path.read_bytes()
    require(len(raw) >= 28, 'Truncated GLB')
    magic, version, length = struct.unpack_from('<III', raw)
    require((magic, version, length) == (0x46546c67, 2, len(raw)), 'Invalid GLB header')
    size, tag = struct.unpack_from('<II', raw, 12)
    require(tag == 0x4e4f534a and size <= len(raw) - 28, 'Invalid JSON chunk')
    gltf = strict_json(raw[20:20 + size])
    binary_size, binary_tag = struct.unpack_from('<II', raw, 20 + size)
    binary = raw[28 + size:]
    require(binary_tag == 0x004e4942 and binary_size == len(binary) == gltf['buffers'][0]['byteLength'], 'Invalid binary chunk')
    return gltf, binary


CASES = {'sparse': 0, 'surface': 1, 'texture': 2, 'calibrated-planar': 3}
CALIBRATED_ERRORS = ('observed-count', 'domain-count', 'null-rows', 'null-observed',
                     'null-domains', 'swapped-domains', 'invalid-dimensions', 'mismatched-pixels', 'nonfinite-pixels')


def verify_calibrated(output, repeats):
    names = ('success', 'recovery', 'undistorted-equivalent', *CALIBRATED_ERRORS)
    values = []
    for index in range(repeats):
        directory = output / str(index)
        require({p.name for p in directory.iterdir()} == {name + '.json' for name in names},
                'Calibrated call coverage differs')
        decoded = {name: strict_json((directory / (name + '.json')).read_text()) for name in names}
        result = decoded['success']
        require(result == decoded['recovery'] == decoded['undistorted-equivalent'],
                'Calibrated recovery/domain equivalence differs')
        require(not any(key in result for key in ('error', 'errorCode', 'meshError', 'textureError')), 'Calibrated success reported an error')
        require(result['representation'] == 'sparse_points' and result['initializerModel'] == 'HOMOGRAPHY'
                and result['scale'] == 'arbitrary' and result['registeredCameras'] == 2
                and result['inputMatches'] == 194 and 'epipolarInliers' not in result,
                'Calibrated representation/model differs')
        require(result['pointCount'] == len(result['points']) >= 170
                and result['homographyInliers'] == result['modelInliers'] == result['inliers'] >= result['pointCount'],
                'Calibrated fixture lost support')
        indices = [point[8] for point in result['points']]
        require(len(set(indices)) == len(indices) and all(type(i) is int and 0 <= i < 194 for i in indices),
                'Calibrated match provenance differs')
        require(all(point[3:6] == [120, 130, 140] for point in result['points']), 'Calibrated colors differ')
        for name in CALIBRATED_ERRORS:
            error = decoded[name]
            require(set(error) == {'errorCode', 'error'} and error['errorCode'] == 'INVALID_INPUT'
                    and isinstance(error['error'], str) and bool(error['error']),
                    'Calibrated invalid input contract differs: ' + name)
        values.append(decoded)
    require(all(value == values[0] for value in values), 'Repeated calibrated JSON differs')
    return values[0]['success']


def verify(case, output, repeats=2):
    require(case in CASES, 'Unknown replay workload')
    require({p.name for p in output.iterdir()} == {str(i) for i in range(repeats)}, 'Replay repetition coverage differs')
    if case == 'calibrated-planar':
        return verify_calibrated(output, repeats)
    values = []
    for index in range(repeats):
        directory = output / str(index)
        decoded = {name: strict_json((directory / (name + '.json')).read_text())
                   for name in ('success', 'recovery', 'export-error', 'invalid')}
        result = decoded['success']
        require('error' in decoded['invalid'], 'Invalid input was not rejected')
        require(not any(key in result for key in ('error', 'meshError', 'textureError')), 'Successful workload reported an error')
        require(result == decoded['recovery'], 'Recovery output differs')
        key = 'error' if case == 'sparse' else 'meshError' if case == 'surface' else 'textureError'
        require(key in decoded['export-error'], 'Export error not preserved')
        require(not (directory / 'missing').exists(), 'Invalid export created its absent parent')
        gltf, _ = parse_glb(directory / 'mesh.glb')
        require(gltf['meshes'][0]['primitives'][0]['mode'] == (0 if case == 'sparse' else 4), 'Representation mismatch')
        require((directory / 'mesh.ply').read_bytes().startswith(b'ply\n'), 'PLY header missing')
        if case == 'sparse':
            require(result['pointCount'] >= 170, 'Sparse fixture lost support')
        if case == 'texture':
            gltf, binary = parse_glb(directory / 'texture.glb')
            primitive = gltf['meshes'][0]['primitives'][0]
            require('TEXCOORD_0' in primitive['attributes'], 'UV attribute missing')
            view = gltf['bufferViews'][gltf['images'][0]['bufferView']]
            require(binary[view['byteOffset']:view['byteOffset'] + view['byteLength']] == (directory / 'atlas.png').read_bytes(), 'Embedded photographic PNG differs')
            require(result['texture']['triangleCount'] > 100, 'Texture fixture lost support')
        values.append(result)
    require(all(value == values[0] for value in values), 'Repeated JSON differs')
    for path in (output / '0').iterdir():
        if path.suffix in ('.glb', '.ply', '.png'):
            for index in range(1, repeats):
                require(path.read_bytes() == (output / str(index) / path.name).read_bytes(), 'Repeated artifact differs')
    return values[0]
