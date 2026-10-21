import os,resource
resource.setrlimit(resource.RLIMIT_AS,(1024**3,1024**3));resource.setrlimit(resource.RLIMIT_CPU,(60,60))
import json,hashlib,time,pathlib
import numpy as np
from PIL import Image,ImageDraw,ImageFont
import argparse
P=pathlib.Path
ap=argparse.ArgumentParser(description='Synthetic view01 projection measurement, not 3D completeness')
ap.add_argument('--repo',type=P,required=True);ap.add_argument('--capture',type=P,required=True)
ap.add_argument('--measurements',type=P,required=True);ap.add_argument('--measurements-sha256',required=True)
ap.add_argument('--output',type=P,required=True)
a=ap.parse_args();repo=a.repo.resolve(strict=True);capture=a.capture.resolve(strict=True);out=a.output
if out.exists() or out.is_symlink():raise ValueError('Fresh external output directory required')
out.mkdir()
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
inputs=[capture/'mesh.ply',capture/'result.json',capture/'capture.json',repo/'benchmarks/fixtures/relief/view-01.jpg',repo/'benchmarks/fixtures/relief/calibration.json',a.measurements,P(__file__)]
before={str(p):sha(p) for p in inputs};start=time.monotonic()
manifest=json.loads(inputs[2].read_text());measured=json.loads(inputs[5].read_text())
assert sha(inputs[2])==measured['captureManifestSha256']
for n in ['mesh.ply','result.json']:assert sha(capture/n)==manifest['artifacts'][n]['sha256']
assert sha(inputs[3])=='66fa84499c73e4a6cbb4af03880e6494433f7d4d32f288476c575a71aa82ab7c'
assert sha(inputs[4])=='284c73d64c92bbed50f762a5bc74b6f39d0a3955a0bd219217b6d23bbd7fd593'
assert sha(inputs[5])==a.measurements_sha256
photo_id=next(p['photoId'] for p in manifest['photos'] if p['sha256']==sha(inputs[3]));result=json.loads(inputs[1].read_text());camera=next(c for c in result['cameras'] if c['photoId']==photo_id)
r=np.asarray(camera['rotation']).reshape(3,3);t=np.asarray(camera['translation']);scale=measured['gauge']['scale']
lines=inputs[0].read_text().splitlines();end=lines.index('end_header');nv=int(next(s for s in lines[:end] if s.startswith('element vertex ')).split()[-1]);nf=int(next(s for s in lines[:end] if s.startswith('element face ')).split()[-1]);assert 0 < nv <= 200000 and 0 < nf <= 200000
v=np.array([list(map(float,s.split())) for s in lines[end+1:end+1+nv]]);faces=np.array([list(map(int,s.split())) for s in lines[end+1+nv:]]);assert faces.shape==(nf,4) and np.all(faces[:,0]==3)
points=scale*(v[:,:3]@r.T+t);rgb=v[:,6:9];assert np.isfinite(points).all() and np.isfinite(rgb).all() and np.all((rgb>=0)&(rgb<=255));assert np.all((faces[:,1:]>=0)&(faces[:,1:]<nv))
# All captured triangles are forward-facing in depth; no near-plane clipping needed.
assert np.all(points[:,2]>0), 'Near-plane crossing requires explicit reviewed clipping, not vertex dropping'
k=np.array(json.loads(inputs[4].read_text())['cameras'][0]['K']);xy=points[:,:2]/points[:,2,None]*[k[0,0],k[1,1]]+[k[0,2],k[1,2]]
w,h=800,600;canvas=np.full((h,w,3),[35,42,50],dtype=np.uint8);depth=np.full((h,w),np.inf);outside=degenerate=0
for row in faces:
 ids=row[1:];tri=xy[ids];z=points[ids,2];colors=rgb[ids]
 lo=np.maximum(np.floor(tri.min(0)).astype(int),[0,0]);hi=np.minimum(np.ceil(tri.max(0)).astype(int),[w-1,h-1])
 if np.any(hi<lo):outside+=1;continue
 x,y=np.meshgrid(np.arange(lo[0],hi[0]+1),np.arange(lo[1],hi[1]+1))
 # Integer pixels follow fixture generator's integer-center rays, not +0.5 shifted rays.
 a,b,c=tri;den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
 if abs(den)<1e-14:degenerate+=1;continue
 wa=((b[1]-c[1])*(x-c[0])+(c[0]-b[0])*(y-c[1]))/den
 wb=((c[1]-a[1])*(x-c[0])+(a[0]-c[0])*(y-c[1]))/den;wc=1-wa-wb
 weights=np.stack([wa,wb,wc],axis=-1);inside=np.all(weights>=-1e-12,axis=-1)
 inv=weights/z;iz=inv.sum(-1);valid=inside&(iz>0)
 candidate=np.divide(1.,iz,out=np.full_like(iz,np.inf),where=valid)
 prior=depth[y,x];visible=valid&(candidate<prior)
 color=np.divide(inv@colors,iz[...,None],out=np.zeros((*iz.shape,3)),where=valid[...,None])
 yy=y[visible];xx=x[visible];depth[yy,xx]=candidate[visible];canvas[yy,xx]=np.clip(np.rint(color[visible]),0,255).astype(np.uint8)

