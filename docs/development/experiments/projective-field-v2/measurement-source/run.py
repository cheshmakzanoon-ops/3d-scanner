"""Posthoc only. No native/Gradle execution; fresh output, 60s/1GiB single-thread bounds."""
import argparse,pathlib,json,hashlib,subprocess,resource,time,os,sys
if not __debug__:raise RuntimeError('Optimized Python refused')
P=pathlib.Path
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--config',type=P,required=True);ap.add_argument('--config-sha256',required=True);ap.add_argument('--python',type=P,required=True);ap.add_argument('--commit',required=True);ap.add_argument('--output',type=P,required=True);a=ap.parse_args();here=P(__file__).resolve().parent
 assert sha(a.config)==a.config_sha256 and not a.output.exists() and not a.output.is_symlink();c=json.loads(a.config.read_text());repo=P(c['repo'])
 for k in ['PYTHONHOME','PYTHONPATH','LD_PRELOAD','LD_LIBRARY_PATH']:assert not os.environ.get(k),'Injected '+k
 git=lambda *s:subprocess.check_output(['git',*s],cwd=repo,text=True).strip()
 assert git('rev-parse','HEAD')==a.commit==c['publishedCommit'] and git('rev-parse',c['publishedRef'])==a.commit and not git('status','--porcelain')
 pins=json.loads((here/'source-pins.json').read_text());pins.update(c['inputSha256'])
 for p,h in pins.items():assert sha(P(p))==h,p
 files=set(P(p) for p in pins)|{here/'source-pins.json',a.config,a.python.resolve(),a.python.parent.parent/'pyvenv.cfg'}
 site=list((a.python.parent.parent/'lib').glob('python*/site-packages'));assert len(site)==1
 for name in ['numpy','numpy.libs','cv2','opencv_python.libs','PIL','pillow.libs']:
  files.update(p for p in (site[0]/name).rglob('*') if p.is_file() and (p.suffix in ['.py','.so'] or '.so.' in p.name))
 for p in files:assert p.is_file() and not p.is_symlink()
 before={str(p):sha(p) for p in sorted(files)};a.output.mkdir();(a.output/'pre-hashes.json').write_text(json.dumps(before,indent=2)+'\n');cache=a.output/'empty-pycache';cache.mkdir()
 env={k:v for k,v in os.environ.items() if not k.startswith(('PYTHON','LD_'))};env.update(PYTHONPYCACHEPREFIX=str(cache.resolve()),PYTHONDONTWRITEBYTECODE='1',OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='1',MKL_NUM_THREADS='1',NUMEXPR_NUM_THREADS='1')
 cmd=[str(a.python),'-B','-X','pycache_prefix='+str(cache.resolve()),str(here/'measure.py'),'--config',str(a.config),'--config-sha256',a.config_sha256,'--output',str(a.output/'measurements.json')]
 def limits():resource.setrlimit(resource.RLIMIT_AS,(1024**3,1024**3));resource.setrlimit(resource.RLIMIT_CPU,(60,60))
 start=time.monotonic();code=None;error=None
 try:
  with (a.output/'execution.log').open('x') as f:code=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,env=env,preexec_fn=limits,timeout=60).returncode
 except Exception as e:error=repr(e)
 after={str(p):sha(p) if p.is_file() else None for p in sorted(files)};(a.output/'post-hashes.json').write_text(json.dumps(after,indent=2)+'\n');ok=code==0 and before==after and git('rev-parse','HEAD')==a.commit and git('rev-parse',c['publishedRef'])==a.commit and not git('status','--porcelain')
 receipt=dict(status='EXPERIMENTAL_PROJECTIVE_FIELD_MEASURE_ONLY_NOT_QUALITY_PASS' if ok else 'FAILED',command=cmd,configSha256=a.config_sha256,exitCode=code,error=error,inputsUnchanged=before==after,seconds=time.monotonic()-start,wallCpuSeconds=60,addressSpaceBytes=1024**3,threads=1,reportSha256=sha(a.output/'measurements.json') if (a.output/'measurements.json').exists() else None)
 (a.output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt));return 0 if ok else 1
if __name__=='__main__':sys.exit(main())
