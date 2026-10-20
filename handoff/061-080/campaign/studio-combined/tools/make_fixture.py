#!/usr/bin/env python3
"""Render a reproducible, explicitly SYNTHETIC non-planar stereo regression fixture.

This is a software-generated camera/geometry test, not evidence of real-object scan quality.
The two JPEGs show the same procedurally textured relief from translated cameras.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import cv2
import numpy as np


def surface_z(x: np.ndarray, y: np.ndarray) -> np.ndarray:
    return 4.2 + 0.52*np.sin(2.4*x)*np.cos(2.0*y) + 0.24*np.cos(4.4*y) + 0.22*np.exp(-((x-.4)**2+(y+.3)**2)*7)


def camera(center: np.ndarray, target: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    forward = target-center; forward /= np.linalg.norm(forward)
    right = np.cross(np.array([0., 1., 0.]), forward); right /= np.linalg.norm(right)
    down = np.cross(forward, right)
    rotation = np.stack([right, down, forward])
    return rotation, -rotation@center


def texture(seed: int = 109) -> np.ndarray:
    rng = np.random.default_rng(seed)
    base = rng.integers(30, 225, (1000, 1200, 3), dtype=np.uint8)
    base = cv2.GaussianBlur(base, (5, 5), .8)
    for _ in range(1000):
        x,y = rng.integers([0,0],[1200,1000]); radius=int(rng.integers(2,18))
        color=tuple(map(int,rng.integers(5,245,3)))
        cv2.circle(base,(int(x),int(y)),radius,color,int(rng.choice([-1,1,2])))
    for i in range(60):
        x,y=rng.integers([10,30],[1100,960])
        cv2.putText(base, f"{i:02x}", (int(x),int(y)), cv2.FONT_HERSHEY_SIMPLEX, .6, (240,220,190), 1, cv2.LINE_AA)
    return base


def generate(destination: Path) -> dict:
    destination.mkdir(parents=True, exist_ok=True)
    width,height,focal=800,600,760.
    k=np.array([[focal,0,(width-1)/2],[0,focal,(height-1)/2],[0,0,1.]])
    grid_y,grid_x=np.mgrid[:height,:width]
    rays=np.stack([(grid_x-k[0,2])/focal,(grid_y-k[1,2])/focal,np.ones_like(grid_x)],-1)
    tex=texture()
    manifest={"synthetic":True,"description":"Procedural textured relief; not a photographed physical object", "worldUnits":"arbitrary synthetic units", "width":width,"height":height,"cameras":[]}
    for index,center in enumerate([np.array([0.,0.,0.]),np.array([.64,.04,.03]),np.array([-.45,.07,.01])]):
        r,t=camera(center,np.array([0.,0.,4.2]))
        directions=rays@r
        distance=(4.2-center[2])/directions[:,:,2]
        for _ in range(45):
            points=center+distance[:,:,None]*directions
            distance=(surface_z(points[:,:,0],points[:,:,1])-center[2])/directions[:,:,2]
        points=center+distance[:,:,None]*directions
        valid=(np.abs(points[:,:,0])<1.72)&(np.abs(points[:,:,1])<1.35)
        uvx=((points[:,:,0]+1.72)/3.44*(tex.shape[1]-1)).astype(np.float32)
        uvy=((points[:,:,1]+1.35)/2.70*(tex.shape[0]-1)).astype(np.float32)
        image=cv2.remap(tex,uvx,uvy,cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
        image[~valid]=(18,18,18)
        name=f"view-{index+1:02d}.jpg"
        if not cv2.imwrite(str(destination/name),image,[cv2.IMWRITE_JPEG_QUALITY,97]):
            raise OSError("Could not save fixture")
        manifest["cameras"].append({"image":name,"K":k.tolist(),"R":r.tolist(),"t":t.tolist(),"center":center.tolist()})
    (destination/"calibration.json").write_text(json.dumps(manifest,indent=2)+"\n")
    return manifest

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output",type=Path)
    args=parser.parse_args()
    print(json.dumps(generate(args.output),indent=2))
