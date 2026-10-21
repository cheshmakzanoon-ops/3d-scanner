"""Evaluator-only mathematical/wiring fixtures; never invoke Gradle or reconstruction."""
import hashlib,importlib.util,json,sys
from pathlib import Path
import numpy as np
import pytest
ROOT=Path(__file__).resolve().parents[1]
TOOL=ROOT/'tools/relief_quality_validation'
# In external review trees the production source is explicitly supplied by test environment.
REPO=Path(__import__('os').environ.get('RF_QUALITY_SOURCE_REPO',str(ROOT)))
@pytest.fixture
def evaluator():
 spec=importlib.util.spec_from_file_location('relief_evaluator',TOOL/'evaluate.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
@pytest.fixture
def capture(tmp_path,evaluator):
 def build(scale=1.0,angle=0.0,offset=(0.,0.,0.)):
  sys.path.insert(0,str(REPO))
  from tools.make_fixture import surface_z
  truth=json.loads((REPO/'benchmarks/fixtures/relief/calibration.json').read_text())
  q=np.array([[np.cos(angle),-np.sin(angle),0],[np.sin(angle),np.cos(angle),0],[0,0,1.]])
  b=np.asarray(offset);xy=np.array([[-.2,-.1],[.3,-.1],[.1,.25]])
  world=np.column_stack([xy,surface_z(xy[:,0],xy[:,1])]);estimated=(world-b)@q/scale
  cameras=[];photos=[]
  for i,c in enumerate(truth['cameras']):
   r=np.array(c['R']);t=np.array(c['t']);name=c['image'];pid='synthetic-helper-'+str(i)
   cameras.append({'photoId':pid,'rotation':(r@q).reshape(-1).tolist(),'translation':((r@b+t)/scale).tolist()})
   photos.append({'photoId':pid,'sha256':evaluator.FIXTURES[name]})
  folder=tmp_path/'capture';folder.mkdir()
  (folder/'result.json').write_text(json.dumps({'artifactKey':'helper-only','cameras':cameras}))
  for name in ['sparse.ply','dense.ply','mesh.ply']:
   mesh=name=='mesh.ply'
   header='ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\n'
   if mesh:header+='element face 1\nproperty list uchar int vertex_indices\n'
   (folder/name).write_text(header+'end_header\n'+'\n'.join(' '.join(map(str,p)) for p in estimated)+'\n'+('3 0 1 2\n' if mesh else ''))
  artifacts={p.name:{'sha256':evaluator.sha(p),'bytes':p.stat().st_size} for p in folder.iterdir()}
  manifest=folder/'capture.json';manifest.write_text(json.dumps({'schema':1,'synthetic':True,'artifacts':artifacts,'photos':photos,'artifactKey':'helper-only'}))
  return folder,manifest,scale
 return build

def invoke(evaluator,folder,manifest,output,monkeypatch):
 monkeypatch.setattr(sys,'argv',['evaluate','--repo',str(REPO),'--capture',str(folder),'--capture-manifest-sha256',evaluator.sha(manifest),'--output',str(output)])
 evaluator.main()

@pytest.mark.parametrize('scale,angle,offset',[(1.,0.,(0.,0.,0.)),(2.5,.37,(1.,-2.,.4))])
def test_exact_evaluator_identity_and_known_rigid_scale(evaluator,capture,tmp_path,monkeypatch,scale,angle,offset):
 folder,manifest,_=capture(scale,angle,offset);output=tmp_path/'measured.json';invoke(evaluator,folder,manifest,output,monkeypatch)
 r=json.loads(output.read_text());assert r['status']=='MEASURE_ONLY_NOT_QUALITY_PASS'
 assert r['gauge']['scale']==pytest.approx(scale,abs=1e-12)
 for c in r['cameras'].values():
  assert c['rotationErrorDegrees']<1e-5
  assert c['centerErrorSyntheticUnits']<1e-12 and c['translationErrorSyntheticUnits']<1e-12
 for name in ['sparse.ply','dense.ply']:
  m=r['surfaceMeasurements'][name];assert m['sampleCount']==3 and m['inDomainSamples']==3
  assert m['verticalResidualInDomainSyntheticUnits']['max']<1e-12
 assert r['surfaceMeasurements']['mesh.ply']['sampleCount']==4096
 assert r['existingTwoViewRegressionReference']['appliedAsGate'] is False

@pytest.mark.parametrize('failure',['tampered','extra','dangling_output'])
def test_evaluator_fail_closed(evaluator,capture,tmp_path,monkeypatch,failure):
 folder,manifest,_=capture();output=tmp_path/'measured.json'
 if failure=='tampered':(folder/'sparse.ply').write_text('changed')
 elif failure=='extra':(folder/'extra').write_text('unapproved')
 else:output.symlink_to(tmp_path/'nonexistent')
 with pytest.raises(ValueError):invoke(evaluator,folder,manifest,output,monkeypatch)
 assert not output.exists()

def test_exercised_source_identity_and_capture_order():
 expected={'SparsePipelineQualityCaptureHostTest.kt':'056a6894293a1e8a6e8e3d310c203e900bcc8cd777d4e5161d3335aa84e5b36d','evaluate.py':'04e99a1946e2716ad55a9c51eea8ab2fcf9de33c93ff01fedcb11fddf2f85a80','validate.py':'2ba5c7a36c430934d2358aaf3077693a4a37d5da9016216f6649445fe57f9975','probe.init.gradle':'4f724496bb1ef978ce4fd8a72aaabf29781f18dcc39bf08c0b4c111adfbe38d4'}
 for name,h in expected.items():assert hashlib.sha256((TOOL/name).read_bytes()).hexdigest()==h
 s=(TOOL/'SparsePipelineQualityCaptureHostTest.kt').read_text()
 assert s.index('fixture.reopen()')<s.index('val capture=')
 assert s.index('assertArtifacts()')<s.index('val capture=')
 assert 'source.copyTo(target,overwrite=false)' in s
 assert 'MEASURE_ONLY_NOT_QUALITY_PASS' in (TOOL/'evaluate.py').read_text()
