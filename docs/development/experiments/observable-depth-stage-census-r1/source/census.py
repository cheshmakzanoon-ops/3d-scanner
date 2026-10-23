import sys
sys.dont_write_bytecode=True
import pathlib,json,hashlib,math,struct,collections,csv,io,resource
import numpy as np
from witness_audit import Reader,audit,need
import cuboid_evaluate as ev

def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def round_away(q):
 need(math.isfinite(q),'FINITE_ROUND_INPUT');fraction,integer=math.modf(q);return int(integer)+(1 if fraction>=.5 else -1 if fraction<=-.5 else 0)
def nearest(v,origin=3,step=2):
 return round_away((v-origin)/step)*step+origin

def native_rounding_controls(path):
 count=0
 for line in pathlib.Path(path).read_text().splitlines():
  kind,a,b=line.split();value=float.fromhex(a);expected=float.fromhex(b);need((round_away(value) if kind=='q' else nearest(value))==expected,'NATIVE_NEXTAFTER_ROUNDING');count+=1
 need(count==2406,'ROUNDING_CONTROL_COUNT');return count

def controls():
 need([nearest(v) for v in (0,1,2,3,4,5,6)]==[-1,1,1,3,5,5,7],'NATIVE_TIE_AWAY_ROUNDING')
 need(list(range(3,160-2-2,2))[-1]==155 and list(range(3,120-2-2,2))[-1]==115,'BORDER_GRID')
 c=[1,0,0,0,1,0,0,0,1,0,0,0,1,1,0,0]
 v=np.array([[0,0,4],[12,0,4],[0,12,4],[0,0,2],[2,0,2],[0,2,2]],float);t=np.array([[0,1,2],[3,4,5]])
 d,f=ev.raster(v,t,c,4,4);need(d[0,0]==2 and f[0,0]==1 and d[0,2]==4 and f[0,2]==0 and not np.isfinite(d[3,3]),'FOREGROUND_OTHER_FACE_BACKGROUND_RASTER')
 uv,z=ev.project(np.array([[2.,0.,4.]]),c);need(uv[0,0]==.5 and z[0]==4,'PROJECTED_OFFSET_NOT_SAME_RAY')
 return {'rounding':'ties away from zero, including negative lattice coordinate','borders':'155/115 last sample; x0 nearest -1 outside','rayDepth':'nearer face/other face/background distinct','projection':'offset ray must use its own truth depth'}

