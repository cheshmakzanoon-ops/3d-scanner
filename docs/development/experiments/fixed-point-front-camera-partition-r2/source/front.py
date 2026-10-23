"""Original deterministic fixed-point empty-ball front; no point normals inferred."""
from exact_geometry import *
from orientation import choose_orientation
import collections,array,heapq,json

class Work:
 def __init__(self,cfg):self.cfg=cfg;self.used=collections.Counter()
 def charge(self,name):
  self.used[name]+=1
  if self.used[name]>self.cfg[name]:raise Refusal('WORK_BUDGET:'+name)

def sphere_neighbors(points,rho,B,W,point_ids=None):
 point_ids=list(range(len(points))) if point_ids is None else point_ids
 if len(point_ids)!=len(points) or len(set(point_ids))!=len(point_ids):raise Refusal("POINT_ID_BINDING")
 order=sorted(range(len(points)),key=lambda i:(points[i],point_ids[i]));rank={v:i for i,v in enumerate(order)};grid=collections.defaultdict(list);cell=[];neighbors=[set() for _ in points]
 for i,p in enumerate(points):
  c=tuple(x//(2*rho) for x in p);cell.append(c);grid[c].append(i)
 for i in order:
  c=cell[i]
  for dx in (-1,0,1):
   for dy in (-1,0,1):
    for dz in (-1,0,1):
     for j in grid.get((c[0]+dx,c[1]+dy,c[2]+dz),()):
      if rank[j]<=rank[i]:continue
      W.charge('maxNeighborVisits');d=sub(points[i],points[j],B)
      if dot(d,d,B)<=4*rho*rho:neighbors[i].add(j);neighbors[j].add(i)
 return order,rank,[sorted(v,key=rank.__getitem__) for v in neighbors]

def proposals(points,rho,B,W,point_ids=None):
 order,rank,neighbors=sphere_neighbors(points,rho,B,W,point_ids);sets=list(map(set,neighbors));packed=array.array('I');incident=collections.defaultdict(list)
 for a in order:
  later=[b for b in neighbors[a] if rank[b]>rank[a]]
  for j,b in enumerate(later):
   for c in later[j+1:]:
    W.charge('maxProposalCombinationVisits')
    if c not in sets[b]:continue
    W.charge('maxProposals');i=len(packed)//3;packed.extend((a,b,c))
    for x,y in ((a,b),(b,c),(c,a)):incident[tuple(sorted((x,y)))].append(i)
 return packed,incident,order,rank,neighbors

def link_ok(existing,extra,W=None):
 edges=existing+[extra];g=collections.defaultdict(set);seen=set()
 for a,b in edges:
  if W:W.charge('maxFrontVisits')
  key=tuple(sorted((a,b)))
  if key in seen:return False
  seen.add(key);g[a].add(b);g[b].add(a)
 if any(len(v)>2 for v in g.values()):return False
 visited=set();stack=[min(g)]
 while stack:
  a=stack.pop()
  if a in visited:continue
  visited.add(a);stack.extend(g[a]-visited)
 return len(visited)==len(g) and sum(len(v)==1 for v in g.values()) in (0,2)

class Topology:
 def __init__(self,n,W):self.edges={};self.links=[[] for _ in range(n)];self.W=W
 def admissible(self,t):
  for a,b in zip(t,t[1:]+t[:1]):
   self.W.charge('maxFrontVisits');old=self.edges.get(tuple(sorted((a,b))),[])
   if len(old)>=2 or (old and old[0]==(a,b)):return False
  for i,a in enumerate(t):
   if not link_ok(self.links[a],(t[(i+1)%3],t[(i+2)%3]),self.W):return False
  return True
 def add(self,t):
  for a,b in zip(t,t[1:]+t[:1]):self.edges.setdefault(tuple(sorted((a,b))),[]).append((a,b))
  for i,a in enumerate(t):self.links[a].append((t[(i+1)%3],t[(i+2)%3]))

GEOMETRIC_REFUSALS={'COLLINEAR','CIRCUMRADIUS_EXCEEDS_RHO','DEGENERATE_CANDIDATE','GRAZING_MEMBER_HEMISPHERE','CONFLICTING_MEMBER_HEMISPHERES','CANDIDATE_CAMERA_DEPTH','GRAZING_SOURCE_NORMAL','DEGENERATE_PROJECTED_SOURCE','NONPOSITIVE_PROJECTED_TRIANGLE'}

def reconstruct(ledger,cfg):
 B=ledger.B;W=Work(cfg);packed,incident,order,rank,neighbors=proposals(ledger.points,ledger.rho,B,W,getattr(ledger,"point_ids",None));n=len(packed)//3;status=bytearray(n);refusals=collections.Counter();faces=[];proofs=[];topo=Topology(len(ledger.points),W);heap=[];queued=set();tested=0
 def triple(i):return tuple(packed[3*i:3*i+3])
 def queue(t):
  for a,b in zip(t,t[1:]+t[:1]):
   key=tuple(sorted((a,b)))
   if len(topo.edges[key])==1 and key not in queued:heapq.heappush(heap,(rank[a],rank[b],a,b));queued.add(key)
 def attempt(i):
  nonlocal tested
  W.charge('maxFrontVisits')
  if status[i]:return False
  status[i]=1;tested+=1;t=triple(i)
  try:
   points=[ledger.points[j] for j in t];rays=[[ledger.centers[ledger.samples[s][0]] for s in ledger.members[j]] for j in t];normal,sign=choose_orientation(points,rays,B)
   if sign<0:t=(t[0],t[2],t[1]);points=[ledger.points[j] for j in t]
   spec=sphere(*points,ledger.rho,B)
   # Exact camera-facing oriented sphere side +1. All points, including unknown
   # normals and unreferenced points, participate in empty-ball obstruction.
   for j in neighbors[t[0]]:
    if j in t:continue
    if ball_side(ledger.points[j],spec,1,B)<0:refusals['NONEMPTY_BALL']+=1;return False
   cert=ledger.candidate_certificate(t)
   if not cert['accepted_support_hypothesis']:
    for why in cert['refusals']:refusals['CERTIFICATE:'+why]+=1
    return False
   if not topo.admissible(t):refusals['ONLINE_EDGE_OR_LINK']+=1;return False
   if len(faces)>=cfg['maxFaces']:raise Refusal('FACE_BUDGET')
   topo.add(t);faces.append(t);proofs.append({'proposal':i,'sourceTriangleIds':cert['contributingSourceIds'],'boundaryTests':cert['boundaryTests'],'orientationSign':1});status[i]=2;queue(t);return True
  except Refusal as e:
   if str(e) not in GEOMETRIC_REFUSALS:raise
   refusals[str(e)]+=1;return False
 def drain():
  while heap:
   W.charge('maxFrontVisits');_,_,a,b=heapq.heappop(heap);key=tuple(sorted((a,b)));queued.discard(key)
   if len(topo.edges.get(key,()))!=1:continue
   for i in incident.get(key,()):
    if attempt(i):break
 # All untried proposals are eventually classified, including all disconnected
 # components. No pleasant-component early exit or post-hoc face deletion.
 for i in range(n):
  if not status[i] and attempt(i):drain()
 if any(v==0 for v in status):raise Refusal('UNCLASSIFIED_PROPOSAL')
 referenced={i for t in faces for i in t};return faces,proofs,{'allIndexedPoints':len(ledger.points),'referencedPoints':len(referenced),'unreferencedPointIds':sorted(set(range(len(ledger.points)))-referenced),'proposals':n,'classifiedProposals':tested,'acceptedFaces':len(faces),'refusalCounts':dict(refusals),'work':dict(W.used),'sharedExactWork':{'clips':B.used_clips,'balls':B.used_balls},'ordering':'XYZ/immutable ID canonical triples; canonical oriented boundary edges','allPointNormalsUnchanged':True}
