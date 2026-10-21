from __future__ import annotations
from collections import Counter
import numpy as np


def mesh(vertices, faces):
    v, raw = np.asarray(vertices, float), np.asarray(faces)
    if v.ndim != 2 or v.shape[1] != 3 or not len(v) or not np.isfinite(v).all():
        raise ValueError('vertices must be nonempty finite Nx3')
    if raw.size == 0:
        return v, np.empty((0,3), int)
    if raw.ndim != 2 or raw.shape[1] != 3 or not np.issubdtype(raw.dtype, np.integer):
        raise ValueError('triangle indices must be integer Nx3')
    f = raw.astype(int)
    if np.any(f < 0) or np.any(f >= len(v)):
        raise ValueError('triangle index out of bounds')
    return v, f


def similarity(source, target):
    """Umeyama least-squares Sim(3), row vectors; no reflections or ICP oracle."""
    x, y = np.asarray(source, float), np.asarray(target, float)
    if x.shape != y.shape or x.ndim != 2 or x.shape[1] != 3 or len(x) < 3 or not np.isfinite([x,y]).all():
        raise ValueError('need finite matching Nx3 landmarks')
    xc, yc = x-x.mean(0), y-y.mean(0)
    if np.linalg.matrix_rank(xc) < 2 or np.linalg.matrix_rank(yc) < 2:
        raise ValueError('degenerate alignment landmarks')
    u, singular, vt = np.linalg.svd(xc.T@yc/len(x))
    d = np.ones(3); d[-1] = np.linalg.det(u@vt)
    rotation = u@np.diag(d)@vt
    scale = float(np.dot(singular,d) / np.mean(np.sum(xc*xc,axis=1)))
    if not scale > 0:
        raise ValueError('invalid similarity scale')
    translation = y.mean(0)-scale*x.mean(0)@rotation
    return scale, rotation, translation


def transform(points, alignment):
    s, r, t = alignment
    return s*np.asarray(points)@r+t


def point_surface_distances(points, vertices, faces):
    """Exact nearest triangle distances; bounded point batches; points-only is explicit."""
    p = np.asarray(points,float)
    v, f = mesh(vertices, faces)
    if p.ndim != 2 or p.shape[1] != 3 or not np.isfinite(p).all():
        raise ValueError('invalid query points')
    result = []
    for start in range(0,len(p),64):
        q = p[start:start+64]
        best = np.full(len(q),np.inf)
        if not len(f):
            for vs in range(0,len(v),2048):
                best = np.minimum(best, np.min(np.sum((q[:,None]-v[None,vs:vs+2048])**2,axis=2),axis=1))
        for fs in range(0,len(f),1024):
            tri = v[f[fs:fs+1024]]
            a,b,c = tri[:,0],tri[:,1],tri[:,2]
            ab,ac = b-a,c-a
            normal = np.cross(ab,ac); norm2 = np.sum(normal*normal,axis=1)
            delta = q[:,None]-a
            signed = np.sum(delta*normal,axis=2)
            projected = q[:,None]-signed[:,:,None]*normal[None]/np.maximum(norm2[None,:,None],1e-30)
            ap = projected-a
            aa,bb,cc = np.sum(ab*ab,axis=1),np.sum(ab*ac,axis=1),np.sum(ac*ac,axis=1)
            d,e = np.sum(ap*ab,axis=2),np.sum(ap*ac,axis=2)
            denom = aa*cc-bb*bb
            u = (cc*d-bb*e)/np.maximum(denom,1e-30)
            w = (aa*e-bb*d)/np.maximum(denom,1e-30)
            inside = (u>=0)&(w>=0)&(u+w<=1)&(norm2>1e-24)
            dist = np.where(inside,signed*signed/np.maximum(norm2,1e-30),np.inf)
            for begin,end in ((a,b),(b,c),(c,a)):
                edge=end-begin
                t=np.clip(np.sum((q[:,None]-begin)*edge,axis=2)/np.maximum(np.sum(edge*edge,axis=1),1e-30),0,1)
                dist=np.minimum(dist,np.sum((q[:,None]-begin-t[:,:,None]*edge)**2,axis=2))
            best = np.minimum(best,np.min(dist,axis=1))
        result.extend(np.sqrt(best))
    return np.asarray(result)


