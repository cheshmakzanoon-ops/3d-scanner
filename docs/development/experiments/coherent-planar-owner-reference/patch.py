"""QA-only exact planar ownership reference. NOT an app mesher or binary64 output.

Priority is explicit stable source ID order, not sensor quality or lossless fusion.
Returns no replacement on refusal; caller must retain the immutable input patch.
"""
from fractions import Fraction as F
from dataclasses import dataclass

class Refusal(Exception): pass

@dataclass
class Limits:
    faces: int = 32
    fragments: int = 512
    output_faces: int = 2048
    bits: int = 4096
    steps: int = 200000

class Guard:
    def __init__(self, limits, cancelled):
        self.limits, self.cancelled, self.steps = limits, cancelled, 0
    def tick(self):
        self.steps += 1
        if self.steps > self.limits.steps: raise Refusal('work budget')
        if self.steps % 64 == 1 and self.cancelled(): raise Refusal('cancelled')
    def value(self, x):
        self.tick()
        if max(x.numerator.bit_length(), x.denominator.bit_length()) > self.limits.bits:
            raise Refusal('rational representation budget')
        return x
    def point(self, p):
        try:
            converted=tuple(F(x) for x in p)
        except (ValueError,OverflowError) as e:
            raise Refusal('invalid numeric coordinate') from e
        return tuple(self.value(x) for x in converted)

