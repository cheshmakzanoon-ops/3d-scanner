#!/usr/bin/env python3
"""Post-hoc synthetic measurements only; never feeds truth into reconstruction."""
import argparse,hashlib,json,pathlib,sys
import numpy as np
P=pathlib.Path
PINS={'tools/make_fixture.py':'c3660732f2fbccf68efd6d5452fdb1701427ce060c076f6d5558c500497783f9','benchmarks/fixtures/relief/calibration.json':'284c73d64c92bbed50f762a5bc74b6f39d0a3955a0bd219217b6d23bbd7fd593','tools/qa/geometry_quality/metrics.py':'bd4ab2f66263b7246aa8827902b0c40ccf20db2cabdcdcdd004e3a9d3125e275','tests/test_pipeline.py':'26d4a548f9bc4ba79be4dc8942738e83f7224face99d2955b5d06b220ad205fd'}
PINS.update({'tools/qa/geometry_quality/__main__.py': '8bc2987aba5e5d554d1db58b61f412c6cdf2604e819e5b45bf7662c5a8b9773d', 'tools/qa/geometry_quality/glb.py': 'f0272ecf74149702e79b002878bce4f57169a37744ed8213acd125541208824a'})
FIXTURES={'view-01.jpg':'66fa84499c73e4a6cbb4af03880e6494433f7d4d32f288476c575a71aa82ab7c','view-02.jpg':'c275db33a1d10d84fe5a9bf78c55a7ea31de002ac305e00fd521e54b351b391b','view-03.jpg':'a0d386746c2a8f47840058613bc89aa3c4a8095995288fa936d368ad67fe6c0f'}
def require(ok,message):
 if not ok:raise ValueError(message)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def bounded(p,maximum=32*1024*1024):
 require(p.is_file() and not p.is_symlink() and 0<p.stat().st_size<=maximum,'Missing/oversized/symlink evidence: '+p.name)
 return p.read_bytes()
