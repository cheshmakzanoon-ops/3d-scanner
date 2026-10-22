# PUBLIC PATH-REDACTED SOURCE; not the exact executed file.
"""Evidence-only stage diagnostic. SOURCE ONLY: root authorization required for exact freeze."""
from pathlib import Path
import argparse,hashlib,json,os,resource,subprocess,time,sys,signal

def need(x,why):
 if not x:raise ValueError(why)
def secure_bytes(path,limit=128*1024*1024):
 path=Path(path);need(path.is_absolute() and str(path)==os.path.normpath(str(path)),'canonical absolute path required')
 fd=os.open('/',os.O_RDONLY|os.O_DIRECTORY|os.O_CLOEXEC)
 try:
  for part in path.parts[1:-1]:
   new=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW|os.O_CLOEXEC,dir_fd=fd);os.close(fd);fd=new
  new=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW|os.O_CLOEXEC,dir_fd=fd);os.close(fd);fd=new
  import stat
  before=os.fstat(fd);need(stat.S_ISREG(before.st_mode) and before.st_nlink==1 and 0<=before.st_size<=limit,'file bound/type')
  chunks=[];left=before.st_size
  while left:
   chunk=os.read(fd,min(left,65536));need(bool(chunk),'truncated read');chunks.append(chunk);left-=len(chunk)
  after=os.fstat(fd);need((before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns,before.st_ctime_ns)==(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns,after.st_ctime_ns),'file changed during read')
  return b''.join(chunks)
 finally:os.close(fd)
