#!/usr/bin/env python3
"""Real-photograph two-view benchmark for the exact portable RealityForge core.

Uses scikit-image's downsampled Middlebury 2014 Motorcycle stereo pair and
independently measured disparity. No dataset images are copied into the repo.
This is a real *rectified stereo pair*, NOT a three-view object scan or a
validation of CameraX, Android camera calibration, dense geometry or texture.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from native_api import NativeEngine

# Downsampled by a factor of four, as published by scikit-image/Middlebury.
# doffs = cx(right) - cx(left); GT depth = f * baseline / (disparity + doffs).
FOCAL_PIXELS = 994.978
CX_LEFT = 311.193
CX_RIGHT = CX_LEFT + 31.086
CY = 254.877
BASELINE_M = 0.193001


def load_middlebury() -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    try:
        from skimage import data
    except ImportError as exc:
        raise RuntimeError("Install tools/requirements-real-benchmark.txt first") from exc
    left, right, disparity = data.stereo_motorcycle()
    if left.shape != right.shape or left.shape[:2] != disparity.shape:
        raise ValueError("Middlebury data shapes do not match")
    return left, right, disparity


def matches_on_real_images(left: np.ndarray, right: np.ndarray,
                           *, ratio: float = .67) -> tuple[np.ndarray, np.ndarray, dict]:
    """Reciprocal SIFT ratio matches; no ground-truth filtering or oracle use."""
    if left.ndim != 3 or right.shape != left.shape or left.shape[2] != 3:
        raise ValueError("Expected equal-sized RGB source photographs")
    detector = cv2.SIFT_create(nfeatures=3500)
    first, a = detector.detectAndCompute(cv2.cvtColor(left, cv2.COLOR_RGB2GRAY), None)
    second, b = detector.detectAndCompute(cv2.cvtColor(right, cv2.COLOR_RGB2GRAY), None)
    if a is None or b is None or min(len(first), len(second)) < 20:
        raise ValueError("No useful features in real photographs")
    matcher = cv2.BFMatcher(cv2.NORM_L2)
    forward = matcher.knnMatch(a, b, k=2)
    backward = matcher.knnMatch(b, a, k=2)
    reverse = {m.queryIdx: m.trainIdx for candidates in backward if len(candidates) == 2
               for m, n in [candidates] if m.distance < ratio * n.distance}
    rows, pixel_pairs = [], []
    height, width = left.shape[:2]
    for candidates in forward:
        if len(candidates) != 2:
            continue
        m, n = candidates
        if m.distance >= ratio * n.distance or reverse.get(m.trainIdx) != m.queryIdx:
            continue
        x_left, y_left = first[m.queryIdx].pt
        x_right, y_right = second[m.trainIdx].pt
        if not (0 <= x_right < width and 0 <= y_right < height):
            continue
        rgb = left[min(height - 1, max(0, round(y_left))),
                   min(width - 1, max(0, round(x_left)))]
        rows.append([(x_left-CX_LEFT)/FOCAL_PIXELS, (y_left-CY)/FOCAL_PIXELS,
                     (x_right-CX_RIGHT)/FOCAL_PIXELS, (y_right-CY)/FOCAL_PIXELS,
                     *rgb.astype(int).tolist()])
        pixel_pairs.append([x_left, y_left, x_right, y_right])
    if len(rows) < 40:
        raise ValueError("Too few reciprocal matches on the real stereo pair")
    return (np.asarray(rows, np.float64), np.asarray(pixel_pairs, np.float64),
            {"leftFeatures": len(first), "rightFeatures": len(second),
             "reciprocalMatches": len(rows), "ratio": ratio})


def evaluate(disparity: np.ndarray, observations: np.ndarray, native: dict) -> dict:
    """GT is accessed ONLY after reconstruction for independent evaluation."""
    points = np.asarray(native["points"], dtype=np.float64)
    ids = points[:, 8].astype(np.int64)
    if ids.size == 0 or ids.min() < 0 or ids.max() >= len(observations):
        raise ValueError("Native point source indexes are invalid")
    coords = observations[ids]
    xs = np.rint(coords[:, 2]).astype(np.int64)
    ys = np.rint(coords[:, 3]).astype(np.int64)
    assert (xs >= 0).all() and (xs < disparity.shape[1]).all()
    assert (ys >= 0).all() and (ys < disparity.shape[0]).all()
    gt = disparity[ys, xs].astype(np.float64)
    valid = np.isfinite(gt) & (gt > 0) & (gt + CX_RIGHT-CX_LEFT > 0)
    if valid.sum() < 30:
        raise ValueError("Insufficient ground-truth pixels for an independent comparison")
    measured_disparity = coords[valid, 0] - coords[valid, 2]
    errors = np.abs(gt[valid] - measured_disparity)
    true_depth = FOCAL_PIXELS * BASELINE_M / (gt[valid] + CX_RIGHT - CX_LEFT)
    # The 2-view SfM reconstruction has a UNIT baseline in arbitrary units;
    # reference camera baseline is known only to this benchmark, NOT the app.
    recon_depth = points[valid, 2] * BASELINE_M
    depth_relative = np.abs(recon_depth - true_depth) / true_depth
    return {
        "groundTruthValidPoints": int(valid.sum()),
        "matchedDisparityAbsErrorPxP50": float(np.median(errors)),
        "matchedDisparityAbsErrorPxP75": float(np.percentile(errors, 75)),
        "matchedDisparityAbsErrorPxP90": float(np.percentile(errors, 90)),
        "referenceScaledDepthRelativeErrorP50": float(np.median(depth_relative)),
        "referenceScaledDepthRelativeErrorP90": float(np.percentile(depth_relative, 90)),
        "rotationFromRectifiedDeg": float(np.degrees(np.arccos(np.clip((np.trace(np.asarray(native['rotation']).reshape(3,3))-1)/2, -1, 1)))),
    }


def benchmark(output: Path | None = None) -> dict:
    started = time.perf_counter()
    left, right, disparity = load_middlebury()
    matches, pixels, matched = matches_on_real_images(left, right)
    native = NativeEngine().reconstruct(matches, 1.5 / FOCAL_PIXELS)
    metrics = evaluate(disparity, pixels, native)
    report = {
        "schema": 1,
        "dataset": "Middlebury 2014 Motorcycle (downsampled scikit-image stereo pair)",
        "datasetOrigin": "https://vision.middlebury.edu/stereo/data/scenes2014/",
        "realPhotographs": True,
        "cameraCount": 2,
        "captureType": "rectified calibrated stereo, not handheld multi-view scanning",
        "inputSha256": {
            "left": hashlib.sha256(left.tobytes()).hexdigest(),
            "right": hashlib.sha256(right.tobytes()).hexdigest(),
            "groundTruthDisparity": hashlib.sha256(disparity.tobytes()).hexdigest(),
        },
        "knownCalibration": {"focalPixels": FOCAL_PIXELS,"cxLeftPixels": CX_LEFT,
                             "cxRightPixels": CX_RIGHT,"cyPixels": CY,
                             "baselineMetersForEvaluationOnly": BASELINE_M},
        **matched,
        "nativeAcceptedSparsePoints": int(native["pointCount"]),
        "nativeMedianReprojectionErrorNormalized": float(native["medianErrorNormalized"]),
        **metrics,
        "processingSecondsOnHost": round(time.perf_counter()-started, 3),
        "limitations": ["Sparse two-view reconstruction only; not a 3D surface or texture",
                        "Absolute metric scale supplied ONLY for benchmark evaluation",
                        "Not an Android camera, physical handset or real multi-view object test",
                        "Ground-truth evaluation uses valid disparities at right-view keypoint locations"],
    }
    if output is not None:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2, sort_keys=True)+"\n")
    return report


def enforce_reference_gates(report: dict) -> None:
    """Regression thresholds tolerate fixture/backend changes, not fake perfection."""
    assert report["realPhotographs"] and report["cameraCount"] == 2
    assert report["reciprocalMatches"] >= 500
    assert report["nativeAcceptedSparsePoints"] >= 450
    assert report["groundTruthValidPoints"] >= 400
    assert report["matchedDisparityAbsErrorPxP50"] < 2.0
    assert report["matchedDisparityAbsErrorPxP75"] < 5.5
    assert report["referenceScaledDepthRelativeErrorP50"] < .12
    assert report["nativeMedianReprojectionErrorNormalized"] < .003
    assert report["rotationFromRectifiedDeg"] < 1.0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("reports/m05-middlebury-real.json"))
    parser.add_argument("--gate", action="store_true", help="Exit nonzero if independently measured quality regresses")
    args = parser.parse_args()
    result=benchmark(args.output)
    if args.gate:enforce_reference_gates(result)
    print(json.dumps(result, indent=2))

if __name__=="__main__":main()
