from __future__ import annotations
from collections import defaultdict
import numpy as np


def linear_rgb(srgb):
    x=np.asarray(srgb,float)
    if not np.isfinite(x).all() or np.any((x<0)|(x>1)):raise ValueError('invalid normalized sRGB')
    return np.where(x<=.04045,x/12.92,((x+.055)/1.055)**2.4)


def sample(texture, uv):
    tex=np.asarray(texture,float);p=np.asarray(uv,float)
    if tex.ndim!=3 or tex.shape[2]!=4 or not np.isfinite(tex).all() or np.any((tex<0)|(tex>1)):raise ValueError('texture must be normalized RGBA')
    if p.shape[-1]!=2 or not np.isfinite(p).all():raise ValueError('invalid texture coordinates')
    return _sample_validated(tex,p)


def _sample_validated(tex,p):
    x=np.clip(p[...,0]*tex.shape[1]-.5,0,tex.shape[1]-1);y=np.clip(p[...,1]*tex.shape[0]-.5,0,tex.shape[0]-1)
    x0=x.astype(int);y0=y.astype(int);x1=np.minimum(x0+1,tex.shape[1]-1);y1=np.minimum(y0+1,tex.shape[0]-1)
    dx=(x-x0)[...,None];dy=(y-y0)[...,None]
    return (1-dy)*((1-dx)*tex[y0,x0]+dx*tex[y0,x1])+dy*((1-dx)*tex[y1,x0]+dx*tex[y1,x1])


def _area(poly):
    if len(poly)<3:return 0.
    poly=np.asarray(poly);return abs(np.sum(poly[:,0]*np.roll(poly[:,1],-1)-poly[:,1]*np.roll(poly[:,0],-1)))/2


def overlap_area(a,b):
    cross=lambda x,y:x[0]*y[1]-x[1]*y[0]
    orientation=np.sign(cross(b[1]-b[0],b[2]-b[0]));poly=list(a)
    if not orientation:return 0.
    for i in range(3):
        origin,end=b[i],b[(i+1)%3];edge=end-origin;out=[]
        for j,p in enumerate(poly):
            q=poly[(j+1)%len(poly)];dp=orientation*cross(edge,p-origin);dq=orientation*cross(edge,q-origin)
            if dp>=-1e-12:out.append(p)
            if (dp<0)!=(dq<0):out.append(p+(q-p)*dp/(dp-dq))
        poly=out
        if not poly:return 0.
    return _area(poly)


