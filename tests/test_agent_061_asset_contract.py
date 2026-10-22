"""Compile real canonical writers; independently decode asymmetric synthetic GLBs."""
from tools.native_process import native_compile_env
from pathlib import Path
import json
import struct
import subprocess
import zlib
import pytest

ROOT = Path(__file__).resolve().parents[1]


def png():
    def chunk(kind, body):
        return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body))
    pixels = bytes([0, 255,0,0,255, 0,255,0,255, 0, 0,0,255,255, 255,255,0,255])
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 2, 2, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')


@pytest.fixture(scope='module')
def outputs(tmp_path_factory):
    folder = tmp_path_factory.mktemp('canonical-glb')
    image = folder / 'reference.png'
    image.write_bytes(png())
    exe = folder / 'writer'
    subprocess.run(['g++', '-std=c++20', '-Wall', '-Wextra', '-Wpedantic', '-Werror', '-I', str(ROOT/'reconstruction/include'),
                    str(ROOT/'reconstruction/src/asset_model.cpp'), str(ROOT/'reconstruction/src/glb_export.cpp'),
                    str(ROOT/'reconstruction/tests/agent_061_asset_test.cpp'), '-lz', '-o', str(exe)], check=True, env=native_compile_env())
    subprocess.run([str(exe), str(folder), str(image)], check=True)
    return folder


def unpack(path):
    blob = path.read_bytes()
    magic, version, size, json_size, kind = struct.unpack_from('<5I', blob)
    assert (magic, version, size, kind) == (0x46546c67, 2, len(blob), 0x4e4f534a)
    assert json_size % 4 == 0
    root = json.loads(blob[20:20+json_size])
    binary_size, binary_kind = struct.unpack_from('<2I', blob, 20+json_size)
    assert binary_kind == 0x004e4942 and binary_size % 4 == 0
    binary = blob[28+json_size:]
    assert len(binary) == binary_size == root['buffers'][0]['byteLength']
    intervals = sorted((v['byteOffset'], v['byteOffset']+v['byteLength']) for v in root['bufferViews'])
    assert all(a >= 0 and a % 4 == 0 and b <= len(binary) for a,b in intervals)
    assert all(a[1] <= b[0] for a,b in zip(intervals, intervals[1:]))
    return root, binary


@pytest.mark.parametrize('name,representation,mode', [('sparse','sparse_points',0), ('dense','dense_points',0), ('metric','dense_points',0), ('surface','observed_surface',4), ('texture','photographic_surface',4)])
def test_representation_lengths_bounds_winding_and_uv(outputs, name, representation, mode):
    root, binary = unpack(outputs/f'{name}.glb')
    assert root['extras']['representation'] == representation
    assert root['extras']['units'] == ('meters' if name == 'metric' else 'arbitrary')
    primitive = root['meshes'][0]['primitives'][0]
    assert primitive['mode'] == mode
    def values(index, fmt, unit):
        a = root['accessors'][index]
        v = root['bufferViews'][a['bufferView']]
        return list(struct.iter_unpack(fmt, binary[v['byteOffset']:v['byteOffset']+a['count']*unit]))
    assert values(0, '<3f', 12) == [(-2,-1,-3),(4,-1,-3),(-2,-5,-3)]
    assert root['accessors'][0]['min'] == [-2,-5,-3]
    assert root['accessors'][0]['max'] == [4,-1,-3]
    assert values(primitive['attributes']['NORMAL'], '<3f', 12) == [(0,0,-1)] * 3
    if name != 'texture':
        color_index = primitive['attributes']['COLOR_0']
        assert root['accessors'][color_index]['componentType'] == 5126
        color = values(color_index, '<4f', 16)
        assert color[0] == (1,0,0,1) and color[2] == (0,0,1,1)
        def linear(value):
            srgb = value / 255
            return srgb / 12.92 if srgb <= .04045 else ((srgb + .055) / 1.055) ** 2.4
        assert color[1] == pytest.approx(tuple(linear(c) for c in (128,64,32)) + (1.0,))
    if mode == 4:
        assert values(primitive['indices'], '<I', 4) == [(0,), (1,), (2,)]
    else:
        assert 'indices' not in primitive
    if name == 'texture':
        assert 'COLOR_0' not in primitive['attributes']
        assert values(primitive['attributes']['TEXCOORD_0'], '<2f', 8) == [(0,0),(1,0),(0,1)]
        image_view = root['bufferViews'][root['images'][0]['bufferView']]
        assert binary[image_view['byteOffset']:image_view['byteOffset']+image_view['byteLength']] == png()
    assert root['materials'][0]['extensions'] == {'KHR_materials_unlit': {}}


def validate_in_trimesh(folder):
    """Optional independent consumer, installed separately from app requirements."""
    import numpy as np
    import trimesh
    expected = np.array([[-2,-1,-3],[4,-1,-3],[-2,-5,-3]], dtype=float)
    for name in ('sparse','dense','metric','surface','texture'):
        scene = trimesh.load_scene(Path(folder)/f'{name}.glb', process=False)
        assert len(scene.geometry) == 1
        geometry = next(iter(scene.geometry.values()))
        np.testing.assert_allclose(geometry.vertices, expected)
        if name in ('sparse','dense','metric'):
            assert isinstance(geometry, trimesh.points.PointCloud)
        else:
            assert isinstance(geometry, trimesh.Trimesh)
            np.testing.assert_array_equal(geometry.faces, [[0,1,2]])
            np.testing.assert_allclose(geometry.face_normals, [[0,0,-1]])
        if name == 'texture':
            # Trimesh exposes OpenGL bottom-left UVs from glTF top-left UVs.
            np.testing.assert_allclose(geometry.visual.uv, [[0,1],[1,1],[0,0]])
            np.testing.assert_array_equal(np.array(geometry.visual.material.baseColorTexture),
                [[[255,0,0,255],[0,255,0,255]], [[0,0,255,255],[255,255,0,255]]])
    return 5


if __name__ == '__main__':
    import sys
    print(f'{validate_in_trimesh(Path(sys.argv[1]))} independent Trimesh imports passed.')
