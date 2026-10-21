"""External telemetry comparison. DRAFT: no run until root approves frozen inputs/recipe."""
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

def verify_geometry_then_load_counts(original,traced,hostBits,hostPly,result,traceCounts):
 for name in ['mesh.bits','mesh.ply','mesh.json']:
  need(secure_bytes(original/name)==secure_bytes(traced/name),'original-v-traced exact '+name+' mismatch')
 need(secure_bytes(original/'mesh.bits')==secure_bytes(hostBits),'host-v-original raw bits mismatch')
 need(secure_bytes(original/'mesh.ply')==secure_bytes(hostPly),'host-v-original PLY mismatch')
 need(json.loads(secure_bytes(original/'mesh.json'))==result['meshPreview'],'host-v-original complete JSON mismatch')
 # Counter bytes are not read until all three-way geometry bindings succeed.
 return json.loads(secure_bytes(traceCounts))

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--config',required=True);ap.add_argument('--work',required=True);a=ap.parse_args();cfgpath=safe(a.config);c=json.loads(secure_bytes(cfgpath));work=Path(a.work)
 need(work.is_absolute() and work.parent.resolve()==work.parent and not work.exists(),'fresh work path');work.mkdir(mode=0o700)
 pins={str(cfgpath):digest(cfgpath)};commands=[];start=time.monotonic()
 try:
  for path,sha in c['inputSha256'].items():need(digest(safe(path))==sha,'input pin');pins[path]=sha
  for name in ['originalEngine','tracedEngine','originalJni','captureJni','compiler','ldd','driver','snapshotHeader','snapshot','hostMeshBits','hostMeshPly','hostResultJson','captureManifest','captureReceipt','hostBeforeMaps','hostAfterMaps','stageAReceipt','shimReceipt']:
   need(c[name] in pins,'required pin '+name)
  need(str(Path(__file__)) in pins,'comparison runner pin');need(all(str(p) in pins for p in Path(c['nativeInclude']).rglob('*.hpp')),'native header pins')
  original=safe(c['originalEngine']);traced=safe(c['tracedEngine'])
  need(digest(original)=='4384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b','original engine identity')
  need(digest(c['originalJni'])=='427f2c8c0e21a5647064edcdbe9307265fe493c256c211e4266f979f9931fcce','immutable original JNI')
  manifest=json.loads(secure_bytes(c['captureManifest']));result=json.loads(secure_bytes(c['hostResultJson']));identity=result['densePreview']['depthCheckpointIdentity'];stride=result['densePreview']['pixelStride']
  need(identity==manifest['depthCheckpointIdentity']==c['depthIdentity'] and stride==c['pixelStride']==3,'actual capture identity/stride')
  for name,key in [('result.json','hostResultJson'),('mesh.ply','hostMeshPly')]:
   data=secure_bytes(c[key],12*1024*1024);entry=manifest['artifacts'][name];need(entry['sha256']==hashlib.sha256(data).hexdigest() and entry['bytes']==len(data),'host manifest binding '+name)
  trace=manifest['nativeTrace'];need(trace['depthCheckpointIdentity']==identity and trace['pixelStride']==stride,'nativeTrace identity')
  need(trace['engineSha256']==digest(traced) and trace['jniSha256']==digest(c['captureJni']),'nativeTrace native identity')
  for name,key in [('input.rffuse','snapshot'),('live-mesh.bits','hostMeshBits')]:
   data=secure_bytes(c[key],12*1024*1024);entry=trace['artifacts'][name];need(entry['sha256']==hashlib.sha256(data).hexdigest() and entry['bytes']==len(data),'nativeTrace artifact binding '+name)
  stage=json.loads(secure_bytes(c['stageAReceipt']));shim=json.loads(secure_bytes(c['shimReceipt']));need(stage['status']=='STAGE_A_BUILD_GUARDS_ONLY_PASS' and stage['tracedEngineSha256']==digest(traced),'StageA binding');need(shim['status']=='SHIM_BUILD_LINKAGE_PASS_NOT_JVM_TESTED' and shim['newShimSha256']==digest(c['captureJni']) and shim['tracedEngineSha256']==digest(traced),'shim binding')
  capture=json.loads(secure_bytes(c['captureReceipt']))
  need(capture['outcome']=='PASS' and capture['status']=='EXIT_0' and capture['restoration']['restored'] is True and capture['restoration']['trackedHashesUnchanged'] is True and capture['inputHashesUnchanged'] is True and capture['compiledInputsUnchanged'] is True and capture['postcheckErrors']==[] and capture['postGitStatus']=='','completed restored capture')
  need(capture['qualityCapture']['manifestSha256']==digest(c['captureManifest']) and capture['qualityCapture']['depthCheckpointIdentity']==identity,'terminal capture manifest binding')
  need(capture['nativeTrace']==trace,'terminal capture nativeTrace binding')
  mappings=trace['loadedLibraries'];mapped_identities=[];pids=[]
  need(json.loads(secure_bytes(c['hostBeforeMaps'],1024*1024))==mappings['beforeCore'] and json.loads(secure_bytes(c['hostAfterMaps'],1024*1024))==mappings['afterCore'],'actual map receipt files')
  for phase in ['beforeCore','afterCore']:
   evidence=mappings[phase];need(evidence['schema']==1 and evidence['status']=='VERIFIED_ACTUAL_JVM_NATIVE_MAPPINGS' and evidence['source']=='/proc/self/maps','actual JVM maps proof')
   need(evidence['relevantMappingsStable'] is True and evidence['nativeFilesPrePostStable'] is True and evidence['loaderEnvironmentOverrides'] is False,'stable native maps proof');pids.append(evidence['jvmPid'])
   libraries=evidence['libraries'];need(len(libraries)==2 and {row['role'] for row in libraries}=={'engine','jni'},'exact mapped library roles');identities={}
   for row in libraries:
    expected=traced if row['role']=='engine' else Path(c['captureJni']);need(row['path']==str(expected) and row['sha256']==digest(expected) and row['bytes']==len(secure_bytes(expected)) and row['linkCount']==1 and row['uid']==evidence['uid'],'mapped library file binding')
    segments=row['segments'];need(segments and any(segment['permissions'][2]=='x' for segment in segments),'executable mapping required')
    for segment in segments:
     need(segment['path']==row['path'] and (segment['deviceMajor'],segment['deviceMinor'],segment['inode'])==(row['deviceMajor'],row['deviceMinor'],row['inode']),'mapped segment identity')
    identities[row['role']]=(row['path'],row['sha256'],row['device'],row['deviceMajor'],row['deviceMinor'],row['inode'],row['uid'],row['bytes'])
   mapped_identities.append(identities)
  need(pids[0]==pids[1] and pids[0]>0 and mapped_identities[0]==mapped_identities[1],'same JVM/native files across core call')
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
  def run(name,cmd,timeout,extra=None):
   left=300-(time.monotonic()-start);budget=min(timeout,left);need(budget>=5,'insufficient StageC deadline for bounded cleanup');record={'name':name,'argv':cmd,'timeoutSeconds':timeout};commands.append(record)
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
  traceCounts=work/'trace-counts.json'
  for label,lib in [('original',original),('traced',traced)]:
   exe=work/(label+'-replay');run('compile-'+label,[c['compiler'],'-std=c++20','-O3','-DNDEBUG','-Werror','-I'+c['nativeInclude'],'-I'+str(Path(c['snapshotHeader']).parent),c['driver'],'-L'+str(lib.parent),'-lrealityforge','-Wl,-z,defs','-Wl,-rpath,'+str(lib.parent),'-o',str(exe)],60)
   linkage=run('linkage-'+label,[c['ldd'],'-r',str(exe)],15);need(str(lib) in linkage and 'undefined symbol:' not in linkage,'wrong native linkage')
   run('replay-'+label,[str(exe),c['snapshot'],identity,str(stride),str(work/label)],60,{'RF_TRACE_COUNTS':str(traceCounts)} if label=='traced' else {})
  counts=verify_geometry_then_load_counts(work/'original',work/'traced',Path(c['hostMeshBits']),Path(c['hostMeshPly']),result,traceCounts)
  need(counts['status']=='UNVERIFIED_UNTIL_ORIGINAL_RAW_MESH_EQUALITY','raw telemetry status')
  rows=counts['sampleRows_sourceId_camera_x_y_normalValidity_normalSupported_visits_passed_rejectMask_outcome'];need([r[0] for r in rows]==list(range(len(rows))),'sample ID ordering')
  need(sum(r[6] for r in rows)==counts['candidateVisits'] and sum(r[7] for r in rows)==counts['candidatePassedAllGates'],'row candidate accounting')
  need(counts['candidateVisits']==sum(counts['candidateFirstReject'].values())+counts['candidatePassedAllGates'],'first-reject accounting')
  need(counts['outcomeUnprocessedLowConfidenceDuplicatePixelVertexBudgetFusedNewAccumulator'][4]==result['meshPreview']['fusedObservations'],'final fusion accounting')
  need(pins=={p:digest(safe(p)) for p in pins},'input changed')
  receipt={'status':'EXACT_ORIGINAL_REPLAY_TELEMETRY_MEASURE_ONLY','causalQualityClaim':False,'elapsedSeconds':time.monotonic()-start,'depthIdentity':identity,'commands':commands,'inputSha256':pins,'outputSha256':{str(p.relative_to(work)):digest(p) for p in work.rglob('*') if p.is_file()},'counts':counts,'limitations':['Three-way exact raw logical mesh bits, native PLY and complete JSON must match before counters are read.','First rejection preserves original branch order and does not establish a bad guard.','No neighborhood support, threshold or production algorithm changed.','Named source/tool and compiler dependency pins do not claim complete OS/linker closure.']}
  (work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print('PASS',digest(work/'receipt.json'))
 except BaseException as error:
  post={}
  for path,expected_sha in pins.items():
   try:actual=digest(safe(path));post[path]={'expected':expected_sha,'actual':actual,'unchanged':actual==expected_sha}
   except Exception as check_error:post[path]={'expected':expected_sha,'unchanged':False,'error':str(check_error)}
  failure={'status':'STAGE_C_FAIL_CLOSED','errorType':type(error).__name__,'error':str(error),'elapsedSeconds':time.monotonic()-start,'commands':commands,'postInputChecks':post,'allKnownInputsUnchanged':all(item['unchanged'] for item in post.values()),'telemetryNotAccepted':True}
  (work/'failure-receipt.json').write_text(json.dumps(failure,indent=2)+'\n');print('FAIL',digest(work/'failure-receipt.json'),str(error),file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