def surface_samples(vertices, faces, count=4096):
    """Fixed area-weighted deterministic surface sampling, independent per mesh."""
    v,f=mesh(vertices,faces)
    if not len(f): return v.copy()
    tri=v[f];area=np.linalg.norm(np.cross(tri[:,1]-tri[:,0],tri[:,2]-tri[:,0]),axis=1)/2
    if area.sum()<=0: raise ValueError('zero-area surface')
    rng=np.random.default_rng(84001)
    chosen=np.searchsorted(np.cumsum(area)/area.sum(),(np.arange(count)+.5)/count)
    a=np.sqrt(rng.random(count)); b=rng.random(count)
    return (1-a[:,None])*tri[chosen,0]+(a*(1-b))[:,None]*tri[chosen,1]+(a*b)[:,None]*tri[chosen,2]


def _segment_triangle(p,q,tri,eps=1e-10):
    a,b,c=tri; edge1=b-a;edge2=c-a;direction=q-p
    h=np.cross(direction,edge2);det=np.dot(edge1,h)
    if abs(det)<eps:return False
    inv=1/det;s=p-a;u=inv*np.dot(s,h)
    z=np.cross(s,edge1);w=inv*np.dot(direction,z);t=inv*np.dot(edge2,z)
    return eps<u<1-eps and eps<w and u+w<1-eps and eps<t<1-eps


def _coplanar_overlap(a,b,normal):
    drop=np.argmax(np.abs(normal));poly=[p for p in np.delete(a,drop,axis=1)]
    clip=np.delete(b,drop,axis=1)
    cross=lambda x,y: x[0]*y[1]-x[1]*y[0]
    orientation=np.sign(cross(clip[1]-clip[0],clip[2]-clip[0]))
    if orientation==0:return False
    for i in range(3):
        origin,end=clip[i],clip[(i+1)%3];edge=end-origin
        output=[]
        for j,p in enumerate(poly):
            q=poly[(j+1)%len(poly)]
            dp,dq=orientation*cross(edge,p-origin),orientation*cross(edge,q-origin)
            if dp>=-1e-12:output.append(p)
            if (dp<0)!=(dq<0): output.append(p+(q-p)*dp/(dp-dq))
        poly=output
        if not poly:return False
    area=abs(sum(cross(poly[i],poly[(i+1)%len(poly)]) for i in range(len(poly))))/2
    return area>1e-12


def topology(vertices, faces, intersection_budget=5000):
    v,f=mesh(vertices,faces)
    if not len(f):return {'representation':'points','self_intersections_status':'NOT_APPLICABLE','components':len(v)}
    edges=Counter();directions=Counter()
    adjacency=[set() for _ in range(len(v))]
    for a,b,c in f:
        for i,j in ((a,b),(b,c),(c,a)):
            edges[tuple(sorted((i,j)))]+=1;directions[(i,j)]+=1
            adjacency[i].add(j);adjacency[j].add(i)
    active=set(f.ravel());components=0
    while active:
        components+=1;stack=[active.pop()]
        while stack:
            for j in adjacency[stack.pop()]:
                if j in active:active.remove(j);stack.append(j)
    geometric=[tuple(sorted(tuple(p) for p in v[face])) for face in f]
    duplicates=sum(n-1 for n in Counter(geometric).values())
    degenerate=int(np.sum(np.linalg.norm(np.cross(v[f[:,1]]-v[f[:,0]],v[f[:,2]]-v[f[:,0]]),axis=1)<1e-12))
    intersections=None
    if len(f)<=intersection_budget:
        intersections=0
        triangles=v[f];low=triangles.min(1);high=triangles.max(1)
        for i in range(len(f)):
            candidates=np.flatnonzero(np.all(high[i]>=low[i+1:],axis=1)&np.all(high[i+1:]>=low[i],axis=1))+i+1
            for j in candidates:
                if geometric[i]==geometric[j]:continue
                a,b=triangles[i],triangles[j];normal=np.cross(a[1]-a[0],a[2]-a[0])
                coplanar=np.linalg.norm(normal)>1e-12 and np.max(np.abs((b-a[0])@normal))<1e-10*np.linalg.norm(normal)
                hit=_coplanar_overlap(a,b,normal) if coplanar else any(_segment_triangle(x[k],x[(k+1)%3],y) for x,y in ((a,b),(b,a)) for k in range(3))
                intersections+=int(hit)
    boundary=sum(n==1 for n in edges.values());nonmanifold=sum(n>2 for n in edges.values())
    winding=sum(n==2 and (directions[(i,j)]==2 or directions[(j,i)]==2) for (i,j),n in edges.items())
    return {'representation':'closed_manifold_candidate' if not(boundary or nonmanifold or degenerate or duplicates or winding or intersections) else 'open_or_defective_triangle_surface',
            'components':components,'boundary_edges':boundary,'nonmanifold_edges':nonmanifold,'inconsistent_winding_edges':winding,
            'duplicate_triangles':duplicates,'degenerate_triangles':degenerate,'self_intersection_pairs':intersections,
            'self_intersections_status':'PASS_CHECK_EXECUTED' if intersections is not None else 'NOT_RUN_BUDGET',
            'watertight_topology':not(boundary or nonmanifold or degenerate or duplicates or winding)}


