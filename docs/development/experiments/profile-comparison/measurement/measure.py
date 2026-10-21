"""Explicit actual-profile adapter. Does NOT invoke or impersonate strict core evaluator."""
import argparse,json,pathlib,hashlib,sys
if not __debug__:raise RuntimeError('Optimized Python disables integrity assertions; refused')
import numpy as np
from alignment import cameras
from projection import projection
P=pathlib.Path
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):
 assert p.is_file() and not p.is_symlink() and 0<p.stat().st_size<=32*1024*1024
 return p.read_bytes()
def verify(p,e):
 b=read(p);assert len(b)==e['bytes'] and hashlib.sha256(b).hexdigest()==e['sha256']
def diff(a,b,path=''):
 if isinstance(a,dict) and isinstance(b,dict):
  return sum([diff(a.get(k),b.get(k),path+'/'+k) for k in sorted(a.keys()|b.keys())],[])
 return [] if a==b else [dict(path=path,Compatibility=a,VerifiedRefinedCompact=b)]
def summary(a):return None if not len(a) else dict(zip(['p50','p90','p95','max'],map(float,np.quantile(a,[.5,.9,.95,1]))))
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--repo',type=P,required=True);ap.add_argument('--evidence',type=P,required=True);ap.add_argument('--receipt-sha256',required=True);ap.add_argument('--comparison-sha256',required=True);ap.add_argument('--output',type=P,required=True);args=ap.parse_args()
 repo=args.repo;ev=args.evidence;pair=ev/'profile-comparison';cap=ev/'quality-capture'
 assert not args.output.exists()
 rp=ev/'receipt.json';assert sha(rp)==args.receipt_sha256
 receipt=json.loads(read(rp));assert receipt['outcome']=='PASS' and receipt['status']=='EXIT_0' and receipt['restoration']['restored'] is True
 mp=pair/'comparison.json';assert sha(mp)==args.comparison_sha256
 m=json.loads(read(mp));assert m['synthetic'] is True and m['schema']==2
 assert m['qualification']=='GEOMETRY_TEXTURE_CAPTURE_ONLY_NOT_REFINED_CACHE_QUALIFICATION'
 assert sha(cap/'capture.json')==m['baselineCaptureManifestSha256']
 cm=json.loads(read(cap/'capture.json'))
 for n,e in cm['artifacts'].items():assert P(n).name==n;verify(cap/n,e)
 verify(pair/'alignment.rfac',m['alignmentCheckpoint']);assert sha(pair/'alignment.rfac')==m['alignmentAdapter']['alignmentCheckpointSha256']
 sys.path.insert(0,str(repo))
 from tools.relief_quality_validation.evaluate import PINS,FIXTURES
 from tools.make_fixture import surface_z
 from tools.qa.geometry_quality.__main__ import load
 from tools.qa.geometry_quality.metrics import topology,surface_samples
 for n,h in PINS.items():assert sha(repo/n)==h
 byhash={h:n for n,h in FIXTURES.items()}
 for n,h in FIXTURES.items():assert sha(repo/'benchmarks/fixtures/relief'/n)==h
 mapping={p['photoId']:byhash[p['sha256']] for p in cm['photos']};assert len(mapping)==3 and set(mapping.values())==set(FIXTURES)
 cams,decode=cameras(read(pair/'alignment.rfac'));assert [c['photoId'] for c in cams]==m['alignmentAdapter']['photoOrder']
 poses={}
 for c in cams:
  r=np.array(c['rotation']).reshape(3,3);t=np.array(c['translation']);assert np.allclose(r@r.T,np.eye(3),atol=1e-6,rtol=0) and abs(np.linalg.det(r)-1)<1e-6
  poses[mapping[c['photoId']]]=(r,t,-r.T@t)
 r,t,c0=poses['view-01.jpg'];c1=poses['view-02.jpg'][2]
 truth=json.loads(read(repo/'benchmarks/fixtures/relief/calibration.json'));ref={c['image']:c for c in truth['cameras']}
 assert np.array_equal(ref['view-01.jpg']['R'],np.eye(3)) and np.array_equal(ref['view-01.jpg']['t'],np.zeros(3))
 baseline=float(np.linalg.norm(np.array(ref['view-02.jpg']['center'])-ref['view-01.jpg']['center']));estimated=float(np.linalg.norm(c1-c0));assert estimated>0
 scale=baseline/estimated;results={};full={}
 assert {p['profile'] for p in m['profiles']}=={'Compatibility','VerifiedRefinedCompact'} and len(m['profiles'])==2
 for entry in m['profiles']:
  name=entry['profile'];folder=pair/name
  required={'mesh.ply','mesh.glb','textured.glb','texture.png','direct-result.json'}
  if name=='Compatibility':
   assert entry['surfaceCheckpointStatus']=='CAPTURED';required.add('surface-v2.rfmesh')
  else:
   assert entry['surfaceCheckpointStatus']=='NOT_AVAILABLE_BY_REFINED_POLICY'
   assert not (folder/'surface-v2.rfmesh').exists() and not (folder/'surface-v2.rfmesh').is_symlink()
  assert set(entry['artifacts'])==required and {x.name for x in folder.iterdir()}==required
  for n,e in entry['artifacts'].items():verify(folder/n,e)
  direct=json.loads(read(folder/'direct-result.json'));assert direct['profile']==name
  full[name]=direct;dense=direct['payload']['dense'];assert dense['executionProfile']==name and dense['geometryReused'] is False
  assert 'mesh' in direct['payload'] and 'texture' in direct['payload']
  if name=='Compatibility':assert dense['surfaceCheckpointAvailable'] is True
  else:assert dense['surfaceCheckpointAvailable'] is False and dense['surfaceCheckpointWritten'] is False and dense['depthCheckpointWritten']==0
  assert dense['selectedPhotoIds']==m['alignmentAdapter']['referencePhotoIds']
  assert [dense[k] for k in ['rectifiedWidth','rectifiedHeight','pixelStride','depthPlanes']]==[256,192,3,56]
  assert dense['workload']['memoryTier']=='compact' and dense['depthCheckpointResumed']==0
  v,f=load(folder/'mesh.ply');assert 0<len(v)<=200000 and 0<len(f)<=200000
  lines=(folder/'mesh.ply').read_text().splitlines();end=lines.index('end_header');rawv=np.array([list(map(float,s.split())) for s in lines[end+1:end+1+len(v)]]);assert np.array_equal(rawv[:,:3],v)
  assert len(lines[end+1+len(v):])==len(f)
  rawf=np.column_stack([np.full(len(f),3),f]);aligned=scale*(v@r.T+t)
  sample=surface_samples(aligned,f,count=4096);inside=(abs(sample[:,0])<1.72)&(abs(sample[:,1])<1.35);res=np.abs(sample[:,2]-surface_z(sample[:,0],sample[:,1]))
  top=topology(v,f) # Default intersection_budget=5000, NEVER relaxed.
  results[name]=dict(surfaceCheckpointStatus=entry['surfaceCheckpointStatus'],artifacts=entry['artifacts'],vertices=len(v),triangles=len(f),topology=top,fullNativeResult=direct,projected=projection(rawv,rawf,r,t,scale,ref['view-01.jpg']['K'],surface_z),surface=dict(sampleCount=len(sample),inDomainSamples=int(inside.sum()),outOfDomainSamples=int((~inside).sum()),verticalResidualInDomainSyntheticUnits=summary(res[inside]),allSamplesAnalyticExtrapolationResidual=summary(res)))
 assert full['Compatibility']['payload']['dense']['executedSourcePhotoIds']==full['VerifiedRefinedCompact']['payload']['dense']['executedSourcePhotoIds']
 report=dict(status='GEOMETRY_TEXTURE_MEASURE_ONLY_NOT_CACHE_OR_QUALITY_PASS',qualification=m['qualification'],refinedCacheQualification='NOT_QUALIFIED_BY_THIS_GEOMETRY_ONLY_EXPERIMENT; prior full-cache failures remain failures',scope=m['scope'],alignmentCheckpoint=m['alignmentCheckpoint'],alignmentDecode=decode,actualCameras=cams,photoHashMapping=cm['photos'],gauge=dict(method='fixed view01 accepted camera plus positive view01-view02 baseline; no surface fit',scale=scale,referenceBaselineSyntheticUnits=baseline,estimatedBaseline=estimated),profiles=results,fullJsonDifferences=diff(full['Compatibility'],full['VerifiedRefinedCompact']),limitations=['New explicit adapter; no fabricated result.json or strict-evaluator pass','Profile bundle comparison, not single-parameter attribution or controller/shipping qualification','Projection coverage is not 3D completeness; wrong-depth covered pixels remain covered and depth residual is separate','Synthetic analytic intended truth, not physical accuracy; ray iteration/JPEG error not independently quantified','No geometry changes, filtering, welding or outlier removal; all native JSON fields retained including topologyIncomplete','Topology self-intersection budget remains 5000; NOT_RUN_BUDGET is unknown, not clean','Mesh surface sample uses existing fixed4096 area-weighted seed84001; vertical error is not Euclidean distance','RFAC reader checks framing and parses cameras, not full production semantic acceptance; requires successful restored harness receipt'])
 args.output.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n')
 print(json.dumps(dict(status=report['status'],reportSha256=sha(args.output))))
if __name__=='__main__':main()
