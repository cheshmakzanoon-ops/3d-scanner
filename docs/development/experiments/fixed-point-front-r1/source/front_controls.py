"""Exact calibrated analytic support controls; NOT native depth/SfM execution."""
import copy,collections,json,random,pathlib
from exact_geometry import *
from ledger_adapter import Ledger
from front import reconstruct,Topology,Work,sphere_neighbors,link_ok,proposals
from controls import run as primitives
from overlay_controls import run_overlay
from adapter_controls import run as adapter_controls

class Analytic(Ledger):
 def __init__(self,points,faces,groups,B,rho=F(1,2)):
  self.B=B;self.rho=rho;self.points=points;self.point_ids=list(range(len(points)));self.ncam=sum(len(cams) for ids,cams in groups);self.samples=[];self.source_q=[];self.projected=[];self.cameras=[];self.centers=[];self.triangles=[];self.bounds=[];self.triangle_index={};self.members=[[] for p in points];self.edges=set();self.unknown=[True]*len(points)
  # Each group explicitly supplies two distinct calibrated source-view IDs and
  # full exact correspondences at common original points. These are analytic
  # correspondences, NOT constructor claims of native reciprocal execution.
  for ids,cams in groups:
   group_maps=[]
   for camera in cams:
    cam=len(self.cameras);a=tuple(map(F,camera));self.cameras.append(a);center=tuple(-sum(a[3*k+j]*a[9+k] for k in range(3)) for j in range(3));self.centers.append(center);lookup={}
    for pid in ids:
     q=self.camera_xyz(cam,points[pid]);xy=self.pixel(cam,q);sid=len(self.samples);lookup[pid]=sid;self.samples.append((cam,xy[0],xy[1],points[pid],F(1),2));self.members[pid].append(sid);self.source_q.append(q);self.projected.append(xy)
    for t in faces:
     if not all(i in lookup for i in t):continue
     corners=tuple(lookup[i] for i in t);tid=len(self.triangles);self.triangles.append((cam,corners));xy=[self.projected[i] for i in corners];box=(min(p[0] for p in xy),max(p[0] for p in xy),min(p[1] for p in xy),max(p[1] for p in xy));self.bounds.append(box)
     for x,y in self.bins(box):self.triangle_index.setdefault((cam,x,y),[]).append(tid)
    group_maps.append(lookup)
   for a in group_maps:
    for b in group_maps:
     if a is b:continue
     for pid in ids:self.edges.add((a[pid],b[pid]))
 def verify(self):pass

def cameras(back=False,depth=F(12)):
 return [[1,0,0,0,-1 if back else 1,0,0,0,-1 if back else 1,-x,0,depth if back else 0,20,20,16,16] for x in (F(0),F(1,4))]
def patch(n=3,z=lambda x,y:F(5),offset=F(0),hole=None):
 pts=[(F(x,2)+offset,F(y,2),z(F(x,2),F(y,2))) for y in range(n) for x in range(n)];faces=[]
 for y in range(n-1):
  for x in range(n-1):
   if (x,y)==hole:continue
   a=y*n+x;b=a+1;c=a+n;d=c+1;faces.extend(((a,b,d),(a,d,c)))
 return pts,faces

