"""The aggregate CLI must reject actual measured overlaps after a legacy PASS."""
import json
from pathlib import Path
import struct
import sys
import types

import numpy as np
import pytest
from PIL import Image

from tools.qa.texture_quality import baseline


def photographic_asset(root, duplicate_pairs):
    """23 disjoint analytic UV islands; each may have one overlapping copy."""
    root.mkdir(parents=True, exist_ok=True)
    vertices, coordinates = [], []
    for island in range(23):
        x = .01 + island * .042
        triangle = np.array([[x, .1], [x + .02, .1], [x, .12]], dtype='<f4')
        for copy in range(2 if duplicate_pairs else 1):
            coordinates.extend(triangle)
            vertices.extend(np.column_stack([triangle * 100, np.full(3, island * 2 + copy + 1)]))
    vertices = np.asarray(vertices, dtype='<f4')
    coordinates = np.asarray(coordinates, dtype='<f4')
    indices = np.arange(len(vertices), dtype='<u4')
    png_path = root / 'middlebury-photographic-atlas.png'
    Image.fromarray(np.full((8, 8, 4), 255, dtype='uint8')).save(png_path)
    chunks = [vertices.tobytes(), coordinates.tobytes(), indices.tobytes(), png_path.read_bytes()]
    views, offset = [], 0
    for chunk in chunks:
        views.append({'buffer': 0, 'byteOffset': offset, 'byteLength': len(chunk)})
        offset += len(chunk)
    binary = b''.join(chunks)
    document = {
        'asset': {'version': '2.0', 'generator': 'SYNTHETIC baseline exit regression'},
        'scene': 0, 'scenes': [{'nodes': [0]}], 'nodes': [{'mesh': 0}],
        'meshes': [{'primitives': [{'attributes': {'POSITION': 0, 'TEXCOORD_0': 1}, 'indices': 2, 'material': 0, 'mode': 4}]}],
        'buffers': [{'byteLength': len(binary)}], 'bufferViews': views,
        'accessors': [
            {'bufferView': 0, 'componentType': 5126, 'count': len(vertices), 'type': 'VEC3'},
            {'bufferView': 1, 'componentType': 5126, 'count': len(vertices), 'type': 'VEC2'},
            {'bufferView': 2, 'componentType': 5125, 'count': len(indices), 'type': 'SCALAR'}],
        'images': [{'bufferView': 3, 'mimeType': 'image/png'}],
        'textures': [{'source': 0, 'sampler': 0}],
        'samplers': [{'magFilter': 9729, 'minFilter': 9729, 'wrapS': 33071, 'wrapT': 33071}],
        'materials': [{'pbrMetallicRoughness': {'baseColorTexture': {'index': 0}}, 'alphaMode': 'BLEND'}]}
    encoded = json.dumps(document).encode()
    encoded += b' ' * (-len(encoded) % 4)
    binary += b'\0' * (-len(binary) % 4)
    body = (struct.pack('<III', 0x46546c67, 2, 28 + len(encoded) + len(binary))
            + struct.pack('<II', len(encoded), 0x4e4f534a) + encoded
            + struct.pack('<II', len(binary), 0x004e4942) + binary)
    (root / 'middlebury-photographic-texture.glb').write_bytes(body)


@pytest.mark.parametrize('overlaps,legacy_exit,expected', [(True, 0, 1), (False, 0, 0), (False, 7, 1)])
def test_aggregate_exit_matches_independent_and_historical_gates(tmp_path, monkeypatch, overlaps, legacy_exit, expected):
    actual_root = Path(baseline.__file__).resolve().parents[3]
    protocol = tmp_path / 'benchmarks/quality_protocols/texture.json'
    protocol.parent.mkdir(parents=True)
    protocol.write_bytes((actual_root / 'benchmarks/quality_protocols/texture.json').read_bytes())
    monkeypatch.setattr(baseline, '__file__', str(tmp_path / 'tools/qa/texture_quality/baseline.py'))
    photographic_asset(tmp_path / 'build/host', overlaps)
    library = tmp_path / 'fixture.so'
    library.write_bytes(b'SYNTHETIC driver placeholder; no native algorithm is mocked as qualified')
    output = tmp_path / 'evidence'
    monkeypatch.setattr(sys, 'argv', ['baseline', '--output-dir', str(output), '--native-library', str(library)])
    def historical_benchmark(command, **kwargs):
        Path(command[-1]).write_text(json.dumps({'synthetic': True, 'legacy_exit': legacy_exit}))
        return types.SimpleNamespace(returncode=legacy_exit, stdout='synthetic historical runner\n', stderr='')
    monkeypatch.setattr(baseline.subprocess, 'run', historical_benchmark)
    monkeypatch.setattr(baseline.subprocess, 'check_output', lambda *args, **kwargs: 'a' * 40 + '\n')
    exit_code = baseline.main()
    report = json.loads((output / 'baseline-receipt.json').read_text())
    # Real independent GLB/PNG decoding and overlap math execute, including23 pairs.
    if legacy_exit == 0:
        assert report['independent_metrics']['uv_overlap_pairs'] == (23 if overlaps else 0)
        assert report['independent_metrics']['distortion_anisotropy_p95'] < 1.001
    assert exit_code == expected
    assert report['status'] == ('PASS' if expected == 0 else 'FAIL')
    assert report['exit_code'] == expected
    assert report['legacy_baseline_status'] == ('PASS' if legacy_exit == 0 else 'FAIL')
    assert report['legacy_exit_code'] == legacy_exit
    if overlaps:
        assert report['independent_uv_gate'] == 'FAIL'
        assert report['independent_uv_failures'] == ['uv_overlap_pairs']
