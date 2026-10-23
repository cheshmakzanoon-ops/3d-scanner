import sys
sys.dont_write_bytecode=True
import pathlib,json,hashlib,os,resource
ROOT=pathlib.Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'source'))
from bounded import run as bounded

def need(ok,why):
 if not ok:raise RuntimeError(why)
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def main():
 need(len(sys.argv)==7,'usage: run.py SOURCE_CELL_REPLAY FIXTURE NATIVE_SOURCE FRESH_OUTPUT QA_PYTHON controls|census')
 run,fixture,source,out=map(lambda x:pathlib.Path(x).resolve(),sys.argv[1:5]);python=pathlib.Path(sys.argv[5]).absolute();mode=sys.argv[6];need(mode in ('controls','census'),'MODE');need(not out.exists() and not any(out.is_relative_to(p) for p in (ROOT,run,fixture,source)),'FRESH_EXTERNAL_OUTPUT');manifest=sha(ROOT/'SHA256SUMS');pins={n:h for h,n in (line.split('  ',1) for line in (ROOT/'SHA256SUMS').read_text().splitlines())}
 def check():
  need(sha(ROOT/'SHA256SUMS')==manifest,'MANIFEST_MUTATED')
  for n,h in pins.items():
   p=(ROOT/n).resolve();need(p.is_relative_to(ROOT) and p.is_file() and sha(p)==h,'PACKAGE:'+n)
 check();env=os.environ.copy();bad=[k for k in ('LD_LIBRARY_PATH','LD_PRELOAD','LD_AUDIT','PYTHONPATH','PYTHONHOME','PYTHONUSERBASE','PYTHONSTARTUP','PYTHONINSPECT','CXX','CC','CXXFLAGS','CFLAGS','LDFLAGS','CPPFLAGS','CPATH','CPLUS_INCLUDE_PATH','LIBRARY_PATH') if env.get(k)];need(not bad,'UNEXPECTED_RUNTIME_OVERRIDE:'+','.join(bad));out.mkdir();resource.setrlimit(resource.RLIMIT_AS,(1073741824,1073741824))
 try:
  compile_call=bounded(['/usr/bin/prlimit','--as=1073741824','--cpu=120','--fsize=1048576','--','/usr/bin/c++','-std=c++20','-O2',str(ROOT/'source/rounding_control.cpp'),'-o',str(out/'rounding-control')],out/'compile.log',120,env,max_bytes=32768)
  need((out/'rounding-control').stat().st_size<=1048576,'HELPER_BINARY_BUDGET')
  native_call=bounded(['/usr/bin/prlimit','--as=1073741824','--cpu=120','--fsize=262144','--',str(out/'rounding-control')],out/'rounding.log',120,env,max_bytes=196608)
  rounding_call=bounded([str(python),'-B','-c','import sys;sys.dont_write_bytecode=True;sys.path.insert(0,sys.argv[1]);from census import native_rounding_controls;print(native_rounding_controls(sys.argv[2]))',str(ROOT/'source'),str(out/'rounding.log')],out/'rounding-check.log',120,env,max_bytes=32768)
  argv=[str(python),'-B',str(ROOT/'source/census.py'),str(ROOT),str(run),str(fixture),str(source),str(out/'data')]
  if mode=='controls':argv=[str(python),'-B','-c','import sys,json;sys.dont_write_bytecode=True;sys.path.insert(0,sys.argv[1]);from census import controls;print(json.dumps(controls(),sort_keys=True))',str(ROOT/'source')]
  result=bounded(['/usr/bin/prlimit','--as=1073741824','--cpu=120','--fsize=1048576','--']+argv,out/'execution.log',120,env,max_bytes=786432) if mode=='controls' else bounded(['/usr/bin/prlimit','--as=1073741824','--cpu=120','--fsize=12582912','--']+argv,out/'execution.log',120,env,max_bytes=786432)
  check();files={str(f.relative_to(out)):sha(f) for f in out.rglob('*') if f.is_file()};receipt={'status':'CONTROLS_PASS' if mode=='controls' else 'EVALUATION_ONLY_COMPLETE','manifest':manifest,'mode':mode,'pythonEntry':str(python),'pythonBinarySHA256':sha(python.resolve()),'call':result,'roundingCalls':[compile_call,native_call,rounding_call],'compilerSHA256':sha('/usr/bin/c++'),'files':files};data=(json.dumps(receipt,indent=2)+'\n').encode();need(len(data)<=1048576 and sum(f.stat().st_size for f in out.rglob('*') if f.is_file())+len(data)<=16777216,'OUTPUT_BUDGET');(out/'receipt.json').write_bytes(data);print(receipt['status'])
 except BaseException as e:(out/'REFUSED.json').write_text(json.dumps({'error':type(e).__name__,'reason':str(e),'noCompleteEvaluation':True})+'\n');raise
if __name__=='__main__':main()
