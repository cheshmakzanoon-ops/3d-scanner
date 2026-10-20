"""Verify actual legacy/compact camera producers against source pixel support."""
from pathlib import Path
import sys
import types

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))


@pytest.mark.parametrize('compact', [False, True])
def test_camera_input_matches_resized_pixel_centres(monkeypatch, tmp_path, compact):
    import benchmark_middlebury as calibration
    import benchmark_texture_middlebury as legacy
    import native_api
    source_h, source_w = 777, 1001
    image = np.zeros((source_h, source_w, 3), np.uint8)
    for module in (calibration, legacy):
        monkeypatch.setattr(module, 'load_middlebury', lambda: (image, image, object()))
        for key, value in {'FOCAL_PIXELS':713.7, 'CX_LEFT':441.25,
                           'CX_RIGHT':402.75, 'CY':319.75}.items():
            monkeypatch.setattr(module, key, value)
    captured = {}

    class CameraCaptured(Exception):
        pass

    class CompactCall:
        def __call__(self, low, width, height, high, photo_width, photo_height,
                     camera_pointer, camera_count, *args):
            captured.update(width=width, height=height,
                cameras=np.ctypeslib.as_array(camera_pointer, shape=(camera_count*16,)).copy().reshape(-1,16))
            raise CameraCaptured

    class Engine:
        def __init__(self, *args):
            self.library = types.SimpleNamespace(rf_textured_surface_compact_json=CompactCall(),
                rf_textured_surface_json=types.SimpleNamespace(argtypes=[]))

        def textured_surface(self, low, high, cameras, *args, **kwargs):
            captured.update(width=low.shape[2], height=low.shape[1], cameras=cameras.copy())
            raise CameraCaptured

    monkeypatch.setattr(legacy, 'NativeEngine', Engine)
    monkeypatch.setattr(native_api, 'NativeEngine', Engine)
    with pytest.raises(CameraCaptured):
        if compact:
            from tools.qa.texture_quality.compact import main
            monkeypatch.setattr(sys, 'argv', ['compact', '--native-library', 'unused.so',
                '--candidate-source-sha', 'a'*40, '--output-dir', str(tmp_path)])
            main()
        else:
            legacy.run(tmp_path, width=317, photo_width=608)
    out_w, out_h = captured['width'], captured['height']
    assert (out_w, out_h) == ((228,177) if compact else (317,246))
    assert out_w/source_w != out_h/source_h
    for camera, source_cx in zip(captured['cameras'], (441.25,402.75)):
        fx, fy, cx, cy = camera[12:16]
        for u, v in ((0.,0.), (17.125,41.75), (out_w-1.,out_h-1.)):
            source_u = ((u*source_w/out_w-.5)+((u+1)*source_w/out_w-.5))/2
            source_v = ((v*source_h/out_h-.5)+((v+1)*source_h/out_h-.5))/2
            ray = ((source_u-source_cx)/713.7, (source_v-319.75)/713.7)
            np.testing.assert_allclose(((u-cx)/fx,(v-cy)/fy), ray, atol=1e-14, rtol=0)
