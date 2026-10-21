"""Owner-authorized bounded read-only measurement; writes only fresh --output directory."""
import argparse,pathlib,os,sys,hashlib,json,subprocess,resource,time
if not __debug__:raise RuntimeError('Optimized Python disables integrity assertions; refused')
P=pathlib.Path
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--repo',type=P,required=True);ap.add_argument('--evidence',type=P,required=True);ap.add_argument('--python',type=P,required=True);ap.add_argument('--commit',required=True);ap.add_argument('--receipt-sha256',required=True);ap.add_argument('--comparison-sha256',required=True);ap.add_argument('--output',type=P,required=True);a=ap.parse_args();here=P(__file__).resolve().parent
 assert not a.output.exists() and not a.output.is_symlink()
 for key in ['PYTHONPATH','PYTHONHOME','LD_PRELOAD','LD_LIBRARY_PATH']:assert not os.environ.get(key),'Injected runtime '+key
 git=lambda *s:subprocess.check_output(['git',*s],cwd=a.repo,text=True).strip()
 assert git('rev-parse','HEAD')==a.commit and not git('status','--porcelain')
 assert sha(a.evidence/'receipt.json')==a.receipt_sha256
 receipt=json.loads((a.evidence/'receipt.json').read_text());assert receipt['outcome']=='PASS' and receipt['status']=='EXIT_0' and receipt['restoration']['restored']
 assert sha(a.evidence/'profile-comparison/comparison.json')==a.comparison_sha256
 comparison=json.loads((a.evidence/'profile-comparison/comparison.json').read_text())
 assert comparison['schema']==2 and comparison['qualification']=='GEOMETRY_TEXTURE_CAPTURE_ONLY_NOT_REFINED_CACHE_QUALIFICATION'
 pins=json.loads((here/'source-pins.json').read_text())
 for p,h in pins.items():assert sha(P(p))==h,p
 files=set(P(p) for p in pins)|{here/'source-pins.json',a.evidence/'receipt.json',a.python.resolve(),a.python.parent.parent/'pyvenv.cfg'}
 for d in [a.evidence/'profile-comparison',a.evidence/'quality-capture']:
  files.update(p for p in d.rglob('*') if p.is_file())
 site=list((a.python.parent.parent/'lib').glob('python*/site-packages'));assert len(site)==1
 for name in ['numpy','numpy.libs','cv2','opencv_python.libs','PIL','pillow.libs']:
  files.update(p for p in (site[0]/name).rglob('*') if p.is_file() and (p.suffix in ['.py','.so'] or '.so.' in p.name))
 for p in files:assert p.is_file() and not p.is_symlink(),str(p)
 before={str(p):sha(p) for p in sorted(files)}
 a.output.mkdir();(a.output/'pre-hashes.json').write_text(json.dumps(before,indent=2)+'\n')
 env={k:v for k,v in os.environ.items() if not k.startswith(('PYTHON','LD_'))};env.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='1',MKL_NUM_THREADS='1',NUMEXPR_NUM_THREADS='1',PYTHONDONTWRITEBYTECODE='1')
 cache=a.output/'empty-pycache';cache.mkdir();env['PYTHONPYCACHEPREFIX']=str(cache.resolve())
 cmd=[str(a.python),'-B','-X','pycache_prefix='+str(cache.resolve()),str(here/'measure.py'),'--repo',str(a.repo),'--evidence',str(a.evidence),'--receipt-sha256',a.receipt_sha256,'--comparison-sha256',a.comparison_sha256,'--output',str(a.output/'measurements.json')]
 def limits():resource.setrlimit(resource.RLIMIT_AS,(1024**3,1024**3));resource.setrlimit(resource.RLIMIT_CPU,(60,60))
 start=time.monotonic();code=None;error=None
 try:
  with (a.output/'execution.log').open('x') as f:code=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,env=env,preexec_fn=limits,timeout=60).returncode
 except Exception as e:error=repr(e)
 after={str(p):sha(p) if p.is_file() else None for p in sorted(files)};(a.output/'post-hashes.json').write_text(json.dumps(after,indent=2)+'\n')
 ok=code==0 and before==after and git('rev-parse','HEAD')==a.commit and not git('status','--porcelain')
 result=dict(status='GEOMETRY_TEXTURE_MEASURE_ONLY_NOT_CACHE_OR_QUALITY_PASS' if ok else 'FAILED',command=cmd,exitCode=code,error=error,inputsUnchanged=before==after,seconds=time.monotonic()-start,wallCpuSeconds=60,addressSpaceBytes=1024**3,threads=1,reportSha256=sha(a.output/'measurements.json') if (a.output/'measurements.json').exists() else None)
 (a.output/'receipt.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result));return 0 if ok else 1
if __name__=='__main__':sys.exit(main())
