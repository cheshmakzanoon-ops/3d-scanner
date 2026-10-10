#!/usr/bin/env python3
"""M06 calibrated, synthetic PHOTOGRAPHS through SIFT, native SfM, PnP and joint BA.

All poses/points are ESTIMATED from image matches. Ground-truth calibration K is used
only for image-ray normalization; original known poses/geometry are not fed to solver.
"""
from __future__ import annotations
import json
from pathlib import Path
import numpy as np
from native_api import NativeEngine
from three_view import features, pairs, normalized

ROOT=Path(__file__).resolve().parents[1]

def run(folder:Path=ROOT/'benchmarks/fixtures/relief') -> dict:
    meta=json.loads((folder/'calibration.json').read_text())
    frames=[features(folder/c['image']) for c in meta['cameras'][:3]]
    ks=[np.asarray(c['K']) for c in meta['cameras'][:3]]
    matches=pairs(frames[0][2],frames[1][2]);ac=dict(pairs(frames[0][2],frames[2][2]));bc=dict(pairs(frames[1][2],frames[2][2]))
    engine=NativeEngine()
    initial=[]
    for a,b in matches:
        uv0=normalized(frames[0][1][a],ks[0]);uv1=normalized(frames[1][1][b],ks[1]);x,y=np.rint(frames[0][1][a]).astype(int)
        h,w=frames[0][0].shape[:2]
        rgb=frames[0][0][np.clip(y,0,h-1),np.clip(x,0,w-1),::-1]
        initial.append([*uv0,*uv1,*rgb.tolist()])
    anchor=engine.reconstruct(np.asarray(initial),1.5/min(ks[0][0,0],ks[1][0,0]))
    trackMap={};third=[]
    for index,point in enumerate(anchor['points']):
        a,b=matches[int(point[8])]
        c=ac.get(a)
        if c is not None and bc.get(b)==c:
            third.append([*point[:3],*normalized(frames[2][1][c],ks[2])]);trackMap[index]=c
    thirdPose=engine.register_camera(np.asarray(third),2./ks[2][0,0])
    cameras=np.array([[1,0,0,0,1,0,0,0,1,0,0,0],
                      [*anchor['rotation'],*anchor['translation']],
                      [*thirdPose['rotation'],*thirdPose['translation']]],dtype=np.float64)
    seeds=[];observations=[]
    for index,point in enumerate(anchor['points']):
        a,b=matches[int(point[8])]
        seeds.append([index,*point[:3],*point[3:6]])
        observations.append([index,0,*normalized(frames[0][1][a],ks[0]),*point[3:6]])
        observations.append([index,1,*normalized(frames[1][1][b],ks[1]),*point[3:6]])
        if index in trackMap:
            observations.append([index,2,*normalized(frames[2][1][trackMap[index]],ks[2]),*point[3:6]])
    ba=engine.bundle_adjust(np.asarray(cameras),np.asarray(seeds),np.asarray(observations),.007,15)
    result={
        'fixture':'synthetic rendered relief photographs; no physical object',
        'cameraCount':3,'initialAnchorPoints':anchor['pointCount'],'thirdViewTrackCount':len(third),
        'bundlePoints':ba['pointCount'],'jointSolver':ba['optimization'],
        'medianErrorBefore':ba['medianErrorBeforeNormalized'],
        'medianErrorAfter':ba['medianErrorAfterNormalized'],
        'costBefore':ba['robustCostBefore'],'costAfter':ba['robustCostAfter'],
        'acceptedSteps':ba['acceptedSteps'],'fixedAnchors':ba['anchorCamerasFixed'],
    }
    return result

if __name__=='__main__':
    print(json.dumps(run(),indent=2))