def run(cfg,out):
 results={};results['reviewedPrimitives']=primitives(Budget());results['reviewedClosedOverlay']=run_overlay(Budget());(out/'adapter').mkdir();results['reviewedAdapterSchema']=adapter_controls(out/'adapter',Budget())
 for kind in ('plane','curved','hole','disconnected','fold','aboveBandLayers','belowResolutionLayers'):
  B=Budget();p,t=patch(n=5 if kind=='hole' else 3,hole=(1,1) if kind=='hole' else None,z=(lambda x,y:F(5)+(x*x+y*y)/16) if kind=='curved' else (lambda x,y:F(5)+abs(x-F(1,2))/2) if kind=='fold' else (lambda x,y:F(5)));groups=[(list(range(len(p))),cameras())]
  if kind=='disconnected':
   q,f=patch(offset=F(4));n=len(p);p+=q;t+=[tuple(i+n for i in tri) for tri in f];groups=[(list(range(len(p))),cameras())]
  if kind in ('aboveBandLayers','belowResolutionLayers'):
   z=F(7) if kind=='aboveBandLayers' else F(101,20);q,f=patch(z=lambda x,y:z);n=len(p);p+=q;t+=[tuple(i+n for i in tri) for tri in f];groups=[(list(range(n)),cameras()),(list(range(n,len(p))),cameras(True,z+5))]
  ledger=Analytic(p,t,groups,B);faces,proof,summary=reconstruct(ledger,cfg);need= lambda yes,msg: None if yes else (_ for _ in ()).throw(RuntimeError(msg))
  if kind in ('plane','curved','hole','disconnected','fold','aboveBandLayers'):need(bool(faces),'CONTROL_NO_FACE:'+kind)
  if kind in ('aboveBandLayers','belowResolutionLayers'):need(all(len({0 if i<n else 1 for i in tri})==1 for tri in faces),'LAYER_BRIDGE')
  if kind=='aboveBandLayers':need(any(all(i<n for i in tri) for tri in faces) and any(all(i>=n for i in tri) for tri in faces),'RESOLVABLE_LAYER_LOSS')
  if kind=='disconnected':need(all(max(p[i][0] for i in tri)-min(p[i][0] for i in tri)<=1 for tri in faces),'DISCONNECTED_BRIDGE')
  if kind=='hole':
   hole=[(F(1,2),F(1,2)),(1,F(1,2)),(1,1),(F(1,2),1)];lines=[edge_line(a,b,B) for a,b in zip(hole,hole[1:]+hole[:1])]
   for tri in faces:
    poly=[p[i][:2] for i in tri];_,inside=cut(poly,lines,B);need(not inside,'UNSUPPORTED_HOLE_FILLED')
  results[kind]={'points':len(p),'faces':len(faces),'unknownNormalsRetained':sum(ledger.unknown),'actualWitnessViewsPerLayer':2,'witnessScope':'exact analytic calibrated correspondence, NOT native consistency execution','summary':summary,'thinQualification':'BELOW_RESOLUTION_UNQUALIFIED' if kind=='belowResolutionLayers' else 'ANALYTIC_ONLY'}
  if kind=='plane':
   # Preserve immutable IDs while permuting storage; geometric output must match.
   perm=list(reversed(range(len(p))));other=Analytic(p,t,groups,Budget());other.points=[other.points[i] for i in perm];other.members=[other.members[i] for i in perm];other.point_ids=perm
   ff,_,_=reconstruct(other,cfg);canon=lambda fs,ids:sorted(min(tuple(ids[i] for i in tri[k:]+tri[:k]) for k in range(3)) for tri in fs);need(canon(faces,list(range(len(p))))==canon(ff,perm),'IMMUTABLE_ID_ORDERING_AND_WINDING');need(canon(faces,list(range(len(p))))!=canon([(a,c,b) for a,b,c in faces],list(range(len(p)))),'REVERSED_WINDING_NOT_DISTINGUISHED')
   # Pure point-identity unit control: coincident positions, distinct immutable IDs.
   probe=p+[p[0]];stable=list(range(len(probe)));per=list(reversed(stable));aa=proposals(probe,F(1,2),Budget(),Work(cfg),stable)[0];bb=proposals([probe[i] for i in per],F(1,2),Budget(),Work(cfg),per)[0];need([tuple(stable[j] for j in aa[i:i+3]) for i in range(0,len(aa),3)]==[tuple(per[j] for j in bb[i:i+3]) for i in range(0,len(bb),3)],'COINCIDENT_ID_ORDERING')
   order,rank,neighbors=sphere_neighbors(ledger.points,ledger.rho,Budget(),Work(cfg))
   for i in order:need(set(neighbors[i])=={j for j in order if i!=j and sum((p[i][k]-p[j][k])**2 for k in range(3))<=4*ledger.rho**2},'NEIGHBOR_COMPLETENESS')
   # Actual front refuses resource exhaustion; no truncated successful mesh.
   small=dict(cfg);small['maxProposals']=0
   try:reconstruct(Analytic(p,t,groups,Budget()),small)
   except Refusal as e:need(str(e)=='WORK_BUDGET:maxProposals','BUDGET_REASON')
   else:raise RuntimeError('BUDGET_NOT_REFUSED')
 # Link tests explicitly cover bow-tie stars and orientation conflict.
 W=Work(cfg);top=Topology(5,W);need= lambda yes,msg: None if yes else (_ for _ in ()).throw(RuntimeError(msg));need(top.admissible((0,1,2)),'LINK_SEED');top.add((0,1,2));need(not top.admissible((0,3,4)),'BOW_TIE_ACCEPTED');need(not top.admissible((0,1,3)),'SAME_WINDING_ACCEPTED');need(top.admissible((1,0,3)),'VALID_LINK_REFUSED')
 results['onlineTopologyAndOrderingAndBudgets']='PASS';return results