def camera_metrics(imported_ids, registered_ids, estimated_centers=None, reference_centers=None, alignment=None, estimated_rotations=None, reference_rotations=None):
    if not imported_ids or len(set(imported_ids))!=len(imported_ids) or len(set(registered_ids))!=len(registered_ids) or not set(registered_ids)<=set(imported_ids):
        raise ValueError('invalid camera identities')
    result={'imported':len(imported_ids),'registered':len(registered_ids),'registration_fraction':len(registered_ids)/len(imported_ids),
            'unregistered_ids':sorted(set(imported_ids)-set(registered_ids))}
    if estimated_centers is not None:
        e,r=np.asarray(estimated_centers,float),np.asarray(reference_centers,float)
        if e.shape!=r.shape or e.shape!=(len(registered_ids),3) or not np.isfinite([e,r]).all():raise ValueError('camera centers mismatch')
        if alignment is not None:e=transform(e,alignment)
        err=np.linalg.norm(e-r,axis=1)
        result.update(center_error_p50=float(np.median(err)),center_error_p95=float(np.percentile(err,95)),trajectory_endpoint_drift=float(np.linalg.norm((e[-1]-e[0])-(r[-1]-r[0]))))
    if estimated_rotations is not None:
        e,r=np.asarray(estimated_rotations,float),np.asarray(reference_rotations,float)
        if e.shape!=r.shape or e.shape!=(len(registered_ids),3,3) or not np.isfinite([e,r]).all():raise ValueError('camera rotation mismatch')
        for rotations in (e,r):
            if not np.allclose(rotations@rotations.transpose(0,2,1),np.eye(3),atol=1e-6) or not np.allclose(np.linalg.det(rotations),1,atol=1e-6):raise ValueError('invalid camera rotation')
        if alignment is not None:e=e@alignment[1]
        angles=np.degrees(np.arccos(np.clip((np.trace(e@r.transpose(0,2,1),axis1=1,axis2=2)-1)/2,-1,1)))
        result['rotation_error_deg_p95']=float(np.percentile(angles,95))
    return result


def evaluate(candidate_v,candidate_f,reference_v,reference_f,eligible_reference_points, tolerance_fraction=.01,alignment=None,samples=4096):
    cv,cf=mesh(candidate_v,candidate_f);rv,rf=mesh(reference_v,reference_f)
    eligible=np.asarray(eligible_reference_points,float)
    if eligible.ndim!=2 or eligible.shape[1]!=3 or not len(eligible) or not np.isfinite(eligible).all():raise ValueError('independently fixed eligible reference samples required')
    diagonal=float(np.linalg.norm(np.ptp(rv,axis=0)))
    if diagonal<=0:raise ValueError('reference bounding box has zero diagonal')
    if not 0<tolerance_fraction<1:raise ValueError('invalid tolerance')
    if alignment is not None:cv=transform(cv,alignment)
    accuracy=point_surface_distances(surface_samples(cv,cf,samples),rv,rf)/diagonal
    completeness=point_surface_distances(eligible,cv,cf)/diagonal
    return {'reference_diagonal':diagonal,'distance_units':'fraction_reference_bbox_diagonal','eligible_reference_samples':len(eligible),
            'output_samples':len(accuracy),'accuracy_p50':float(np.median(accuracy)),'accuracy_p95':float(np.percentile(accuracy,95)),
            'accuracy_p99':float(np.percentile(accuracy,99)),'reference_distance_p95':float(np.percentile(completeness,95)),
            'completeness_fraction':float(np.mean(completeness<=tolerance_fraction)),'tolerance_fraction':tolerance_fraction,
            'topology':topology(cv,cf),'alignment':'posthoc_similarity' if alignment is not None else 'declared_same_frame',
            'metric_scale_claim':False}
