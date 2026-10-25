"""Analytic exact-arithmetic reference tests; no production mesher enablement."""
import importlib.util
from pathlib import Path
import sys
_MODULE_NAME = "_coherent_planar_owner_reference"
_SOURCE = Path(__file__).resolve().parents[1] / "docs/development/experiments/coherent-planar-owner-reference/patch.py"
_SPEC = importlib.util.spec_from_file_location(_MODULE_NAME, _SOURCE)
_MODULE = importlib.util.module_from_spec(_SPEC)
sys.modules[_MODULE_NAME] = _MODULE
_SPEC.loader.exec_module(_MODULE)

from fractions import Fraction as F
from itertools import combinations
from collections import Counter
import math
import pytest
from _coherent_planar_owner_reference import reconcile,Limits

def orient(a,b,c):return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
def area(p):return abs(sum(a[0]*b[1]-a[1]*b[0] for a,b in zip(p,p[1:]+p[:1])))/2 if p else F(0)
def intersect(poly,triangle):
    # Independently written exact convex intersection for test oracle.
    for a,b in zip(triangle,triangle[1:]+triangle[:1]):
        out=[]
        if not poly:break
        for i,q in enumerate(poly):
            p=poly[i-1]; dp,dq=orient(a,b,p),orient(a,b,q)
            if (dp<0 and dq>=0) or (dp>=0 and dq<0):
                out.append(tuple((dq*x-dp*y)/(dq-dp) for x,y in zip(p,q)))
            if dq>=0:out.append(q)
        poly=out
    return poly

def union_area(tris):
    # Independent inclusion-exclusion oracle, intentionally small fixtures only.
    total=F(0)
    for n in range(1,len(tris)+1):
        for subset in combinations(tris,n):
            p=list(subset[0])
            for t in subset[1:]:p=intersect(p,t)
            total+=(-1)**(n+1)*area(p)
    return total

def square(prefix,x=F(0),y=F(0),s=F(1),z=F(5)):
    a,b,c,d=(x,y,z),(x+s,y,z),(x+s,y+s,z),(x,y+s,z)
    return [(prefix+'0',(a,b,c)),(prefix+'1',(a,c,d))]

def audit(src,result):
    assert result['status']=='EXACT_REFERENCE_ONLY',result
    ts=[]; edges=Counter();points=set()
    for f in result['faces']:
        assert f['status']=='INTERPOLATED'
        owner=result['owners'][f['owner']]
        for p,w in zip(f['positions'],f['barycentrics']):
            assert min(w)>=0 and sum(w)==1
            assert p==tuple(sum(w[i]*owner[i][k] for i in range(3)) for k in range(3))
        t=[p[:2] for p in f['positions']]
        assert orient(*t)>0
        ts.append(t);points.update(t)
        for a,b in zip(t,t[1:]+t[:1]):edges[tuple(sorted((a,b)))]+=1
    assert all(n<=2 for n in edges.values())
    for a,b in edges:
        for p in points:
            if p not in (a,b) and orient(a,b,p)==0:
                assert not (min(a[0],b[0])<=p[0]<=max(a[0],b[0]) and min(a[1],b[1])<=p[1]<=max(a[1],b[1]))
    for a,b in combinations(ts,2):assert area(intersect(a,b))==0
    inputs=[[tuple(map(F,p[:2])) for p in t] for _,t in src]
    assert sum(map(area,ts))==union_area(inputs)
    # Output subset of owners plus equal exact union area proves no positive-area loss/addition.
    return edges

@pytest.mark.parametrize('offset',[F(0),F(1,100),F(1,2),F(1),F(2),F(1,2**60)])
def test_overlap_touch_disjoint_sliver(offset):
    src=square('a')+square('b',offset)
    r=reconcile(src);audit(src,r)
    assert r==reconcile(list(reversed(src)))

def test_hole_and_disconnected():
    src=square('a')+square('b',F(2))+square('c',F(0),F(2))
    audit(src,reconcile(src))

def test_tjunction_collinear():
    src=square('a')+[('b',((F(1),F(1,2),F(5)),(F(2),F(0),F(5)),(F(2),F(1),F(5))))]
    audit(src,reconcile(src))

def test_attribute_owner_not_averaged():
    src=square('a')+square('b')
    r=reconcile(src);audit(src,r)
    assert {f['owner'][0] for f in r['faces']}=={'a'}
    assert len(r['source_records'])==4
    for f in r['faces']:
        # Arbitrary affine per-owner attribute transfers exactly.
        owner=r['owners'][f['owner']]; attr=[3*p[0]-2*p[1]+7 for p in owner]
        for p,w in zip(f['positions'],f['barycentrics']):assert sum(x*y for x,y in zip(w,attr))==3*p[0]-2*p[1]+7

@pytest.mark.parametrize('mode',['one_ulp','layer','opposite','crease'])
def test_refuse_ambiguous(mode):
    src=square('a');sid,t=square('b')[0];t=list(t)
    if mode=='opposite':t.reverse()
    elif mode=='layer':t=[(p[0],p[1],p[2]+F(1,10000)) for p in t]
    elif mode=='one_ulp':t[0]=(t[0][0],t[0][1],math.nextafter(5.,6.))
    else:t[0]=(t[0][0],t[0][1],F(6))
    r=reconcile(src+[(sid,t)])
    assert r['status']=='UNCHANGED' and not r['faces']

@pytest.mark.parametrize('limits',[Limits(faces=1),Limits(fragments=1),Limits(output_faces=1),Limits(bits=2),Limits(steps=10)])
def test_budget_no_partial(limits):
    r=reconcile(square('a')+square('b',F(1,3)),limits)
    assert r['status']=='UNCHANGED' and r['faces']==[]

