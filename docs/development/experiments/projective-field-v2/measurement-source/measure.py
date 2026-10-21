"""Explicit experimental interpolated global-field adapter; original native audit remains separate."""
import argparse,json,pathlib,hashlib,sys
if not __debug__:raise RuntimeError('Optimized Python refused')
import numpy as np
from projection import projection
P=pathlib.Path
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def summary(a):return None if not len(a) else dict(zip(['p50','p90','p95','p99','max'],map(float,np.quantile(a,[.5,.9,.95,.99,1]))))
def read(p):
 assert p.is_file() and not p.is_symlink() and 0<p.stat().st_size<=32*1024*1024
 return json.loads(p.read_bytes())
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--config',type=P,required=True);ap.add_argument('--config-sha256',required=True);ap.add_argument('--output',type=P,required=True);a=ap.parse_args();assert sha(a.config)==a.config_sha256 and not a.output.exists();c=read(a.config)
 assert c['schema']==2 and c['scope']=='EXPERIMENTAL_PROJECTIVE_FIELD_V2_FIXED_SNAPSHOT_MEASURE_ONLY'
 repo=P(c['repo']);pins=c['inputSha256']
 for p,h in pins.items():assert sha(P(p))==h,p
 def pinned(path):
  p=P(path);assert str(p) in pins;return p
 host=read(pinned(c['hostReceipt']));assert host['outcome']=='PASS' and host['status']=='EXIT_0' and host['restoration']['restored'] is True
 stagec=read(pinned(c['baselineReceipt']));assert stagec['status']=='EXACT_ORIGINAL_REPLAY_TELEMETRY_MEASURE_ONLY'
 candidate=read(pinned(c['candidateReceipt']));assert candidate['status']==c['candidateTerminalStatus']
 assert c['candidateTerminalStatus']=='EXPERIMENTAL_PROJECTIVE_FIELD_REPLAY_ONLY_NOT_QUALITY_ACCEPTANCE' and candidate['causalQualityClaim'] is False
 for key,h in candidate['outputSha256'].items():assert sha(pinned(str(P(c['candidateReceipt']).parent/key)))==h
 provenance=read(pinned(c['meshes']['candidate']['provenance']))
 witness=read(pinned(c['candidateWitness']))
 counters=read(pinned(c['meshes']['candidate']['counters']));assert counters==candidate['newAlgorithmCounters'] and counters['scope']=='new_projective_algorithm_only_not_v4_attribution'
 assert provenance==candidate['candidate'] and provenance['representation']=='experimental_canonical_projective_camera_z_field_v1'
 assert provenance['vertices']==2608 and provenance['faces']==1514 and len(provenance['faceWitnessPatch'])==1514
 assert witness['status']=='INDEPENDENT_WHOLE_FACE_WITNESS_PASS' and witness['facesChecked']==1514 and witness['sourcePatchesChecked']==6782 and witness['notQualityAcceptance'] is True
 for key in ['experimental.ply','provenance.json','counters.json','SUCCESS']:assert candidate['outputSha256']['candidate/'+key]==candidate['outputSha256']['repeat/'+key]
 for command in candidate['commands']:assert command['returnCode']==0 and command['timedOut'] is False and not command['remainingGroupMembers'] and command['cleanupError'] is None
 snapshot=pinned(c['snapshot']);assert sha(snapshot)=='d1b605dffddb9488e77ff2f16706f3cf0f2d4161e3239d03f51648d5290b71c0'
 assert stagec['depthIdentity']==c['depthIdentity']=='9fb072db88498574d07b6125101f2454a1d4343b209944fe8de983b17f0f0995'
 assert candidate['inputSha256'][str(snapshot)]==sha(snapshot)
 cap=pinned(c['captureManifest']);m=read(cap);result=read(pinned(c['captureResult']));assert m['synthetic'] is True and m['artifactKey']==result['artifactKey']
 assert m['artifacts']['result.json']['sha256']==sha(P(c['captureResult']))
 assert m['nativeTrace']['artifacts']['input.rffuse']['sha256']==sha(snapshot)
 sys.path.insert(0,str(repo))
 from tools.relief_quality_validation.evaluate import PINS,FIXTURES
 from tools.make_fixture import surface_z
 from tools.qa.geometry_quality.__main__ import load
 from tools.qa.geometry_quality.metrics import topology,surface_samples
 for n,h in PINS.items():assert sha(repo/n)==h
 for n,h in FIXTURES.items():assert sha(repo/'benchmarks/fixtures/relief'/n)==h
 byhash={v:k for k,v in FIXTURES.items()};mapping={p['photoId']:byhash[p['sha256']] for p in m['photos']};assert len(mapping)==3 and set(mapping.values())==set(FIXTURES)
 poses={}
 for cam in result['cameras']:
  r=np.array(cam['rotation']).reshape(3,3);t=np.array(cam['translation']);assert np.allclose(r@r.T,np.eye(3),atol=1e-6,rtol=0) and abs(np.linalg.det(r)-1)<1e-6
  poses[mapping[cam['photoId']]]=(r,t,-r.T@t)
 assert len(poses)==len(result['cameras'])==3
 r,t,c0=poses['view-01.jpg'];c1=poses['view-02.jpg'][2];truth=read(repo/'benchmarks/fixtures/relief/calibration.json');ref={x['image']:x for x in truth['cameras']}
 assert np.array_equal(ref['view-01.jpg']['R'],np.eye(3)) and np.array_equal(ref['view-01.jpg']['t'],np.zeros(3))
 baseline=float(np.linalg.norm(np.array(ref['view-02.jpg']['center'])-ref['view-01.jpg']['center']));estimated=float(np.linalg.norm(c1-c0));assert estimated>0;scale=baseline/estimated
 results={};depths={};masks={};distances={}
 for name in ['original','candidate']:
  paths=c['meshes'][name];ply=pinned(paths['ply']);native=None
  if name=='original':
   jp=pinned(paths['json']);bits=pinned(paths['bits']);native=read(jp)
   assert native['schema']==8 and native['topologyIncomplete'] is False and native['triangleCount']==6741 and native['intersections']==6190 and sha(ply)==m['artifacts']['mesh.ply']['sha256']
   artifacts=[('mesh.ply',ply),('mesh.json',jp),('mesh.bits',bits)]
   for key,path in artifacts:assert sha(path)==stagec['outputSha256']['original/'+key]
  else:
   artifacts=[('experimental.ply',ply),('provenance.json',pinned(paths['provenance'])),('SUCCESS',pinned(paths['success'])),('counters.json',pinned(paths['counters']))]
   assert provenance['depthIdentity']==candidate['depthIdentity']==c['depthIdentity']
  v,f=load(ply);assert 0<len(v)<=200000 and 0<len(f)<=200000
  assert (len(v),len(f))==((5753,6741) if name=='original' else (2608,1514))
  lines=ply.read_text().splitlines();end=lines.index('end_header');rawv=np.array([list(map(float,s.split())) for s in lines[end+1:end+1+len(v)]]);assert np.array_equal(rawv[:,:3],v) and len(lines[end+1+len(v):])==len(f)
  rawf=np.column_stack([np.full(len(f),3),f]);aligned=scale*(v@r.T+t);samples=surface_samples(aligned,f,count=4096);inside=(abs(samples[:,0])<1.72)&(abs(samples[:,1])<1.35);res=np.abs(samples[:,2]-surface_z(samples[:,0],samples[:,1]))
  projected,depths[name],masks[name],distances[name]=projection(rawv,rawf,r,t,scale,ref['view-01.jpg']['K'],surface_z)
  results[name]=dict(rawArtifacts={key:dict(path=str(path),sha256=sha(path)) for key,path in artifacts},fullNativeMeshJson=native,nativeAuditStatus='COMPLETE_EXISTING_BASELINE_6190' if name=='original' else 'NOT_AVAILABLE_NOT_CALCULATED',representation='original_SurfaceMesh_native_output' if name=='original' else provenance['representation'],vertices=len(v),triangles=len(f),externalTopology=topology(v,f),projected=projected,surface=dict(sampleCount=4096,inDomainSamples=int(inside.sum()),outOfDomainSamples=int((~inside).sum()),verticalResidualInDomainSyntheticUnits=summary(res[inside]),allSamplesAnalyticExtrapolationResidual=summary(res)))
 assert np.array_equal(masks['original'],masks['candidate']) and np.array_equal(distances['original'],distances['candidate'])
 truthmask=masks['original'];visible={n:np.isfinite(d)&truthmask for n,d in depths.items()};common=visible['original']&visible['candidate'];union=visible['original']|visible['candidate'];count=int(common.sum())
 errors={n:np.abs(depths[n][common]-distances[n][common]) for n in depths}
 sameSupport=dict(rule='Exact truth AND finite original depth AND finite candidate depth, no residual threshold/filter',commonTruthPixels=count,unionCoveredTruthPixels=int(union.sum()),lostTruthPixels=int((visible['original']&~visible['candidate']).sum()),gainedTruthPixels=int((visible['candidate']&~visible['original']).sum()),depthResidualAtIdenticalCommonTruthPixelsSyntheticUnits={n:summary(e) for n,e in errors.items()},pairedAbsoluteResidualChangeCandidateMinusOriginal=summary(errors['candidate']-errors['original']),strictlyImprovedPixels=int((errors['candidate']<errors['original']).sum()),strictlyWorsenedPixels=int((errors['candidate']>errors['original']).sum()),unchangedPixels=int((errors['candidate']==errors['original']).sum()))
 report=dict(status='EXPERIMENTAL_PROJECTIVE_FIELD_MEASURE_ONLY_NOT_QUALITY_PASS',scope=c['scope'],lineage='trace-v2 snapshot d1b605 / StageC exact original replay; NOT profile-pair baseline 6162',configSha256=a.config_sha256,inputSha256=pins,gauge=dict(method='fixed actual original capture view01 pose plus positive baseline view01-view02; no surface fitting',scale=scale,referenceBaselineSyntheticUnits=baseline,estimatedBaseline=estimated),actualOriginalCameras=result['cameras'],photoHashMapping=m['photos'],meshes=results,commonSupport=sameSupport,experimentalEvidence=dict(fullProvenance=provenance,newAlgorithmCounters=counters,independentWitness=witness,receiptLimitations=candidate['limitations'],receiptElapsedSeconds=candidate['elapsedSeconds'],timeScope='Receipt total includes compile/link/tests/two replays/two verifiers; NOT candidate runtime',storageScope='workspaceBytes plus stackAllowanceBytes exclude input and Python verifier; NOT process RSS',candidateNativeAudit=None,externalIntersectionBudget=5000),limitations=['Posthoc synthetic comparison only; NEW experimental canonical projective camera-z field, not original SurfaceMesh/raw bits/native JSON; not Android/full-controller/cache/shipping qualification','Projected image coverage is not 3D completeness; sample populations differ except explicit common-support report','Common support is intersection selected by BOTH outputs, not independent held-out evaluation; keep lost/gained pixels visible','No changed truth, filtering, welding, smoothing or near-plane clipping substitution','Existing baseline native6190 separate; candidate native audit unavailable. External default5000face crossing budget unchanged: original unknown, candidate computed. Neither executed status nor topology label means quality PASS','Surface vertical residual is not Euclidean distance; synthetic truth/render discretization not independently quantified'])
 for p,h in pins.items():assert sha(P(p))==h
 a.output.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n');print(json.dumps(dict(status=report['status'],reportSha256=sha(a.output))))
if __name__=='__main__':main()
