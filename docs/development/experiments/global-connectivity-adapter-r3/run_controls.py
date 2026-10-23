import sys
sys.dont_write_bytecode=True
import pathlib,json,resource,signal,time,hashlib
ROOT=pathlib.Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'source'))
def need(b,m):
 if not b:raise RuntimeError(m)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def interrupted(sig,frame):raise RuntimeError('interrupted '+str(sig))
def main():
 need(len(sys.argv)==2,'fresh output required');out=pathlib.Path(sys.argv[1]).resolve();need(not out.exists() and not out.is_relative_to(ROOT),'fresh external output required');initial=sha(ROOT/'SHA256SUMS');pins={}
 for line in (ROOT/'SHA256SUMS').read_text().splitlines():
  h,n=line.split('  ',1);p=(ROOT/n).resolve();need(p.is_relative_to(ROOT) and p.is_file() and sha(p)==h,'package identity');pins[n]=h
 need({'source/ledger_adapter.py','source/adapter_controls.py','source/exact_geometry.py','source/support_overlay.py','source/closed_certificate.py','source/orientation.py','run_controls.py'}<=pins.keys(),'required sources missing')
 resource.setrlimit(resource.RLIMIT_AS,(268435456,268435456));resource.setrlimit(resource.RLIMIT_CPU,(120,120))
 for s in (signal.SIGINT,signal.SIGTERM,signal.SIGALRM):signal.signal(s,interrupted)
 signal.alarm(120);out.mkdir();start=time.monotonic()
 try:
  from exact_geometry import Budget
  from adapter_controls import run
  B=Budget();result=run(out,B)
  for n,h in pins.items():need(sha(ROOT/n)==h,'source changed')
  need(sha(ROOT/'SHA256SUMS')==initial,'manifest changed');result.update(status='PASS_ANALYTIC_ADAPTER_ONLY',sourceManifest=initial,photo='NOT_RUN_MISSING_PRODUCTION_LEDGER',front='NOT_RUN',supportWork=B.used_clips,seconds=time.monotonic()-start,maxRssKiB=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
  data=(json.dumps(result,indent=2)+'\n').encode();need(sum(p.stat().st_size for p in out.rglob('*') if p.is_file())+len(data)<=67108864,'output budget');(out/'receipt.json').write_bytes(data);print(json.dumps(result))
 except BaseException as e:(out/'REFUSED.txt').write_text(type(e).__name__+': '+str(e)+'\nNo geometry published.\n');raise
 finally:signal.alarm(0)
if __name__=='__main__':main()
