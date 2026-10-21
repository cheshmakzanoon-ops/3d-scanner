"""Read-only exact native eligibility plus structural source-camera edge classification."""
from pathlib import Path
import argparse,json,os,resource,subprocess,time,sys,signal
sys.dont_write_bytecode=True
from secure import secure_bytes,need,digest,safe

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--config',required=True);ap.add_argument('--work',required=True);a=ap.parse_args();cfg=safe(a.config);c=json.loads(secure_bytes(cfg));work=Path(a.work);draft=Path(__file__).parent
 need(work.is_absolute() and work.parent.resolve()==work.parent and not work.exists(),'fresh canonical work');work.mkdir(mode=0o700)
 pins={str(cfg):digest(cfg)};commands=[];start=time.monotonic()
 try:
  for path,sha in c['inputSha256'].items():need(digest(safe(path))==sha,'input pin '+path);pins[path]=sha
  for key in ['compiler','ldd','python','originalEngine','stageCReceipt','stageCCounts']:
   need(c[key] in pins,'required pin '+key)
  for name in ['run.py','secure.py','inspect.py','test_inspect.py','classify.py','topology_filter.cpp','snapshot.hpp']:need(str(draft/name) in pins,'source pin '+name)
  need(all(str(p) in pins for p in Path(c['nativeInclude']).rglob('*.hpp')),'native header pins')
  need(digest(c['originalEngine'])=='4384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b','original engine')
  stage=json.loads(secure_bytes(c['stageCReceipt']));need(stage['status']=='EXACT_ORIGINAL_REPLAY_TELEMETRY_MEASURE_ONLY' and stage['outputSha256']['trace-counts.json']==digest(c['stageCCounts']),'admitted sample rows')
  need(set(c['meshes'])=={'original','candidate'},'exact comparison meshes')
  for label,m in c['meshes'].items():
   need(label in ['original','candidate'] and all(m[k] in pins for k in ['receipt','bits','json']),'mesh input pins');receipt=json.loads(secure_bytes(m['receipt']));need(receipt['status']==m['expectedStatus'] and receipt['inputSha256'][c['snapshot']]==pins[c['snapshot']],'admitted same-input mesh')
  env={'PATH':'/usr/bin:/bin','HOME':os.environ['HOME'],'LC_ALL':'C','PYTHONDONTWRITEBYTECODE':'1'}
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
   left=180-(time.monotonic()-start);budget=min(timeout,left);need(budget>=5,'insufficient edge classification deadline for bounded cleanup');record={'name':name,'argv':cmd,'timeoutSeconds':timeout};commands.append(record)
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
  run('structural-guard-tests',[c['python'],str(draft/'test_inspect.py')],30)
  exe=work/'native-edge-eligibility';engine=Path(c['originalEngine'])
  run('compile',[c['compiler'],'-std=c++20','-O3','-DNDEBUG','-Werror','-I'+c['nativeInclude'],'-I'+str(draft),str(draft/'topology_filter.cpp'),'-L'+str(engine.parent),'-lrealityforge','-Wl,-z,defs','-Wl,-rpath,'+str(engine.parent),'-o',str(exe)],60)
  linkage=run('linkage',[c['ldd'],'-r',str(exe)],15);need(str(engine) in linkage and 'undefined symbol:' not in linkage and 'not found' not in linkage,'exact original native linkage')
  results={}
  for label,m in c['meshes'].items():
   proof=work/(label+'-native-eligibility.json');out=work/(label+'-edges.json');run(label+'-native-eligibility',[str(exe),m['bits'],str(proof)],60)
   run(label+'-classify',[c['python'],str(draft/'classify.py'),'--receipt',m['receipt'],'--receipt-sha',pins[m['receipt']],'--bits',m['bits'],'--mesh-json',m['json'],'--bits-key',m['bitsKey'],'--json-key',m['jsonKey'],'--counts',c['stageCCounts'],'--proof',str(proof),'--out',str(out)],60)
   result=json.loads(secure_bytes(out,12*1024*1024));results[label]={'aggregates':result['aggregates'],'rawEdgeRowsSha256':digest(out),'nativeEligibilityProofSha256':digest(proof)}
  need(pins=={path:digest(safe(path)) for path in pins},'input mutated')
  receipt={'status':'STRUCTURAL_EDGE_PROVENANCE_MEASURE_ONLY','noGeometryMutation':True,'noCrossingClassification':True,'elapsedSeconds':time.monotonic()-start,'inputSha256':pins,'commands':commands,'meshes':results,'outputSha256':{p.name:digest(p) for p in work.iterdir() if p.is_file()},'scope':'Native zero-intersection-budget run supplies exact face eligibility and edge totals only. It does not replace earlier complete crossing diagnostics. Camera refs are native references, not guessed photo IDs.'}
  (work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print('PASS',digest(work/'receipt.json'))
 except BaseException as error:
  post={}
  for path,expected_sha in pins.items():
   try:actual=digest(safe(path));post[path]={'expected':expected_sha,'actual':actual,'unchanged':actual==expected_sha}
   except Exception as check_error:post[path]={'expected':expected_sha,'unchanged':False,'error':str(check_error)}
  failure={'status':'EDGE_PROVENANCE_FAIL_CLOSED','errorType':type(error).__name__,'error':str(error),'elapsedSeconds':time.monotonic()-start,'commands':commands,'postInputChecks':post,'allKnownInputsUnchanged':all(item['unchanged'] for item in post.values()),'noGeometryMutation':True,'noCrossingClassification':True}
  (work/'failure-receipt.json').write_text(json.dumps(failure,indent=2)+'\n');print('FAIL',digest(work/'failure-receipt.json'),str(error),file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
