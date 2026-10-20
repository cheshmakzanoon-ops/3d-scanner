from pathlib import Path
import hashlib,json,os,subprocess,sys,xml.etree.ElementTree as E
root=Path('/workspace/campaign/final-candidate');prefix=Path('/workspace/campaign/final6-pytest');lib=Path('/workspace/campaign/evidence/final-2f18a4f/librealityforge.so')
expected='2f18a4f5a87139c38ee388f6c70af33e0fb247f7'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
git=lambda s:subprocess.check_output(['git','-C',str(root),'rev-parse',s],text=True).strip()
assert git('HEAD')==expected
r={'source_sha':expected,'source_tree_sha':git('HEAD^{tree}'),'native_source_sha':'6c386d38141cdb979ed0223f53c16962f8cd438a','native_library':str(lib),'native_library_sha256':sha(lib),'source_and_library_held_immutable':True}
assert r['native_library_sha256']=='b95bcb9a8705e4c2a83a9dcdd7463644a211d08c340cc9ac5566240b2eb26d74'
env=dict(os.environ,RF_NATIVE_LIBRARY=str(lib),OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='2',OPENCV_FOR_THREADS_NUM='2')
env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' -XX:ActiveProcessorCount=2'
command=[sys.executable,'-m','pytest','-q','--junitxml='+str(prefix)+'.xml'];r['command']=command
Path(str(prefix)+'-inputs.json').write_text(json.dumps(r,indent=2)+'\n')
with Path(str(prefix)+'.log').open('w') as log: result=subprocess.run(command,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=1800)
Path(str(prefix)+'.exit').write_text(str(result.returncode)+'\n')
suite=E.parse(str(prefix)+'.xml').getroot();suites=[suite] if suite.tag=='testsuite' else list(suite.findall('testsuite'))
r['results']={k:sum(int(s.get(k,0)) for s in suites) for k in ('tests','failures','errors','skipped')};r['seconds']=sum(float(s.get('time',0)) for s in suites);r['exit_code']=result.returncode;r['source_sha_after']=git('HEAD');r['native_library_sha256_after']=sha(lib)
assert r['source_sha_after']==expected and r['native_library_sha256_after']==r['native_library_sha256']
Path(str(prefix)+'-receipt.json').write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r,indent=2));print('\n'.join(Path(str(prefix)+'.log').read_text().splitlines()[-25:]));sys.exit(result.returncode)
