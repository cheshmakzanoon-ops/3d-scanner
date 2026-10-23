#!/usr/bin/env python3
"""Bounded fresh-output generation only. No reconstruction invocation."""
import os,sys,pathlib,json,hashlib,subprocess,signal,time
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).resolve().parents[1]
def need(b,m):
 if not b:raise RuntimeError(m)
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def interrupted(sig,frame):raise RuntimeError('render interrupted '+str(sig))
def main():
 need(len(sys.argv)==2,'usage: render.py FRESH_OUTPUT');out=pathlib.Path(sys.argv[1]).resolve();need(not out.exists(),'existing output refused');need(not out.is_relative_to(ROOT),'output must be external')
 cfg=json.loads((ROOT/'render-config.json').read_text());config_hash=sha(ROOT/'render-config.json');source=ROOT/'source/generate.py'
 need(sha(source)==cfg['generatorSHA256'],'generator identity');need(sha(cfg['blenderPath'])==cfg['blenderSHA256'],'Blender identity');need(sha(cfg['prlimitPath'])==cfg['prlimitSHA256'],'prlimit identity')
 env=dict(os.environ)
 need(not any(env.get(k) for k in ('ASAN_OPTIONS','LSAN_OPTIONS','UBSAN_OPTIONS')),'sanitizer override environment not accepted')
 for k in ('LD_LIBRARY_PATH','LD_PRELOAD','LD_AUDIT'):env.pop(k,None)
 env.update(PYTHONDONTWRITEBYTECODE='1',OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='2')
 out.mkdir();assets=out/'assets';log=out/'render.log';start=time.monotonic();proc=None;old={}
 receipt={'schema':1,'status':'RUNNING','configSHA256':config_hash,'runnerSHA256':sha(__file__),'generatorSHA256':sha(source),'blenderSHA256':sha(cfg['blenderPath']),'prlimitSHA256':sha(cfg['prlimitPath']),'candidate':'NOT_RUN','reconstruction':'NOT_RUN'}
 command=[cfg['prlimitPath'],'--as='+str(cfg['maxAddressSpaceBytes']),'--cpu='+str(cfg['maxSeconds']),'--',cfg['blenderPath'],'--background','--threads',str(cfg['threads']),'--python',str(source),'--',str(assets)]
 receipt['command']=command
 try:
  for s in (signal.SIGINT,signal.SIGTERM):old[s]=signal.signal(s,interrupted)
  with log.open('xb') as f:
   proc=subprocess.Popen(command,stdout=f,stderr=subprocess.STDOUT,env=env,start_new_session=True)
   while proc.poll() is None:
    need(time.monotonic()-start<=cfg['maxSeconds'],'render wall limit')
    need(log.stat().st_size<=cfg['maxLogBytes'],'render log limit')
    need(sum(p.stat().st_size for p in assets.glob('*') if p.is_file())<=cfg['maxAssetBytes'],'render asset limit')
    time.sleep(.1)
  need(proc.returncode==0,'render process exit '+str(proc.returncode))
  expected={'fixture.json'}|{f'view-{i:02d}.png' for i in range(8)};need({p.name for p in assets.iterdir()}==expected,'exact asset set')
  need(sum(p.stat().st_size for p in assets.iterdir())<=cfg['maxAssetBytes'],'final asset limit')
  meta=json.loads((assets/'fixture.json').read_text());need((meta['width'],meta['height'],len(meta['cameras']),meta['seed'])==(160,120,8,78424),'fixture settings changed')
  need(sha(ROOT/'render-config.json')==config_hash and sha(source)==cfg['generatorSHA256'],'inputs changed')
  receipt.update(status='RENDERED_NEW_SYNTHETIC_FIXTURE',seconds=time.monotonic()-start,assetSHA256={p.name:sha(p) for p in sorted(assets.iterdir())},assetBytes=sum(p.stat().st_size for p in assets.iterdir()),logSHA256=sha(log),renderer=meta['renderer'],numpyVersion=meta['numpyVersion'])
 except BaseException as e:
  receipt.update(status='REFUSED',error=type(e).__name__+': '+str(e));raise
 finally:
  for s in old:signal.signal(s,signal.SIG_IGN)
  if proc is not None:
   try:os.killpg(proc.pid,signal.SIGTERM)
   except ProcessLookupError:pass
   time.sleep(.2)
   try:os.killpg(proc.pid,signal.SIGKILL)
   except ProcessLookupError:pass
   proc.wait(timeout=5)
  for s,h in old.items():signal.signal(s,h)
  (out/'render-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
 if receipt['status']=='RENDERED_NEW_SYNTHETIC_FIXTURE':print(json.dumps({'status':receipt['status'],'bytes':receipt['assetBytes']}))
if __name__=='__main__':main()
