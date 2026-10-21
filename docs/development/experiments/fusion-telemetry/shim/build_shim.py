"""Private original-source JNI shim build only; no JVM/capture/replay."""
from pathlib import Path
import argparse,json,os,resource,subprocess,time,sys,signal,re,shlex
sys.dont_write_bytecode=True
from secure import secure_bytes,need,digest,safe

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--config',required=True);ap.add_argument('--work',required=True);a=ap.parse_args();cfg=safe(a.config);c=json.loads(secure_bytes(cfg));work=Path(a.work)
 need(work.is_absolute() and work.parent.resolve()==work.parent and not work.exists(),'fresh canonical work');work.mkdir(mode=0o700)
 pins={str(cfg):digest(cfg)};commands=[];start=time.monotonic()
 try:
  for path,sha in c['inputSha256'].items():need(digest(safe(path))==sha,'frozen input pin '+path);pins[path]=sha
  for name in ['compiler','readelf','nm','ldd','originalEngine','originalJni','tracedEngine','jniSource','stageAReceipt']:
   need(c[name] in pins,'required pin '+name)
  need(str(Path(__file__)) in pins and str(Path(__file__).parent/'secure.py') in pins,'runner pins')
  stage=json.loads(secure_bytes(c['stageAReceipt']));need(stage['status']=='STAGE_A_BUILD_GUARDS_ONLY_PASS' and stage['tracedEngineSha256']==digest(c['tracedEngine']),'StageA binding')
  need(digest(c['originalEngine'])=='4384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b','original engine')
  need(digest(c['originalJni'])=='427f2c8c0e21a5647064edcdbe9307265fe493c256c211e4266f979f9931fcce','original shim')
  env={'PATH':'/usr/bin:/bin','LC_ALL':'C','HOME':os.environ['HOME'],'PYTHONDONTWRITEBYTECODE':'1'}
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
   left=120-(time.monotonic()-start);budget=min(timeout,left);need(budget>=5,'insufficient StageB shim deadline for bounded cleanup');record={'name':name,'argv':cmd,'timeoutSeconds':timeout};commands.append(record)
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
  engine=Path(c['tracedEngine']);obj=work/'jni.o';dep=work/'jni.d';shim=work/'librealityforge_jni.so'
  run('compile',[c['compiler'],'-std=c++20','-O0','-fPIC','-Werror','-I'+c['nativeInclude'],'-I'+c['jdkInclude'],'-I'+c['jdkInclude']+'/linux','-MD','-MF',str(dep),'-c',c['jniSource'],'-o',str(obj)],60)
  deps=shlex.split(secure_bytes(dep).decode().replace('\\\n',' ').split(':',1)[1]);consumed={}
  for path in deps:
   physical=str(Path(path).resolve());need(physical in pins,'unreviewed compiler dependency '+physical);need(digest(physical)==pins[physical],'dependency changed');consumed[physical]=pins[physical]
  run('link',[c['compiler'],'-shared',str(obj),'-L'+str(engine.parent),'-lrealityforge','-Wl,-z,defs','-Wl,--enable-new-dtags','-Wl,-rpath,'+str(engine.parent),'-o',str(shim)],30)
  dynamic=run('dynamic',[c['readelf'],'-d',str(shim)],10)
  need(re.findall(r'\(RUNPATH\).*?\[(.*?)\]',dynamic)==[str(engine.parent)],'exact trace RUNPATH');need('(RPATH)' not in dynamic,'unexpected RPATH');need('Shared library: [librealityforge.so]' in dynamic,'engine NEEDED')
  linkage=run('linkage',[c['ldd'],'-r',str(shim)],10)
  resolved=re.findall(r'^\s*librealityforge\.so => (\S+)',linkage,re.M)
  need(resolved==[str(engine)] and 'undefined symbol:' not in linkage and 'not found' not in linkage,'exact engine resolution')
  def exports(text):return sorted(line.split()[-1] for line in text.splitlines() if line.split() and line.split()[-1].startswith('Java_'))
  old=exports(run('original-exports',[c['nm'],'-D','--defined-only',c['originalJni']],10));new=exports(run('new-exports',[c['nm'],'-D','--defined-only',str(shim)],10));need(old and old==new,'JNI export parity')
  need(pins=={path:digest(safe(path)) for path in pins},'input mutated')
  receipt={'status':'SHIM_BUILD_LINKAGE_PASS_NOT_JVM_TESTED','noCaptureOrReplay':True,'elapsedSeconds':time.monotonic()-start,'inputSha256':pins,'consumedCompilerDependencies':consumed,'commands':commands,'tracedEngineSha256':digest(engine),'newShimSha256':digest(shim),'jniExports':new,'limits':{'aggregateSeconds':120,'perProcessAddressSpaceBytes':2*1024**3,'maxCompilerJobs':1},'outputSha256':{p.name:digest(p) for p in work.iterdir() if p.is_file()}}
  (work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print('PASS',digest(work/'receipt.json'))
 except BaseException as error:
  post={}
  for path,expected_sha in pins.items():
   try:actual=digest(safe(path));post[path]={'expected':expected_sha,'actual':actual,'unchanged':actual==expected_sha}
   except Exception as check_error:post[path]={'expected':expected_sha,'unchanged':False,'error':str(check_error)}
  failure={'status':'STAGE_B_SHIM_FAIL_CLOSED','errorType':type(error).__name__,'error':str(error),'elapsedSeconds':time.monotonic()-start,'commands':commands,'postInputChecks':post,'allKnownInputsUnchanged':all(item['unchanged'] for item in post.values()),'noRealCaptureOrReplay':True,'noTelemetryFindings':True}
  (work/'failure-receipt.json').write_text(json.dumps(failure,indent=2)+'\n');print('FAIL',digest(work/'failure-receipt.json'),str(error),file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
