#!/usr/bin/env python3
"""Known-camera eight-view SYNTHETIC plane sweep; coverage is measured on a fixed world grid.

This is not a physical 360-degree scan or an SfM/camera-registration benchmark.
The reference geometry is used only to render inputs and measure the outputs.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import time
import cv2
import numpy as np
from native_api import NativeEngine


def fixture(count: int = 8, width: int = 104, height: int = 76):
    if not 2 <= count <= 8:
        raise ValueError('Fixture supports two to eight camera photographs')
    rng = np.random.default_rng(140013)
    surface = rng.integers(0, 256, (1024, 1024, 3), dtype=np.uint8)
    f = width * 116.0 / 104
    xx, yy = np.meshgrid(np.arange(width), np.arange(height))
    cx, cy = (width-1)/2, (height-1)/2
    cameras, images = [], []
    for i in range(count):
        wx = (xx-cx)*5.0/f + i*.65
        wy = (yy-cy)*5.0/f
        rgb = cv2.remap(surface, (wx*31+256).astype('float32'),
                       (wy*31+256).astype('float32'), cv2.INTER_LINEAR)
        images.append(np.dstack((rgb, np.full((height,width),255,np.uint8))))
        cameras.append([1,0,0,0,1,0,0,0,1,-i*.65,0,0,f,f,cx,cy])
    return np.asarray(images,np.uint8), np.asarray(cameras,np.float64)


def evaluate(points, cameras, width=104, height=76):
    """Fraction of fixed 0.15-unit cells observed by >=2 cameras with a nearby correct-depth point."""
    points = np.asarray(points,float)
    # Same domain in first-three/eight-view comparisons; no changing denominators.
    xs, ys = np.meshgrid(np.arange(-2.0,6.51,.15), np.arange(-1.2,1.21,.15))
    visible = np.zeros(xs.shape,int)
    for c in cameras:
        u = (xs+c[9])*c[12]/5 + c[14]
        v = ys*c[13]/5 + c[15]
        visible += (u>=6)&(u<width-6)&(v>=6)&(v<height-6)
    eligible = visible>=2
    if not len(points):
        return dict(coveredCells=0,observableCells=int(eligible.sum()),coverage=0.0,depthErrorP50=None)
    correct=points[np.abs(points[:,2]-5.0)<.15]
    covered=np.zeros(xs.shape,bool)
    for i, (x,y) in enumerate(zip(xs.ravel(),ys.ravel())):
        covered.ravel()[i]=len(correct)>0 and np.any((correct[:,0]-x)**2+(correct[:,1]-y)**2 < .15**2)
    hit=int(np.sum(covered&eligible));total=int(eligible.sum())
    return dict(coveredCells=hit,observableCells=total,coverage=hit/total,
                depthErrorP50=float(np.median(np.abs(points[:,2]-5.0))))


def run():
    images,cameras=fixture()
    engine=NativeEngine(); results={}
    for count in (3,8):
        started=time.perf_counter()
        result=engine.surface(images[:count],cameras[:count],3.1,7.0,64,2)
        assert 'meshError' not in result, result.get('meshError')
        dense=result['dense'];mesh=result['mesh']
        results[str(count)]={**evaluate(mesh['vertices'],cameras),
            'vertices':mesh['vertexCount'],'triangles':mesh['triangleCount'],
            'pointCount':dense['pointCount'],'sourceViewIndices':dense['sourceViewIndices'],
            'consistentPixelsPerView':dense['consistentPixelsPerView'],
            'peakLuminanceBytes':dense['peakLuminanceBytes'],
            'wallSeconds':time.perf_counter()-started}
    return dict(schema=14,synthetic=True,physicalDevice=False,knownCameraPoses=True,
        groundTruthUsedByDepthSolver=False,worldGridCellSize=.15,groundTruthPlaneZ=5.,
        measurements=results,coverageRatio=results['8']['coverage']/results['3']['coverage'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=Path('reports/m14-eight-view-coverage.json'))
    parser.add_argument('--gate',action='store_true')
    args=parser.parse_args();result=run()
    if args.gate:
        m=result['measurements']
        assert result['coverageRatio']>1.35
        assert m['8']['depthErrorP50']<.08
        assert all(n>0 for n in m['8']['consistentPixelsPerView'])
        assert all(1<=len(v)<=3 for v in m['8']['sourceViewIndices'])
        assert m['8']['peakLuminanceBytes']<=4*104*76*4
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
