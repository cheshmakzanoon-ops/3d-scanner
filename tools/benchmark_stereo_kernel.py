#!/usr/bin/env python3
"""Compare M13 with a separately built pre-M13 library on identical real images.

No timing threshold in CI: host speed is not a phone performance guarantee.
Both binaries must be built Release with the same compiler and options.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import platform
import statistics
import time
from pathlib import Path
import cv2
import numpy as np
from benchmark_middlebury import load_middlebury, FOCAL_PIXELS, CX_LEFT, CX_RIGHT, CY
from native_api import NativeEngine, library_path


RESIZE_CAMERA_POLICY = 'rf-pixel-centres-v2:opencv-area:half-pixel-resize-v1'


def resized_cameras(source_width: int, source_height: int,
                    width: int, height: int) -> np.ndarray:
    """Published source calibration transformed to resized pixel centres.

    OpenCV maps a destination centre back to (u+.5)/scale-.5, so focal
    lengths scale directly while principal points also carry that offset.
    """
    if min(source_width, source_height, width, height) <= 0:
        raise ValueError('Image dimensions must be positive')
    sx, sy = width / source_width, height / source_height
    return np.asarray([[1,0,0,0,1,0,0,0,1,-float(i),0,0,
        FOCAL_PIXELS*sx, FOCAL_PIXELS*sy,
        (cx+.5)*sx-.5, (CY+.5)*sy-.5]
        for i,cx in enumerate((CX_LEFT,CX_RIGHT))], dtype=np.float64)


def run(baseline: Path, current: Path, repeats: int = 5) -> dict:
    if not 3 <= repeats <= 20:
        raise ValueError('Use 3–20 alternating timed samples')
    left, right, _ = load_middlebury()  # Disparity is not read by either reconstruction.
    h, w = left.shape[:2]
    width, height = 228, round(h * 228 / w)
    images = np.stack([np.dstack((cv2.resize(im, (width, height), interpolation=cv2.INTER_AREA),
        np.full((height, width), 255, np.uint8))) for im in (left, right)])
    cameras = resized_cameras(w, h, width, height)
    engines = [NativeEngine(baseline.resolve()), NativeEngine(current.resolve())]
    times: list[list[float]] = [[], []]
    outputs = [None, None]
    for i in range(repeats + 1):
        for j in ((0,1) if i % 2 == 0 else (1,0)):
            started=time.perf_counter()
            outputs[j]=engines[j].surface(images,cameras,6.,80.,64,3)
            if i: times[j].append(time.perf_counter()-started)
    old,new=outputs
    if not (old is not None and new is not None):
        raise AssertionError
    deltas={}
    for kind,key in [('dense','points'),('mesh','vertices'),('mesh','triangles')]:
        a,b=np.asarray(old[kind][key],float),np.asarray(new[kind][key],float)
        if a.shape != b.shape or not np.allclose(a,b,rtol=1e-6,atol=1e-7):
            raise AssertionError(f'Geometry parity failed for {kind}.{key}')
        deltas[f'{kind}.{key}']=float(np.max(np.abs(a-b))) if a.size else 0.
    return dict(schema=13, dataset='Real Middlebury Motorcycle, 228-pixel preview',
        resizeCameraPolicy=RESIZE_CAMERA_POLICY,
        inputCameraRowsSha256=hashlib.sha256(cameras.tobytes()).hexdigest(),
        inputImageBytesSha256=hashlib.sha256(images.tobytes()).hexdigest(),
        platform=platform.platform(), baselineSha256=hashlib.sha256(baseline.read_bytes()).hexdigest(),
        currentSha256=hashlib.sha256(current.read_bytes()).hexdigest(),
        baselineSeconds=times[0], optimizedSeconds=times[1],
        hostMedianSpeedup=statistics.median(times[0])/statistics.median(times[1]),
        geometryMaximumAbsoluteDeltas=deltas, densePoints=new['dense']['pointCount'],
        vertices=new['mesh']['vertexCount'], triangles=new['mesh']['triangleCount'],
        phonePerformanceVerified=False, geometryGatesUnchanged=True)

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline',type=Path,required=True)
    parser.add_argument('--current',type=Path,default=None)
    parser.add_argument('--output',type=Path,default=Path('reports/m13-stereo-kernel.json'))
    args=parser.parse_args()
    result=run(args.baseline,args.current or library_path())
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
