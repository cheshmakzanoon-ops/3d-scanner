"""Read-only full cell provenance replay and explicitly derived raw-corner support.
This does not run an R3 certificate, global front or a new solver predicate.
"""
import pathlib,struct,math,json,collections,sys
from fractions import Fraction as F
from witness_audit import Reader,need,d64,flt

def packed(x):return struct.pack('<d',x)
def sub(a,b):return tuple(x-y for x,y in zip(a,b))
def dot(a,b):return (a[0]*b[0]+a[1]*b[1])+a[2]*b[2]
def cross(a,b):return (a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
def norm(a):return math.sqrt(dot(a,a))
def check_d(row,k,x):need(packed(d64(row[k]))==packed(x),'NATIVE_DOUBLE_BITS:'+str(k))
def audit(path):
 p=pathlib.Path(path);prep=[r.split() for r in (p/'prepared.tsv').read_text().splitlines()];acc=[r.split() for r in (p/'accumulators.tsv').read_text().splitlines()];members=[r.split() for r in (p/'memberships.tsv').read_text().splitlines()]
 need(len(prep)<=35112 and len(acc)<=35112 and len(members)<=35112,'LEDGER_BUDGET')
 points=[tuple(map(float,r[19:22])) for r in acc];raw=[tuple(map(float,r[4:7])) for r in prep];keys=[tuple(map(int,r[1:4])) for r in prep];lookup={k:i for i,k in enumerate(keys)};need(len(lookup)==len(keys),'DUPLICATE_SOURCE_PIXEL')
 grid={};source_acc={}
 for r in members:
  aid,sid=int(r[1]),int(r[2]);need(keys[sid] not in grid,'DUPLICATE_ACCEPTED_PIXEL');grid[keys[sid]]=(aid,sid);source_acc[sid]=aid
 inp=Reader(p/'inputs.bits');need(inp.integer()==8,'CAMERA_COUNT');cams=[]
 for _ in range(8):need(inp.take('QQ')==(160,120),'CAMERA_DIMS');cams.append(inp.take('16d'));need(inp.integer()==76800,'IMAGE_SIZE');inp.skip(76800)
 need(inp.pos==len(inp.data),'INPUT_EOF')
 dense=Reader(p/'dense.bits');need(dense.integer()==8,'MAP_COUNT');depth=[]
 for _ in range(8):
  need(dense.take('QQ')==(160,120),'MAP_DIMS');need(dense.integer()==19200,'DEPTH_SIZE');depth.append(dense.take('19200f'));need(dense.integer()==19200,'CONF_SIZE');dense.skip(19200*4)
 # Complete DenseResult tail/EOF is independently checked by witness_audit.
 declared=tuple(map(int,(p/'cell-counts.tsv').read_text().split()));need(len(declared)==2 and declared[0]==34048 and declared[1]<=68096,'COUNTS')
 cb=(p/'cells.bin').read_bytes();tb=(p/'triangles.bin').read_bytes();need(len(cb)==declared[0]*320 and len(tb)==declared[1]*256,'BINARY_WIDTH')
 cells=list(struct.iter_unpack('<40Q',cb));triangles=list(struct.iter_unpack('<32Q',tb));expected=[];complete=0;missing=0
 for i,c in enumerate(cells):
  cam=i//(76*56);n=i%(76*56);x=3+2*(n%76);y=3+2*(n//76);pix=(y*160+x,y*160+x+2,(y+2)*160+x,(y+2)*160+x+2)
  need(c[:8]==(i,cam,x,y,*pix) and not any(c[20:]),'CELL_SEQUENCE_PIXELS_RESERVED')
  binds=[grid.get((cam,j%160,j//160)) for j in pix];ids=tuple(b[0] if b else -1 for b in binds)
  need(c[8:12]==tuple(v%(1<<64) for v in ids),'CELL_ACCUMULATOR_BINDING')
  if min(ids)<0:missing+=1;need(not any(c[12:20]),'UNEVALUATED_MISSING_CELL');continue
  complete+=1;need(c[16]==1 and c[17] in (0,1),'COMPLETE_CELL_FLAGS');need(c[12:16]==tuple(struct.unpack('<I',struct.pack('<f',depth[cam][j]))[0] for j in pix),'CELL_ORIGINAL_DEPTH')
  v=[points[j] for j in ids];d03=norm(sub(v[0],v[3]));d12=norm(sub(v[1],v[2]));check_d(c,18,d03);check_d(c,19,d12)
  diag=d03<d12 or (d03==d12 and sorted((v[0],v[3]))<sorted((v[1],v[2])))
  need(c[17]==diag,'NATIVE_DIAGONAL_BINDING')
  for local in (((0,1,3),(0,3,2)) if diag else ((0,1,2),(1,3,2))):expected.append((c,local,binds))
 need(len(expected)==len(triangles),'COMPLETE_TRIANGLE_ATTEMPTS')
 facekeys=set();counts=collections.Counter();liftcounts=collections.Counter();obs_inc=[0]*len(prep);acc_inc=[0]*len(acc);camera_inc=collections.Counter();rawrows=[];accepted=0
 for seq,(t,(c,local,binds)) in enumerate(zip(triangles,expected)):
  ids=tuple(binds[j][0] for j in local);sids=tuple(binds[j][1] for j in local);d=tuple(flt(c[12+j]) for j in local);cam=c[1];C=cams[cam];need(t[:9]==(seq,c[0],cam,*local,*ids) and t[9:12]==tuple(c[12+j] for j in local),'TRIANGLE_BINDING');need(not any(t[28:]),'TRIANGLE_RESERVED')
  mask=0;true=0;computed={};status=0;winding=None
  def test(bit,result):
   nonlocal mask,true
   mask|=1<<bit
   if result:true|=1<<bit
   return result
  if test(0,len(set(ids))!=3):status=1
  else:
   hi=max(d);lo=min(d);computed.update({15:hi,16:lo})
   if test(1,lo<=0 or (hi-lo)/hi>.08):status=2
   else:
    A,B,D=(points[j] for j in ids);footprint=hi*2/min(C[12],C[13]);limit=footprint*3.2;computed.update({17:footprint,18:limit})
    if test(2,norm(sub(A,B))>limit) or test(3,norm(sub(A,D))>limit) or test(4,norm(sub(B,D))>limit):status=3
    else:
     normal=cross(sub(B,A),sub(D,A));computed.update({19+j:v for j,v in enumerate(normal)})
     if test(5,norm(normal)<footprint*footprint*.015):status=4
     else:
      key=tuple(sorted(ids));duplicate=key in facekeys;facekeys.add(key)
      if test(6,duplicate):status=5
      else:
       centroid=tuple(((A[j]+B[j])+D[j])*(1./3) for j in range(3));computed.update({22+j:v for j,v in enumerate(centroid)})
       center=tuple(-((C[j]*C[9]+C[3+j]*C[10])+C[6+j]*C[11]) for j in range(3));winding=dot(normal,sub(center,centroid))<0
  need((t[12],t[13],t[14])==(mask,true,status),'SHORT_CIRCUIT_PREDICATES')
  for k in range(15,25):
   if k in computed:check_d(t,k,computed[k])
   else:need(t[k]==0,'UNEVALUATED_TRIANGLE_VALUE')
  if status==0:need((t[25],t[26],t[27])==(int(winding),accepted,1),'OBSERVED_WINDING_FACE_INDEX');accepted+=1
  else:need(not any(t[25:28]),'WINDING_NOT_EVALUATED_ON_REFUSAL')
  counts[str(status)]+=1
  # New support policy B: geometric passes before duplicate suppression,
  # original immutable verified XYZ; native winding is NOT reused.
  reason='NATIVE_GEOMETRIC_REFUSAL';flip=-1
  if status in (0,5):
   P=[tuple(F(v) for v in raw[s]) for s in sids];Q=[]
   for xyz in P:Q.append(tuple(sum(F(C[3*j+k])*xyz[k] for k in range(3))+F(C[9+j]) for j in range(3)))
   if any(q[2]<=F(.01) for q in Q):reason='RAW_NONPOSITIVE_DEPTH'
   else:
    xy=[(F(C[12])*q[0]/q[2]+F(C[14]),F(C[13])*q[1]/q[2]+F(C[15])) for q in Q]
    reproj=all(abs(x-keys[s][1])<=F(.25) and abs(y-keys[s][2])<=F(.25) for s,(x,y) in zip(sids,xy))
    area=(xy[1][0]-xy[0][0])*(xy[2][1]-xy[0][1])-(xy[1][1]-xy[0][1])*(xy[2][0]-xy[0][0]);n=cross(sub(P[1],P[0]),sub(P[2],P[0]))
    if not reproj:reason='RAW_REPROJECTION_REFUSAL'
    elif not area or not any(n):reason='RAW_DEGENERATE_REFUSAL'
    else:
     # Exact algebraic projection camera center, not native transpose center.
     r0=tuple(F(v) for v in C[:3]);r1=tuple(F(v) for v in C[3:6]);r2=tuple(F(v) for v in C[6:9]);det=dot(r0,cross(r1,r2));need(det!=0,'CAMERA_INVERTIBLE')
     cof=(cross(r1,r2),cross(r2,r0),cross(r0,r1));center=tuple(-sum(F(C[9+k])*cof[k][j] for k in range(3))/det for j in range(3));centroid=tuple(sum(q[j] for q in P)/3 for j in range(3));orientation=dot(n,sub(center,centroid))
     if not orientation:reason='RAW_EDGE_ON_ORIENTATION_REFUSAL'
     else:
      reason='DERIVED_SUPPORT';flip=int(orientation<0)
      for sid,aid in zip(sids,ids):obs_inc[sid]+=1;acc_inc[aid]+=1
      camera_inc[str(cam)]+=1
  liftcounts[reason]+=1;rawrows.append(f'{seq} {c[0]} {cam} '+ ' '.join(map(str,sids))+f' {reason} {flip}\n')
 # Retain all directed statuses, using strict mutual identities only for
 # feasibility incidence; no new query and no presumed source correspondence.
 wr=list(struct.iter_unpack('<24Q',(p/'witness.bin').read_bytes()));directed=set();confirmations=0
 for w in wr:
  if w[5]==0:
   confirmations+=1;a=lookup.get((w[1],w[3],w[4]));b=lookup.get((w[2],w[6],w[7]))
   if a is not None and b is not None:directed.add((a,b))
 strict={(a,b) for a,b in directed if (b,a) in directed};degree=collections.Counter(a for a,b in strict);strict_inc=sum(obs_inc[a]>0 and obs_inc[b]>0 for a,b in strict)
 incidence='sourceId accumulatorId supportTriangleIncidence strictReverseDegree preparedNormalSupported\n'+''.join(f'{s} {source_acc.get(s,-1)} {obs_inc[s]} {degree[s]} {prep[s][21]}\n' for s in range(len(prep)))
 accrows='accumulatorId supportCornerIncidence preparedNormalSupported sourceCount\n'+''.join(f'{i} {acc_inc[i]} {r[1]} {r[30]}\n' for i,r in enumerate(acc))
 outputs={'derived-support.tsv':'attemptId cellId camera source0 source1 source2 status derivedFlip\n'+''.join(rawrows),'source-incidence.tsv':incidence,'accumulator-incidence.tsv':accrows}
 result={'scope':'PROVENANCE_INCIDENCE_ONLY_NOT_R3_CERTIFICATE','cells':len(cells),'completeCells':complete,'missingCornerCells':missing,'triangleAttempts':len(triangles),'nativeStatusCounts':dict(counts),'nativeAcceptedBeforeCleanup':accepted,'derivedLiftCounts':dict(liftcounts),'derivedTrianglesPerCamera':dict(camera_inc),'allObservations':len(prep),'allAccumulators':len(acc),'observationsWithDerivedIncidence':sum(n>0 for n in obs_inc),'accumulatorsWithDerivedIncidence':sum(n>0 for n in acc_inc),'unknownNormalAccumulators':sum(r[1]=='0' for r in acc),'unknownNormalAccumulatorsWithIncidence':sum(r[1]=='0' and acc_inc[i]>0 for i,r in enumerate(acc)),'directedConfirmations':confirmations,'strictReverseDirectedLinks':len(strict),'strictReverseLinksWithBothEndpointsIncident':strict_inc,'nativePredicateReplay':'EXACT_BITS_AND_BRANCHES','rawLift':'EXPLICIT_DERIVED_POLICY_NOT_NATIVE_RAW_TRIANGULATION','coverage':'NOT_EVALUATED','front':'NOT_RUN'}
 outputs['cell-audit.json']=json.dumps(result,indent=2)+'\n';need(sum(len(v.encode()) for v in outputs.values())<=16777216,'ADAPTER_OUTPUT_BUDGET')
 for n,v in outputs.items():(p/n).write_text(v)
 return result
if __name__=='__main__':
 need(len(sys.argv)==2,'usage: cell_audit.py OBSERVER_OUTPUT');print(json.dumps(audit(sys.argv[1])))
