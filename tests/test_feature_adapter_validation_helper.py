"""Mocked helper orchestration only, not Android/OpenCV algorithm evidence."""
import importlib.util,json,os,zipfile
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1]
HELPER=ROOT/'tools/feature_adapter_validation/validate.py'
@pytest.fixture
def setup(tmp_path,monkeypatch):
 spec=importlib.util.spec_from_file_location('adapter_helper',HELPER);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 here=tmp_path/'harness';here.mkdir();(here/'validate.py').write_bytes(HELPER.read_bytes());m.__file__=str(here/'validate.py')
 for name in m.HELPERS:(here/name).write_text('test source')
 repo=tmp_path/'repo';repo.mkdir()
 for rel in m.SOURCES:
  f=repo/'app/src/main/java/com/realityforge/scanner'/rel;f.parent.mkdir(parents=True,exist_ok=True);f.write_text('production source')
 fixture=repo/'benchmarks/fixtures/relief/view-01.jpg';fixture.parent.mkdir(parents=True);fixture.write_bytes(b'fixture');m.FIXTURE_SHA=m.sha(fixture)
 java=tmp_path/'jdk/bin/java';java.parent.mkdir(parents=True);java.write_text('java');(java.parent.parent/'release').write_text('release')
 compiler=tmp_path/'compiler.jar';compiler.write_text('compiler');compiler_cp=tmp_path/'compiler-cp';compiler_cp.write_text(str(compiler))
 runtime=tmp_path/'runtime.jar'
 with zipfile.ZipFile(runtime,'w') as z:z.writestr('dependency/Present.class',b'bytecode')
 runtime_cp=tmp_path/'runtime-cp';runtime_cp.write_text(str(runtime))
 inventory=tmp_path/'inventory.json'
 def inventory_write():inventory.write_text(json.dumps({'schemaVersion':1,'task':':app:hostIntegrationTest','runtimeJars':[{'path':str(runtime),'sha256':m.sha(runtime)}],'omitted':[{'path':'app-output','reason':'fresh sources'}]}))
 inventory_write()
 sdk=tmp_path/'sdk';sdk.mkdir();sdk_jar=sdk/m.SDK_NAME;sdk_jar.write_bytes(b'android');m.SDK_SHA=m.sha(sdk_jar)
 cap=tmp_path/'cap';(cap/'build/lib').mkdir(parents=True);(cap/'smoke').mkdir();native=cap/'build/lib/libopencv_java4120.so';native.write_bytes(b'native')
 aar=tmp_path/'opencv.aar'
 with zipfile.ZipFile(aar,'w') as z:z.writestr('classes.jar',b'classes')
 ai=cap/'smoke/aar-input.json';ai.write_text(json.dumps({'aar_sha256':m.sha(aar),'classes_jar_sha256':__import__('hashlib').sha256(b'classes').hexdigest()}))
 receipt={'schema':1,'classification':'HOST_OPENCV_JAVA_JNI_CAPABILITY_ONLY','official_source':{'commit':'49486f61fb25722cbcf586b7f4320921d46fb38e'},'build':{'exit_code':0},'linkage':{'native_version':'4.12.0'},'sha256':{str(x.relative_to(cap)):m.sha(x) for x in [native,ai]}}
 (cap/'validation.json').write_text(json.dumps(receipt))
 link=tmp_path/'link.json';signatures=tmp_path/'signatures';signatures.write_text('API')
 link.write_text(json.dumps({'schema':1,'classification':'HOST_SIFT_ADDITIONAL_LINKAGE_ONLY','classes':{x:{'equal':True,'missingSymbols':[]} for x in ['org.opencv.features2d.SIFT','org.opencv.features2d.Feature2D','org.opencv.core.MatOfKeyPoint']},'inputs':{str(native):m.sha(native),str(cap/'validation.json'):m.sha(cap/'validation.json')},'files':{str(signatures):m.sha(signatures)}}))
 work=tmp_path/'work'
 args=['--repo',str(repo),'--work',str(work),'--java',str(java),'--compiler-cp',str(compiler_cp),'--runtime-cp',str(runtime_cp),'--android-sdk-jar',str(sdk_jar),'--offline-sdk-dir',str(sdk),'--capability-dir',str(cap),'--aar',str(aar),'--sift-linkage',str(link),'--dependency-inventory',str(inventory),'--dependency-inventory-sha256',m.sha(inventory)]
 calls=[]
 def execute(cmd,**kw):
  calls.append((cmd,kw))
  if '-d' in cmd:
   out=Path(cmd[cmd.index('-d')+1]);out.mkdir();(out/'FeatureAdapterProbeTest.class').write_bytes(b'class')
  else:kw['stdout'].write('OK (1 test)\n')
  return m.subprocess.CompletedProcess(cmd,0)
 monkeypatch.setattr(m.subprocess,'run',execute)
 return m,args,work,repo,cap,sdk,runtime,inventory,inventory_write,calls,execute