def main():
 package,run,fixture,source,out=map(pathlib.Path,sys.argv[1:]);cfg=json.loads((package/'config.json').read_text());need(not out.exists(),'FRESH_OUTPUT');out.mkdir();need(0<resource.getrlimit(resource.RLIMIT_AS)[0]<=cfg['maxAddressSpaceBytes'] and 0<resource.getrlimit(resource.RLIMIT_CPU)[0]<=cfg['maxSeconds'],'LIMITS');roots={'run':run,'fixture':fixture,'source':source};pins=json.loads((package/'input-pins.json').read_text())
 def verify():
  for root,fs in pins.items():
   for name,h in fs.items():need(sha(roots[root]/name)==h,'INPUT_HASH:'+root+'/'+name)
 written=0
 def write(name,data):
  nonlocal written
  if not isinstance(data,bytes):data=data.encode()
  need(written+len(data)<=cfg['maxDataBytes'],'OUTPUT_BUDGET');(out/name).write_bytes(data);written+=len(data)
 try:
  verify();control=controls();p=run/'observer';native=audit(p);m=json.loads((fixture/'evaluation-only/fixture.json').read_text());ev.validate_fixture(m);v=np.asarray(m['vertices']);tri=np.asarray(m['triangles']);normals=ev.normals(v,tri);cams=np.asarray(m['cameras']);diag=float(np.linalg.norm(np.ptp(v,axis=0)));inp=Reader(p/'inputs.bits');need(inp.integer()==8,'INPUT_VIEWS')
  for cam in range(8):need(inp.take('QQ')==(160,120),'DIMS');need(np.array_equal(inp.take('16d'),cams[cam]),'EVALUATOR_SOLVER_CAMERA_BINDING');need(inp.integer()==76800,'IMAGE_SIZE');inp.skip(76800)
  need(inp.pos==len(inp.data),'INPUT_EOF');r=Reader(p/'dense.bits');need(r.integer()==8,'MAP_COUNT');maps=[]
  for cam in range(8):
   need(r.take('QQ')==(160,120) and r.integer()==19200,'MAP_DIMS');depth=np.asarray(r.take('19200f')).reshape(120,160);need(r.integer()==19200,'CONF_SIZE');conf=np.asarray(r.take('19200f')).reshape(120,160);maps.append((depth,conf))
  # The unchanged complete native auditor above consumes/validates the full
  # dense.bits tail, actual attempts, generated IDs and all counters.
  prep=[line.split() for line in (p/'prepared.tsv').read_text().splitlines()];lookup={(int(a[1]),int(a[2]),int(a[3])):int(a[0]) for a in prep};need(len(lookup)==9172,'SAMPLE_IDENTITIES');members={int(a[2]):int(a[1]) for a in (line.split() for line in (p/'memberships.tsv').read_text().splitlines())};need(len(members)==9171,'MEMBERSHIP_COUNT');incident=set()
  for row in (p/'derived-support.tsv').read_text().splitlines()[1:]:
   a=row.split()
   if a[6]=='DERIVED_SUPPORT':incident.update(map(int,a[3:6]))
  attempts=collections.defaultdict(collections.Counter)
  for row in struct.iter_unpack('<24Q',(p/'witness.bin').read_bytes()):attempts[(row[1],row[3],row[4])][str(row[5])]+=1
  plan=[list(map(int,line.split()))[2:] for line in (p/'source-plan.tsv').read_text().splitlines()]
  grid={(x,y) for y in range(3,116,2) for x in range(3,156,2)};need(len(grid)*8==35112,'GRID_COUNT');gridmask=np.zeros((120,160),bool)
  for x,y in grid:gridmask[y,x]=True
  stages=['NO_ELIGIBLE_GRID','ZERO_PHOTOMETRIC_DEPTH','POSITIVE_NOT_VERIFIED','VERIFIED_NOT_ACCEPTED','ACCEPTED_NOT_SOURCE_INCIDENT','SOURCE_INCIDENT']
  def classify(cam,x,y):
   if (x,y) not in grid:return stages[0],None,None
   d=float(maps[cam][0][y,x]);sid=lookup.get((cam,x,y));aid=members.get(sid)
   if d<=0:need(sid is None,'ZERO_VERIFIED');return stages[1],sid,aid
   if sid is None:return stages[2],sid,aid
   if aid is None:return stages[3],sid,aid
   return (stages[5] if sid in incident else stages[4]),sid,aid
  truth=[ev.raster(v,tri,c,160,120) for c in cams];gridcounts=[];positiveErrors=[]
  for cam,(d,conf) in enumerate(maps):
   need(np.all(np.isfinite(d)) and np.all(d>=0) and np.all(d[~gridmask]==0),'ACTUAL_MAP_LATTICE');cnt=collections.Counter();err=[]
   for x,y in sorted(grid,key=lambda z:(z[1],z[0])):
    stage,sid,aid=classify(cam,x,y);cnt[stage]+=1
    if d[y,x]>0:
     need(sum(attempts[(cam,x,y)].values())==len(plan[cam]),'POSITIVE_ATTEMPTS_COMPLETE')
     if np.isfinite(truth[cam][0][y,x]):err.append(abs(float(d[y,x])-float(truth[cam][0][y,x])))
   gridcounts.append({'camera':cam,'stageCounts':{s:cnt[s] for s in stages},'positiveDepthOnTruthError':ev.stats(np.asarray(err))});positiveErrors.extend(err)
  points,faceids=ev.sample(v,tri);visible=ev.observable_convex(points,normals[faceids],cams,160,120)>=2;ids=np.flatnonzero(visible);need(len(ids)==3734,'FROZEN_OBSERVABLE_COUNT')
  # Independently reproduce the evaluation-only all-point necessary bound,
  # without using it to select or discard any reference sample/view.
  acc=[line.split() for line in (p/'accumulators.tsv').read_text().splitlines()];need(len(acc)==4855,'ACCUMULATOR_COUNT')
  # Native accumulator column positions are bound by explicit header below.
  from_header='accumulatorId supportedNormal weightedPx weightedPy weightedPz weightedNx weightedNy weightedNz weightedR weightedG weightedB weight maxConfidence viewMask fusionRadius radiusLevel cellX cellY cellZ px py pz r g b confidence supportingViews preFaceNx preFaceNy preFaceNz sourceCount sourceIds...'.split()
  pool=np.asarray([[float(a[from_header.index(k)]) for k in ('px','py','pz')] for a in acc]);need(len(pool)==4855 and np.isfinite(pool).all(),'ALL_POINT_POOL');nearestPool=[]
  for q in np.array_split(points[ids],60):nearestPool.extend(np.sqrt(np.min(np.sum((q[:,None,:]-pool[None,:,:])**2,axis=2),axis=1)))
  rho=.04965026414510365;bound=np.asarray(nearestPool)>2*rho+.01*diag+1e-12*max(1,diag)
  records=[];perView=[collections.Counter() for _ in cams];groups=collections.defaultdict(collections.Counter);reference=[];all_errors=[]
  for j,idx in enumerate(ids):
   q=points[idx];face=int(faceids[idx]);views=[];accurate={str(f):False for f in cfg['errorFractions']};verifiedAcc={str(f):False for f in cfg['errorFractions']};state=[]
   for cam,c in enumerate(cams):
    uv,z=ev.project(q[None,:],c);u,w=map(float,uv[0]);z=float(z[0]);center=-c[:9].reshape(3,3).T@c[9:12]
    isvis=float(np.dot(normals[face],center-q))>1e-9 and z>0 and 6<=u<154 and 6<=w<114
    if not isvis:continue
    x,y=nearest(u),nearest(w);stage,sid,aid=classify(cam,x,y);d=float(maps[cam][0][y,x]) if (x,y) in grid else None;cf=float(maps[cam][1][y,x]) if (x,y) in grid else None;tf=int(truth[cam][1][y,x]) if 0<=x<160 and 0<=y<120 else -1;tz=float(truth[cam][0][y,x]) if tf>=0 else None
    relation='BACKGROUND' if tf<0 else ('SAME_PHYSICAL_FACE' if np.array_equal(normals[tf],normals[face]) else 'OTHER_FACE');error=abs(d-tz) if d is not None and d>0 and tz is not None else None
    perView[cam][stage]+=1;groups['unreachable1pct' if bound[j] else 'potentiallyReachable1pct'][stage]+=1;groups[relation][stage]+=1;views.append(cam);state.append(stages.index(stage))
    if error is not None:
     all_errors.append(error)
     if relation=='SAME_PHYSICAL_FACE':
      for f in cfg['errorFractions']:
       if error<=diag*f:accurate[str(f)]=True;verifiedAcc[str(f)]|=sid is not None
    records.append([int(idx),face,cam,u,w,z,x,y,x-u,y-w,relation,tf,tz,stage,d,cf,sid,aid,json.dumps(dict(attempts[(cam,x,y)]),sort_keys=True,separators=(',',':')),error,int(bound[j])])
   need(len(views)>=2,'VISIBLE_SAMPLE_VIEW_COMPLETENESS');reference.append({'sample':int(idx),'visibleViews':views,'highestRetainedStage':stages[max(state)],'nearestAllPointDistance':float(nearestPool[j]),'unreachableAtOnePercent':bool(bound[j]),'anySameFaceAccuratePositiveMap':accurate,'anySameFaceAccurateVerifiedMap':verifiedAcc})
  need(len(records)<=cfg['maxVisibleSampleViewRows'],'ROW_BOUND');buf=io.StringIO();writer=csv.writer(buf);writer.writerow('sample triangle camera u v sampleZ gridX gridY offsetX offsetY gridRayRelation gridTriangle gridTruthZ stage depth confidence sampleId accumulatorId actualAttemptStatusCounts absoluteGridZError unreachable1pct'.split());writer.writerows(records);write('visible-sample-view-stages.csv',buf.getvalue());write('reference-summaries.json',json.dumps(reference,separators=(',',':'))+'\n')
  result={'status':'EVALUATION_ONLY_COMPLETE','scope':cfg['scope'],'nativeAudit':native,'controls':control,'allGridSites':35112,'observableSamples':3734,'visibleSampleViewRows':len(records),'referenceDiagonal':diag,'perView':[{'camera':i,'visibleSampleViewStageCounts':{s:c[s] for s in stages},'allGrid':gridcounts[i]} for i,c in enumerate(perView)],'groupedVisibleSampleViewCounts':{g:{s:c[s] for s in stages} for g,c in groups.items()},'highestRetainedStagePerReference':dict(collections.Counter(r['highestRetainedStage'] for r in reference)),'anyViewSameFaceAccuratePositiveMap':{str(f):sum(r['anySameFaceAccuratePositiveMap'][str(f)] for r in reference) for f in cfg['errorFractions']},'anyViewSameFaceAccurateVerifiedMap':{str(f):sum(r['anySameFaceAccurateVerifiedMap'][str(f)] for r in reference) for f in cfg['errorFractions']},'unreachableAtOnePercent':int(bound.sum()),'zeroPhotometricDepthReason':'UNAVAILABLE_NO_REJECTED_NCC_WINNER_COST_LOGS','zeroDepthNotReinterpretedAsConsistencyRejection':True,'geometryFedToSolver':False,'solverInvoked':False,'sourcePlan':plan,'positiveMapTruthZErrorAllGrid':ev.stats(np.asarray(positiveErrors)),'gridRayTruthCaveat':'offset grid ray may hit another face/background; no comparison against offset original-sample depth','lattice':'native origin3 stride2 std::round ties away from zero; x3..155/y3..115; all positive map entries bound to this lattice','inputsUnchanged':True}
  verify();write('summary.json',json.dumps(result,indent=2,allow_nan=False)+'\n');print(json.dumps({k:result[k] for k in ('status','visibleSampleViewRows','highestRetainedStagePerReference','anyViewSameFaceAccuratePositiveMap','anyViewSameFaceAccurateVerifiedMap','unreachableAtOnePercent')}))
 except BaseException as e:
  data=json.dumps({'error':type(e).__name__,'reason':str(e),'status':'REFUSED_NO_COMPLETE_CENSUS'})+'\n'
  if written+len(data.encode())<=cfg['maxDataBytes']:write('REFUSED.json',data)
  raise
if __name__=='__main__':main()
