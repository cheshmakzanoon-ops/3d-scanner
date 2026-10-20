"""Deterministic first-party SYNTHETIC interchange fixtures; no physical scans."""
import argparse
import io
import json
from pathlib import Path
import struct
import numpy as np
from PIL import Image


def glb(path, *, bad_index=False, flipped=False, bad_normal=False, metric=False, tangent_normal=False):
    positions = np.array([[0, 0, 0], [2, 0, 0], [0, 1, 3]], '<f4')
    normal = np.array([0, -3, 1], '<f4') / np.sqrt(10)
    normals = np.tile(normal * (-1 if bad_normal else 1), (3, 1)).astype('<f4')
    if tangent_normal:
        normals[1] = [1, 0, 0]
    uv = np.array([[0, 0], [1, 0], [0, 1]], '<f4')
    if flipped:
        uv[:, 1] = 1 - uv[:, 1]
    image = Image.fromarray(np.array([[[255, 0, 0], [0, 255, 0]], [[0, 0, 255], [255, 255, 0]]], 'u1'))
    png = io.BytesIO()
    image.save(png, format='PNG')
    chunks = [positions.tobytes(), normals.tobytes(), uv.tobytes(),
              np.array([0, 1, 9 if bad_index else 2], '<u4').tobytes(), png.getvalue()]
    views, data = [], b''
    for chunk in chunks:
        data += b'\0' * (-len(data) % 4)
        views.append({'buffer': 0, 'byteOffset': len(data), 'byteLength': len(chunk)})
        data += chunk
    doc = {'asset': {'version': '2.0', 'generator': 'Agent090 synthetic fixture'},
           'extras': {'representation': 'photographic_triangles', 'scale': 'metric' if metric else 'arbitrary'},
           'scene': 0, 'scenes': [{'nodes': [0]}], 'nodes': [{'mesh': 0}],
           'meshes': [{'primitives': [{'attributes': {'POSITION': 0, 'NORMAL': 1, 'TEXCOORD_0': 2}, 'indices': 3, 'material': 0}]}],
           'buffers': [{'byteLength': len(data)}], 'bufferViews': views,
           'accessors': [{'bufferView': 0, 'componentType': 5126, 'count': 3, 'type': 'VEC3', 'min': [0, 0, 0], 'max': [2, 1, 3]},
                         {'bufferView': 1, 'componentType': 5126, 'count': 3, 'type': 'VEC3'},
                         {'bufferView': 2, 'componentType': 5126, 'count': 3, 'type': 'VEC2'},
                         {'bufferView': 3, 'componentType': 5125, 'count': 3, 'type': 'SCALAR'}],
           'materials': [{'pbrMetallicRoughness': {'baseColorTexture': {'index': 0}, 'metallicFactor': 0}}],
           'textures': [{'source': 0}], 'images': [{'bufferView': 4, 'mimeType': 'image/png'}]}
    encoded = json.dumps(doc, separators=(',', ':')).encode()
    encoded += b' ' * (-len(encoded) % 4)
    data += b'\0' * (-len(data) % 4)
    result = struct.pack('<4sII', b'glTF', 2, 28 + len(encoded) + len(data))
    result += struct.pack('<II', len(encoded), 0x4e4f534a) + encoded
    result += struct.pack('<II', len(data), 0x004e4942) + data
    path.write_bytes(result)
    return image


def make(root):
    root.mkdir(parents=True, exist_ok=True)
    image = glb(root / 'asymmetric.glb')
    glb(root / 'tangent-normal.glb', tangent_normal=True)
    for name, kwargs in [('invalid-index', {'bad_index': True}), ('flipped-uv', {'flipped': True}),
                         ('reversed-normal', {'bad_normal': True}), ('unproven-metric', {'metric': True})]:
        glb(root / (name + '.glb'), **kwargs)
    image.save(root / 'checker.png')
    obj = 'mtllib asymmetric.mtl\nv 0 0 0\nv 2 0 0\nv 0 1 3\nvt 0 1\nvt 1 1\nvt 0 0\nusemtl checker\nf 1/1 2/2 3/3\n'
    (root / 'asymmetric.obj').write_text(obj)
    (root / 'asymmetric.mtl').write_text('newmtl checker\nKd 1 1 1\nmap_Kd checker.png\n')
    (root / 'missing-sidecar.obj').write_text(obj.replace('asymmetric.mtl', 'absent.mtl'))
    (root / 'traversal.obj').write_text(obj.replace('asymmetric.mtl', '../absent.mtl'))
    (root / 'asymmetric.ply').write_text('ply\nformat ascii 1.0\ncomment SYNTHETIC arbitrary scale\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nelement face 1\nproperty list uchar int vertex_indices\nend_header\n0 0 0\n2 0 0\n0 1 3\n3 0 1 2\n')
    normal = np.array([0, -3, 1]) / np.sqrt(10)
    (root / 'asymmetric.stl').write_bytes(b'SYNTHETIC arbitrary scale'.ljust(80, b'\0') + struct.pack('<I12fH', 1, *normal, 0, 0, 0, 2, 0, 0, 0, 1, 3, 0))
    expected = {'representation': 'triangles', 'triangles': 1, 'bounds': [[0, 0, 0], [2, 1, 3]]}
    (root / 'geometry.expected.json').write_text(json.dumps(expected, indent=2) + '\n')
    expected.update(textured_triangles=1, texture_samples=[{'point': [0, 0, 0], 'rgb': [255, 0, 0]}, {'point': [2, 0, 0], 'rgb': [0, 255, 0]}, {'point': [0, 1, 3], 'rgb': [0, 0, 255]}])
    (root / 'textured.expected.json').write_text(json.dumps(expected, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    make(parser.parse_args().directory)