def summary(a):
 return None if not len(a) else {'p50':float(np.median(a)),'p90':float(np.quantile(a,.9)),'p95':float(np.quantile(a,.95)),'max':float(np.max(a))}
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--repo',type=P,required=True);ap.add_argument('--capture',type=P,required=True);ap.add_argument('--capture-manifest-sha256',required=True);ap.add_argument('--output',type=P,required=True);args=ap.parse_args()
 repo=args.repo.resolve();capture=args.capture.resolve();require(not args.output.exists() and not args.output.is_symlink(),'Output must be new and not symlink')
 pins={**PINS,**{'benchmarks/fixtures/relief/'+k:v for k,v in FIXTURES.items()}}
 for name,h in pins.items():require(sha(repo/name)==h,'Truth/provenance input changed: '+name)
 manifest_path=capture/'capture.json';raw=bounded(manifest_path,64*1024)
 require(hashlib.sha256(raw).hexdigest()==args.capture_manifest_sha256,'Capture manifest mismatch')
 manifest=json.loads(raw);require(manifest['schema']==1 and manifest['synthetic'] is True,'Not synthetic capture')
 names={'result.json','sparse.ply','dense.ply','mesh.ply'}
 require(set(manifest['artifacts'])==names,'Wrong captured artifact set')
 require({p.name for p in capture.iterdir()}==names|{'capture.json'},'Unexpected capture files')
 for name,entry in manifest['artifacts'].items():
  data=bounded(capture/name);require(len(data)==entry['bytes'] and hashlib.sha256(data).hexdigest()==entry['sha256'],'Captured artifact changed')
 sys.path.insert(0,str(repo))
 from tools.make_fixture import surface_z
 from tools.qa.geometry_quality.__main__ import load
 from tools.qa.geometry_quality.metrics import surface_samples
 truth=json.loads((repo/'benchmarks/fixtures/relief/calibration.json').read_text())
 require(truth['synthetic'] is True,'Physical claim forbidden')
 by_hash={v:k for k,v in FIXTURES.items()};photo_map={p['photoId']:by_hash[p['sha256']] for p in manifest['photos']}
 require(len(manifest['photos'])==len(photo_map)==3 and set(photo_map.values())==set(FIXTURES),'Photo identity mapping mismatch')
 result=json.loads((capture/'result.json').read_text());require(result['artifactKey']==manifest['artifactKey'],'Result key mismatch')
 cameras={photo_map[c['photoId']]:c for c in result['cameras']};require(len(cameras)==len(result['cameras'])==3,'Wrong cameras')
 reference={c['image']:c for c in truth['cameras']}
 def pose(c):
  r=np.asarray(c['rotation'],float).reshape(3,3);t=np.asarray(c['translation'],float)
  require(t.shape==(3,) and np.isfinite(r).all() and np.isfinite(t).all(),'Invalid pose')
  require(np.allclose(r@r.T,np.eye(3),atol=1e-6,rtol=0) and abs(np.linalg.det(r)-1)<1e-6,'Improper pose rotation')
  return r,t,-r.T@t
 poses={n:pose(c) for n,c in cameras.items()};r0,t0,c0=poses['view-01.jpg'];c1=poses['view-02.jpg'][2]
 require(np.array_equal(reference['view-01.jpg']['R'],np.eye(3)) and np.array_equal(reference['view-01.jpg']['t'],np.zeros(3)),'Expected fixture reference frame changed')
 baseline=float(np.linalg.norm(np.array(reference['view-02.jpg']['center'])-reference['view-01.jpg']['center']))
 estimated=float(np.linalg.norm(c1-c0));require(np.isfinite(estimated) and estimated>0,'Unobservable baseline')
 scale=baseline/estimated
 def transform(x):return scale*(np.asarray(x)@r0.T+t0)
 camera_metrics={}
 for name,(r,t,c) in poses.items():
  rel=r@r0.T;trans=scale*(t-rel@t0);rr=np.asarray(reference[name]['R']);tt=np.asarray(reference[name]['t'])
  angle=float(np.degrees(np.arccos(np.clip((np.trace(rel@rr.T)-1)/2,-1,1))))
  dot=None
  if name!='view-01.jpg':
   denominator=float(np.linalg.norm(trans)*np.linalg.norm(tt))
   require(np.isfinite(denominator) and denominator>0,'Undefined translation direction: '+name)
   dot=float(np.dot(trans,tt)/denominator)
  camera_metrics[name]={'rotationErrorDegrees':angle,'translationDirectionDot':dot,'centerErrorSyntheticUnits':float(np.linalg.norm(transform(c)-reference[name]['center'])),'translationErrorSyntheticUnits':float(np.linalg.norm(trans-tt)),'referenceCameraUsedToFixGauge':name=='view-01.jpg'}
 measurements={}
 for name in ['sparse.ply','dense.ply','mesh.ply']:
  vertices,faces=load(capture/name);require(len(vertices)<=200000 and len(faces)<=200000,'Geometry evaluation budget exceeded')
  aligned=transform(vertices)
  points=surface_samples(aligned,faces,count=4096) if len(faces) else aligned
  finite=np.isfinite(points).all(axis=1);require(finite.all(),'Nonfinite geometry')
  inside=(abs(points[:,0])<1.72)&(abs(points[:,1])<1.35)
  residual=np.abs(points[:,2]-surface_z(points[:,0],points[:,1]))
  measurements[name]={'vertices':len(vertices),'triangles':len(faces),'sampleCount':len(points),'sampleRule':'fixed4096 area-weighted seed84001' if len(faces) else 'all output vertices','invalidSampleCount':int((~finite).sum()),'inDomainSamples':int(inside.sum()),'outOfDomainSamples':int((~inside).sum()),'inDomainFraction':float(inside.mean()),'verticalResidualInDomainSyntheticUnits':summary(residual[inside]),'allSamplesAnalyticExtrapolationResidual':summary(residual),'notCompleteness':True}
 report={'schema':1,'status':'MEASURE_ONLY_NOT_QUALITY_PASS','synthetic':True,'metricScaleClaim':False,'captureManifestSha256':sha(manifest_path),'artifacts':manifest['artifacts'],'truthInputSha256':pins,'evaluatorSha256':sha(P(__file__)),'gauge':{'method':'fixed view01 estimated pose plus positive view01-view02 baseline scale only; no fitted surface or all-camera alignment','scale':scale,'referenceBaselineSyntheticUnits':baseline,'estimatedBaseline':estimated},'cameras':camera_metrics,'surfaceMeasurements':measurements,'existingTwoViewRegressionReference':{'source':'tests/test_pipeline.py','rotationDegreesMax':1.0,'translationDirectionDotMin':.995,'sparseVerticalMedianMax':.08,'sparseVerticalP90Max':.16,'appliedAsGate':False,'reason':'Full-core uses selected anchor/global typed BA/different image front end; dense/mesh distributions have no matching prior thresholds. Descriptive comparison only.'},'limitations':['Vertical graph residual is not Euclidean point-to-surface distance','Domain fraction measures output support, not visible-reference completeness','Generator analytic surface is intended synthetic truth;45-step ray iteration/JPEG/interpolation discretization not independently quantified here','No held-out photo reprojection or physical accuracy','Camera view01 gauge and view01-view02 baseline chosen for alignment cannot independently validate absolute pose/scale']}
 for name,h in pins.items():require(sha(repo/name)==h,'Truth/provenance changed during evaluation')
 for name,entry in manifest['artifacts'].items():require(sha(capture/name)==entry['sha256'],'Capture changed during evaluation')
 require(sha(manifest_path)==args.capture_manifest_sha256,'Manifest changed during evaluation')
 encoded=json.dumps(report,indent=2,allow_nan=False)+'\n'
 with args.output.open('x') as stream:stream.write(encoded)
 print(json.dumps({'status':report['status'],'reportSha256':sha(args.output)}))
if __name__=='__main__':main()