import sys
sys.path.insert(0,str(repo))
from tools.make_fixture import surface_z
source=repo/'tools/make_fixture.py'
assert sha(source)=='c3660732f2fbccf68efd6d5452fdb1701427ce060c076f6d5558c500497783f9'
inputs.append(source);before[str(source)]=sha(source)
# Reproduce the fixture generator's prescribed rays and45-step surface intersection, no image/color mask.
grid_y,grid_x=np.mgrid[:h,:w]
rays=np.stack([(grid_x-k[0,2])/k[0,0],(grid_y-k[1,2])/k[1,1],np.ones_like(grid_x)],-1)
distance=np.full((h,w),4.2)
for _ in range(45):
 truth_points=distance[:,:,None]*rays
 distance=surface_z(truth_points[:,:,0],truth_points[:,:,1])
truth_points=distance[:,:,None]*rays
truth=(np.abs(truth_points[:,:,0])<1.72)&(np.abs(truth_points[:,:,1])<1.35)
visible=np.isfinite(depth);intersection=truth&visible
truth_count=int(truth.sum());mesh_count=int(visible.sum());matched=int(intersection.sum())
err=np.abs(depth[intersection]-distance[intersection])
def quantiles(x):return {k:float(v) for k,v in zip(['p50','p90','p95','p99','max'],np.quantile(x,[.5,.9,.95,.99,1]))}
# Native counters are copied directly; they span three downsampled reference views, not this800x600 image.
scalars={key:{a:b for a,b in result[key].items() if not isinstance(b,(list,dict))} for key in ['densePreview','meshPreview','texturePreview','scanDiagnostics']}
report={'schema':1,'status':'IMAGE_PROJECTION_MEASUREMENT_NOT_3D_COMPLETENESS','method':'Camera01 exact800x600 integer-center rays/K; generator45step analytic finite-domain mask; unchanged actual mesh perspective zbuffer. No threshold/color mask, dilation, smoothing or outlier filtering.','truthPixels':truth_count,'meshVisiblePixels':mesh_count,'meshVisibleInsideTruthPixels':matched,'truthUncoveredPixels':truth_count-matched,'meshVisibleOutsideTruthPixels':mesh_count-matched,'visibleTruthImageCoverage':matched/truth_count,'uncoveredTruthFraction':1-matched/truth_count,'outsideTruthFractionOfMeshPixels':(mesh_count-matched)/mesh_count,'depthResidualAtCoveredTruthPixelsSyntheticUnits':quantiles(err),'nativeStageScalars':scalars,'inputSha256':before,'inputsUnchanged':before=={str(p):sha(p) for p in inputs},'triangles':nf,'whollyOutsideViewportTriangles':outside,'zeroProjectedAreaTriangles':degenerate,'seconds':time.monotonic()-start,'limitations':['Projected image footprint can include wrong-depth triangles; depth residual reported separately without threshold','Knowntruth mask reproduces synthetic renderer, not independent physical observation','No visible-reference3D completeness or object360coverage claim','Native depth/mesh sample counters are different denominators from imagepixels; do not subtract them as samepopulation']}
assert report['inputsUnchanged']
target=out/'coverage.json';assert not target.exists();target.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n')
print(json.dumps({k:v for k,v in report.items() if k not in ['nativeStageScalars','inputSha256']}))
print('reportSha256',sha(target))