def cross3(a,b):
    return (a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
def sub(a,b): return tuple(x-y for x,y in zip(a,b))
def dot(a,b): return sum(x*y for x,y in zip(a,b))

def reconcile(sources, limits=None, cancelled=lambda:False):
    """sources: [(stable_id, ((x,y,z),...3))]. Coordinates exact rationals or floats.
    Floats converted exactly (no epsilon). Output retains rational positions and
    per-face one-owner corner barycentrics. All input geometry and IDs remain in result.
    """
    g=Guard(Limits() if limits is None else limits,cancelled)
    try:
        if type(g.limits) is not Limits:
            raise Refusal('unsupported limits')
        defaults=Limits()
        for name in ('faces','fragments','output_faces','bits','steps'):
            value=getattr(g.limits,name)
            if type(value) is not int or not 1<=value<=getattr(defaults,name):
                raise Refusal('limits must be positive bounded integers')
        if not isinstance(sources,(tuple,list)) or not sources or len(sources)>g.limits.faces:
            raise Refusal('input face budget or unsupported container')
        for record in sources:
            if not isinstance(record,(tuple,list)) or len(record)!=2:
                raise Refusal('source record shape')
            triangle=record[1]
            if not isinstance(triangle,(tuple,list)) or len(triangle)!=3:
                raise Refusal('triangle shape')
            for point in triangle:
                if not isinstance(point,(tuple,list)) or len(point)!=3:
                    raise Refusal('point shape')
                if any(type(x) not in (int,float,F) for x in point):
                    raise Refusal('coordinates must be int, float or Fraction')
                for x in point:
                    if type(x) is int and x.bit_length()>g.limits.bits:
                        raise Refusal('input integer bit budget')
                    if type(x) is F and max(x.numerator.bit_length(),x.denominator.bit_length())>g.limits.bits:
                        raise Refusal('input rational bit budget')
        ids=[s[0] for s in sources]
        if any(type(i) is not str or not 1<=len(i)<=128 for i in ids) or len(set(ids))!=len(ids):
            raise Refusal('unique string source IDs required')
        src=sorted((i,tuple(g.point(p) for p in t)) for i,t in sources)
        if any(len(t)!=3 or any(len(p)!=3 for p in t) for _,t in src):
            raise Refusal('triangle shape')
        a,b,c=src[0][1]; normal=g.point(cross3(sub(b,a),sub(c,a)))
        if not any(normal): raise Refusal('degenerate source')
        axis=max(range(3),key=lambda k:abs(normal[k])); axes=[k for k in range(3) if k!=axis]
        def project(p): return tuple(p[k] for k in axes)
        def lift(p):
            q=[F(0)]*3
            for k,x in zip(axes,p):q[k]=x
            q[axis]=g.value(a[axis]-sum(normal[k]*(q[k]-a[k]) for k in axes)/normal[axis])
            return tuple(q)
        def orient(a,b,c):return g.value((b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]))
        def area(poly):return g.value(sum(poly[i][0]*poly[(i+1)%len(poly)][1]-poly[i][1]*poly[(i+1)%len(poly)][0] for i in range(len(poly)))) if poly else F(0)
        triangles={}
        originals={sid:t for sid,t in src}
        for sid,t in src:
            for p in t:
                if g.value(dot(normal,sub(p,a)))!=0: raise Refusal('noncoplanar patch')
            n=g.point(cross3(sub(t[1],t[0]),sub(t[2],t[0])))
            if g.value(dot(n,normal))<=0:raise Refusal('degenerate or opposite-facing source')
            p=tuple(map(project,t))
            if area(p)<0:p=(p[0],p[2],p[1])
            triangles[sid]=p
        def clean(poly):
            out=[]
            for p in poly:
                if not out or p!=out[-1]:out.append(p)
            if len(out)>1 and out[0]==out[-1]:out.pop()
            return out if len(out)>=3 and area(out)>0 else []
        def clip(poly,a,b,inside):
            if not poly:return []
            result=[]
            for p,q in zip(poly,poly[1:]+poly[:1]):
                dp,dq=orient(a,b,p),orient(a,b,q)
                pin=dp>=0 if inside else dp<=0
                qin=dq>=0 if inside else dq<=0
                if pin:result.append(p)
                if pin!=qin:
                    ratio=g.value(dp/(dp-dq))
                    result.append(g.point(tuple(x+ratio*(y-x) for x,y in zip(p,q))))
            return clean(result)
        fragments=[]
        for sid,tri in triangles.items():
            pieces=[list(tri)]
            for earlier in triangles:
                if earlier==sid:break
                cutter=triangles[earlier]; next_pieces=[]
                for poly in pieces:
                    remainder=poly
                    for u,v in zip(cutter,cutter[1:]+cutter[:1]):
                        outside=clip(remainder,u,v,False)
                        if outside:next_pieces.append(outside)
                        remainder=clip(remainder,u,v,True)
                        if not remainder:break
                    if len(next_pieces)+len(fragments)>g.limits.fragments:raise Refusal('fragment budget')
                pieces=next_pieces
            fragments.extend((sid,p) for p in pieces)
            if len(fragments)>g.limits.fragments:raise Refusal('fragment budget')
        # Node all retained polygon edges with every source/fragment boundary vertex.
        points=sorted(set(p for _,poly in fragments for p in poly)|set(p for t in triangles.values() for p in t))
        faces=[]
        for sid,poly in fragments:
            boundary=[]
            for u,v in zip(poly,poly[1:]+poly[:1]):
                k=0 if u[0]!=v[0] else 1
                nodes=[]
                for p in points:
                    if orient(u,v,p)==0:
                        t=g.value((p[k]-u[k])/(v[k]-u[k]))
                        if 0<=t<1:nodes.append((t,p))
                boundary.extend(p for _,p in sorted(nodes))
            center=g.point(tuple(sum(p[k] for p in poly)/len(poly) for k in range(2)))
            owner=triangles[sid];den=orient(*owner)
            for u,v in zip(boundary,boundary[1:]+boundary[:1]):
                t=(center,u,v)
                if orient(*t)<=0:raise Refusal('degenerate output')
                weights=[]
                for p in t:
                    w=(g.value(orient(owner[1],owner[2],p)/den),g.value(orient(owner[2],owner[0],p)/den),g.value(orient(owner[0],owner[1],p)/den))
                    if min(w)<0 or sum(w)!=1:raise Refusal('owner containment')
                    original_projected=tuple(map(project, originals[sid]))
                    weights.append(tuple(w[owner.index(q)] for q in original_projected))
                # Barycentrics always refer to immutable original corner order.
                positions=tuple(map(lift,t))
                if g.value(dot(cross3(sub(positions[1],positions[0]),sub(positions[2],positions[0])),normal))<0:
                    positions=(positions[0],positions[2],positions[1]);weights=(weights[0],weights[2],weights[1])
                faces.append({'owner':sid,'positions':positions,'barycentrics':tuple(weights),'status':'INTERPOLATED'})
                if len(faces)>g.limits.output_faces:raise Refusal('output face budget')
        return {'status':'EXACT_REFERENCE_ONLY','faces':faces,'owners':originals,'source_records':src,'steps':g.steps,'ownership_policy':'lexicographically first stable source ID; input geometry and IDs retained in source_records; external metadata association required'}
    except Refusal as e:
        return {'status':'UNCHANGED','reason':str(e),'faces':[],'steps':g.steps}
