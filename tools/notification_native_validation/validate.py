#!/usr/bin/env python3
"""Opt-in HOST integration; one bounded test and separately bounded compilation restoration."""
import argparse, hashlib, io, json, os, pathlib, re, shlex, signal, subprocess, sys, time, zipfile
import xml.etree.ElementTree as ET
P=pathlib.Path
SOURCE=P(__file__).resolve().parent; INIT=SOURCE/'probe.init.gradle'
SOURCE_NAMES=['SandboxNativeLoaderRunner.kt','NotificationNativeTokenHostTest.kt']
NATIVE_PINS={'opencv': 'e573a54e917ca142706d37537058009000c0df9157bc46cb66fbfec5a522d2d1', 'shim': '427f2c8c0e21a5647064edcdbe9307265fe493c256c211e4266f979f9931fcce', 'engine': '4384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b'}
def validate_native_pins(native):
 require({k:v["sha256"] for k,v in native.items()}==NATIVE_PINS,"Unreviewed native capability; locations are configurable, identities are not")
def require(ok,message):
 if not ok:raise ValueError(message)
def read_config(path,expected_hash):
 require(sha(path)==expected_hash,'Configuration hash mismatch')
 c=json.loads(P(path).read_text());require(c.get('schema')==1,'Unsupported configuration')
 required=['repo','environmentScript','java','gradle','opencvAar','opencvClasses','dropOpenCvRuntime','appClasses','native','provenance']
 require(all(k in c for k in required),'Incomplete explicit configuration')
 require(P(c['repo']).is_absolute(),'Repository path must be absolute')
 for key in ['environmentScript','java','gradle','opencvAar','opencvClasses','dropOpenCvRuntime','appClasses']:
  require(set(c[key])=={'path','sha256'},'Invalid artifact entry: '+key)
  require(P(c[key]['path']).is_absolute(),'Artifact path must be absolute')
  require(sha(c[key]['path'])==c[key]['sha256'],'Artifact hash mismatch: '+key)
 require(set(c['native'])=={'opencv','shim','engine'},'Wrong native providers')
 for value in [*c['native'].values(),*c['provenance']]:
  require(P(value['path']).is_absolute(),'Native/provenance path must be absolute')
  require(sha(value['path'])==value['sha256'],'Native/provenance hash mismatch')
 require(bool(c['provenance']),'Recorded native provenance required; hashes alone do not prove source-build identity')
 return c
def sha(p):
 d=hashlib.sha256()
 with P(p).open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):d.update(b)
 return d.hexdigest()
def wait_bounded(proc,timeout=600):
 try:return 'EXIT_'+str(proc.wait(timeout=timeout))
 except subprocess.TimeoutExpired:
  os.killpg(proc.pid,signal.SIGTERM)
  try:proc.wait(timeout=15)
  except subprocess.TimeoutExpired:
   os.killpg(proc.pid,signal.SIGKILL);proc.wait(timeout=15)
  return 'OUTER_TIMEOUT_'+str(timeout)+'S'
def junit_pass(suites):
 return bool(suites) and sum(int(x.get('tests',0)) for x in suites)==1 and all(int(x.get(k,0))==0 for x in suites for k in ['failures','errors','skipped'])
def write_receipt(path,value):
 temporary=path.with_name(path.name+'.partial');temporary.write_text(json.dumps(value,indent=2));temporary.replace(path)
