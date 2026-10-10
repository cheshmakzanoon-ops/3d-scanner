#!/usr/bin/env python3
"""M07 real photographs: independently evaluate a CPU stereo POINT preview.

Rectified Motorcycle is 2 real physical photographs from Middlebury; camera
poses/intrinsics are benchmark calibration, not obtained from RealityForge SfM.
The disparity GT is used ONLY for evaluation, never for depth selection/estimation.
This is not validation of a 360-degree scan, mobile capture, or triangle surfaces.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import numpy as np
import cv2
from benchmark_middlebury import load_middlebury,FOCAL_PIXELS,CX_LEFT,CX_RIGHT,CY
from native_api import NativeEngine


def run(size:int=228,planes:int=64)->dict:
    left,right,disparity=load_middlebury()
    h,w=left.shape[:2]
    outw=int(size);outh=round(h*outw/w)
    ratiox=outw/w;ratioy=outh/h
    # Bilinear RGB sampling is independent of native optimization.
    rgba=[]
    for img in (left,right):
        rgb=cv2.resize(img,(outw,outh),interpolation=cv2.INTER_AREA)
        alpha=np.full((outh,outw,1),255,np.uint8)
        rgba.append(np.concatenate([rgb,alpha],axis=-1))
    cameras=[]
    for i,cx in enumerate((CX_LEFT,CX_RIGHT)):
        cameras.append([1,0,0,0,1,0,0,0,1,-float(i),0,0,
                        FOCAL_PIXELS*ratiox,FOCAL_PIXELS*ratioy,
                        cx*ratiox,CY*ratioy])
    result=NativeEngine().dense(np.asarray(rgba),np.asarray(cameras),near=6,far=80,
                                depth_planes=planes,pixel_stride=3)
    points=np.asarray(result['points'],float).reshape(-1,8)
    if not len(points):raise AssertionError('CPU dense stereo produced zero reciprocal points')
    # Evaluate only 3D points with a valid independently measured disparity.
    # World frame equals left camera. Baseline is 1 arbitrary unit (known only in evaluation).
    xleft=points[:,0]*FOCAL_PIXELS/points[:,2]+CX_LEFT
    yleft=points[:,1]*FOCAL_PIXELS/points[:,2]+CY
    xpix=np.rint(xleft).astype(int);ypix=np.rint(yleft).astype(int)
    valid=(xpix>=0)&(xpix<w)&(ypix>=0)&(ypix<h)
    xpix=np.clip(xpix,0,w-1);ypix=np.clip(ypix,0,h-1)
    truth=disparity[ypix,xpix].astype(float)
    valid &= np.isfinite(truth)&(truth>0)&(truth+CX_RIGHT-CX_LEFT>0)
    predicted_disp=FOCAL_PIXELS/points[:,2]-(CX_RIGHT-CX_LEFT)
    err=np.abs(predicted_disp[valid]-truth[valid])
    gt_depth=FOCAL_PIXELS/(truth[valid]+CX_RIGHT-CX_LEFT)
    rel=np.abs(points[valid,2]-gt_depth)/gt_depth
    if not len(err):raise AssertionError('No valid independently measured disparity overlap')
    return {'schema':7,'dataset':'Middlebury 2014 Motorcycle (real rectified stereo)',
            'realPhotographs':True,'cameraCount':2,'sourceImageDimensions':[w,h],
            'inputDownsampledDimensions':[outw,outh],
            'calibrationUsedForProcessing':'published rectified focal/principal offsets, arbitrary unit baseline',
            'groundTruthUsedForOptimization':False,
            'densePointCount':int(len(points)),
            'groundTruthMatchedPoints':int(len(err)),
            'sampledPixels':result['sampledPixels'],
            'photometricPixels':result['photometricPixels'],
            'consistentPixels':result['consistentPixels'],
            'disparityAbsoluteErrorPxP50':float(np.median(err)),
            'disparityAbsoluteErrorPxP90':float(np.percentile(err,90)),
            'depthRelativeErrorP50':float(np.median(rel)),
            'depthRelativeErrorP90':float(np.percentile(rel,90)),
            'limitations':['Two cameras only, not a handheld 360 object scan',
                 'No 3D mesh or photographic UV textures',
                 'Ground-truth annotations cover only evaluated pixels',
                 'Downsampled low-resolution CPU reference preview']}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=Path('reports/m07-middlebury-dense.json'))
    parser.add_argument('--gate',action='store_true')
    args=parser.parse_args()
    data=run()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(data,indent=2,sort_keys=True)+'\n')
    if args.gate:
        assert data['groundTruthMatchedPoints']>=120
        assert data['densePointCount']>=150
        assert data['disparityAbsoluteErrorPxP50']<10.0
        assert data['depthRelativeErrorP50']<.5
    print(json.dumps(data,indent=2))

if __name__=='__main__':main()
