#!/usr/bin/env python3
import sys
sys.dont_write_bytecode=True
import pathlib,hashlib,json,os,resource
ROOT=pathlib.Path(__file__).resolve().parent

def need(b,m):
 if not b:raise RuntimeError(m)
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def main():
 mode=sys.argv[1] if len(sys.argv)>1 else ''
 if mode=='controls':
  need(len(sys.argv)==4,'usage: run.py controls FRESH_OUTPUT QA_PYTHON');out=pathlib.Path(sys.argv[2]).resolve();python=pathlib.Path(sys.argv[3]).absolute();run=fixture=None
 elif mode=='proof':
  need(len(sys.argv)==5,'usage: run.py proof SOURCE_CELL_REPLAY FRESH_OUTPUT QA_PYTHON');run=pathlib.Path(sys.argv[2]).resolve();out=pathlib.Path(sys.argv[3]).resolve();python=pathlib.Path(sys.argv[4]).absolute();fixture=None
 elif mode=='whole':
  need(len(sys.argv)==6,'usage: run.py whole SOURCE_CELL_REPLAY FIXTURE FRESH_OUTPUT QA_PYTHON');run,fixture,out=map(lambda x:pathlib.Path(x).resolve(),sys.argv[2:5]);python=pathlib.Path(sys.argv[5]).absolute()
 else:raise RuntimeError('mode controls, proof or whole required')
 need(not out.exists() and not out.is_relative_to(ROOT) and (run is None or not out.is_relative_to(run)) and (fixture is None or not out.is_relative_to(fixture)),'FRESH_EXTERNAL_OUTPUT')
 manifest=sha(ROOT/'SHA256SUMS');pins={}
 for line in (ROOT/'SHA256SUMS').read_text().splitlines():
  h,n=line.split('  ',1);p=(ROOT/n).resolve();need(p.is_relative_to(ROOT) and p.is_file() and sha(p)==h,'PACKAGE:'+n);pins[n]=h
 need({'source/front.py','source/bridge.py','source/ledger_adapter.py','source/native_diagnostics.cpp','source/gates.py','config.json','input-pins.json','native-source-pins.json'}<=pins.keys(),'PACKAGE_SOURCE_SET')
 env=os.environ.copy();forbidden=('LD_LIBRARY_PATH','LD_PRELOAD','LD_AUDIT','LD_RUN_PATH','CXX','CC','CXXFLAGS','CFLAGS','LDFLAGS','CPPFLAGS','CMAKE_TOOLCHAIN_FILE','CMAKE_PREFIX_PATH','CMAKE_GENERATOR','PYTHONPATH','PYTHONHOME','PYTHONUSERBASE','PYTHONSTARTUP','PYTHONINSPECT','RF_LEDGER_OBSERVER_DIR');overrides=[k for k in forbidden if env.get(k)];need(not overrides,'UNEXPECTED_RUNTIME_OVERRIDE:'+','.join(overrides))
 sys.path.insert(0,str(ROOT/'source'));from bounded import run as bounded
 from bridge import verify
 from gates import compare
 resource.setrlimit(resource.RLIMIT_AS,(1073741824,1073741824));cfg=json.loads((ROOT/'config.json').read_text());out.mkdir();calls=[]
 def call(argv,name,seconds=600):calls.append(bounded(list(map(str,argv)),out/(name+'.log'),seconds,env))
 def identities():
  need(sha(ROOT/'SHA256SUMS')==manifest,'MANIFEST_MUTATED')
  for n,h in pins.items():need(sha(ROOT/n)==h,'PACKAGE_MUTATED:'+n)
 try:
  call([python,'--version'],'python-version',60)
  (out/'premeasurement.json').write_text(json.dumps({'manifest':manifest,'configSHA256':sha(ROOT/'config.json'),'mode':mode,'pythonEntry':str(python),'pythonBinarySHA256':sha(python.resolve()),'inputPinsSHA256':sha(ROOT/'input-pins.json'),'inheritedOverrides':overrides},indent=2)+'\n')
  call(['/usr/bin/prlimit','--as=1073741824','--cpu=600','--fsize=16777216','--',python,'-B',ROOT/'source/run_controls.py',ROOT,out/'controls'],'controls')
  if mode in ('proof','whole'):
   verify(run,json.loads((ROOT/'input-pins.json').read_text())['files'])
   call(['/usr/bin/prlimit','--as=1073741824','--cpu=600','--fsize=16777216','--',python,'-B',ROOT/'source/partition_proof.py',ROOT,run,out/'partition-proof'],'partition-proof')
  if mode=='whole':
   ip=json.loads((ROOT/'input-pins.json').read_text())['files'];verify(run,ip);source=run/'baseline-source';build=run/'baseline-build';lib=(build/'librealityforge.so').resolve();oldreceipt=json.loads((run/'receipt.json').read_text());need(oldreceipt['manifest']=='774a927f92da7423574b2282761f889317617c4561dec1a491aa12e18714071e' and oldreceipt['sourceCommit']==cfg['sourceCommit'],'SOURCE_CELL_EXECUTION_IDENTITY');need(sha(lib)==oldreceipt['native']['baseline']['library'],'MATCHED_LIBRARY')
   for n,h in json.loads((ROOT/'native-source-pins.json').read_text()).items():need((source/n).is_file() and sha(source/n)==h,'PINNED_NATIVE_SOURCE:'+n)
   fixtureManifest=sha(fixture/'SHA256SUMS');need(fixtureManifest=='c1107dd09510ffbba286b1c7bdae2e3d099726df8704322f20ed92e1010176b6','FIXTURE_MANIFEST');fp={n:h for h,n in (line.split('  ',1) for line in (fixture/'SHA256SUMS').read_text().splitlines())}
   for n,h in fp.items():need(sha(fixture/n)==h,'FIXTURE:'+n)
   binary=out/'native-diagnostics';call(['/usr/bin/c++','-std=c++20','-O2','-I'+str(source/'reconstruction/include'),ROOT/'source/native_diagnostics.cpp','-L'+str(build),'-Wl,-rpath,'+str(build),'-lrealityforge','-o',binary],'native-compile',120);call(['/usr/bin/ldd',binary],'native-ldd',60);need(('librealityforge.so => '+str(lib)) in (out/'native-ldd.log').read_text(),'LOADER_BINDING')
   call(['/usr/bin/prlimit','--as=1073741824','--cpu=600','--fsize=268435456','--',python,'-B',ROOT/'source/geometry_arm.py',ROOT,run,out/'geometry'],'geometry')
   call(['/usr/bin/prlimit','--as=1073741824','--cpu=600','--fsize=16777216','--',binary,out/'geometry/candidate.bits',out/'native'],'native')
   seal={'meshBitsSHA256':sha(out/'native/mesh.bits'),'sourceInputsUnchanged':True,'oracleCameraExtrinsicsInput':True,'geometryMasksPassedToSolver':False,'pointRelocation':False};(out/'solver-seal.json').write_text(json.dumps(seal,indent=2)+'\n');verify(run,ip)
   call(['/usr/bin/prlimit','--as=1073741824','--cpu=600','--fsize=16777216','--',python,'-B',ROOT/'source/evaluate_sealed.py',fixture/'evaluation-only/fixture.json',out/'native/mesh.bits',out/'solver-seal.json',out/'evaluation.json'],'evaluate')
   need(sha(out/'native/mesh.bits')==seal['meshBitsSHA256'],'POST_EVAL_MESH');gate=compare(json.loads((run/'evaluation.json').read_text()),json.loads((out/'evaluation.json').read_text()),json.loads((out/'native/topology.json').read_text()),json.loads((out/'geometry/geometry-receipt.json').read_text()));(out/'gates.json').write_text(json.dumps(gate,indent=2)+'\n')
   verify(run,ip);need(sha(fixture/'SHA256SUMS')==fixtureManifest,'FIXTURE_CHANGED')
   for n,h in fp.items():need(sha(fixture/n)==h,'FIXTURE_CHANGED:'+n)
  identities();need(sum(f.stat().st_size for f in out.rglob('*') if f.is_file())<=cfg['maxOutputBytes'],'WHOLE_OUTPUT_BUDGET');receipt={'status':'CONTROLS_PASS' if mode=='controls' else ('PARTITION_PROOF_PASS' if mode=='proof' else gate['status']),'mode':mode,'manifest':manifest,'calls':calls,'artifacts':{str(f.relative_to(out)):sha(f) for f in out.rglob('*') if f.is_file()},'nativeLibrary':sha(lib) if mode=='whole' else None,'compiler':sha('/usr/bin/c++'),'prlimit':sha('/usr/bin/prlimit')};(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(receipt['status'])
 except BaseException as e:
  (out/'REFUSED.json').write_text(json.dumps({'error':type(e).__name__,'reason':str(e),'manifest':manifest,'quality':'NO_GO_INCOMPLETE_ARM','successfulGeometryPublication':False,'calls':calls},indent=2)+'\n');raise
if __name__=='__main__':main()
