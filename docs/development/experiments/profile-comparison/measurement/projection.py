import numpy as np
def projection(v,faces,r,t,scale,K,surface_z):
 nv=len(v)
 points=scale*(v[:,:3]@r.T+t);rgb=v[:,6:9];assert np.isfinite(points).all() and np.isfinite(rgb).all() and np.all((rgb>=0)&(rgb<=255));assert np.all((faces[:,1:]>=0)&(faces[:,1:]<nv))
 # All captured triangles are forward-facing in depth; no near-plane clipping needed.
 assert np.all(points[:,2]>0), 'Near-plane crossing requires explicit reviewed clipping, not vertex dropping'
 k=np.array(K);xy=points[:,:2]/points[:,2,None]*[k[0,0],k[1,1]]+[k[0,2],k[1,2]]
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
 def quantiles(x):return None if not len(x) else {k:float(v) for k,v in zip(['p50','p90','p95','p99','max'],np.quantile(x,[.5,.9,.95,.99,1]))}
 return dict(truthPixels=truth_count,meshVisiblePixels=mesh_count,meshVisibleInsideTruthPixels=matched,truthUncoveredPixels=truth_count-matched,meshVisibleOutsideTruthPixels=mesh_count-matched,visibleTruthImageCoverage=matched/truth_count,depthResidualAtCoveredTruthPixelsSyntheticUnits=quantiles(err),whollyOutsideViewportTriangles=outside,zeroProjectedAreaTriangles=degenerate)