def cmd(*args):return subprocess.check_output(args,cwd=REPO,text=True).strip()
def main(argv=None):
 global REPO
 ap=argparse.ArgumentParser();ap.add_argument('--expected-commit',required=True);ap.add_argument('--output',type=P,required=True);ap.add_argument('--config',type=P,required=True);ap.add_argument('--config-sha256',required=True);args=ap.parse_args(argv)
 require(__debug__,'Do not invoke integrity harness with Python optimization')
 settings=read_config(args.config,args.config_sha256);validate_native_pins(settings['native']);REPO=P(settings['repo']).resolve(strict=True)
 AAR=P(settings['opencvAar']['path']);CLASSES=P(settings['opencvClasses']['path']);DROP=P(settings['dropOpenCvRuntime']['path']);APP=P(settings['appClasses']['path'])
 JAVA=P(settings['java']['path']);ENV=P(settings['environmentScript']['path']);GRADLE=P(settings['gradle']['path'])
 NATIVE={k:(P(v['path']),v['sha256']) for k,v in settings['native'].items()}
 require(args.output.resolve()!=REPO and REPO not in args.output.resolve().parents,'Use fresh external output')
 # Fail before setup on inherited loader/JVM injection. Known cloud proxy settings only.
 for k,v in os.environ.items():
  if k.startswith('LD_') and v:raise RuntimeError('Inherited loader override: '+k)
  if k in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS'] and v:
   for t in shlex.split(v):
    if not re.fullmatch(r'-D(?:https?|socks)\.proxy(?:Host|Port)=[A-Za-z0-9.:-]+|-Djavax\.net\.ssl\.trustStore=/etc/ssl/certs/java/cacerts',t):raise RuntimeError('Unreviewed JVM option in '+k)
 require(cmd('bash','-c','source "$1"; readlink -f "$(command -v java)"','env-check',str(ENV))==str(JAVA.resolve()),'Environment selects a different JDK')
 assert cmd('git','rev-parse','HEAD')==args.expected_commit
 assert not cmd('git','status','--porcelain'), 'Clean published source required'
 args.output.mkdir(parents=True,exist_ok=False); alias=args.output/'aliases';alias.mkdir()
 for k,(p,h) in NATIVE.items():assert sha(p)==h
 (alias/'libopencv_java4.so').symlink_to(NATIVE['opencv'][0]);(alias/'librealityforge.so').symlink_to(NATIVE['shim'][0])
 expected_sources={n:sha(SOURCE/n) for n in SOURCE_NAMES}
 require(sha(AAR)=='f71846a313388d9da667a59be1e921669fcf792d1ba264b3898253ca789bc3f0','Unexpected OpenCV AAR')
 require(sha(CLASSES)=='60b7be2b59393bc1689b840d7b60fd75b811bb06477bdd03abee6fbc17afa5cf','Unexpected original OpenCV classes')
 with zipfile.ZipFile(AAR) as z: assert hashlib.sha256(z.read('classes.jar')).hexdigest()==sha(CLASSES)
 with zipfile.ZipFile(CLASSES) as z,zipfile.ZipFile(DROP) as q:
  expected={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.endswith('.class')}
  assert expected=={n:hashlib.sha256(q.read(n)).hexdigest() for n in q.namelist() if n.endswith('.class')}
  names=['org.opencv.android.OpenCVLoader','org.opencv.android.StaticHelper','org.opencv.core.Core','org.opencv.core.Mat']
  class_hashes={n:expected[n.replace('.','/')+'.class'] for n in names}
 with zipfile.ZipFile(APP) as z:class_hashes['com.realityforge.scanner.engine.NativeCore']=hashlib.sha256(z.read('com/realityforge/scanner/engine/NativeCore.class')).hexdigest()
 sdk=REPO/'app/build/robolectric-sdk'; jars=list(sdk.iterdir());assert len(jars)==1 and jars[0].name=='android-all-15-robolectric-12650502.jar' and sha(jars[0])=='6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b'
 fixture=REPO/'benchmarks/fixtures/relief'
 fixture_hashes={'view-01.jpg':'66fa84499c73e4a6cbb4af03880e6494433f7d4d32f288476c575a71aa82ab7c','view-02.jpg':'c275db33a1d10d84fe5a9bf78c55a7ea31de002ac305e00fd521e54b351b391b','view-03.jpg':'a0d386746c2a8f47840058613bc89aa3c4a8095995288fa936d368ad67fe6c0f','calibration.json':'284c73d64c92bbed50f762a5bc74b6f39d0a3955a0bd219217b6d23bbd7fd593'}
 for n,h in fixture_hashes.items():assert sha(fixture/n)==h
 bound=[*[fixture/n for n in fixture_hashes],AAR,CLASSES,DROP,APP,INIT,P(__file__),*jars,*[v[0] for v in NATIVE.values()],*[SOURCE/n for n in expected_sources],JAVA,JAVA.parent.parent/'release',GRADLE,ENV,args.config,*[P(v['path']) for v in settings['provenance']]]
 bound += [REPO/x for x in cmd('git','ls-files').splitlines() if (REPO/x).is_file()]
 before={str(p):sha(p) for p in bound};(args.output/'pre-hashes.json').write_text(json.dumps(before,indent=2))
 cfg={'fixtureDir':str(fixture),'fixtureHashes':fixture_hashes,'sources':str(SOURCE),'output':str(args.output),'sdk':str(sdk),'aliasDir':str(alias),'dropOpenCvRuntime':str(DROP),'classHashes':class_hashes,'native':{k:{'path':str(p),'sha256':h} for k,(p,h) in NATIVE.items()}}
 cfgpath=args.output/'config.json';cfgpath.write_text(json.dumps(cfg,indent=2));bound.append(cfgpath);before[str(cfgpath)]=sha(cfgpath);(args.output/'pre-hashes.json').write_text(json.dumps(before,indent=2))
 dynamic=cmd('readelf','-d',str(NATIVE['shim'][0]));(args.output/'shim-dynamic.txt').write_text(dynamic)
 assert 'Shared library: [librealityforge.so]' in dynamic and 'Library runpath: ['+str(NATIVE['engine'][0].parent)+']' in dynamic
 exports=cmd('nm','-D',str(NATIVE['shim'][0]));(args.output/'shim-exports.txt').write_text(exports)
 for n in ['beginReconstruction','pollNativeProgress','cancelReconstruction','finishReconstruction','reconstructCancellable']:assert 'Java_com_realityforge_scanner_engine_NativeCore_'+n in exports
 command=['bash','-c','source "$1"; exec "$2" --no-daemon --max-workers=1 --no-parallel -Dorg.gradle.jvmargs="-Xmx1g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8" --offline --dependency-verification strict -I "$3" -PloaderProbeConfig="$4" :app:notificationNativeTokenProbe --tests com.realityforge.scanner.hostintegration.NotificationNativeTokenHostTest','probe',str(ENV),str(GRADLE),str(INIT),str(cfgpath)]
 env=dict(os.environ)
 for k in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS']:env.pop(k,None)
 status='NOT_RUN';start=time.time()
 try:
  with (args.output/'gradle.log').open('w') as log:
   proc=subprocess.Popen(command,cwd=REPO,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
   status=wait_bounded(proc)
 finally:
  errors=[]
  def safe_sha(path):
   try:return sha(path)
   except Exception as e:errors.append({'path':str(path),'error':repr(e)});return None
  def safe_cmd(*a):
   try:return cmd(*a)
   except Exception as e:errors.append({'command':a,'error':repr(e)});return None
  after={str(p):safe_sha(p) for p in bound};(args.output/'post-hashes.json').write_text(json.dumps(after,indent=2))
  post_commit=safe_cmd('git','rev-parse','HEAD');post_status=safe_cmd('git','status','--porcelain')
  compiled=[]
  for evidence in ['compiled-probes.json','actual-classpath.json']:
   try:
    data=json.loads((args.output/evidence).read_text())
    if evidence=='compiled-probes.json': compiled.extend(data)
    else:
     for item in data['classpath']:
      if item.get('sha256'):compiled.append(item)
      compiled.extend(item.get('files',[]))
   except Exception as e:errors.append({'evidence':evidence,'error':repr(e)})
  compiled_checks=[{'path':item['path'],'expected':item['sha256'],'actual':safe_sha(item['path'])} for item in compiled]
  compiled_unchanged=bool(compiled_checks) and all(x['expected']==x['actual'] for x in compiled_checks)
  junit=[];junit_ok=False
  try:
   for xml in (args.output/'xml').glob('TEST-*.xml'):
    suite=ET.parse(xml).getroot();junit.append({'path':str(xml),**suite.attrib})
   junit_ok=junit_pass(junit)
  except Exception as e:errors.append({'junitError':repr(e)})
  all_checks=status=='EXIT_0' and not errors and before==after and post_commit==args.expected_commit and post_status=='' and compiled_unchanged and junit_ok
  (args.output/'post-compiled-hashes.json').write_text(json.dumps(compiled_checks,indent=2))
  receipt={'outcome':'PENDING_RESTORATION' if all_checks else 'FAILED','junit':junit,'postcheckErrors':errors,'postCommit':post_commit,'postGitStatus':post_status,'compiledInputsUnchanged':compiled_unchanged,'status':status,'durationSeconds':time.time()-start,'commit':args.expected_commit,'tree':safe_cmd('git','rev-parse','HEAD^{tree}'),'inputHashesUnchanged':before==after,'command':command,'classHashes':class_hashes,'scope':'HOST real Room/controller notification dispatch and actual JNI entrypoint token cancellation; reflected ownership, no kernel/worker/service/device qualification','restoration':'PENDING'}
  receipt_path=args.output/'receipt.json';write_receipt(receipt_path,receipt)
  # Preserve test evidence before normal compilation removes invocation-only probe classes.
  restore=['bash','-c','source "$1"; exec "$2" --no-daemon --max-workers=1 --no-parallel -Dorg.gradle.jvmargs="-Xmx1g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8" --offline --dependency-verification strict :app:compileDebugUnitTestKotlin','restore',str(ENV),str(GRADLE)]
  restoration={'command':restore,'status':'NOT_RUN'}
  try:
   with (args.output/'restoration.log').open('w') as log:
    rp=subprocess.Popen(restore,cwd=REPO,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    restoration['status']=wait_bounded(rp)
   restoration['remainingProbeClasses']=[str(p) for p in (REPO/'app/build').rglob('*.class') if p.name.startswith(('SandboxNativeLoader','NotificationNativeTokenHostTest'))]
   restoration['commit']=safe_cmd('git','rev-parse','HEAD');restoration['gitStatus']=safe_cmd('git','status','--porcelain')
   tracked=[REPO/x for x in safe_cmd('git','ls-files').splitlines() if (REPO/x).is_file()]
   restoration['trackedHashesUnchanged']=all(safe_sha(p)==before.get(str(p)) for p in tracked)
   restoration['restored']=restoration['status']=='EXIT_0' and not restoration['remainingProbeClasses'] and restoration['commit']==args.expected_commit and restoration['gitStatus']=='' and restoration['trackedHashesUnchanged']
  except Exception as e:restoration.update(restored=False,error=repr(e))
  receipt['restoration']=restoration
  if not restoration.get('restored') or errors:all_checks=False;receipt['outcome']='FAILED'
  receipt['outcome']='PASS' if all_checks else 'FAILED'
  write_receipt(receipt_path,receipt)
 if not all_checks:raise SystemExit(1)
if __name__=='__main__':main()
