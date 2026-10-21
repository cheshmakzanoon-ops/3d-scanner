"""Helper fail-closed orchestration; fake compiler/runner, not extractor/JNI coverage."""
import importlib.util
import json
from pathlib import Path
import zipfile
import pytest

ROOT=Path(__file__).resolve().parents[1]
HELPER=ROOT/'tools/feature_extractor_validation/validate.py'

@pytest.fixture
def setup(tmp_path,monkeypatch):
 spec=importlib.util.spec_from_file_location('extractor_helper',HELPER)
 m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 here=tmp_path/'harness';here.mkdir();(here/'validate.py').write_bytes(HELPER.read_bytes());m.__file__=str(here/'validate.py')
 for name in ['ProductionExtractorHostTest.kt','HostRunner.kt']:(here/name).write_text('test source')
 repo=tmp_path/'repo';repo.mkdir()
 for rel in m.SOURCES:
  p=repo/'app/src/main/java/com/realityforge/scanner/engine'/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('production source')
 java=tmp_path/'jdk/bin/java';java.parent.mkdir(parents=True);java.write_text('java');(java.parent.parent/'release').write_text('jdk release')
 cap=tmp_path/'cap';(cap/'build/lib').mkdir(parents=True);(cap/'smoke').mkdir()
 native=cap/'build/lib/libopencv_java4120.so';native.write_bytes(b'native')
 aar=tmp_path/'opencv.aar'
 with zipfile.ZipFile(aar,'w') as z:z.writestr('classes.jar',b'aar classes')
 ai=cap/'smoke/aar-input.json';ai.write_text(json.dumps({'aar_sha256':m.sha(aar),'classes_jar_sha256':__import__('hashlib').sha256(b'aar classes').hexdigest()}))
 manifest={'schema':1,'classification':'HOST_OPENCV_JAVA_JNI_CAPABILITY_ONLY','official_source':{'commit':'49486f61fb25722cbcf586b7f4320921d46fb38e'},'build':{'exit_code':0},'linkage':{'native_version':'4.12.0'},'sha256':{str(p.relative_to(cap)):m.sha(p) for p in [native,ai]}}
 (cap/'validation.json').write_text(json.dumps(manifest))
 cp=[]
 for name in ['compiler','runtime']:
  jar=tmp_path/(name+'.jar');jar.write_text(name)
  f=tmp_path/(name+'-classpath');f.write_text(str(jar));cp.append(f)
 link=tmp_path/'sift-linkage.json';linked=tmp_path/'signatures.txt';linked.write_text('verified signatures')
 link.write_text(json.dumps({'schema':1,'classification':'HOST_SIFT_ADDITIONAL_LINKAGE_ONLY',
  'classes':{x:{'equal':True,'missingSymbols':[]} for x in ['org.opencv.features2d.SIFT','org.opencv.features2d.Feature2D','org.opencv.core.MatOfKeyPoint']},
  'inputs':{str(native):m.sha(native),str(cap/'validation.json'):m.sha(cap/'validation.json')},'files':{str(linked):m.sha(linked)}}))
 work=tmp_path/'work'
 args=['--repo',str(repo),'--work',str(work),'--java',str(java),'--compiler-classpath',str(cp[0]),'--runtime-classpath',str(cp[1]),'--aar',str(aar),'--capability-dir',str(cap),'--linkage-receipt',str(link)]
 calls=[]
 def compiler(cmd,**kw):
  calls.append(cmd)
  if '-d' in cmd:
   output=Path(cmd[cmd.index('-d')+1]);output.mkdir();(output/'HostRunnerKt.class').write_text('fresh class')
 monkeypatch.setattr(m.subprocess,'run',compiler)
 return m,args,work,repo,cap,cp,calls,compiler

def test_parser_requires_all_explicit_inputs():
 spec=importlib.util.spec_from_file_location('extractor_parser',HELPER);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 with pytest.raises(SystemExit):m.parser().parse_args(['compile'])

def test_success_receipts_and_fresh_outputs(setup):
 m,args,w,*_=setup;m.main(['compile',*args]);first=json.loads((w/'compile-receipt.json').read_text())['output']
 m.main(['run',*args]);assert json.loads((w/'run-receipt.json').read_text())['status']=='PASS'
 m.main(['compile',*args]);assert not (w/'run-receipt.json').exists();assert json.loads((w/'compile-receipt.json').read_text())['output']!=first

@pytest.mark.parametrize('kind',['missing','native','source','runtime','classpath','output','aar_extracted','linkage','linkage_file','linkage_classes'])
def test_mutated_inputs_or_outputs_reject_stale_run(setup,kind):
 m,args,w,repo,cap,cp,calls,_=setup;m.main(['compile',*args]);m.main(['run',*args]);before=len(calls)
 if kind=='missing':(cap/'build/lib/libopencv_java4120.so').unlink()
 elif kind=='native':(cap/'build/lib/libopencv_java4120.so').write_text('changed')
 elif kind=='source':(repo/'app/src/main/java/com/realityforge/scanner/engine/features/GrayFeatureExtraction.kt').write_text('changed')
 elif kind=='runtime':Path(cp[1].read_text()).write_text('changed')
 elif kind=='classpath':cp[1].write_text(cp[1].read_text()+'\n')
 elif kind=='output':(Path(json.loads((w/'compile-receipt.json').read_text())['output'])/'HostRunnerKt.class').write_text('changed')
 elif kind=='aar_extracted':(w/'aar-classes.jar').write_text('changed')
 elif kind=='linkage':Path(args[-1]).write_text('{}')
 elif kind=='linkage_file':(w.parent/'signatures.txt').write_text('changed')
 else:
  p=Path(args[-1]);data=json.loads(p.read_text());data['classes'].pop('org.opencv.features2d.SIFT');p.write_text(json.dumps(data))
 with pytest.raises((ValueError,FileNotFoundError)):m.main(['run',*args])
 assert not (w/'run-receipt.json').exists();assert len(calls)==before

@pytest.mark.parametrize('mode',['compile','run'])
def test_postcheck_mutation_never_leaves_success_receipt(setup,monkeypatch,mode):
 m,args,w,repo,cap,cp,calls,compiler=setup
 m.main(['compile',*args])
 def mutate(cmd,**kw):
  compiler(cmd,**kw)
  (repo/'app/src/main/java/com/realityforge/scanner/engine/features/GrayFeatureExtraction.kt').write_text('changed while executing')
 monkeypatch.setattr(m.subprocess,'run',mutate)
 with pytest.raises(ValueError):m.main([mode,*args])
 assert not (w/(mode+'-receipt.json')).exists()

def test_failed_compile_invalidates_prior_success(setup,monkeypatch):
 m,args,w,repo,cap,cp,calls,compiler=setup;m.main(['compile',*args]);m.main(['run',*args])
 def fail(*a,**kw):raise m.subprocess.CalledProcessError(1,a[0])
 monkeypatch.setattr(m.subprocess,'run',fail)
 with pytest.raises(m.subprocess.CalledProcessError):m.main(['compile',*args])
 assert not (w/'compile-receipt.json').exists();assert not (w/'run-receipt.json').exists()
