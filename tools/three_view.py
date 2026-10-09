#!/usr/bin/env python3
"""Actual 3-photo feature-track recovery + shared C++ two-view SfM and PnP.

This is a deterministic, calibrated SYNTHETIC benchmark for M02, not a 3D mesh exporter.
"""
from __future__ import annotations
import json
from pathlib import Path
import cv2
import numpy as np
from native_api import NativeEngine


def features(path:Path):
    image=cv2.imread(str(path),cv2.IMREAD_COLOR)
    if image is None: raise ValueError("Missing image: "+str(path))
    keypoints,descriptors=cv2.SIFT_create(nfeatures=3500).detectAndCompute(
        cv2.cvtColor(image,cv2.COLOR_BGR2GRAY),None)
    if descriptors is None: raise ValueError("No features")
    return image, np.asarray([key.pt for key in keypoints]),descriptors


def pairs(a:np.ndarray,b:np.ndarray) -> list[tuple[int,int]]:
    matcher=cv2.BFMatcher(cv2.NORM_L2)
    forward=matcher.knnMatch(a,b,k=2)
    backward=matcher.knnMatch(b,a,k=2)
    reverse={m.queryIdx:m.trainIdx for matches in backward if len(matches)==2
             for m,n in [matches] if m.distance < .75*n.distance}
    return [(m.queryIdx,m.trainIdx) for matches in forward if len(matches)==2
            for m,n in [matches] if m.distance < .75*n.distance and reverse.get(m.trainIdx)==m.queryIdx]


def normalized(p:np.ndarray,k:np.ndarray) -> np.ndarray:
    return (p-np.array([k[0,2],k[1,2]]))/np.array([k[0,0],k[1,1]])


def run_three(folder:Path, output:Path|None=None) -> dict:
    meta=json.loads((folder/'calibration.json').read_text())
    if len(meta['cameras'])<3: raise ValueError('Need three calibrated photographs')
    imgs=[folder/x['image'] for x in meta['cameras'][:3]]
    data=[features(im) for im in imgs]
    k=[np.array(x['K']) for x in meta['cameras'][:3]]
    ab=pairs(data[0][2],data[1][2]);ac=pairs(data[0][2],data[2][2]);bc=pairs(data[1][2],data[2][2])
    if len(ab)<20:raise ValueError('Insufficient anchors')
    rows=[]
    for a,b in ab:
        pa=normalized(data[0][1][a],k[0]);pb=normalized(data[1][1][b],k[1]);x,y=np.rint(data[0][1][a]).astype(int)
        h,w=data[0][0].shape[:2]
        rgb=data[0][0][np.clip(y,0,h-1),np.clip(x,0,w-1),::-1]
        rows.append([*pa,*pb,*rgb.tolist()])
    engine=NativeEngine();anchor=engine.reconstruct(np.asarray(rows),1.5/min(k[0][0,0],k[1][0,0]))
    a2c=dict(ac);b2c=dict(bc)
    xyz_uv=[]
    for point in anchor['points']:
        index=int(point[8]);ia,ib=ab[index]
        third=a2c.get(ia)
        if third is None or b2c.get(ib)!=third:continue
        xyz_uv.append([*point[:3],*normalized(data[2][1][third],k[2])])
    if len(xyz_uv)<16:raise ValueError(f'Only {len(xyz_uv)} three-view tracks')
    camera=engine.register_camera(np.asarray(xyz_uv),2.0/k[2][0,0])
    result={"schema":2,"representation":"three_view_camera_pose","synthetic":meta['synthetic'],
            "anchorPoints":anchor['pointCount'],"anchorPairs":len(ab),"trackCandidates":len(xyz_uv),
            "registeredCameras":3,"thirdCamera":camera,"anchorCamera":{
                "rotation":anchor['rotation'],"translation":anchor['translation']}}
    if output:
        output.mkdir(parents=True,exist_ok=True)
        (output/'m02-three-view.json').write_text(json.dumps(result,indent=2)+'\n')
    return result


if __name__=='__main__':
    folder=Path(__file__).resolve().parents[1]/'benchmarks/fixtures/relief'
    out=Path(__file__).resolve().parents[1]/'build/three-view'
    r=run_three(folder,out)
    print(json.dumps({k:v for k,v in r.items() if k!='thirdCamera'},indent=2))
    print('third pose:',json.dumps({k:v for k,v in r['thirdCamera'].items() if k!='inlierIndices'},indent=2))
