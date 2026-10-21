"""Private StageA build+guard tests only. Never captures or replays the real fixture."""
from pathlib import Path
import argparse,hashlib,json,os,resource,subprocess,time,sys,signal
sys.dont_write_bytecode=True
from compare import secure_bytes,need,digest,safe

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--config',required=True);ap.add_argument('--work',required=True);a=ap.parse_args();cfg=safe(a.config);c=json.loads(secure_bytes(cfg));draft=Path(__file__).parent;repo=Path(c['repo']);work=Path(a.work)
 need(work.is_absolute() and work.parent.resolve()==work.parent and not work.exists(),'fresh canonical StageA work');work.mkdir(mode=0o700)
 pins={str(cfg):digest(cfg)};commands=[];start=time.monotonic()
 try:
  for path,sha in c['inputSha256'].items():need(digest(safe(path))==sha,'frozen input pin');pins[path]=sha
  need(digest(c['originalEngine'])=='4384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b','original engine pin');need(digest(c['originalJni'])=='427f2c8c0e21a5647064edcdbe9307265fe493c256c211e4266f979f9931fcce','original JNI pin')
  for f in [*draft.glob('*.py'),*draft.glob('*.cpp'),*draft.glob('*.hpp'),draft/'telemetry.patch',draft/'source-pins.json']:
   need(str(f) in pins,'unpinned draft source')
  for name in ['originalEngine','originalJni','cmake','ninja','compiler','patch','python','ldd']:need(c[name] in pins,'unpinned tool')
  source=work/'source';source.mkdir();expected={}
  for relative,sha in c['repoSourceSha256'].items():
   rel=Path(relative);need(not rel.is_absolute() and '..' not in rel.parts and (relative=='CMakeLists.txt' or relative.startswith('reconstruction/')),'source scope')
   path=repo/rel;need(str(path) in pins and pins[str(path)]==sha,'repo source pin');target=source/rel;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(secure_bytes(path));expected[relative]=sha
  for name in ['telemetry.hpp','snapshot.hpp']:
   target=source/'reconstruction/include'/name;target.write_bytes(secure_bytes(draft/name));expected[str(target.relative_to(source))]=digest(draft/name)
  (work/'inputs.json').write_text(json.dumps(pins,indent=2)+'\n');env={'PATH':'/usr/bin:/bin','LC_ALL':'C','HOME':os.environ['HOME'],'PYTHONDONTWRITEBYTECODE':'1'}
  def limit():resource.setrlimit(resource.RLIMIT_AS,(2*1024**3,2*1024**3))
  def group_members(pgid):
   members=[]
   for entry in Path('/proc').iterdir():
    if not entry.name.isdigit():continue
    try:
     value=(entry/'stat').read_text();fields=value[value.rfind(')')+2:].split()
     if int(fields[2])==pgid and fields[0]!='Z':members.append(int(entry.name))
    except FileNotFoundError:pass
   return members
  def run(name,cmd,timeout):
   left=360-(time.monotonic()-start);budget=min(timeout,left);need(budget>=5,'insufficient StageA deadline for bounded cleanup');record={'name':name,'argv':cmd,'timeoutSeconds':timeout};commands.append(record)
   deadline=time.monotonic()+budget
   q=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,stdin=subprocess.DEVNULL,text=True,env=env,cwd=work,preexec_fn=limit,start_new_session=True)
   timed_out=False;stdout='';stderr='';cleanup_error=None;unexpected=None
   try:stdout,stderr=q.communicate(timeout=budget-4)
   except BaseException as first:
    timed_out=isinstance(first,subprocess.TimeoutExpired);unexpected=None if timed_out else first
    try:os.killpg(q.pid,signal.SIGTERM)
    except ProcessLookupError:pass
    try:stdout,stderr=q.communicate(timeout=min(2,max(0,deadline-time.monotonic())))
    except subprocess.TimeoutExpired:
     try:os.killpg(q.pid,signal.SIGKILL)
     except ProcessLookupError:pass
     try:stdout,stderr=q.communicate(timeout=min(2,max(0,deadline-time.monotonic())))
     except subprocess.TimeoutExpired as final:
      cleanup_error='process group did not drain after TERM/KILL';stdout=final.output or '';stderr=final.stderr or '';q.stdout.close();q.stderr.close()
   # A child that closes inherited pipes must not survive an apparently successful command.
   remaining=group_members(q.pid)
   if remaining:
    try:os.killpg(q.pid,signal.SIGKILL)
    except ProcessLookupError:pass
    cleanup_error='live process-group members remained after command termination'
   if q.poll() is None:
    q.kill()
    try:q.wait(timeout=max(0,deadline-time.monotonic()))
    except subprocess.TimeoutExpired:cleanup_error='direct process did not terminate after KILL'
   while remaining and time.monotonic()<deadline:
    time.sleep(min(.02,max(0,deadline-time.monotonic())));remaining=group_members(q.pid)
   if remaining:cleanup_error='live process-group members remain after bounded KILL cleanup'
   if isinstance(stdout,bytes):stdout=stdout.decode('utf-8',errors='replace')
   if isinstance(stderr,bytes):stderr=stderr.decode('utf-8',errors='replace')
   (work/(name+'.log')).write_text(stdout+stderr);record.update(returnCode=q.returncode,timedOut=timed_out,remainingGroupMembers=remaining,cleanupError=cleanup_error)
   if unexpected is not None:raise unexpected
   need(not timed_out,name+' timeout');need(cleanup_error is None,name+' cleanup incomplete');need(q.returncode==0,name+' exit '+str(q.returncode));return stdout+stderr
  run('apply-private-patch',[c['patch'],'--batch','--forward','--fuzz=0','--no-backup-if-mismatch','-p1','--directory',str(source),'--input',str(draft/'telemetry.patch')],15)
  delta=json.loads(secure_bytes(draft/'source-pins.json'));expected['reconstruction/src/surface.cpp']=delta['patchedSurfaceSha256'];expected['reconstruction/include/realityforge/surface_evidence.hpp']=delta['patchedEvidenceSha256']
  need(expected=={str(p.relative_to(source)):digest(p) for p in source.rglob('*') if p.is_file()},'private source delta not exact')
  build=work/'build'
  run('configure',[c['cmake'],'-S',str(source),'-B',str(build),'-G','Ninja','-DCMAKE_MAKE_PROGRAM='+c['ninja'],'-DCMAKE_CXX_COMPILER='+c['compiler'],'-DCMAKE_BUILD_TYPE=Release','-DCMAKE_CXX_FLAGS=-Werror','-DRF_REQUIRE_CAMPAIGN_031_060=ON','-DRF_SANITIZE=OFF','-DRF_EXPERIMENTAL_VULKAN=OFF'],30)
  targets=['realityforge','rf_surface_tests','rf_surface_fusion_tests','rf_surface_evidence_tests']
  run('build',[c['cmake'],'--build',str(build),'--target',*targets,'--parallel','2'],120)
  lib=build/'librealityforge.so';linkage=run('trace-library-linkage',[c['ldd'],'-r',str(lib)],15);need('undefined symbol:' not in linkage,'trace undefined symbols')
  for name in targets[1:]:run(name,[str(build/name)],30)
  guard=work/'snapshot-guards';engine=Path(c['originalEngine'])
  run('compile-snapshot-guards',[c['compiler'],'-std=c++20','-O2','-Werror','-I'+str(repo/'reconstruction/include'),'-I'+str(draft),str(draft/'guard_test.cpp'),'-L'+str(engine.parent),'-lrealityforge','-Wl,-z,defs','-Wl,-rpath,'+str(engine.parent),'-o',str(guard)],60)
  linkage=run('guard-linkage',[c['ldd'],'-r',str(guard)],15);need(str(engine) in linkage and 'undefined symbol:' not in linkage,'guard original native linkage')
  run('snapshot-guards',[str(guard),str(work/'snapshot-guard-evidence')],30)
  run('comparison-guards',[c['python'],str(draft/'comparison_guard_test.py'),'--work',str(work/'comparison-guard-evidence')],30)
  need(pins=={path:digest(safe(path)) for path in pins},'original input mutated')
  need(expected=={str(p.relative_to(source)):digest(p) for p in source.rglob('*') if p.is_file()},'private source mutated by build')
  receipt={'status':'STAGE_A_BUILD_GUARDS_ONLY_PASS','noRealCaptureOrReplay':True,'noTelemetryFindings':True,'elapsedSeconds':time.monotonic()-start,'limits':{'maxParallelCompilerJobs':2,'perProcessAddressSpaceBytes':2*1024**3,'aggregateSeconds':360},'commands':commands,'inputSha256':pins,'privateSourceSha256':expected,'tracedEngineSha256':digest(lib),'guardReceipt':json.loads(secure_bytes(work/'snapshot-guard-evidence/guard-receipt.json')),'comparisonReceipt':json.loads(secure_bytes(work/'comparison-guard-evidence/receipt.json')),'outputSha256':{str(p.relative_to(work)):digest(p) for p in work.iterdir() if p.is_file()}}
  (work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print('PASS',digest(work/'receipt.json'))
 except BaseException as error:
  post={}
  for path,expected_sha in pins.items():
   try:actual=digest(safe(path));post[path]={'expected':expected_sha,'actual':actual,'unchanged':actual==expected_sha}
   except Exception as check_error:post[path]={'expected':expected_sha,'unchanged':False,'error':str(check_error)}
  failure={'status':'STAGE_A_FAIL_CLOSED','errorType':type(error).__name__,'error':str(error),'elapsedSeconds':time.monotonic()-start,'commands':commands,'postInputChecks':post,'allKnownInputsUnchanged':all(item['unchanged'] for item in post.values()),'noRealCaptureOrReplay':True,'noTelemetryFindings':True}
  (work/'failure-receipt.json').write_text(json.dumps(failure,indent=2)+'\n');print('FAIL',digest(work/'failure-receipt.json'),str(error),file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
