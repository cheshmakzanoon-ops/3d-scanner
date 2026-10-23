"""Original reconstructed exact geometry, R1. No photographic solver or mesher."""
from fractions import Fraction as F

class Refusal(RuntimeError): pass

class Budget:
    def __init__(self,clips=5000000,balls=10000000,fragments=4096,bits=4096):
        self.clips=clips;self.balls=balls;self.fragments=fragments;self.bits=bits
        self.used_clips=0;self.used_balls=0
    def number(self,x):
        x=F(x)
        if max(abs(x.numerator).bit_length(),x.denominator.bit_length())>self.bits:raise Refusal('RATIONAL_BIT_BUDGET')
        return x
    def clip(self):
        self.used_clips+=1
        if self.used_clips>self.clips:raise Refusal('SUPPORT_WORK_BUDGET')
    def ball(self):
        self.used_balls+=1
        if self.used_balls>self.balls:raise Refusal('BALL_BUDGET')
    def parts(self,p):
        if len(p)>self.fragments:raise Refusal('FRAGMENT_BUDGET')

def vector(p,n,B):
    if len(p)!=n:raise Refusal('VECTOR_DIMENSION')
    return tuple(B.number(x) for x in p)
def cross2(a,b,c,B):
    a,b,c=[vector(p,2,B) for p in (a,b,c)]
    return B.number((b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]))
def area(poly,B):
    poly=[vector(p,2,B) for p in poly]
    return B.number(sum(poly[i][0]*poly[(i+1)%len(poly)][1]-poly[i][1]*poly[(i+1)%len(poly)][0] for i in range(len(poly)))/2) if len(poly)>2 else F(0)
def canonical(poly,B):
    out=[]
    for p in poly:
        p=vector(p,2,B)
        if not out or out[-1]!=p:out.append(p)
    if len(out)>1 and out[0]==out[-1]:out.pop()
    ar=area(out,B)
    if not ar:return []
    if ar<0:out.reverse()
    start=min(range(len(out)),key=lambda i:out[i]);return out[start:]+out[:start]
def evaluate(f,p,B):
    f=vector(f,3,B);p=vector(p,2,B)
    return B.number(f[0]*p[0]+f[1]*p[1]+f[2])
def clip_line(poly,line,positive,B):
    B.clip();poly=[vector(p,2,B) for p in poly];line=vector(line,3,B)
    if all(x==0 for x in line):return canonical(poly,B) if positive else []
    out=[]
    for p,q in zip(poly,poly[1:]+poly[:1]):
        dp=evaluate(line,p,B);dq=evaluate(line,q,B)
        ip=dp>=0 if positive else dp<=0;iq=dq>=0 if positive else dq<=0
        if ip:out.append(p)
        if ip!=iq:
            t=B.number(dp/(dp-dq));out.append(tuple(B.number(p[k]+t*(q[k]-p[k])) for k in range(2)))
    return canonical(out,B)
def edge_line(a,b,B):
    a=vector(a,2,B);b=vector(b,2,B)
    return B.number(a[1]-b[1]),B.number(b[0]-a[0]),B.number(a[0]*b[1]-a[1]*b[0])
def cut(poly,lines,B):
    current=canonical(poly,B);outside=[]
    for line in lines:
        piece=clip_line(current,line,False,B)
        if piece:outside.append(piece)
        current=clip_line(current,line,True,B)
        if not current:break
    B.parts(outside);return outside,current
def partition(poly,triangle,B):
    tri=canonical(triangle,B)
    if len(tri)!=3:raise Refusal('DEGENERATE_SOURCE_TRIANGLE')
    return cut(poly,[edge_line(a,b,B) for a,b in zip(tri,tri[1:]+tri[:1])],B)
