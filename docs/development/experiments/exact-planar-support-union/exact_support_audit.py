"""Independent rational polygon clipping of serialized candidate vs source support.
No GEOS predicates, buffers, grid snapping, or positive-area tolerances.
Coordinates are the exact binary64 chart projection values used by the kernel.
"""
from fractions import Fraction as F
import json,time
from pathlib import Path
import numpy as np
ROOT=Path(__file__).resolve().parent
OPERATIONS=0;DEADLINE=float('inf')

def cross(a,b,p):return (b[0]-a[0])*(p[1]-a[1])-(b[1]-a[1])*(p[0]-a[0])
def area(p):return abs(sum(a[0]*b[1]-b[0]*a[1] for a,b in zip(p,p[1:]+p[:1])))/2 if len(p)>2 else F(0)
def oriented(p):return p if sum(a[0]*b[1]-b[0]*a[1] for a,b in zip(p,p[1:]+p[:1]))>0 else p[::-1]
def clip(poly,a,b,inside=True):
    global OPERATIONS
    OPERATIONS+=1
    if OPERATIONS>2000000 or time.monotonic()>DEADLINE:raise RuntimeError('Independent rational audit resource budget exceeded')
    result=[]
    for p,q in zip(poly,poly[1:]+poly[:1]):
        cp=cross(a,b,p);cq=cross(a,b,q);ip=cp>=0 if inside else cp<=0;iq=cq>=0 if inside else cq<=0
        if ip:result.append(p)
        if ip!=iq:
            t=cp/(cp-cq);result.append(tuple(p[i]+t*(q[i]-p[i]) for i in range(2)))
    return result

def intersect(p,q):
    for a,b in zip(q,q[1:]+q[:1]):
        p=clip(p,a,b)
        if not area(p):return []
    return p

def bounds(p):return (min(x[0] for x in p),min(x[1] for x in p),max(x[0] for x in p),max(x[1] for x in p))
def bbox_overlap(a,b):return a[0]<b[2] and b[0]<a[2] and a[1]<b[3] and b[1]<a[3]
def subtract(p,q):
    pieces=[]
    for a,b in zip(q,q[1:]+q[:1]):
        outside=clip(p,a,b,False)
        if area(outside):pieces.append(outside)
        p=clip(p,a,b)
        if not area(p):break
    return pieces

def uncovered(poly,cover):
    remaining=[poly]
    for q,bq in cover:
        following=[]
        for p in remaining:
            following.extend(subtract(p,q) if bbox_overlap(bounds(p),bq) else [p])
        if len(following)>32768:raise RuntimeError('Independent rational audit fragment budget exceeded')
        remaining=following
        if not remaining:break
    return sum(map(area,remaining),F(0))
def project(vertices,R,t):
    q=np.array(vertices)@R.T+t;uv=q[:,:2]/q[:,2,None]
    return [tuple(F(float(x)) for x in row) for row in uv]
def audit(name,directory=None):
    directory=ROOT/'results' if directory is None else Path(directory)
    source=json.loads((directory/f'{name}-input.json').read_text());candidate=json.loads((directory/f'{name}-candidate.json').read_text())
    result=audit_data(source,candidate)
    print(name,json.dumps(result),flush=True);return result

def audit_data(source,candidate):
    global OPERATIONS,DEADLINE
    OPERATIONS=0;DEADLINE=time.monotonic()+30
    if len(source['triangles'])>4096 or len(candidate['triangles'])>256:raise RuntimeError('Independent rational audit input budget exceeded')
    anchor=min(source['cameras'],key=lambda c:tuple(c['rotation']+c['translation']));R=np.array(anchor['rotation']).reshape(3,3);t=np.array(anchor['translation']);byid={o['id']:o for o in source['observations']}
    sources=[oriented(project([byid[i]['xyz'] for i in tri['sources']],R,t)) for tri in source['triangles']]
    points=project(candidate['vertices'],R,t);faces=[oriented([points[i] for i in tri]) for tri in candidate['triangles']]
    sb=[(p,bounds(p)) for p in sources];fb=[(p,bounds(p)) for p in faces]
    lost=sum((uncovered(p,fb) for p in sources),F(0));added=sum((uncovered(p,sb) for p in faces),F(0))
    result={'lostSupportExact':str(lost),'addedSupportExact':str(added),'faces':len(faces),'sourceFaces':len(sources)}
    first=[byid[i]['xyz'] for i in source['triangles'][0]['sources']]
    p=[[F(float(v)) for v in row] for row in first]
    a=[p[1][i]-p[0][i] for i in range(3)];b=[p[2][i]-p[0][i] for i in range(3)]
    n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
    plane_origin=p[0]
    def on_plane(row):return sum(n[i]*(F(float(row[i]))-plane_origin[i]) for i in range(3))==0
    axes=[i for i in range(3) if i!=max(range(3),key=lambda i:abs(n[i]))]
    def world_project(row):return tuple(F(float(row[i])) for i in axes)
    ws=[oriented([world_project(byid[i]['xyz']) for i in tri['sources']]) for tri in source['triangles']]
    wp=[world_project(row) for row in candidate['vertices']]
    wf=[oriented([wp[i] for i in tri]) for tri in candidate['triangles']]
    wsb=[(p,bounds(p)) for p in ws];wfb=[(p,bounds(p)) for p in wf]
    result['lostWorldPlaneSupportExact']=str(sum((uncovered(p,wfb) for p in ws),F(0)))
    result['addedWorldPlaneSupportExact']=str(sum((uncovered(p,wsb) for p in wf),F(0)))
    badlinks=[]
    for fi,p in enumerate(wf):
        exact={i for i,(q,bq) in enumerate(wsb) if bbox_overlap(bounds(p),bq) and area(intersect(p,q))>0}
        actual=set(candidate['faceSourceTriangles'][fi])
        if exact!=actual:badlinks.append({'face':fi,'falseLinks':sorted(actual-exact),'missingLinks':sorted(exact-actual)})
    result['overlapLinkMismatches']=badlinks
    result['emittedVerticesExactlyOnSourcePlane']=all(on_plane(row) for row in candidate['vertices'])
    result['sourceVerticesExactlyCoplanar']=all(on_plane(byid[i]['xyz']) for tri in source['triangles'] for i in tri['sources'])
    return result
if __name__=='__main__':
    result={name:audit(name) for name in ['identical','translated','permuted','rotated','partial','complementary','hole']}
    (ROOT/'exact-support-audit.json').write_text(json.dumps(result,indent=2))
    if not all(r['lostSupportExact']=='0' and r['addedSupportExact']=='0' and r['lostWorldPlaneSupportExact']=='0' and r['addedWorldPlaneSupportExact']=='0' and not r['overlapLinkMismatches'] and r['emittedVerticesExactlyOnSourcePlane'] and r['sourceVerticesExactlyCoplanar'] for r in result.values()):raise AssertionError('Exact support or overlap provenance audit failed')
