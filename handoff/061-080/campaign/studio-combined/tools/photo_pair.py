#!/usr/bin/env python3
"""Offline SIFT → native two-view camera recovery → sparse GLB/PLY diagnostic.

This host harness requires calibrated pinhole images. It does NOT create a dense mesh.
It is for algorithm validation, not a server dependency of the Android application.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import time
import cv2
import numpy as np
from native_api import NativeEngine


def extract_matches(first:Path,second:Path,ka:np.ndarray,kb:np.ndarray) -> tuple[np.ndarray,dict]:
    images=[]
    for path in (first,second):
        image=cv2.imread(str(path),cv2.IMREAD_COLOR)
        if image is None: raise ValueError(f"Cannot read {path}")
        if image.shape[0]*image.shape[1]>25_000_000: raise ValueError("Host harness input limit: 25 MP per photo")
        images.append(image)
    sift=cv2.SIFT_create(nfeatures=3500)
    a,da=sift.detectAndCompute(cv2.cvtColor(images[0],cv2.COLOR_BGR2GRAY),None)
    b,db=sift.detectAndCompute(cv2.cvtColor(images[1],cv2.COLOR_BGR2GRAY),None)
    if da is None or db is None or min(len(a),len(b))<2: raise ValueError("Insufficient distinct features")
    matcher=cv2.BFMatcher(cv2.NORM_L2)
    forward=matcher.knnMatch(da,db,k=2); backward=matcher.knnMatch(db,da,k=2)
    reverse={m.queryIdx:m.trainIdx for row in backward if len(row)==2 for m,n in [row] if m.distance<.67*n.distance}
    rows=[]
    for neighbors in forward:
        if len(neighbors)!=2: continue
        m,n=neighbors
        if m.distance>=.67*n.distance or reverse.get(m.trainIdx)!=m.queryIdx: continue
        pa=np.array([*a[m.queryIdx].pt,1.]);pb=np.array([*b[m.trainIdx].pt,1.])
        na=np.linalg.solve(ka,pa);nb=np.linalg.solve(kb,pb)
        x,y=np.rint(pa[:2]).astype(int);h,w=images[0].shape[:2]
        rgb=images[0][np.clip(y,0,h-1),np.clip(x,0,w-1),::-1]
        rows.append([na[0]/na[2],na[1]/na[2],nb[0]/nb[2],nb[1]/nb[2],*rgb.tolist()])
    return np.asarray(rows,dtype=np.float64).reshape(-1,7),{"firstFeatures":len(a),"secondFeatures":len(b),"reciprocalMatches":len(rows)}


def run(first:Path,second:Path,ka:np.ndarray,kb:np.ndarray,output:Path) -> dict:
    output.mkdir(parents=True,exist_ok=True)
    started=time.monotonic()
    rows,stats=extract_matches(first,second,ka,kb)
    result=NativeEngine().reconstruct(rows,1.5/min(ka[0,0],ka[1,1],kb[0,0],kb[1,1]),output/"sparse.glb",output/"sparse.ply")
    result.update(stats)
    result["secondsOnThisHost"]=round(time.monotonic()-started,3)
    result["inputs"]=[first.name,second.name]
    result["warning"]="Two-view sparse points only. Not a dense surface mesh, not a quality claim for real-world scans."
    (output/"reconstruction.json").write_text(json.dumps(result,indent=2)+"\n")
    np.save(output/"matches.npy",rows,allow_pickle=False)
    return result


def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("first",type=Path)
    parser.add_argument("second",type=Path)
    parser.add_argument("--calibration",required=True,type=Path,help="JSON with cameras[].image and cameras[].K")
    parser.add_argument("--output",required=True,type=Path)
    args=parser.parse_args()
    metadata=json.loads(args.calibration.read_text())
    cameras={c["image"]:np.asarray(c["K"],float) for c in metadata["cameras"]}
    try: ka,kb=cameras[args.first.name],cameras[args.second.name]
    except KeyError as error: parser.error(f"Missing calibration for {error}")
    result=run(args.first,args.second,ka,kb,args.output)
    print(json.dumps({k:v for k,v in result.items() if k!="points"},indent=2))

if __name__=="__main__": main()