def union_partition(candidate,triangles,B):
    """Coverage-only first ownership, NOT full visibility/witness evidence."""
    remainder=[canonical(candidate,B)];covered=[]
    if not remainder[0]:raise Refusal('DEGENERATE_CANDIDATE')
    if len({i for i,t in triangles})!=len(triangles):raise Refusal('DUPLICATE_SOURCE_ID')
    for sid,tri in sorted(triangles,key=lambda x:x[0]):
        nxt=[]
        for poly in remainder:
            outside,inside=partition(poly,tri,B);nxt.extend(outside)
            if inside:covered.append((sid,inside))
            B.parts(nxt+covered)
        remainder=nxt
    return remainder,covered
def affine(points,values,B):
    if len(points)!=3 or len(values)!=3:raise Refusal('AFFINE_DIMENSION')
    a,b,c=[vector(p,2,B) for p in points];values=[B.number(x) for x in values]
    det=cross2(a,b,c,B)
    if not det:raise Refusal('DEGENERATE_DEPTH_TRIANGLE')
    dx=B.number(((values[1]-values[0])*(c[1]-a[1])-(values[2]-values[0])*(b[1]-a[1]))/det)
    dy=B.number(((b[0]-a[0])*(values[2]-values[0])-(c[0]-a[0])*(values[1]-values[0]))/det)
    return dx,dy,B.number(values[0]-dx*a[0]-dy*a[1])
def ratio_bounds(poly,candidate_inverse,source_inverse,B):
    ratios=[]
    for p in poly:
        c=evaluate(candidate_inverse,p,B);s=evaluate(source_inverse,p,B)
        if min(c,s)<=0:raise Refusal('NONPOSITIVE_DEPTH')
        ratios.append(B.number(s/c))
    if not ratios:raise Refusal('EMPTY_PATCH')
    return min(ratios),max(ratios)
def dot(a,b,B):
    a=vector(a,3,B);b=vector(b,3,B);return B.number(sum(x*y for x,y in zip(a,b)))
def sub(a,b,B):
    a=vector(a,3,B);b=vector(b,3,B);return tuple(B.number(x-y) for x,y in zip(a,b))
def cross(a,b,B):
    a=vector(a,3,B);b=vector(b,3,B)
    return tuple(B.number(a[(i+1)%3]*b[(i+2)%3]-a[(i+2)%3]*b[(i+1)%3]) for i in range(3))
def sphere(a,b,c,rho,B):
    a,b,c=[vector(p,3,B) for p in (a,b,c)];rho=B.number(rho)
    if rho<=0:raise Refusal('NONPOSITIVE_RADIUS')
    u=sub(b,a,B);v=sub(c,a,B);n=cross(u,v,B);nn=dot(n,n,B)
    if not nn:raise Refusal('COLLINEAR')
    uu=dot(u,u,B);vv=dot(v,v,B);vn=cross(v,n,B);nu=cross(n,u,B)
    offset=tuple(B.number((uu*vn[i]+vv*nu[i])/(2*nn)) for i in range(3))
    center=tuple(B.number(a[i]+offset[i]) for i in range(3));r2=dot(offset,offset,B);height2=B.number((rho*rho-r2)/nn)
    if height2<0:raise Refusal('CIRCUMRADIUS_EXCEEDS_RHO')
    return center,n,r2,height2
def algebraic_sign(a,b,s,B):
    a=B.number(a);b=B.number(b);s=B.number(s)
    if s<0:raise Refusal('NEGATIVE_RADICAND')
    if not s or not b:return (a>0)-(a<0)
    if not a:return (b>0)-(b<0)
    if (a>0)==(b>0):return (a>0)-(a<0)
    delta=B.number(a*a-b*b*s);return ((delta>0)-(delta<0))*((a>0)-(a<0))
def ball_side(point,spec,side,B):
    if side not in (-1,1):raise Refusal('INVALID_SPHERE_SIDE')
    B.ball();center,n,r2,height2=spec;d=sub(point,center,B)
    return algebraic_sign(dot(d,d,B)-r2,-2*side*dot(d,n,B),height2,B)
