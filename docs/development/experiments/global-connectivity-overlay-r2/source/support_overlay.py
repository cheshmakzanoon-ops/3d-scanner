"""Reconstructed all-source measured-support overlay. Engineering hypothesis only."""
from exact_geometry import *
SIMPLEX=[(F(0),F(0)),(F(1),F(0)),(F(0),F(1))]
TOL=F(.055)

def values_line(v,B):return B.number(v[1]-v[0]),B.number(v[2]-v[0]),B.number(v[0])
def source_record(sid,cam,ids,source_xyz,candidate_xyz,B):
    if not isinstance(sid,int) or not 0<=sid<40000 or not isinstance(cam,int) or not 0<=cam<4:raise Refusal('SOURCE_RECORD_ID')
    if len(ids)!=3 or len(set(ids))!=3 or any(not isinstance(i,int) or not 0<=i<20000 for i in ids):raise Refusal('SOURCE_CORNER_ID')
    if len(source_xyz)!=3 or len(candidate_xyz)!=3:raise Refusal('TRIANGLE_DIMENSION')
    src=[vector(p,3,B) for p in source_xyz];cand=[vector(p,3,B) for p in candidate_xyz]
    if any(p[2]<=0 for p in src+cand):raise Refusal('NONPOSITIVE_PROJECTED_TRIANGLE')
    proj=[(B.number(p[0]/p[2]),B.number(p[1]/p[2])) for p in src]
    winding=cross2(*proj,B)
    if not winding:raise Refusal('DEGENERATE_PROJECTED_SOURCE')
    if winding<0:
        src[1],src[2]=src[2],src[1];proj[1],proj[2]=proj[2],proj[1];ids=(ids[0],ids[2],ids[1])
    lines=[]
    for a,b in zip(proj,proj[1:]+proj[:1]):
        vals=[B.number((b[0]-a[0])*(p[1]-a[1]*p[2])-(b[1]-a[1])*(p[0]-a[0]*p[2])) for p in cand]
        lines.append(values_line(vals,B))
    inv=affine(proj,[B.number(1/p[2]) for p in src],B)
    ratio=values_line([B.number(inv[0]*p[0]+inv[1]*p[1]+inv[2]*p[2]) for p in cand],B)
    cp=[(B.number(p[0]/p[2]),B.number(p[1]/p[2])) for p in cand]
    return {'id':sid,'camera':cam,'corners':tuple(ids),'lines':lines,'ratio':ratio,'surface_support':bool(cross2(*cp,B))}

def depth_lines(r,B):
    x,y,z=r['ratio']
    return (x,y,B.number(z-(1-TOL))),(-x,-y,B.number(1/(1-TOL)-z))
def overlay(records,B):
    if len(records)>40000 or len({r['id'] for r in records})!=len(records):raise Refusal('RECORD_COUNT_OR_DUPLICATE_ID')
    patches=[(SIMPLEX,{})]
    for r in sorted(records,key=lambda r:r['id']):
        nxt=[]
        for poly,labels in patches:
            outside,inside=cut(poly,r['lines'],B);nxt.extend((p,labels) for p in outside)
            if inside:
                lo,hi=depth_lines(r,B)
                support=clip_line(inside,lo,True,B);front=clip_line(inside,lo,False,B)
                behind=clip_line(support,hi,False,B);support=clip_line(support,hi,True,B)
                for kind,p in [('front',front),('support',support),('behind',behind)]:
                    if p:
                        d=dict(labels);d[r['id']]=kind;nxt.append((p,d))
            B.parts(nxt)
        patches=nxt
    return patches

def has_link(aa,bb,edges,B):
    for a in aa:
        for b in bb:
            B.clip()
            if (a,b) in edges and (b,a) in edges:return True
    return False

def graph_refusals(support,edges,B):
    cams={c for c,tid,corners in support};graph={c:set() for c in cams}
    for ca,ia,aa in support:
        for cb,ib,bb in support:
            if ca!=cb and has_link(aa,bb,edges,B):graph[ca].add(cb)
    reached=set();stack=[min(cams)] if cams else []
    while stack:
        c=stack.pop()
        if c not in reached:reached.add(c);stack.extend(sorted(graph[c]-reached))
    why=[]
    if len(cams)<2:why.append('INSUFFICIENT_VIEWS')
    if reached!=cams:why.append('MISSING_POSITIVE_CORNER_WITNESS')
    return why

def certify(records,edges,B):
    patches=overlay(records,B);lookup={r['id']:r for r in records};refusals=set();detail=[];contributors=set()
    for poly,labels in patches:
        why=[]
        if 'front' in labels.values():why.append('OBSERVED_FREE_SPACE')
        active=[lookup[i] for i,kind in labels.items() if kind=='support' and lookup[i]['surface_support']]
        for a in active:
            for b in active:
                if a['id']<b['id'] and a['camera']==b['camera']:
                    B.clip()
                    if any(evaluate(a['ratio'],p,B)!=evaluate(b['ratio'],p,B) for p in poly):why.append('SAME_CAMERA_DEPTH_CONFLICT')
        why+=graph_refusals([(r['camera'],r['id'],r['corners']) for r in active],edges,B)
        contributors.update(r['id'] for r in active);refusals.update(why)
        detail.append({'area':str(area(poly,B)),'labels':labels,'refusals':sorted(set(why))})
    return {'accepted_support_hypothesis':not refusals,'refusals':sorted(refusals),'patches':detail,'contributingSourceIds':sorted(contributors)},patches