def uv_metrics(vertices,faces,uv,texture,supported_faces=None,original_face_count=None,overlap_budget=5000):
    from tools.qa.geometry_quality.metrics import mesh
    v,f=mesh(vertices,faces);uv=np.asarray(uv,float);tex=np.asarray(texture,float)
    if uv.shape!=(len(v),2) or not np.isfinite(uv).all():raise ValueError('UV count/nonfinite mismatch')
    if not len(f):raise ValueError('texture evaluation requires triangles')
    sample(tex,uv[:1])
    if original_face_count is None:original_face_count=len(f)
    if type(original_face_count)!=int or original_face_count<len(f):raise ValueError('original geometry denominator invalid')
    tri=v[f];utri=uv[f]
    area3=np.linalg.norm(np.cross(tri[:,1]-tri[:,0],tri[:,2]-tri[:,0]),axis=1)/2
    area2=np.abs((utri[:,1,0]-utri[:,0,0])*(utri[:,2,1]-utri[:,0,1])-(utri[:,1,1]-utri[:,0,1])*(utri[:,2,0]-utri[:,0,0]))/2
    valid=(area3>1e-12)&(area2>1e-14)
    density=np.sqrt(area2[valid]*tex.shape[0]*tex.shape[1]/area3[valid])
    distortions=[]
    for t,u in zip(tri[valid],utri[valid]):
        e1=t[1]-t[0];e2=t[2]-t[0];e1n=e1/np.linalg.norm(e1)
        basis=np.array([[np.linalg.norm(e1),np.dot(e2,e1n)],[0,np.linalg.norm(e2-np.dot(e2,e1n)*e1n)]])
        jac=np.diag([tex.shape[1],tex.shape[0]])@(u[1:]-u[0]).T@np.linalg.inv(basis)
        singular=np.linalg.svd(jac,compute_uv=False);distortions.append(float(singular[0]/singular[-1]))
    centers=utri.mean(axis=1)
    # Fixed seven interior probes per source face, with area denominator retained.
    bary=np.array([[1/3]*3,[.8,.1,.1],[.1,.8,.1],[.1,.1,.8],[.45,.45,.1],[.45,.1,.45],[.1,.45,.45]])
    alpha=sample(tex,np.einsum('sc,fcd->fsd',bary,utri))[...,3]
    supported=(alpha>=.99).all(axis=1)&valid&np.all((utri>=0)&(utri<=1),axis=(1,2))
    if supported_faces is not None:
        declared=np.asarray(supported_faces)
        if declared.shape!=(len(f),) or declared.dtype!=bool:raise ValueError('supported face mask mismatch')
        supported &= declared
    supported_area=float(np.sum(area3[supported]));total_area=float(area3.sum())
    overlap_pairs=None;overlap_sum=None;overlap_examples=[]
    if len(f)<=overlap_budget:
        overlap_pairs=0;overlap_sum=0.;low=utri.min(1);high=utri.max(1)
        for i in range(len(f)):
            candidates=np.flatnonzero(np.all(high[i]>low[i+1:],axis=1)&np.all(high[i+1:]>low[i],axis=1))+i+1
            for j in candidates:
                area=overlap_area(utri[i],utri[j])
                if area>1e-12:
                    overlap_pairs+=1;overlap_sum+=area
                    if len(overlap_examples)<5:overlap_examples.append({'face_a':int(i),'face_b':int(j),'area':float(area),'uv_a':utri[i].tolist(),'uv_b':utri[j].tolist()})
    # Geometric shared edges may use duplicated UV vertices; compare both chart sides.
    edges=defaultdict(list)
    for face_index,face in enumerate(f):
        for a,b in ((0,1),(1,2),(2,0)):
            pa,pb=tuple(v[face[a]]),tuple(v[face[b]])
            if pa>pb:pa,pb=pb,pa;a,b=b,a
            edges[(pa,pb)].append((face_index,uv[face[a]],uv[face[b]]))
    seam_values=[];seam_edges=0;unknown_seams=0;adjacency=[set() for _ in f]
    t=np.linspace(.05,.95,16)[:,None]
    for records in edges.values():
        if len(records)!=2:continue
        (i,a,b),(j,c,d)=records
        if np.allclose([a,b],[c,d],atol=1e-10):adjacency[i].add(j);adjacency[j].add(i);continue
        seam_edges+=1
        left,right=_sample_validated(tex,a+(b-a)*t),_sample_validated(tex,c+(d-c)*t)
        good=(left[:,3]>=.99)&(right[:,3]>=.99)
        unknown_seams+=int(not good.all())
        if good.any():seam_values.extend(np.mean(np.abs(linear_rgb(left[good,:3])-linear_rgb(right[good,:3])),axis=1))
    unseen=set(range(len(f)));charts=0
    while unseen:
        charts+=1;stack=[unseen.pop()]
        while stack:
            for j in adjacency[stack.pop()]:
                if j in unseen:unseen.remove(j);stack.append(j)
    return {'uv_out_of_bounds_vertices':int(np.sum(np.any((uv<0)|(uv>1),axis=1))),
            'degenerate_uv_faces':int(np.sum(area2<=1e-14)),'degenerate_geometry_faces':int(np.sum(area3<=1e-12)),
            'uv_overlap_pairs':overlap_pairs,'uv_overlap_area_sum':overlap_sum,'overlap_examples':overlap_examples,'overlap_status':'CHECKED' if overlap_pairs is not None else 'NOT_RUN_BUDGET',
            'chart_count':charts,'distortion_anisotropy_p95':float(np.percentile(distortions,95)) if distortions else None,
            'texel_density_p50':float(np.median(density)) if len(density) else None,'density_units':'texels_per_declared_world_unit',
            'supported_faces':int(supported.sum()),'candidate_faces':len(f),'original_faces':original_face_count,
            'supported_face_fraction_original':int(supported.sum())/original_face_count,'retained_geometry_fraction':len(f)/original_face_count,
            'supported_area_fraction_candidate':supported_area/total_area if total_area else 0.,
            'potential_seam_edges':seam_edges,'unknown_seam_edges':unknown_seams,'seam_linear_rgb_abs_p95':float(np.percentile(seam_values,95)) if seam_values else None,
            'support_sampling':'seven fixed interior probes per face; not proof every texel supported'}


def alpha_metrics(texture, independently_expected_support):
    tex=np.asarray(texture,float);mask=np.asarray(independently_expected_support)
    if mask.dtype!=bool or mask.shape!=tex.shape[:2]:raise ValueError('independent support mask dimensions/type mismatch')
    alpha=tex[:,:,3]
    return {'expected_known_pixels':int(mask.sum()),'missing_known_pixels':int(np.sum(mask&(alpha<.99))),
            'unknown_pixels':int((~mask).sum()),'opaque_unknown_pixels':int(np.sum((~mask)&(alpha>.01)))}


