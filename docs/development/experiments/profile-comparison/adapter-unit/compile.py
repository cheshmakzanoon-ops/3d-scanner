#!/usr/bin/env python3
"""Compile-only staging with mandatory independent normal compilation restoration. No tests."""
import hashlib,json,pathlib as P,os,signal,subprocess,sys,time,re,shlex
ROOT=P.Path('/workspace/scratch/6b683a119bde');REPO=ROOT/'3d-scanner';HERE=P.Path(__file__).resolve().parent;D=P.Path('/tmp/rf-relief-profile-pair-draft');OUT=P.Path('/tmp/android-relief-profile-compile-evidence')
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def live_group(pgid):
 live=[]
 for entry in P.Path('/proc').iterdir():
  if not entry.name.isdecimal():continue
  try:
   # comm may contain spaces/parentheses; fields after its final ')' start at state.
   fields=(entry/'stat').read_text().rsplit(')',1)[1].split()
   if int(fields[2])==pgid and fields[0] not in ['Z','X']:live.append(int(entry.name))
  except FileNotFoundError:continue
  except ProcessLookupError:continue
 return live

def drain(proc):
 for sig,seconds in [(signal.SIGTERM,15),(signal.SIGKILL,15)]:
  if not live_group(proc.pid):return True
  try:os.killpg(proc.pid,sig)
  except ProcessLookupError:pass
  deadline=time.monotonic()+seconds
  while time.monotonic()<deadline:
   proc.poll()
   if not live_group(proc.pid):return True
   time.sleep(.1)
 return not live_group(proc.pid)

def invoke(command,env,log):
 proc=None;code=None;error=None;stopped=True
 try:
  proc=subprocess.Popen(command,cwd=REPO,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  try:code=proc.wait(timeout=600)
  except subprocess.TimeoutExpired:code='TIMEOUT600S'
 except Exception as e:error=repr(e)
 finally:
  if proc is not None:
   # Even launcher exit0 is insufficient if its Gradle descendants remain alive.
   try:stopped=drain(proc)
   except Exception as e:stopped=False;error=(error or '')+' group-drain-check:'+repr(e)
   if not stopped:error=(error or '')+' process group still live; restoration blocked'
 return code,error,stopped

def git(*args):return subprocess.check_output(['git',*args],cwd=REPO,text=True).strip()
def main():
 assert __debug__
 expected=sys.argv[1];assert len(expected)==40 and all(x in '0123456789abcdef' for x in expected)
 assert git('rev-parse','HEAD')==expected and git('status','--porcelain')==''
 pins=json.loads((HERE/'compile-pins.json').read_text())
 for p,h in pins.items():assert sha(P.Path(p))==h,p
 cfg=json.loads((D/'current-environment.json').read_text())
 for k,v in os.environ.items():
  if k.startswith('LD_') and v:raise RuntimeError('Inherited loader override: '+k)
  if k in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS'] and v:
   for token in shlex.split(v):
    if not re.fullmatch(r'-D(?:https?|socks)\.proxy(?:Host|Port)=[A-Za-z0-9.:-]+|-Djavax\.net\.ssl\.trustStore=/etc/ssl/certs/java/cacerts',token):raise RuntimeError('Unreviewed JVM option in '+k)
 selected=subprocess.check_output(['bash','-c','source "$1"; readlink -f "$(command -v java)"','env-check',cfg['environmentScript']['path']],cwd=REPO,text=True).strip()
 assert selected==str(P.Path(cfg['java']['path']).resolve()),'Environment selects a different JDK' 
 files=[P.Path(p) for p in pins]+[HERE/'compile-pins.json']
 files += [REPO/p for p in git('ls-files').splitlines() if (REPO/p).is_file()]
 before={str(p):sha(p) for p in sorted(set(files))}
 OUT.mkdir(exist_ok=False);(OUT/'pre-hashes.json').write_text(json.dumps(before,indent=2))
 common='--no-daemon --max-workers=1 --no-parallel -Dorg.gradle.jvmargs="-Xmx1g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8" --offline --dependency-verification strict -PcandidateSigning=test -PrealityForgeTestBuildType=debug -PrealityForgeInstrumentationQa=false '
 def command(extra):return ['bash','-c','source "$1"; exec "$2" '+common+extra+' :app:compileDebugUnitTestKotlin','compile',cfg['environmentScript']['path'],cfg['gradle']['path']]
 probe=command('-I '+str(HERE/'compile.init.gradle'));restore=command('')
 env=dict(os.environ)
 for k in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS']:env.pop(k,None)
 code=None;restored=None;error=None;stopped=False;restoreStopped=False;start=time.monotonic()
 try:
  with (OUT/'compile.log').open('x') as log:code,error,stopped=invoke(probe,env,log)
 except Exception as e:error=repr(e)
 finally:
  try:
   if stopped:
    with (OUT/'restoration.log').open('x') as log:restored,restoreError,restoreStopped=invoke(restore,env,log)
    if restoreError:error=(error or '')+' restoration:'+restoreError
   else:restored='BLOCKED_PRIOR_PROCESS_GROUP_STILL_LIVE'
  except Exception as e:error=(error or '')+' restoration:'+repr(e)
 after={}
 for p in sorted(set(files)):
  try:after[str(p)]=sha(p)
  except Exception as e:after[str(p)]={'readError':repr(e)}
 (OUT/'post-hashes.json').write_text(json.dumps(after,indent=2))
 remaining=[str(p) for p in (REPO/'app/build').rglob('*.class') if p.name.startswith(('VerifiedProfilePair','SparsePipelineQualityCaptureHostTest','SparsePrefixRunner','SandboxNativeLoader','OpaqueBitmapBridge'))]
 head=git('rev-parse','HEAD');status=git('status','--porcelain');ok=code==0 and restored==0 and before==after and not remaining and head==expected and status=='' and error is None and stopped and restoreStopped
 receipt={'scope':'COMPILE_ONLY_NOT_TESTED_OR_RUNTIME_QUALIFIED','outcome':'PASS' if ok else 'FAILED','compileCommand':probe,'compileExit':code,'restorationCommand':restore,'restorationExit':restored,'restored':restored==0 and stopped and restoreStopped and not remaining and before==after and head==expected and status=='','compileProcessGroupStopped':stopped,'restorationProcessGroupStopped':restoreStopped,'remainingProbeClasses':remaining,'inputsUnchanged':before==after,'expectedCommit':expected,'postCommit':head,'postGitStatus':status,'seconds':time.monotonic()-start,'error':error}
 (OUT/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt));return 0 if ok else 1
if __name__=='__main__':sys.exit(main())
