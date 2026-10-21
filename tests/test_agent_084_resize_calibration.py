"""Camera inputs must describe the pixel centres actually sampled by cv2.resize."""
from pathlib import Path
import sys

import cv2
import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))


@pytest.mark.parametrize('module_name,operation', [
    ('benchmark_dense_middlebury', 'dense'),
    ('benchmark_surface_middlebury', 'surface'),
])
def test_resized_camera_preserves_original_pinhole_rays(monkeypatch, module_name, operation):
    import importlib
    module = importlib.import_module(module_name)
    source_h, source_w = 777, 1001
    image = np.zeros((source_h, source_w, 3), np.uint8)
    # Truth is deliberately unreadable: interception occurs before evaluation.
    monkeypatch.setattr(module, 'load_middlebury', lambda: (image, image, object()))
    monkeypatch.setattr(module, 'FOCAL_PIXELS', 713.7)
    monkeypatch.setattr(module, 'CX_LEFT', 441.25)
    monkeypatch.setattr(module, 'CX_RIGHT', 402.75)
    monkeypatch.setattr(module, 'CY', 319.75)
    captured = {}

    class CameraCaptured(Exception):
        pass

    class Engine:
        def capture(self, images, cameras, *args, **kwargs):
            captured.update(images=images, cameras=cameras)
            raise CameraCaptured

    setattr(Engine, operation, Engine.capture)
    monkeypatch.setattr(module, 'NativeEngine', Engine)
    with pytest.raises(CameraCaptured):
        module.run(size=317)
    images, cameras = captured['images'], np.asarray(captured['cameras'])
    _, out_h, out_w, _ = images.shape
    assert (out_w, out_h) == (317, 246)
    assert out_w/source_w != out_h/source_h  # Rounded height needs its own scale.
    for index, source_cx in enumerate((441.25, 402.75)):
        fx, fy, cx, cy = cameras[index, 12:16]
        for u, v in ((0., 0.), (17.125, 41.75), (158., 120.), (316., 245.)):
            # Map the two destination pixel edges, then take their source midpoint.
            source_u = ((u*source_w/out_w-.5)+((u+1)*source_w/out_w-.5))/2
            source_v = ((v*source_h/out_h-.5)+((v+1)*source_h/out_h-.5))/2
            expected = ((source_u-source_cx)/713.7, (source_v-319.75)/713.7)
            actual = ((u-cx)/fx, (v-cy)/fy)
            np.testing.assert_allclose(actual, expected, atol=1e-14, rtol=0)


def test_area_resize_linear_pixels_equal_source_block_centres():
    y, x = np.mgrid[:39, :84]
    source = (2*x+3*y+7).astype(np.float32)
    actual = cv2.resize(source, (21, 13), interpolation=cv2.INTER_AREA)
    # Every output pixel averages exactly four by three source pixels.
    blocks = source.reshape(13, 3, 21, 4).mean(axis=(1, 3))
    np.testing.assert_allclose(actual, blocks, atol=1e-5, rtol=0)
    v, u = np.mgrid[:13, :21]
    centres = 2*(4*u+1.5)+3*(3*v+1)+7
    np.testing.assert_allclose(actual, centres, atol=1e-5, rtol=0)
