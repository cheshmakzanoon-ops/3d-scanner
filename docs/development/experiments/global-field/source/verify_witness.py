"""Independent exact-input witness and directed-edge audit. No geometry repair."""
import struct,json,hashlib,sys,math
from pathlib import Path
from fractions import Fraction
from itertools import permutations

def need(x,s):
 if not x:raise ValueError(s)
def sub(a,b):return tuple(x-y for x,y in zip(a,b))
def dot(a,b):return sum(x*y for x,y in zip(a,b))
def cross(a,b):return (a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
def length(a):return math.sqrt(dot(a,a))
def camera(v,p):return tuple(dot(v['r'][3*i:3*i+3],p)+v['t'][i] for i in range(3))
def projected_contains(triangle,uv):
 # Exact signs for the represented IEEE-754 projected coordinates, not an
 # epsilon or an enlarged source triangle. Full predicate avoids an unproven
 # floating error threshold. IEEE float conversion has bounded integer size.
 triangle=[tuple(Fraction.from_float(x) for x in p) for p in triangle]
 uv=tuple(Fraction.from_float(x) for x in uv)
 orient=lambda a,b,c:(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);area=orient(*triangle)
 if area==0:return False
 return all(orient(triangle[k],triangle[(k+1)%3],uv)*(1 if area>0 else -1)>=0 for k in range(3))
def selftest():
 triangle=[(0.,0.),(1.,0.),(0.,1.)];need(projected_contains(triangle,(.2,.2)),'inside');need(not projected_contains(triangle,(.8,.8)),'outside');need(not projected_contains([(0.,0.),(1.,0.),(2.,0.)],(.5,0.)),'degenerate')
 face=[(.1,.1),(.9,.1),(.1,1.2)];centroid=tuple(sum(p[k] for p in face)/3 for k in range(2));need(projected_contains(triangle,centroid) and not all(projected_contains(triangle,p) for p in face),'centroid-only false positive guard');need(all(projected_contains(list(reversed(triangle)),p)==projected_contains(triangle,p) for p in face),'source winding invariant')
 # Preserve the original decimal near-boundary case. Its exact binary
 # coordinates sum to slightly more than one: it is OUTSIDE, in all6orders.
 for order in permutations(triangle):
  need(not projected_contains(order,(.9,.1)),'original near-boundary exact outside')
  need(projected_contains(order,(.75,.25)),'binary-exact edge inclusive')
  need(projected_contains(order,(0.,0.)),'binary-exact vertex inclusive')
  need(projected_contains(order,(.2,.2)),'all permutations interior')
  need(not projected_contains(order,(.8,.8)),'all permutations exterior')
 print('independent witness primitive guards PASS')
class Reader:
 def __init__(self,b):self.b=b;self.o=0
 def raw(self,n):need(n>=0 and self.o+n<=len(self.b),'snapshot bounds');a=self.b[self.o:self.o+n];self.o+=n;return a
 def unpack(self,fmt):fmt='<'+fmt;return struct.unpack(fmt,self.raw(struct.calcsize(fmt)))
 def n(self,limit):x=self.unpack('I')[0];need(x<=limit,'snapshot count bound');return x
 def vec(self):return self.unpack('ddd')
def snapshot(path):
 b=Path(path).read_bytes();need(256<=len(b)<=12*1024**2 and hashlib.sha256(b[:-32]).digest()==b[-32:],'snapshot integrity');r=Reader(b[:-32]);need(r.raw(8)==b'RFFUSE01','schema');need(r.unpack('II')==(1,len(b)),'version/length');identity=r.raw(64).decode();r.raw(64)
 minimum,jump,edge=r.unpack('fdd');maxv,maxf,stride,_,_=r.unpack('IIIII');need(maxv==40000 and maxf==80000 and stride==3,'fixed settings')
 views=[]
 for _ in range(r.n(8)):
  width,height=r.unpack('II');fx,fy,cx,cy=r.unpack('dddd');rot=r.unpack('9d');translation=r.vec();rgba=r.raw(r.n(1280000));need(len(rgba)==width*height*4,'rgba');views.append(dict(width=width,height=height,fx=fx,fy=fy,cx=cx,cy=cy,r=rot,t=translation))
 maps=[]
 for _ in range(r.n(8)):
  width,height=r.unpack('II');n=r.n(320000);depth=r.unpack(str(n)+'f');n=r.n(320000);r.raw(n*4);need(len(depth)==width*height,'map');maps.append(depth)
 for _ in range(r.n(50000)):r.raw(32)
 samples=[]
 for _ in range(r.n(100000)):
  xyz=r.vec();r.raw(3);confidence,support,camera_id,x,y=r.unpack('fBBHH');r.raw(29);samples.append(dict(xyz=xyz,confidence=confidence,support=support,camera=camera_id,x=x,y=y))
 return identity,views,maps,samples,stride,minimum,jump,edge

def main():
 need(len(sys.argv)==4,'snapshot candidate-directory report');identity,views,maps,samples,stride,minimum,jump,edge=snapshot(sys.argv[1]);d=Path(sys.argv[2]);p=json.loads((d/'provenance.json').read_text());need(p['depthIdentity']==identity,'identity');pitch=sorted(camera(views[s['camera']],s['xyz'])[2]*stride/min(views[s['camera']]['fx'],views[s['camera']]['fy']) for s in samples)[len(samples)//2];need(p['pitch']==pitch and p['truncation']==2*pitch and p['wholeFaceSlab']==.25*pitch,'fixed scale')
 rows=(d/'experimental.ply').read_text().splitlines();end=rows.index('end_header');nv=p['vertices'];nf=p['faces'];need(0<nv<=40000 and 0<nf<=80000 and len(rows)==end+1+nv+nf,'ply count');vertices=[tuple(map(float,row.split())) for row in rows[end+1:end+1+nv]];faces=[tuple(map(int,row.split())) for row in rows[end+1+nv:]];need(all(len(v)==3 and all(math.isfinite(x) for x in v) for v in vertices),'finite vertices');need(all(len(f)==4 and f[0]==3 and len(set(f[1:]))==3 and all(0<=i<nv for i in f[1:]) for f in faces),'face indices');need(len(p['faceWitnessPatch'])==nf,'witness count')
 lookup={(s['camera'],s['x'],s['y']):i for i,s in enumerate(samples)};need(len(lookup)==len(samples),'sample uniqueness');patches=[]
 for row in p['observedPatches']:
  need(len(row)==4,'patch schema');cam,*ids=row;need(0<=cam<len(views) and len(set(ids))==3 and all(0<=i<len(samples) and samples[i]['camera']==cam for i in ids),'source provenance');v=views[cam];ss=[samples[i] for i in ids];x=min(s['x'] for s in ss);y=min(s['y'] for s in ss);need(x>=3 and y>=3 and (x-3)%stride==0 and (y-3)%stride==0 and x+stride<v['width']-3 and y+stride<v['height']-3,'source cell grid')
  corners=[lookup.get((cam,x+dx,y+dy)) for dx,dy in [(0,0),(stride,0),(0,stride),(stride,stride)]];need(all(i is not None and samples[i]['confidence']>=minimum and samples[i]['support']>=2 for i in corners),'complete verified source cell');dist=lambda a,b:length(sub(samples[corners[a]]['xyz'],samples[corners[b]]['xyz']));triangles=[(0,1,3),(0,3,2)] if dist(0,3)<dist(1,2) else [(0,1,2),(1,3,2)];need(tuple(ids) in [tuple(corners[i] for i in t) for t in triangles],'original deterministic diagonal')
  depths=[maps[cam][s['y']*v['width']+s['x']] for s in ss];hi=max(depths);lo=min(depths);foot=hi*stride/min(v['fx'],v['fy']);abc=[s['xyz'] for s in ss];normal=cross(sub(abc[1],abc[0]),sub(abc[2],abc[0]));norm=length(normal);need(lo>0 and (hi-lo)/hi<=jump and all(length(sub(abc[i],abc[j]))<=foot*edge for i,j in [(0,1),(0,2),(1,2)]) and norm>=foot*foot*.015,'source triangle guards');normal=tuple(n/norm for n in normal);rays=[camera(v,q) for q in abc];patches.append((v,abc,normal,rays))
 edge_counts={};bad=0
 for f,pi in zip(faces,p['faceWitnessPatch']):
  need(isinstance(pi,int) and 0<=pi<len(patches),'witness index');v,abc,n,rays=patches[pi]
  for index in f[1:]:
   point=vertices[index];q=camera(v,point);need(q[2]>.01 and abs(dot(n,sub(point,abc[0])))<=.25*pitch,'wholeface slab/z');uv=(q[0]/q[2],q[1]/q[2]);triangle=[(r[0]/r[2],r[1]/r[2]) for r in rays]
   need(projected_contains(triangle,uv),'wholeface projected containment')
  for a,b in zip(f[1:],f[2:]+f[1:2]):key=(min(a,b),max(a,b));count,balance=edge_counts.get(key,(0,0));edge_counts[key]=(count+1,balance+(1 if a<b else -1))
 boundary=sum(c==1 for c,b in edge_counts.values());nonmanifold=sum(c>2 for c,b in edge_counts.values());winding=sum(c==2 and b!=0 for c,b in edge_counts.values());need((boundary,nonmanifold,winding)==(p['boundaryEdges'],p['nonmanifoldEdges'],p['windingConflictEdges']),'independent directed edge parity')
 report={'status':'INDEPENDENT_WHOLE_FACE_WITNESS_PASS','facesChecked':nf,'sourcePatchesChecked':len(patches),'boundaryEdges':boundary,'nonmanifoldEdges':nonmanifold,'windingConflictEdges':winding,'strictFloatingBoundaryPolicy':'Exact rational 2D orientation on represented projected IEEE coordinates; inclusive exact boundary, no tolerance enlargement. Plane slab remains strict double arithmetic.','notQualityAcceptance':True}
 with open(sys.argv[3],'x') as out:json.dump(report,out,indent=2);out.write('\n')
 print(report['status'])
if __name__=='__main__':
 if sys.argv[1:]==['--selftest']:selftest()
 else:main()