def test_parser_requires_explicit_inventory_and_dependencies():
 spec=importlib.util.spec_from_file_location('adapter_parser',HELPER);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 with pytest.raises(SystemExit):m.parser().parse_args([])

def test_success_limits_and_no_reuse(setup):
 m,args,w,*rest=setup;m.main(args)
 assert json.loads((w/'run-receipt.json').read_text())['status']=='PASS'
 calls=rest[-2];assert calls[0][1]['timeout']==120 and calls[1][1]['timeout']==180
 assert '-Xmx384m' in calls[0][0] and '-Xmx1g' in calls[1][0]
 with pytest.raises(FileExistsError):m.main(args)

@pytest.mark.parametrize('kind',['missing_native','native','sdk','extra_sdk','inventory','fixture','source_missing','aar','linkage','signature','classpath_order'])
def test_preflight_rejects_changed_inputs(setup,kind):
 m,args,w,repo,cap,sdk,runtime,inventory,save,calls,execute=setup
 if kind=='missing_native':(cap/'build/lib/libopencv_java4120.so').unlink()
 elif kind=='native':(cap/'build/lib/libopencv_java4120.so').write_text('bad')
 elif kind=='sdk':(sdk/m.SDK_NAME).write_text('bad')
 elif kind=='extra_sdk':(sdk/'extra.jar').write_text('bad')
 elif kind=='inventory':inventory.write_text('{}')
 elif kind=='fixture':(repo/'benchmarks/fixtures/relief/view-01.jpg').write_text('bad')
 elif kind=='source_missing':(repo/'app/src/main/java/com/realityforge/scanner'/m.SOURCES[0]).unlink()
 elif kind=='aar':Path(args[args.index('--aar')+1]).write_text('bad')
 elif kind=='linkage':Path(args[args.index('--sift-linkage')+1]).write_text('{}')
 elif kind=='signature':(w.parent/'signatures').write_text('bad')
 else:Path(args[args.index('--runtime-cp')+1]).write_text(str(runtime)+os.pathsep+str(runtime))
 with pytest.raises((ValueError,FileNotFoundError,KeyError)):m.main(args)
 assert not calls and not (w/'run-receipt.json').exists()

@pytest.mark.parametrize('entry',['org/opencv/core/Mat.class','com/realityforge/scanner/engine/ImageLoader.class','robolectric-deps.properties'])
def test_forbidden_provider_or_resolver_even_with_matching_inventory(setup,entry):
 m,args,w,repo,cap,sdk,runtime,inventory,save,calls,execute=setup
 with zipfile.ZipFile(runtime,'a') as z:z.writestr(entry,b'forbidden')
 save();args[-1]=m.sha(inventory)
 with pytest.raises(ValueError):m.main(args)
 assert not calls and not (w/'run-receipt.json').exists()

@pytest.mark.parametrize('phase,kind',[('compile','source'),('run','source'),('run','class'),('run','sdk_extra'),('run','fixture')])
def test_postchecks_fail_without_success_receipt(setup,monkeypatch,phase,kind):
 m,args,w,repo,cap,sdk,runtime,inventory,save,calls,execute=setup
 def mutate(cmd,**kw):
  result=execute(cmd,**kw)
  if ('-d' in cmd)==(phase=='compile'):
   if kind=='class':(w/'classes/FeatureAdapterProbeTest.class').write_text('changed')
   elif kind=='sdk_extra':(sdk/'extra.jar').write_text('bad')
   elif kind=='fixture':(repo/'benchmarks/fixtures/relief/view-01.jpg').write_text('changed')
   else:(repo/'app/src/main/java/com/realityforge/scanner'/m.SOURCES[0]).write_text('changed')
  return result
 monkeypatch.setattr(m.subprocess,'run',mutate)
 with pytest.raises(ValueError):m.main(args)
 assert not (w/'run-receipt.json').exists()

@pytest.mark.parametrize('mode',['compiler_failure','runtime_failure','wrong_count'])
def test_real_process_failure_or_wrong_count_is_not_success(setup,monkeypatch,mode):
 m,args,w,repo,cap,sdk,runtime,inventory,save,calls,execute=setup
 def fail(cmd,**kw):
  if '-d' in cmd:
   if mode=='compiler_failure':raise m.subprocess.CalledProcessError(1,cmd)
   return execute(cmd,**kw)
  kw['stdout'].write('OK (0 tests)\n' if mode=='wrong_count' else 'FAILED\n')
  return m.subprocess.CompletedProcess(cmd,0 if mode=='wrong_count' else 1)
 monkeypatch.setattr(m.subprocess,'run',fail)
 with pytest.raises((ValueError,m.subprocess.CalledProcessError)):m.main(args)
 assert not (w/'run-receipt.json').exists()
