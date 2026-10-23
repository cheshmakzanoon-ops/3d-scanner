"""Member-camera ray/observed triangle constraints, not measured point normals."""
from exact_geometry import *

def choose_orientation(points,member_centers,B):
    if len(points)!=3 or len(member_centers)!=3 or any(not rays for rays in member_centers):raise Refusal('MISSING_MEMBER_RAY_IDENTITY')
    points=[vector(p,3,B) for p in points];normal=cross(sub(points[1],points[0],B),sub(points[2],points[0],B),B)
    if not dot(normal,normal,B):raise Refusal('DEGENERATE_CANDIDATE')
    signs=set()
    for p,rays in zip(points,member_centers):
        for center in rays:
            B.clip();d=dot(normal,sub(center,p,B),B)
            if not d:raise Refusal('GRAZING_MEMBER_HEMISPHERE')
            signs.add(1 if d>0 else -1)
    if len(signs)!=1:raise Refusal('CONFLICTING_MEMBER_HEMISPHERES')
    sign=next(iter(signs));return tuple(B.number(sign*x) for x in normal),sign

def source_normal_constraint(normal,source_triangle,center,B):
    if len(source_triangle)!=3:raise Refusal('INVALID_SOURCE_TRIANGLE')
    a,b,c=source_triangle;n=cross(sub(b,a,B),sub(c,a,B),B);view=dot(n,sub(center,a,B),B)
    if not view:raise Refusal('GRAZING_SOURCE_NORMAL')
    if view<0:n=tuple(-x for x in n)
    return dot(normal,n,B)>0
