"""Deterministic tiny analytic fixtures, explicitly unsuitable for physical acceptance."""
from pathlib import Path
import json
from .manifest import sha256


def generate(root: Path) -> dict:
    from PIL import Image
    import numpy as np
    root.mkdir(parents=True, exist_ok=True)
    photos = []
    for i in range(3):
        y, x = np.mgrid[:24, :32]
        rgb = np.stack(((x*7+i*31)%256, (y*11+i*17)%256, ((x+y)*5+i*43)%256), -1).astype('uint8')
        path = root / f'view-{i}.png'
        Image.fromarray(rgb).save(path, optimize=False)
        photos.append({'id': f'view-{i}', 'path': path.name, 'sha256': sha256(path), 'width': 32, 'height': 24,
                       'lens_id': 'analytic-pinhole', 'role': 'held_out' if i == 2 else 'reconstruction'})
    calibration = root / 'calibration.json'
    calibration.write_text(json.dumps({'lens_id': 'analytic-pinhole', 'image_size': [32, 24], 'K': [[30,0,15.5],[0,30,11.5],[0,0,1]]}, sort_keys=True)+'\n')
    reference = root / 'reference.obj'
    reference.write_text('v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nf 1 2 3\nf 1 3 4\n')
    manifest = {'schema_version': 1, 'dataset_id': 'synthetic-analytic-square-v1', 'kind': 'synthetic', 'split': 'regression',
                'scene': 'flat', 'units': 'arbitrary', 'metric_scale_verified': False,
                'permission': {'license': 'CC0-1.0', 'source': 'first-party procedural fixture', 'authorization': 'generated for testing',
                               'provenance': 'tools.qa.corpus.fixture.generate v1', 'evaluation': True, 'redistribution': True},
                'capture': {'device': 'analytic generator', 'lens_id': 'analytic-pinhole', 'settings': '32x24 deterministic RGB', 'physical': False},
                'photos': photos, 'calibration': {'path': calibration.name, 'sha256': sha256(calibration), 'lens_id': 'analytic-pinhole', 'provenance': 'analytic exact intrinsics'},
                'reference': {'path': reference.name, 'sha256': sha256(reference), 'units': 'arbitrary', 'method': 'analytic', 'independent': True, 'provenance': 'unit square; never estimated from candidate'}}
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2, sort_keys=True)+'\n')
    return manifest