def test_cancel_no_partial():assert reconcile(square('a'),cancelled=lambda:True)['status']=='UNCHANGED'

def test_exact_oblique_plane_and_original_corner_order():
    src=[(sid,tuple((p[0],p[1],2*p[0]-3*p[1]+F(7)) for p in t)) for sid,t in square('a')+square('b',F(1,3))]
    r=reconcile(src);assert r['status']=='EXACT_REFERENCE_ONLY'
    for f in r['faces']:
        owner=r['owners'][f['owner']]
        for p,w in zip(f['positions'],f['barycentrics']):assert p==tuple(sum(w[i]*owner[i][k] for i in range(3)) for k in range(3))

def rectangle(sid,x0,y0,x1,y1):
    a,b,c,d=[(F(x),F(y),F(5)) for x,y in [(x0,y0),(x1,y0),(x1,y1),(x0,y1)]]
    return [(sid+'0',(a,b,c)),(sid+'1',(a,c,d))]

def test_true_hole_boundary_loops():
    src=rectangle('a',0,0,3,1)+rectangle('b',0,2,3,3)+rectangle('c',0,1,1,2)+rectangle('d',2,1,3,2)
    edges=audit(src,reconcile(src))
    graph={}
    for (a,b),n in edges.items():
        if n==1:graph.setdefault(a,set()).add(b);graph.setdefault(b,set()).add(a)
    assert all(len(ns)==2 for ns in graph.values())
    loops=0; unseen=set(graph)
    while unseen:
        loops+=1;todo=[unseen.pop()]
        while todo:
            for p in graph[todo.pop()]:
                if p in unseen:unseen.remove(p);todo.append(p)
    assert loops==2

@pytest.mark.parametrize('scale',[F(1,2**20),F(2**20)])
@pytest.mark.parametrize('axis',[0,1,2])
def test_rotation_scale_winding(scale,axis):
    from _coherent_planar_owner_reference import dot,cross3,sub
    def transform(p):
        p=tuple(scale*x for x in p)
        return p[axis:]+p[:axis]
    src=[(sid,tuple(transform(p) for p in t)) for sid,t in square('a')+square('b',F(1,3))]
    r=reconcile(src);assert r['status']=='EXACT_REFERENCE_ONLY'
    for f in r['faces']:
        o=r['owners'][f['owner']];t=f['positions']
        assert dot(cross3(sub(t[1],t[0]),sub(t[2],t[0])),cross3(sub(o[1],o[0]),sub(o[2],o[0])))>0
        for p,w in zip(t,f['barycentrics']):assert p==tuple(sum(w[i]*o[i][k] for i in range(3)) for k in range(3))
    assert r==reconcile(list(reversed(src)))

def test_late_cancellation_discards_already_constructed_work():
    checks=0
    def cancel():
        nonlocal checks
        checks+=1
        return checks>=12
    r=reconcile(square('a')+square('b',F(1,3)),cancelled=cancel)
    assert checks==12 and r['status']=='UNCHANGED' and r['faces']==[]

def test_same_owner_negative_projection_orientation():
    # Globally reverse orientation; preserve the input's winding, not arbitrary chart CCW.
    from _coherent_planar_owner_reference import dot,cross3,sub
    src=[(sid,(t[0],t[2],t[1])) for sid,t in square('a')+square('b',F(1,3))]
    r=reconcile(src);assert r['status']=='EXACT_REFERENCE_ONLY'
    for f in r['faces']:
        o=r['owners'][f['owner']];t=f['positions']
        assert dot(cross3(sub(t[1],t[0]),sub(t[2],t[0])),cross3(sub(o[1],o[0]),sub(o[2],o[0])))>0
        for p,w in zip(t,f['barycentrics']):assert p==tuple(sum(w[i]*o[i][k] for i in range(3)) for k in range(3))

@pytest.mark.parametrize('src',[None,[None],[('a',None)],[('a',(None,None,None))],[('a',((None,0,0),(0,1,0),(1,0,0)))],[('a',((True,0,0),(0,1,0),(1,0,0)))],[('a',((float('nan'),0,0),(0,1,0),(1,0,0)))],[('a',((float('inf'),0,0),(0,1,0),(1,0,0)))],[(1,((0,0,0),(0,1,0),(1,0,0)))]])
def test_invalid_structure_refuses_without_partial_output(src):
    r=reconcile(src)
    assert r['status']=='UNCHANGED' and r['faces']==[]

@pytest.mark.parametrize('error',[TypeError,ValueError,OverflowError,ZeroDivisionError])
def test_callback_programmer_exception_is_not_swallowed(error):
    def broken():raise error('programmer error')
    with pytest.raises(error,match='programmer error'):reconcile(square('a'),cancelled=broken)

@pytest.mark.parametrize('limits',[False,{},Limits(faces=True),Limits(bits=4097),Limits(steps=0),Limits(fragments=-1),Limits(output_faces=2.0)])
def test_invalid_limits_refuse(limits):
    assert reconcile(square('a'),limits)['status']=='UNCHANGED'

@pytest.mark.parametrize('sid',['','x'*129])
def test_invalid_identifier_length_refuses(sid):
    assert reconcile([(sid,square('a')[0][1])])['status']=='UNCHANGED'

@pytest.mark.parametrize('x',[2**4097,F(1,2**4097),'1/2'])
def test_coordinate_preflight(x):
    assert reconcile([('a',((x,0,0),(0,1,0),(1,0,0)))])['status']=='UNCHANGED'
