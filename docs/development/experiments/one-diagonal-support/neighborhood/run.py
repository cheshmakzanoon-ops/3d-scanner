"""Read-only actual snapshot neighborhood diagnostic; no alternative normals or mesh."""
from pathlib import Path
import argparse,json,os,resource,subprocess,time,sys,signal,collections,math
sys.dont_write_bytecode=True
from secure import secure_bytes,need,digest,safe

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--config',required=True);ap.add_argument('--work',required=True);a=ap.parse_args();cfg=safe(a.config);c=json.loads(secure_bytes(cfg));work=Path(a.work)
 need(work.is_absolute() and work.parent.resolve()==work.parent and not work.exists(),'fresh canonical work');work.mkdir(mode=0o700)
 pins={str(cfg):digest(cfg)};commands=[];start=time.monotonic()
 try:
  for path,sha in c['inputSha256'].items():need(digest(safe(path))==sha,'input pin '+path);pins[path]=sha
  for name in ['compiler','ldd','originalEngine','reader','snapshotHeader','snapshot','stageCReceipt','stageCCounts']:
   need(c[name] in pins,'required pin '+name)
  need(str(Path(__file__)) in pins and str(Path(__file__).parent/'secure.py') in pins,'runner pins')
  need(all(str(p) in pins for p in Path(c['nativeInclude']).rglob('*.hpp')),'native header pins')
  need(digest(c['originalEngine'])=='4384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b','immutable original engine')
  stage=json.loads(secure_bytes(c['stageCReceipt']));need(stage['status']=='EXACT_ORIGINAL_REPLAY_TELEMETRY_MEASURE_ONLY' and stage['depthIdentity']==c['depthIdentity'],'admitted StageC input')
  need(stage['inputSha256'][c['snapshot']]==digest(c['snapshot']) and stage['outputSha256']['trace-counts.json']==digest(c['stageCCounts']),'exact admitted snapshot/counters')
  counts=json.loads(secure_bytes(c['stageCCounts'],12*1024*1024));baseline=counts['sampleRows_sourceId_camera_x_y_normalValidity_normalSupported_visits_passed_rejectMask_outcome']
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
   left=180-(time.monotonic()-start);budget=min(timeout,left);need(budget>=5,'insufficient neighborhood deadline for bounded cleanup');record={'name':name,'argv':cmd,'timeoutSeconds':timeout};commands.append(record)
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
  engine=Path(c['originalEngine']);exe=work/'inspect';out=work/'neighborhoods.json'
  run('compile',[c['compiler'],'-std=c++20','-O3','-DNDEBUG','-Werror','-I'+c['nativeInclude'],'-I'+str(Path(c['snapshotHeader']).parent),c['reader'],'-L'+str(engine.parent),'-lrealityforge','-Wl,-z,defs','-Wl,-rpath,'+str(engine.parent),'-o',str(exe)],60)
  linkage=run('linkage',[c['ldd'],'-r',str(exe)],15);need(str(engine) in linkage and 'undefined symbol:' not in linkage,'original engine linkage')
  run('inspect',[str(exe),c['snapshot'],c['depthIdentity'],str(c['pixelStride']),str(out)],60)
  data=json.loads(secure_bytes(out,12*1024*1024));rows=[[v[k] for k in ['sourceSampleId','camera','x','y','baselineNormalValidity','inBoundsMask','finitePositiveDepthMask','positiveFiniteConfidenceMask','relativeJumpWithinOriginalThresholdMask','relativeJump']] for v in data['samples']];need(data['schema']==1 and data['depthIdentity']==c['depthIdentity'] and data['pixelStride']==c['pixelStride'],'reader identity')
  need(data['snapshotSha256']==digest(c['snapshot']) and data['centerMask']==16 and data['cardinalAndCenterMask']==186 and data['diagonalMask']==325 and data['allNineMask']==511,'snapshot/mask schema binding')
  need(len(rows)==len(baseline)==data['sampleCount'] and all(row[:5]==old[:5] for row,old in zip(rows,baseline)),'per-sample original normal validity parity')
  masks=collections.Counter();patterns=collections.Counter();eligible=collections.Counter();eligible_outcomes=collections.Counter();validity=[0]*5;early={key:0 for key in counts['normalEarlyReturn']};jumps=[]
  for row,old in zip(rows,baseline):
   need(len(row)==10 and all(type(v)==int and 0<=v<=511 for v in row[5:9]),'row masks');inside,depth,confidence,jump=row[5:9];values=row[9];need(len(values)==9,'nine jump values')
   need(depth & ~inside==0 and confidence & ~inside==0 and jump & ~depth==0,'mask containment');validity[row[4]]+=1;masks[depth]+=1
   missing_cardinals=4-(depth & 170).bit_count();missing_diagonals=4-(depth & 325).bit_count();patterns[(bool(depth & 16),missing_cardinals,missing_diagonals)]+=1
   for index,value in enumerate(values):
    expected=bool(depth & (1<<index) and depth & 16);need((value is not None)==expected,'jump availability')
    if value is not None:
     need(type(value) in [float,int] and math.isfinite(value) and value>=0,'finite observed jump');need(bool(jump & (1<<index))==(value<=data['maximumRelativeDepthJump']),'jump threshold consistency')
     if index!=4:jumps.append(value)
   reason=None
   if inside!=511:reason='boundary'
   elif not depth & 16:reason='center_unavailable'
   else:
    for index in range(9):
     if not depth & (1<<index):reason='neighbor_nonpositive';break
     if not jump & (1<<index):reason='neighbor_jump';break
    if reason is None and confidence!=511:reason='confidence_unavailable'
   if reason is not None:early[reason]+=1
   # Descriptive availability subset, not an alternative acceptance/fusion policy.
   if inside==511 and depth & 186==186 and depth!=511 and confidence & depth==depth and jump & depth==depth:
    eligible[missing_diagonals]+=1;eligible_outcomes[(missing_diagonals,old[9])]+=1
  need(validity==data['baselineValidityCounts']==counts['normalValiditySupportedSparseDiscontinuityIllConditionedInvalid'],'original validity aggregate parity');need(early==counts['normalEarlyReturn'],'original early-return parity')
  jumps.sort()
  def quantile(q):return jumps[int((len(jumps)-1)*q)] if jumps else None
  aggregates={'sampleCount':len(rows),'perSampleOriginalNormalValidityParity':True,'normalValidity':validity,'originalEarlyReturns':early,'depthPresentMaskHistogram':{str(k):v for k,v in sorted(masks.items())},'availability_centerPresent_missingCardinals_missingDiagonals_count':[[int(k[0]),k[1],k[2],v] for k,v in sorted(patterns.items())],'centerCardinalsPresent_missingDiagonals_allObservedConfidenceAndJumpPass':dict(sorted(eligible.items())),'sameSubset_missingDiagonals_originalOutcome_count':[[k[0],k[1],v] for k,v in sorted(eligible_outcomes.items())],'allObservedNoncenterJumpDistribution':{'count':len(jumps),'min':quantile(0),'p50':quantile(.5),'p90':quantile(.9),'p95':quantile(.95),'p99':quantile(.99),'max':quantile(1),'aboveOriginalThreshold':sum(v>data['maximumRelativeDepthJump'] for v in jumps),'originalThreshold':data['maximumRelativeDepthJump']}}
  need(pins=={path:digest(safe(path)) for path in pins},'input mutated')
  receipt={'status':'READ_ONLY_NEIGHBORHOOD_AVAILABILITY_PASS','noAlternativeNormalOrFusionOrMesh':True,'elapsedSeconds':time.monotonic()-start,'inputSha256':pins,'commands':commands,'aggregates':aggregates,'outputSha256':{p.name:digest(p) for p in work.iterdir() if p.is_file()},'scope':'Same admitted frozen snapshot; descriptive availability only, not evidence relaxing a support guard is safe.'}
  (work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print('PASS',digest(work/'receipt.json'))
 except BaseException as error:
  post={}
  for path,expected_sha in pins.items():
   try:actual=digest(safe(path));post[path]={'expected':expected_sha,'actual':actual,'unchanged':actual==expected_sha}
   except Exception as check_error:post[path]={'expected':expected_sha,'unchanged':False,'error':str(check_error)}
  failure={'status':'NEIGHBORHOOD_READER_FAIL_CLOSED','errorType':type(error).__name__,'error':str(error),'elapsedSeconds':time.monotonic()-start,'commands':commands,'postInputChecks':post,'allKnownInputsUnchanged':all(item['unchanged'] for item in post.values()),'noAlternativeNormalOrFusionOrMesh':True}
  (work/'failure-receipt.json').write_text(json.dumps(failure,indent=2)+'\n');print('FAIL',digest(work/'failure-receipt.json'),str(error),file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
