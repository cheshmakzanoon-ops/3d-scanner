"""External scoped one-diagonal candidate; never edits production or claims quality."""
from pathlib import Path
import argparse,json,os,resource,subprocess,time,sys,signal
sys.dont_write_bytecode=True
from secure import secure_bytes,need,digest,safe

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--config',required=True);ap.add_argument('--work',required=True);a=ap.parse_args();cfg=safe(a.config);c=json.loads(secure_bytes(cfg));draft=Path(__file__).parent;work=Path(a.work);repo=Path(c['repo'])
 need(work.is_absolute() and work.parent.resolve()==work.parent and not work.exists(),'fresh canonical work');work.mkdir(mode=0o700)
 pins={str(cfg):digest(cfg)};commands=[];start=time.monotonic()
 try:
  for path,sha in c['inputSha256'].items():need(digest(safe(path))==sha,'input pin '+path);pins[path]=sha
  for name in ['compiler','cmake','ninja','patch','ldd','originalEngine','originalJni','snapshot','stageCReceipt','stageCCounts','neighborhoodReceipt']:
   need(c[name] in pins,'required pin '+name)
  for name in ['run.py','secure.py','source.patch','SOURCE_PINS.json','snapshot.hpp','baseline_adapter.hpp','candidate_test.cpp','candidate_contract_evidence_test.cpp','support.cpp','replay.cpp']:
   need(str(draft/name) in pins,'draft source pin '+name)
  need(digest(c['originalEngine'])=='4384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b' and digest(c['originalJni'])=='427f2c8c0e21a5647064edcdbe9307265fe493c256c211e4266f979f9931fcce','original binaries')
  stage=json.loads(secure_bytes(c['stageCReceipt']));neighborhood=json.loads(secure_bytes(c['neighborhoodReceipt']));need(stage['status']=='EXACT_ORIGINAL_REPLAY_TELEMETRY_MEASURE_ONLY' and neighborhood['status']=='READ_ONLY_NEIGHBORHOOD_AVAILABILITY_PASS','admitted baseline')
  need(neighborhood['inputSha256'][c['snapshot']]==digest(c['snapshot']) and stage['inputSha256'][c['snapshot']]==digest(c['snapshot']) and stage['outputSha256']['trace-counts.json']==digest(c['stageCCounts']) and stage['depthIdentity']==c['depthIdentity'],'same frozen snapshot/counters')
  source=work/'source';source.mkdir();expected={}
  for relative,sha in c['repoSourceSha256'].items():
   rel=Path(relative);need(not rel.is_absolute() and '..' not in rel.parts and (relative=='CMakeLists.txt' or relative.startswith('reconstruction/')),'source scope');path=repo/rel;need(pins[str(path)]==sha,'native source pin');target=source/rel;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(secure_bytes(path));expected[relative]=sha
  env={'PATH':'/usr/bin:/bin','HOME':os.environ['HOME'],'LC_ALL':'C'}
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
   left=480-(time.monotonic()-start);budget=min(timeout,left);need(budget>=5,'insufficient candidate deadline for bounded cleanup');record={'name':name,'argv':cmd,'timeoutSeconds':timeout};commands.append(record)
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
  run('apply-private-patch',[c['patch'],'--batch','--forward','--fuzz=0','--no-backup-if-mismatch','-p1','--directory',str(source),'--input',str(draft/'source.patch')],15)
  delta=json.loads(secure_bytes(draft/'SOURCE_PINS.json'));expected[delta['baselineHeader']]=delta['candidateHeaderSha256'];need(expected=={str(p.relative_to(source)):digest(p) for p in source.rglob('*') if p.is_file()},'only reviewed native header changed')
  build=work/'build';run('configure',[c['cmake'],'-S',str(source),'-B',str(build),'-G','Ninja','-DCMAKE_MAKE_PROGRAM='+c['ninja'],'-DCMAKE_CXX_COMPILER='+c['compiler'],'-DCMAKE_BUILD_TYPE=Release','-DCMAKE_CXX_FLAGS=-Werror','-DRF_REQUIRE_CAMPAIGN_031_060=ON','-DRF_SANITIZE=OFF','-DRF_EXPERIMENTAL_VULKAN=OFF'],30)
  run('build',[c['cmake'],'--build',str(build),'--target','realityforge','rf_surface_tests','rf_surface_fusion_tests','--parallel','2'],120)
  engine=build/'librealityforge.so';linkage=run('engine-linkage',[c['ldd'],'-r',str(engine)],15);need('undefined symbol:' not in linkage and 'not found' not in linkage,'candidate engine linkage')
  for target in ['rf_surface_tests','rf_surface_fusion_tests']:run(target,[str(build/target)],30)
  def compile_exe(label,filename):
   exe=work/label;run('compile-'+label,[c['compiler'],'-std=c++20','-O3','-DNDEBUG','-UNDEBUG','-Werror','-I'+str(source/'reconstruction/include'),'-I'+str(draft),str(draft/filename),'-L'+str(build),'-lrealityforge','-Wl,-z,defs','-Wl,-rpath,'+str(build),'-o',str(exe)],60)
   linkage=run('linkage-'+label,[c['ldd'],'-r',str(exe)],15);need(str(engine) in linkage and 'undefined symbol:' not in linkage and 'not found' not in linkage,'exact private candidate linkage');return exe
  guard=compile_exe('candidate-guards','candidate_test.cpp');guard_summary=json.loads(run('candidate-guards',[str(guard)],30));need(guard_summary['status']=='PASS_CANDIDATE_SYNTHETIC_GUARDS_ONLY','candidate synthetic guards')
  contract=compile_exe('candidate-contract','candidate_contract_evidence_test.cpp');run('candidate-contract',[str(contract)],30)
  support=compile_exe('candidate-support','support.cpp');support_file=work/'support.json';run('candidate-support',[str(support),c['snapshot'],c['depthIdentity'],str(support_file)],60)
  support_data=json.loads(secure_bytes(support_file,12*1024*1024));rows=support_data['rows_source_camera_x_y_originalValidity_candidateValidity_eligible_completeNine'];baseline=json.loads(secure_bytes(c['stageCCounts']))['sampleRows_sourceId_camera_x_y_normalValidity_normalSupported_visits_passed_rejectMask_outcome']
  need(len(rows)==len(baseline) and all(row[:5]==old[:5] for row,old in zip(rows,baseline)),'actual original per-sample validity parity')
  need(support_data['completeNineBitIdentical']==int(neighborhood['aggregates']['depthPresentMaskHistogram']['511']) and support_data['eligibleOneDiagonal']==neighborhood['aggregates']['centerCardinalsPresent_missingDiagonals_allObservedConfidenceAndJumpPass']['1'],'exact measured full-nine/one-diagonal counts')
  replay=compile_exe('candidate-replay','replay.cpp');run('candidate-replay',[str(replay),c['snapshot'],c['depthIdentity'],str(c['pixelStride']),str(work/'candidate')],60)
  need(pins=={path:digest(safe(path)) for path in pins},'input mutated');need(expected=={str(p.relative_to(source)):digest(p) for p in source.rglob('*') if p.is_file()},'private source mutated')
  mesh=json.loads(secure_bytes(work/'candidate/mesh.json',12*1024*1024));summary={k:v for k,v in mesh.items() if k not in ['vertices','triangles']}
  receipt={'status':'EXPERIMENT_ONLY_ONE_MISSING_DIAGONAL_REPLAY_READY_NOT_QUALITY_ACCEPTED','productionChanged':False,'qualityAccepted':False,'elapsedSeconds':time.monotonic()-start,'inputSha256':pins,'commands':commands,'privateSourceSha256':expected,'candidateEngineSha256':digest(engine),'syntheticGuards':guard_summary,'supportSummary':{k:v for k,v in support_data.items() if not k.startswith('rows_')},'meshSummary':summary,'outputSha256':{str(p.relative_to(work)):digest(p) for p in work.rglob('*') if p.is_file() and not str(p.relative_to(work)).startswith(('source/','build/'))},'scope':'Only one missing finite-zero diagonal; original nine-observed evidence bit identity checked, but geometry intentionally may differ. No quality/shipping acceptance.','originalEvidenceTestPolicyConflict':'Original surface_evidence_test.cpp one-missing-diagonal rejection assertion is intentionally replaced ONLY in separate experimental candidate-contract test, not claimed unchanged original-suite PASS.'}
  (work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print('PASS',digest(work/'receipt.json'))
 except BaseException as error:
  post={}
  for path,expected_sha in pins.items():
   try:actual=digest(safe(path));post[path]={'expected':expected_sha,'actual':actual,'unchanged':actual==expected_sha}
   except Exception as check_error:post[path]={'expected':expected_sha,'unchanged':False,'error':str(check_error)}
  failure={'status':'ONE_DIAGONAL_EXPERIMENT_FAIL_CLOSED','errorType':type(error).__name__,'error':str(error),'elapsedSeconds':time.monotonic()-start,'commands':commands,'postInputChecks':post,'allKnownInputsUnchanged':all(item['unchanged'] for item in post.values()),'qualityAccepted':False,'productionChanged':False}
  (work/'failure-receipt.json').write_text(json.dumps(failure,indent=2)+'\n');print('FAIL',digest(work/'failure-receipt.json'),str(error),file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
