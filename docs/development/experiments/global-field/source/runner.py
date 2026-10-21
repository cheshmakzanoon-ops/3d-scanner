"""External global-field experiment. SOURCE ONLY: root authorization required for exact freeze."""
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
def safe(p):
 p=Path(p);need(p.is_absolute() and p.resolve()==p,'canonical no-symlink input');need(bool(secure_bytes(p)),'nonempty input');return p

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--config',required=True);ap.add_argument('--work',required=True);a=ap.parse_args();cfgpath=safe(a.config);c=json.loads(secure_bytes(cfgpath));work=Path(a.work)
 need(work.is_absolute() and work.parent==Path('/tmp') and work.name.startswith('relief-global-field-v4-evidence') and not work.exists(),'fresh external /tmp work path required');work.mkdir(mode=0o700)
 pins={str(cfgpath):digest(cfgpath)};commands=[];start=time.monotonic()
 try:
  for path,sha in c['inputSha256'].items():need(digest(safe(path))==sha,'input pin '+path);pins[path]=sha
  for name in ['originalEngine','compiler','ldd','driver','tests','header','snapshotHeader','snapshot','stageCReceipt','baselineBits','baselinePly','baselineJson','parentSupervisor','witnessValidator','python']:
   need(c[name] in pins,'required pin '+name)
  need(str(Path(__file__)) in pins,'runner pin');need(all(str(p) in pins for p in Path(c['nativeInclude']).rglob('*.hpp')),'native header pins')
  original=safe(c['originalEngine']);need(digest(original)=='4384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b','original engine identity')
  stage=json.loads(secure_bytes(c['stageCReceipt']));need(stage['status']=='EXACT_ORIGINAL_REPLAY_TELEMETRY_MEASURE_ONLY' and stage['depthIdentity']==c['depthIdentity'],'validated StageC identity')
  for key,name in [('baselineBits','mesh.bits'),('baselinePly','mesh.ply'),('baselineJson','mesh.json')]:need(stage['outputSha256']['original/'+name]==digest(c[key]),'validated original baseline binding')
  need(stage['inputSha256'][c['snapshot']]==digest(c['snapshot']),'same frozen snapshot');need(c['pixelStride']==3,'unchanged stride')
  env={'PATH':'/usr/bin:/bin','HOME':str(work),'TMPDIR':str(work),'LC_ALL':'C'}
  def limit():
   resource.setrlimit(resource.RLIMIT_AS,(2*1024**3,2*1024**3));resource.setrlimit(resource.RLIMIT_CORE,(0,0));resource.setrlimit(resource.RLIMIT_FSIZE,(32*1024**2,32*1024**2))
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
   q=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,stdin=subprocess.DEVNULL,text=True,env=env | (extra or {}),cwd=work,preexec_fn=limit,start_new_session=True)
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
  for label,key in [('tests','tests'),('replay','driver')]:
   exe=work/label
   run('compile-'+label,[c['compiler'],'-std=c++20','-O2','-fno-fast-math','-ffp-contract=off','-Werror','-I'+c['nativeInclude'],'-I'+str(Path(c['snapshotHeader']).parent),'-I'+str(Path(c['header']).parent),c[key],'-L'+str(original.parent),'-lrealityforge','-Wl,-z,defs','-Wl,-rpath,'+str(original.parent),'-o',str(exe)],60)
   linkage=run('linkage-'+label,[c['ldd'],'-r',str(exe)],15);need(str(original) in linkage and 'undefined symbol:' not in linkage,'wrong native linkage')
  run('synthetic-guards',[str(work/'tests')],60)
  run('independent-witness-primitive-guards',[c['python'],c['witnessValidator'],'--selftest'],15)
  for label in ['candidate','repeat']:
   run('replay-'+label,[str(work/'replay'),c['snapshot'],c['depthIdentity'],str(work/label)],65)
   need((work/label/'SUCCESS').is_file(),'missing completion marker')
   result=json.loads(secure_bytes(work/label/'provenance.json'))
   need(result['representation']=='experimental_interpolated_observed_patch_global_field_v1' and result['depthIdentity']==c['depthIdentity'],'experimental identity')
   need(0<result['vertices']<=40000 and 0<result['faces']<=80000,'nonempty bounded geometry')
   need(result['workspaceBytes']+result['stackAllowanceBytes']<=32*1024**2,'algorithm scratch bound')
  for label in ['candidate','repeat']:
   run('independent-witness-'+label,[c['python'],c['witnessValidator'],c['snapshot'],str(work/label),str(work/(label+'-independent-witness.json'))],30)
  for name in ['experimental.ply','provenance.json','SUCCESS']:need(secure_bytes(work/'candidate'/name)==secure_bytes(work/'repeat'/name),'determinism '+name)
  need(pins=={p:digest(safe(p)) for p in pins},'input changed')
  receipt={'status':'EXPERIMENTAL_GLOBAL_FIELD_REPLAY_ONLY_NOT_QUALITY_ACCEPTANCE','causalQualityClaim':False,'elapsedSeconds':time.monotonic()-start,'depthIdentity':c['depthIdentity'],'commands':commands,'inputSha256':pins,'outputSha256':{str(p.relative_to(work)):digest(p) for p in work.rglob('*') if p.is_file()},'candidate':result,'limitations':['No general plane/grid invariance: exact-zero endpoints unsupported and fail closed.','Observed-patch oriented signed-distance field, not projective camera-z TSDF.','Whole-face witness admits bounded proximity, not exact source-plane coincidence.','Independent witness verified; paired quality metrics including vertex-link manifoldness, winding, crossing/coverage and completeness still required before quality interpretation.','Faces oriented toward tetra scalar gradient; independent directed-edge diagnostics reported, no topology guarantee.','Workspace scratch is not process RSS. Named pins do not claim full OS/linker closure.']}
  (work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print('PASS',digest(work/'receipt.json'))
 except BaseException as error:
  post={}
  for path,expected_sha in pins.items():
   try:actual=digest(safe(path));post[path]={'expected':expected_sha,'actual':actual,'unchanged':actual==expected_sha}
   except Exception as check_error:post[path]={'expected':expected_sha,'unchanged':False,'error':str(check_error)}
  failure={'status':'EXPERIMENTAL_GLOBAL_FIELD_FAIL_CLOSED','errorType':type(error).__name__,'error':str(error),'elapsedSeconds':time.monotonic()-start,'commands':commands,'postInputChecks':post,'allKnownInputsUnchanged':all(item['unchanged'] for item in post.values()),'candidateNotAccepted':True}
  (work/'failure-receipt.json').write_text(json.dumps(failure,indent=2)+'\n');print('FAIL',digest(work/'failure-receipt.json'),str(error),file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
