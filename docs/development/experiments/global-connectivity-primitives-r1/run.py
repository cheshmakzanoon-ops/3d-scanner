#!/usr/bin/env python3
"""Fresh-output primitive replay. Standard library only; no child processes."""
import sys
sys.dont_write_bytecode=True
import pathlib,json,hashlib,resource,signal,time
ROOT=pathlib.Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'source'))

def require(b,m):
    if not b:raise RuntimeError(m)
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def interrupt(signum,frame):raise RuntimeError('INTERRUPTED_OR_WALL_LIMIT:'+str(signum))
def main():
    require(len(sys.argv)==2,'usage: python -B run.py FRESH_OUTPUT_OUTSIDE_PACKAGE')
    out=pathlib.Path(sys.argv[1]).resolve();require(not out.is_relative_to(ROOT),'output must be outside package');require(not out.exists(),'existing output refused')
    initial_manifest=digest(ROOT/'SHA256SUMS')
    expected={}
    for line in (ROOT/'SHA256SUMS').read_text().splitlines():
        sha,name=line.split('  ',1);p=(ROOT/name).resolve();require(p.is_relative_to(ROOT),'manifest escape');require(p.is_file() and digest(p)==sha,'package identity mismatch:'+name);expected[name]=sha
    require({'run.py','config.json','source/exact_geometry.py','source/controls.py'}<=expected.keys(),'required manifest inputs absent')
    from exact_geometry import Budget
    from controls import run
    cfg=json.loads((ROOT/'config.json').read_text());out.mkdir();started=time.monotonic()
    try:
        resource.setrlimit(resource.RLIMIT_AS,(cfg['maxAddressSpaceBytes'],cfg['maxAddressSpaceBytes']))
        resource.setrlimit(resource.RLIMIT_CPU,(cfg['maxSecondsPerCase'],cfg['maxSecondsPerCase']))
        for sig in (signal.SIGINT,signal.SIGTERM,signal.SIGALRM):signal.signal(sig,interrupt)
        signal.alarm(cfg['maxSecondsPerCase'])
        B=Budget(cfg['maxClippingChecks'],cfg['maxBallChecks'],cfg['maxPartitionFragments'],cfg['maxRationalBits'])
        result=run(B)
        for name,sha in expected.items():require(digest(ROOT/name)==sha,'package changed during run')
        require(digest(ROOT/'SHA256SUMS')==initial_manifest,'manifest changed during run')
        result.update(status='PASS_PRIMITIVES_ONLY',revision='reconstructed-r1',photo='NOT_RUN_MISSING_FIXTURE',adapter='NOT_RUN',front='NOT_RUN',work={'support':B.used_clips,'balls':B.used_balls},seconds=time.monotonic()-started,maxRssKiB=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,sourceManifest=initial_manifest)
        data=(json.dumps(result,indent=2)+'\n').encode();require(len(data)<=cfg['maxOutputBytes'],'output budget');(out/'receipt.json').write_bytes(data)
        print(json.dumps({'status':result['status'],'receipt':str(out/'receipt.json')}))
    except BaseException as e:
        (out/'REFUSED.txt').write_text(type(e).__name__+': '+str(e)+'\nNo geometry published.\n');raise
    finally:signal.alarm(0)
if __name__=='__main__':main()
