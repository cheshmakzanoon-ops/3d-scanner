"""Analytic input-calibration regression; no real photos or native timing claims."""
from pathlib import Path
import sys

import cv2
import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from benchmark_stereo_kernel import resized_cameras
from benchmark_middlebury import FOCAL_PIXELS, CX_LEFT, CX_RIGHT, CY


def test_asymmetric_resize_preserves_source_rays_and_baseline():
    source_width, source_height = 1001, 777
    width, height = 317, 241
    sx, sy = width / source_width, height / source_height
    cameras = resized_cameras(source_width, source_height, width, height)
    assert cameras.shape == (2, 16)
    for index, source_cx in enumerate((CX_LEFT, CX_RIGHT)):
        row = cameras[index]
        np.testing.assert_array_equal(row[:9], np.eye(3).ravel())
        np.testing.assert_array_equal(row[9:12], [-index, 0, 0])
        # Independently map destination centres to their original image area.
        for u, v in ((0., 0.), (17.125, 41.75), (158., 120.), (316., 240.)):
            source_u, source_v = (u + .5) / sx - .5, (v + .5) / sy - .5
            expected = [(source_u - source_cx) / FOCAL_PIXELS,
                        (source_v - CY) / FOCAL_PIXELS]
            actual = [(u - row[14]) / row[12], (v - row[15]) / row[13]]
            np.testing.assert_allclose(actual, expected, rtol=0, atol=1e-14)


def test_actual_area_resampling_matches_calibrated_pixel_centres():
    # INTER_AREA integrates exactly 4x3 source pixels. The mean of this linear
    # pixel field equals its value at that block's geometric centre.
    y, x = np.mgrid[:39, :84]
    source = (2 * x + 3 * y + 7).astype(np.float32)
    resized = cv2.resize(source, (21, 13), interpolation=cv2.INTER_AREA)
    row = resized_cameras(84, 39, 21, 13)[0]
    v, u = np.mgrid[:13, :21]
    source_u = FOCAL_PIXELS * (u - row[14]) / row[12] + CX_LEFT
    source_v = FOCAL_PIXELS * (v - row[15]) / row[13] + CY
    np.testing.assert_allclose(resized, 2 * source_u + 3 * source_v + 7,
                               rtol=0, atol=1e-10)


def test_zero_sized_camera_input_is_rejected():
    with pytest.raises(ValueError, match='positive'):
        resized_cameras(1001, 0, 317, 241)