def h(f):return hashlib.sha256(secure_bytes(f)).hexdigest()
def regular(path,limit):need(bool(secure_bytes(path,limit)),"empty input");return Path(path)
def digest(p):return hashlib.sha256(secure_bytes(p)).hexdigest()
def safe(p,allow_empty=False):
 p=Path(p);need(p.is_absolute() and p.resolve()==p,'canonical no-symlink input');data=secure_bytes(p);need(bool(data) or allow_empty,'nonempty input');return p

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--config',required=True);ap.add_argument('--work',required=True);a=ap.parse_args();cfgpath=safe(a.config);c=json.loads(secure_bytes(cfgpath));work=Path(a.work)
 need(work.is_absolute() and work.parent==Path(c['approvedScratchParent']) and work.name.startswith('relief-projective-field-v2-diagnosis-evidence') and not work.exists(),'fresh external configured scratch path required');work.mkdir(mode=0o700)
 pins={str(cfgpath):digest(cfgpath)};commands=[];start=time.monotonic();empty=c.get('emptyDependencies',{})
 try:
  semantic={v for v in c.values() if isinstance(v,str)}
  for path,record in empty.items():
   need(path not in semantic and record=={'bytes':0,'sha256':'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'},'explicit empty dependency only')
   need(c['inputSha256'].get(path)==record['sha256'] and len(secure_bytes(path))==0,'empty dependency manifest mismatch')
  for path,sha in c['inputSha256'].items():need(digest(safe(path,path in empty))==sha,'input pin '+path);pins[path]=sha
  for name in ['originalEngine','compiler','ldd','driver','tests','header','snapshotHeader','snapshot','stageCReceipt','baselineBits','baselinePly','baselineJson','parentSupervisor','witnessValidator','python','originalJni','captureManifest','captureReceipt']:
   need(c[name] in pins,'required pin '+name)
  need(str(Path(__file__)) in pins,'runner pin');need(all(str(p) in pins for p in Path(c['nativeInclude']).rglob('*.hpp')),'native header pins')
  original=safe(c['originalEngine']);need(digest(original)=='4384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b','original engine identity')
  need(digest(c['originalJni'])=='427f2c8c0e21a5647064edcdbe9307265fe493c256c211e4266f979f9931fcce','historical unchanged original JNI identity')
  capture=json.loads(secure_bytes(c['captureReceipt']));manifest=json.loads(secure_bytes(c['captureManifest']));trace=manifest['nativeTrace']
  need(capture['outcome']=='PASS' and capture['status']=='EXIT_0' and capture['restoration']['restored'] is True,'historical completed capture')
  need(capture['nativeTrace']==trace and trace['depthCheckpointIdentity']==c['depthIdentity'] and trace['pixelStride']==c['pixelStride'],'historical source trace binding')
  need(trace['artifacts']['input.rffuse']['sha256']==digest(c['snapshot']) and trace['artifacts']['input.rffuse']['bytes']==len(secure_bytes(c['snapshot'])),'frozen source snapshot binding')
  stage=json.loads(secure_bytes(c['stageCReceipt']));need(stage['status']=='EXACT_ORIGINAL_REPLAY_TELEMETRY_MEASURE_ONLY' and stage['depthIdentity']==c['depthIdentity'],'validated StageC identity')
  for key,name in [('baselineBits','mesh.bits'),('baselinePly','mesh.ply'),('baselineJson','mesh.json')]:need(stage['outputSha256']['original/'+name]==digest(c[key]),'validated original baseline binding')
  need(stage['inputSha256'][c['snapshot']]==digest(c['snapshot']),'same frozen snapshot');need(c['pixelStride']==3,'unchanged stride')
  env={'PATH':c['toolSearchPath'],'HOME':str(work),'TMPDIR':str(work),'LC_ALL':'C','PYTHONDONTWRITEBYTECODE':'1','OPENBLAS_NUM_THREADS':'1','OMP_NUM_THREADS':'1','MKL_NUM_THREADS':'1','NUMEXPR_NUM_THREADS':'1'}
  def limit():
   resource.setrlimit(resource.RLIMIT_CPU,(120,120));resource.setrlimit(resource.RLIMIT_AS,(2*1024**3,2*1024**3));resource.setrlimit(resource.RLIMIT_CORE,(0,0));resource.setrlimit(resource.RLIMIT_FSIZE,(32*1024**2,32*1024**2))
  def group_members(pgid):
   members=[]
   for entry in Path('/proc').iterdir():
    if not entry.name.isdigit():continue
    try:
     value=(entry/'stat').read_text();fields=value[value.rfind(')')+2:].split()
     if int(fields[2])==pgid and fields[0]!='Z':members.append(int(entry.name))
    except FileNotFoundError:pass
   return members
  def run(name,cmd,timeout,extra=None):
   left=300-(time.monotonic()-start);budget=min(timeout,left);need(budget>=5,'insufficient experiment deadline for bounded cleanup');record={'name':name,'argv':cmd,'timeoutSeconds':timeout};commands.append(record)
   deadline=time.monotonic()+budget
   def command_limit():
    limit()
    if cmd[0]==c['auditPython']:resource.setrlimit(resource.RLIMIT_AS,(1024**3,1024**3));resource.setrlimit(resource.RLIMIT_CPU,(60,60))
   q=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,stdin=subprocess.DEVNULL,text=True,env=env | (extra or {}),cwd=work,preexec_fn=command_limit,start_new_session=True)
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
  cache=work/'empty-pycache';cache.mkdir(mode=0o700);env['PYTHONPYCACHEPREFIX']=str(cache)
  for key in ['diagnosticDriver','diagnosticTests','observerHeader','copiedPredicates','serializer','audit','auditPython','originalReplay','oldReceipt','oldCandidatePly','oldCandidateProvenance','oldCandidateCounters','oldCandidateSuccess']:
   need(c[key] in pins,'required diagnostic pin '+key)
  old=json.loads(secure_bytes(c['oldReceipt']))
  need(old['status']=='EXPERIMENTAL_PROJECTIVE_FIELD_REPLAY_ONLY_NOT_QUALITY_ACCEPTANCE' and len(old['inputSha256'])==424,'original replay binding')
  need(all(p in pins and pins[p]==h for p,h in old['inputSha256'].items()),'all 424 original pins retained')
  for label,key in [('observer-tests','diagnosticTests'),('observer','diagnosticDriver')]:
   exe=work/label
   run('compile-'+label,[c['compiler'],'-std=c++20','-O2','-fno-fast-math','-ffp-contract=off','-Werror','-I'+c['nativeInclude'],'-I'+str(Path(c['snapshotHeader']).parent),'-I'+str(Path(c['header']).parent),c[key],'-L'+str(original.parent),'-lrealityforge','-Wl,-z,defs','-Wl,-rpath,'+str(original.parent),'-o',str(exe)],60)
   linkage=run('linkage-'+label,[c['ldd'],'-r',str(exe)],15);need(str(original) in linkage and 'undefined symbol:' not in linkage,'wrong native linkage')
  # Existing immutable binary parity, then synthetic observer guards before actual diagnostic.
  run('original-parity',[c['originalReplay'],c['snapshot'],c['depthIdentity'],str(work/'original-parity')],65)
  artifacts={'experimental.ply':'oldCandidatePly','provenance.json':'oldCandidateProvenance','counters.json':'oldCandidateCounters','SUCCESS':'oldCandidateSuccess'}
  for name,key in artifacts.items():
   need(secure_bytes(work/'original-parity'/name)==secure_bytes(c[key]),'INVALID_PARITY original '+name)
   need(digest(c[key])==old['outputSha256']['candidate/'+name]==old['outputSha256']['repeat/'+name],'old repeated artifact binding '+name)
  run('synthetic-observer-guards',[str(work/'observer-tests')],60)
  run('synthetic-indexed-fan-guards',[c['auditPython'],'-B','-X','pycache_prefix='+str(cache),c['audit'],'--selftest'],15)
  run('diagnostic',[str(work/'observer'),c['snapshot'],c['depthIdentity'],str(work/'diagnostic')],124)
  for name,key in artifacts.items():need(secure_bytes(work/'diagnostic'/'authoritative'/name)==secure_bytes(c[key]),'INVALID_PARITY observer '+name)
  counts=json.loads(secure_bytes(work/'diagnostic'/'diagnostic.json'))
  need(counts['status']=='DIAGNOSTIC_COMPLETE_REQUIRES_EXTERNAL_PARITY','diagnostic completion')
  need(counts['scratchBytes']+counts['stackAllowanceBytes']<=64*1024**2,'diagnostic scratch bound')
  run('indexed-stage-audit',[c['auditPython'],'-B','-X','pycache_prefix='+str(cache),c['audit'],str(work/'diagnostic'),str(work/'stage-audit.json')],60)
  need(pins=={p:digest(safe(p,p in empty)) for p in pins},'input changed')
  receipt={'status':'EVIDENCE_ONLY_DIAGNOSIS_COMPLETE_EXACT_V2_PARITY_NOT_QUALITY_ACCEPTANCE','parityArtifacts':list(artifacts),'all424OriginalPinsRetained':True,'elapsedSeconds':time.monotonic()-start,'commands':commands,'inputSha256':pins,'outputSha256':{str(p.relative_to(work)):digest(p) for p in work.rglob('*') if p.is_file()},'diagnostic':counts,'stageAudit':json.loads(secure_bytes(work/'stage-audit.json')),'limitations':['Counterfactual four-corner eligibility is counts only, not new extracted geometry or recovered coverage.','Exhaustive witness search is exact implemented single-patch predicate, not physical absence of surface evidence.','Indexed link transitions are associated with original filtering, not a universal causal repair proof.','Diagnostic scratch budget64MiB is separate from original32MiB; neither is process RSS.','No changed truth, thresholds, field integration, source masks or production output.']}
  (work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print('PASS',digest(work/'receipt.json'))
 except BaseException as error:
  post={}
  for path,expected_sha in pins.items():
   try:actual=digest(safe(path,path in empty));post[path]={'expected':expected_sha,'actual':actual,'unchanged':actual==expected_sha}
   except Exception as check_error:post[path]={'expected':expected_sha,'unchanged':False,'error':str(check_error)}
  failure={'status':'EVIDENCE_ONLY_DIAGNOSIS_FAIL_CLOSED_COUNTS_UNKNOWN','errorType':type(error).__name__,'error':str(error),'elapsedSeconds':time.monotonic()-start,'commands':commands,'postInputChecks':post,'allKnownInputsUnchanged':all(item['unchanged'] for item in post.values()),'candidateNotAccepted':True}
  (work/'failure-receipt.json').write_text(json.dumps(failure,indent=2)+'\n');print('FAIL',digest(work/'failure-receipt.json'),str(error),file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
