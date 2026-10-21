#!/usr/bin/env python3
"""M08 real stereo: measured OPEN triangle surfaces against independent Middlebury disparity.

Uses two calibrated real Motorcycle images at preview resolution. Ground truth is
consulted ONLY AFTER native depth/mesh reconstruction, never used to generate faces.
This is not a 360-degree/physical-device/high-resolution texture quality claim.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import cv2
import numpy as np
from benchmark_middlebury import load_middlebury,FOCAL_PIXELS,CX_LEFT,CX_RIGHT,CY
from native_api import NativeEngine



def projected_surface_coverage(vertices:np.ndarray, triangles:np.ndarray, truth:np.ndarray,
        focal:float, cx:float, cy:float, disparity_offset:float)->dict:
    """Evaluate a fixed GT pixel grid, not vertex counts that change with welding.

    Rasterize only existing triangles at original-image pixel centers. Perspective
    correct inverse-depth interpolation and a nearest-surface z-buffer prevent
    overlapping faces from inflating coverage. GT is consulted only afterwards.
    This measures the left stereo image, not unseen sides or whole-object coverage.
    """
    height,width=truth.shape
    xyz=vertices[:,:3]
    uv=xyz[:,:2]/xyz[:,2,None]*focal+np.array([cx,cy])
    zbuffer=np.full((height,width),np.inf)
    for ids in triangles:
        a,b,c=uv[ids];z=xyz[ids,2]
        if not np.all(z>0):continue
        lo=np.maximum(0,np.ceil(np.min([a,b,c],axis=0)).astype(int))
        hi=np.minimum([width-1,height-1],np.floor(np.max([a,b,c],axis=0)).astype(int))
        if np.any(hi<lo):continue
        denominator=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
        if abs(denominator)<1e-12:continue
        x,y=np.meshgrid(np.arange(lo[0],hi[0]+1),np.arange(lo[1],hi[1]+1))
        u=((b[1]-c[1])*(x-c[0])+(c[0]-b[0])*(y-c[1]))/denominator
        v=((c[1]-a[1])*(x-c[0])+(a[0]-c[0])*(y-c[1]))/denominator
        w=1-u-v
        inside=(u>=-1e-12)&(v>=-1e-12)&(w>=-1e-12)
        inverse_depth=u/z[0]+v/z[1]+w/z[2]
        candidate=np.full(inverse_depth.shape,np.inf)
        np.divide(1.,inverse_depth,out=candidate,where=inside&(inverse_depth>0))
        target=zbuffer[lo[1]:hi[1]+1,lo[0]:hi[0]+1]
        np.minimum(target,candidate,out=target)
    valid=np.isfinite(truth)&(truth>0)&((truth+disparity_offset)>0)
    covered=valid&np.isfinite(zbuffer)
    gt_depth=focal/(truth[covered]+disparity_offset)
    accurate=int(np.count_nonzero(np.abs(zbuffer[covered]-gt_depth)/gt_depth<.05))
    eligible=int(np.count_nonzero(valid));covered_count=int(np.count_nonzero(covered))
    if not eligible:raise AssertionError('No valid independently measured pixel grid')
    return {'groundTruthEligiblePixels':eligible,'groundTruthCoveredPixels':covered_count,
        'groundTruthAccurateCoveredPixels5pct':accurate,
        'groundTruthPixelCoverage':covered_count/eligible,
        'groundTruthAccuratePixelCoverage5pct':accurate/eligible}


def run(size:int=228,planes:int=64,glb:Path|None=None,ply:Path|None=None)->dict:
    left,right,truth=load_middlebury()
    height,width=left.shape[:2];ow=size;oh=round(height*ow/width)
    rx=ow/width;ry=oh/height
    images=[]
    for source in (left,right):
        rgb=cv2.resize(source,(ow,oh),interpolation=cv2.INTER_AREA)
        rgba=np.concatenate([rgb,np.full((oh,ow,1),255,np.uint8)],axis=-1)
        images.append(rgba)
    # Match the pixel-centre transform of the area-resized photographs.
    cameras=np.asarray([[1,0,0,0,1,0,0,0,1,-float(i),0,0,
            FOCAL_PIXELS*rx,FOCAL_PIXELS*ry,(cx+.5)*rx-.5,(CY+.5)*ry-.5]
        for i,cx in enumerate((CX_LEFT,CX_RIGHT))],dtype=np.float64)
    native=NativeEngine().surface(np.asarray(images),cameras,6.,80.,planes,3,glb=glb,ply=ply)
    if 'meshError' in native:raise AssertionError('Real-photo surface gate rejected: '+native['meshError'])
    mesh=native['mesh']; dense=native['dense']
    vertices=np.asarray(mesh['vertices'],dtype=np.float64).reshape(-1,11)
    triangles=np.asarray(mesh['triangles'],dtype=np.int64).reshape(-1,3)
    assert len(vertices)>0 and len(triangles)>0
    assert np.all((triangles>=0)&(triangles<len(vertices)))
    zp=vertices[:,2]
    xpix=np.rint(vertices[:,0]*FOCAL_PIXELS/zp+CX_LEFT).astype(int)
    ypix=np.rint(vertices[:,1]*FOCAL_PIXELS/zp+CY).astype(int)
    valid=(xpix>=0)&(xpix<width)&(ypix>=0)&(ypix<height)
    xpix=np.clip(xpix,0,width-1);ypix=np.clip(ypix,0,height-1)
    observed=truth[ypix,xpix].astype(float)
    valid &= (zp>0)&np.isfinite(observed)&(observed>0)&(observed+CX_RIGHT-CX_LEFT>0)
    disparity_pred=FOCAL_PIXELS/zp-(CX_RIGHT-CX_LEFT)
    error=np.abs(disparity_pred[valid]-observed[valid])
    gt_depth=FOCAL_PIXELS/(observed[valid]+CX_RIGHT-CX_LEFT)
    relative=np.abs(zp[valid]-gt_depth)/gt_depth
    if not len(error):raise AssertionError('No independently measured mesh/ground truth overlap')
    triangle_depths=zp[triangles]
    max_relative_jump=np.max(triangle_depths,axis=1)-np.min(triangle_depths,axis=1)
    max_relative_jump/=np.maximum(np.max(triangle_depths,axis=1),1e-8)
    coverage=projected_surface_coverage(vertices,triangles,truth,FOCAL_PIXELS,CX_LEFT,CY,CX_RIGHT-CX_LEFT)
    return {**coverage,'schema':8,'dataset':'Middlebury 2014 Motorcycle (real rectified stereo)',
        'realPhotographs':True,'cameraCount':2,'sourceImageDimensions':[width,height],
        'downsampledDimensions':[ow,oh],
        'calibration':'published rectified intrinsics, arbitrary unit baseline',
        'groundTruthUsedForOptimization':False,
        'verifiedDepthSamples':mesh['inputSamples'],
        'acceptedSurfaceSamples':mesh['acceptedSamples'],
        'fusedObservations':mesh['fusedObservations'],
        'meshVertexCount':mesh['vertexCount'],'meshTriangleCount':mesh['triangleCount'],
        'densePointCount':dense['pointCount'],
        'groundTruthMatchedMeshVertices':int(len(error)),
        'disparityAbsoluteErrorPxP50':float(np.median(error)),
        'disparityAbsoluteErrorPxP90':float(np.percentile(error,90)),
        'depthRelativeErrorP50':float(np.median(relative)),
        'depthRelativeErrorP90':float(np.percentile(relative,90)),
        'maximumFaceRelativeDepthJump':float(np.max(max_relative_jump)),
        'meshWatertight':False,'photographicUvTextures':False,
        'limitations':['Two real calibrated stereo photos only; no 360 object scan',
            'Open camera-visible surfaces, no filled holes or unobserved backside',
            'Vertex colors are sampled; no photographic UV texture atlas',
            'Baseline scale arbitrary; cannot claim dimensions in millimeters',
            'Vertex errors and fixed-pixel coverage evaluate only the left stereo image, not whole-object completeness']}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,default=Path('reports/m08-middlebury-surface.json'))
    p.add_argument('--gate',action='store_true')
    args=p.parse_args()
    result=run()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2,sort_keys=True)+'\n')
    if args.gate:
        assert result['meshTriangleCount']>=100
        assert result['meshVertexCount']>=80
        assert result['groundTruthMatchedMeshVertices']>=50
        assert result['disparityAbsoluteErrorPxP50']<5
        assert result['depthRelativeErrorP50']<.20
        assert result['maximumFaceRelativeDepthJump']<=.081
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