def render(vertices,faces,uv,texture,width,height,K,R,t):
    """Independent pinhole z-buffer, perspective-correct UV; integer pixel centers."""
    from tools.qa.geometry_quality.metrics import mesh
    v,f=mesh(vertices,faces);uv=np.asarray(uv,float)
    K,R,t=np.asarray(K,float),np.asarray(R,float),np.asarray(t,float)
    if K.shape!=(3,3) or R.shape!=(3,3) or t.shape!=(3,) or not np.isfinite([K,R]).all() or not np.isfinite(t).all() or not np.allclose(R@R.T,np.eye(3),atol=1e-6) or not np.isclose(np.linalg.det(R),1):raise ValueError('invalid camera pose/intrinsics')
    if type(width)!=int or type(height)!=int or not 0<width*height<=16000000:raise ValueError('render image budget')
    if uv.shape!=(len(v),2) or not np.isfinite(uv).all():raise ValueError('invalid UVs')
    texture=np.asarray(texture,float);sample(texture,uv[:1])
    camera=v@R.T+t;projected=camera@K.T
    image=np.zeros((height,width,4),float);depth=np.full((height,width),np.inf)
    for face in f:
        z=camera[face,2]
        if np.any(z<=1e-8):continue
        p=projected[face,:2]/projected[face,2,None]
        lo=np.maximum(np.ceil(p.min(0)).astype(int),[0,0]);hi=np.minimum(np.floor(p.max(0)).astype(int),[width-1,height-1])
        if np.any(lo>hi):continue
        mat=np.vstack([p.T,np.ones(3)])
        if abs(np.linalg.det(mat))<1e-12:continue
        ys,xs=np.mgrid[lo[1]:hi[1]+1,lo[0]:hi[0]+1]
        bary=np.stack([xs,ys,np.ones_like(xs)],-1)@np.linalg.inv(mat).T
        inside=(bary>=-1e-10).all(axis=2);weights=bary/z;invdepth=weights.sum(axis=2)
        d=1/np.maximum(invdepth,1e-30);weights/=np.maximum(invdepth[:,:,None],1e-30)
        coords=weights@uv[face]
        rgba=_sample_validated(texture,coords)
        good=inside&(d<depth[ys,xs])
        image[ys[good],xs[good]]=rgba[good];depth[ys[good],xs[good]]=d[good]
    return image,depth


def held_out_metrics(predicted,reference,independent_visible_mask):
    a,b=np.asarray(predicted,float),np.asarray(reference,float);mask=np.asarray(independent_visible_mask)
    if a.shape!=b.shape or a.ndim!=3 or a.shape[2]!=4 or mask.shape!=a.shape[:2] or mask.dtype!=bool or not mask.any():raise ValueError('held-out comparison shape/mask mismatch')
    if not np.isfinite([a,b]).all() or np.any((a<0)|(a>1)|(b<0)|(b>1)):raise ValueError('invalid held-out RGBA')
    supported=mask&(a[:,:,3]>=.99)&(b[:,:,3]>=.99)
    error=np.abs(linear_rgb(a[supported,:3])-linear_rgb(b[supported,:3]))
    return {'eligible_reference_pixels':int(mask.sum()),'compared_pixels':int(supported.sum()),
            'held_out_support_fraction':float(supported.sum()/mask.sum()),
            'linear_rgb_rmse':float(np.sqrt(np.mean(error*error))) if error.size else None,
            'linear_rgb_abs_p95':float(np.percentile(error,95)) if error.size else None,
            'alignment':'declared calibrated camera, no optimized image warp',
            'occlusion':'independent supplied reference visibility mask, never candidate-derived'}


def geometry_retention(vertices,faces,original_vertices,original_faces):
    from collections import Counter
    from tools.qa.geometry_quality.metrics import mesh
    v,f=mesh(vertices,faces);ov,of=mesh(original_vertices,original_faces)
    if not len(of):raise ValueError('original mesh must have faces')
    key=lambda tri:tuple(sorted(tuple(p) for p in np.round(tri,6)))
    available=Counter(key(tri) for tri in ov[of]);matched=0
    for tri in v[f]:
        k=key(tri)
        if available[k]<=0:raise ValueError('candidate face absent from original geometry or duplicated')
        available[k]-=1;matched+=1
    return {'matched_source_faces':matched,'original_faces':len(of),'retained_original_face_fraction':matched/len(of),'matching':'coordinate triangles rounded to six decimals, same declared frame'}
