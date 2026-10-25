"""HOST guard regressions only, not Android/Gradle/OpenCV pipeline execution."""
import contextlib, hashlib, importlib.util, io, json, os, signal, sys, tarfile, time
from pathlib import Path
import pytest
from synthetic_child_env import synthetic_child_env
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
# The candidate's tools namespace also needs the original compiler-only boundary.
import tools
SOURCE=Path(os.environ.get('RF_SOURCE_ROOT',ROOT));sys.path.append(str(SOURCE))
if SOURCE.exists():tools.__path__.append(str(SOURCE/'tools'))
from tools.eight_view_host import runtime as r, policy as p
from tools.eight_view_host.capability import native_declarations, jni_symbol, extract_source
BASE='a'*40;SHA='b'*40;MSG='test: exact reviewed experiment'

def valid_event():
 return {'GITHUB_EVENT_NAME':'push','GITHUB_REPOSITORY':p.REPOSITORY,'GITHUB_SHA':SHA,'GITHUB_REF':'refs/heads/main','GITHUB_RUN_ATTEMPT':'1'}, {'repository':{'full_name':p.REPOSITORY,'private':False,'default_branch':'main'},'ref':'refs/heads/main','before':BASE,'after':SHA,'head_commit':{'id':SHA,'message':MSG},'created':False,'deleted':False,'forced':False}

def test_unarmed_is_closed():
 env,e=valid_event()
 with pytest.raises(r.Blocked):p.event_guard(env,e,base='NOT_ARMED',message='NOT_ARMED')
 assert p.event_guard(env,e,BASE,MSG)==SHA

@pytest.mark.parametrize('key,value',[('GITHUB_EVENT_NAME','workflow_dispatch'),('GITHUB_REPOSITORY','other/repo'),('GITHUB_SHA','bad'),('GITHUB_REF','refs/heads/other'),('GITHUB_RUN_ATTEMPT','2')])
def test_environment_admission(key,value):
 env,e=valid_event();env[key]=value
 with pytest.raises(r.Failure):p.event_guard(env,e,BASE,MSG)

@pytest.mark.parametrize('key,value',[('ref','refs/heads/other'),('before','c'*40),('after','c'*40),('created',True),('deleted',True),('forced',True),('forced','false')])
def test_payload_admission(key,value):
 env,e=valid_event();e[key]=value
 with pytest.raises(r.Failure):p.event_guard(env,e,BASE,MSG)

@pytest.mark.parametrize('owner,key,value',[('repository','private',True),('repository','private','false'),('repository','default_branch','other'),('repository','full_name','other/repo'),('head_commit','id','c'*40),('head_commit','message',MSG+'\n')])
def test_nested_admission(owner,key,value):
 env,e=valid_event();e[owner][key]=value
 with pytest.raises(r.Failure):p.event_guard(env,e,BASE,MSG)

def junit(tmp_path):
 path=tmp_path/'result.xml';path.write_text(f'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="{p.CLASS}" name="{p.METHOD}"/></testsuite>')
 events=[{'event':'start','class':p.CLASS,'method':p.METHOD},{'event':'finish','class':p.CLASS,'method':p.METHOD,'result':'SUCCESS'}]
 return path,events

def test_exact_junit(tmp_path):
 path,events=junit(tmp_path);assert p.junit_proof(path,events)['tests']==1
 for bad in ([],events[:1],events+events,events[::-1]):
  with pytest.raises(r.Failure):p.junit_proof(path,bad)

@pytest.mark.parametrize('tag',['failure','error','skipped'])
def test_junit_does_not_hide_failure(tmp_path,tag):
 path,events=junit(tmp_path);path.write_text(path.read_text().replace('/></testsuite>',f'><{tag}/></testcase></testsuite>'))
 with pytest.raises(r.Failure):p.junit_proof(path,events)

def measured():
 return {'classification':'ALL8_SPARSE_ACCEPTED_NOT_SURFACE_QUALITY','inputPhotoIds':list('abcdefgh'),'registeredPhotoIds':list('abcdefgh'),'unregisteredPhotoIds':[],'geometry':{'finalSparseOutcome':'global_typed_ba_admitted12'}},[{'phase':'READY','pairCount':28,'pairNumber':i,'geometryEntered':True} for i in range(1,29)]

def identities():
 return [{'photoId':x,'fixturePath':'images/'+x+'.png','imageSha256':hashlib.sha256(x.encode()).hexdigest()} for x in 'abcdefgh'], {'images':{'images/'+x+'.png':hashlib.sha256(x.encode()).hexdigest() for x in 'abcdefgh'}}

def test_measurement_not_just_test_exit():
 identity,observed=identities()
 result,pairs=measured();assert p.measurement_proof(result,pairs,identity,observed)['registeredCameraCount']==8
 for key,value in [('registeredPhotoIds',list('abcdefg')),('classification','INCOMPLETE_OR_FALLBACK'),('inputPhotoIds',list('aaaaaabc')),('unregisteredPhotoIds',['h']),('geometry',{'finalSparseOutcome':'verified_anchor_fallback'})]:
  bad=dict(result);bad[key]=value
  with pytest.raises(r.Failure):p.measurement_proof(bad,pairs,identity,observed)
 with pytest.raises(r.Failure):p.measurement_proof(result,pairs[:-1],identity,observed)

def test_jni_name_and_descriptor():
 text='  private static native void hello_0(long);\n    descriptor: (J)V\n'
 assert native_declarations(text)==[('hello_0','(J)V')]
 assert jni_symbol('org.opencv.core.Mat','hello_0')=='Java_org_opencv_core_Mat_hello_10'
 with pytest.raises(r.Failure):native_declarations('  private native void bad();')

@pytest.mark.parametrize('name,kind',[('../escape','file'),('/absolute','file'),('root/a','symlink'),('root/a','hardlink'),('foreign/a','file')])
def test_archive_closed(tmp_path,name,kind):
 archive=tmp_path/'a.tar.gz'
 with tarfile.open(archive,'w:gz') as t:
  i=tarfile.TarInfo(name)
  if kind=='symlink':i.type=tarfile.SYMTYPE;i.linkname='../../escape'
  if kind=='hardlink':i.type=tarfile.LNKTYPE;i.linkname='../../escape'
  t.addfile(i)
 with pytest.raises(r.Failure):extract_source(archive,tmp_path/'out','root')

def test_display_secret_and_frame_roundtrip():
 assert r.safe_display(b'password=hidden')[1]=='WITHHELD_SECRET_MARKER'
 raw=b'known\x00bytes\n';out=io.StringIO()
 with contextlib.redirect_stdout(out):r.frame('proof',raw)
 import base64
 lines=out.getvalue().splitlines();meta=json.loads(lines[0].split(' ',1)[1]);restored=b''.join(base64.b64decode(x.split(' ',3)[3]) for x in lines[1:])
 assert restored==raw and meta['sha256']==hashlib.sha256(raw).hexdigest()

# Only explicit generated Python child commands below use synthetic_child_env.
# Real compiler/JNI/ELF commands and Runner runtime inheritance remain unchanged.
def runner(tmp_path):
 result=r.Runner(tmp_path,tmp_path/'owned',seconds=180)
 result.disk_guard=lambda:None
 return result

def test_real_closed_stdin_and_retained_hash(tmp_path):
 run=runner(tmp_path)
 with contextlib.redirect_stdout(io.StringIO()):raw,code=run.command('stdin',[sys.executable,'-I','-S','-c','import sys; print(repr(sys.stdin.read()))'],env=synthetic_child_env())
 assert code==0 and raw==b"''\n",{'exit':code,'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}
 assert run.commands[0]['complete'] and (run.work/'logs/stdin.raw.log').read_bytes()==raw
 run.cleanup()

def test_real_no_output_timeout_keeps_identity_and_stops(tmp_path):
 run=runner(tmp_path)
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(r.Deadline):run.command('sleep',[sys.executable,'-c','import time;time.sleep(5)'],seconds=.1)
 record=run.commands[0];assert record['bytes']==0 and not record['complete'] and record['pid']>0
 assert r.proc_info(record['pid']) is None
 run.cleanup()

def test_output_overflow_retains_exact_partial(tmp_path):
 run=runner(tmp_path)
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(r.Failure):run.command('overflow',[sys.executable,'-c','print("x"*10000)'],limit=100)
 assert run.commands[0]['bytes']==100 and not run.commands[0]['complete']
 assert (run.work/'logs/overflow.raw.log').stat().st_size==100
 run.cleanup()

def test_deadline_clipping_and_late_cancel(tmp_path):
 now=[0];run=r.Runner(tmp_path,tmp_path/'owned',clock=lambda:now[0],seconds=180)
 assert run.remaining(100)==60
 now[0]=61
 with pytest.raises(r.Deadline):run.remaining(1)
 run.signal=signal.SIGTERM
 with pytest.raises(r.Cancelled):run.cleanup()

def test_private_tree_symlink_refused(tmp_path):
 (tmp_path/'escape').symlink_to('/etc/passwd')
 with pytest.raises(r.Failure):r.inventory(tmp_path)

def test_inherited_child_pipe_timeout_kills_owned_group(tmp_path):
 run=runner(tmp_path)
 code='import subprocess,sys; p=subprocess.Popen([sys.executable,"-I","-S","-c","import time; time.sleep(10)"]); print(p.pid,flush=True)'
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(r.Deadline):run.command('child-pipe',[sys.executable,'-I','-S','-c',code],seconds=.3,env=synthetic_child_env())
 pid=int((run.work/'logs/child-pipe.raw.log').read_text().strip())
 assert (i:=r.proc_info(pid)) is None or i['state']=='Z'
 run.cleanup()

def test_detached_descendant_is_owned_and_stopped(tmp_path):
 run=runner(tmp_path)
 code='import subprocess,sys,time; p=subprocess.Popen([sys.executable,"-c","import time; time.sleep(10)"],start_new_session=True); print(p.pid,flush=True);time.sleep(10)'
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(r.Deadline):run.command('detached',[sys.executable,'-c',code],seconds=.5)
 pid=int((run.work/'logs/detached.raw.log').read_text().strip());assert pid in run.owned
 assert (i:=r.proc_info(pid)) is None or i['state']=='Z'
 run.cleanup()

def test_stale_private_daemon_log_cannot_admit_foreign_process(tmp_path):
 import subprocess
 run=runner(tmp_path)
 child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(10)','org.gradle.launcher.daemon.bootstrap.GradleDaemon'],start_new_session=True)
 try:
  with pytest.raises(r.Blocked,match='preexisting children'):run.activate_lifecycle()
  pth=run.work/'gradle/daemon/8.13';pth.mkdir(parents=True);(pth/f'daemon-{child.pid}.out.log').write_text('')
  run.scan();assert child.pid not in run.owned
  run.cleanup();assert child.poll() is None
 finally:child.terminate();child.wait(timeout=3)

def test_fast_double_fork_is_adopted_despite_delayed_scan(tmp_path):
 run=runner(tmp_path);before=r.subreaper();marker=tmp_path/'detached-pid'
 original=run.disk_guard
 run.disk_guard=lambda:(time.sleep(.15),original())
 code="""import os,sys,time
if os.fork()==0:
 os.setsid()
 if os.fork()==0:
  open(sys.argv[1],'w').write(str(os.getpid()))
  for fd in (0,1,2):
   os.dup2(os.open(os.devnull,os.O_RDWR),fd)
  time.sleep(20)
 os._exit(0)
os._exit(0)
"""
 with contextlib.redirect_stdout(io.StringIO()):raw,code=run.command('fast-double-fork',[sys.executable,'-c',code,str(marker)])
 pid=int(marker.read_text());assert code==0 and pid in run.owned
 assert r.proc_info(pid) is None
 run.cleanup();assert r.subreaper()==before;run.cleanup()

def test_signal_in_spawn_assignment_is_retained_and_cleaned(tmp_path,monkeypatch):
 run=runner(tmp_path);original=r.subprocess.Popen
 def spawn(*a,**kw):
  child=original(*a,**kw);os.kill(os.getpid(),signal.SIGTERM);return child
 run.install_signals()
 try:
  monkeypatch.setattr(r.subprocess,'Popen',spawn)
  with contextlib.redirect_stdout(io.StringIO()),pytest.raises(r.Cancelled):run.command('spawn-signal',[sys.executable,'-c','import time;time.sleep(10)'])
  assert r.proc_info(run.commands[0]['pid']) is None
 finally:run.restore_signals()

def test_evidence_io_does_not_mask_primary_timeout(tmp_path,monkeypatch):
 run=runner(tmp_path);original=Path.write_bytes
 def write(p,data):
  if p.name=='no-output.raw.log':raise OSError('injected evidence disk failure')
  return original(p,data)
 monkeypatch.setattr(Path,'write_bytes',write)
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(r.Deadline) as caught:run.command('no-output',[sys.executable,'-c','import time;time.sleep(10)'],seconds=.1)
 assert run.evidence_errors and any('disk failure' in n for n in caught.value.__notes__)
 with pytest.raises(r.Failure):run.cleanup()

def test_success_with_evidence_io_failure_is_not_success(tmp_path,monkeypatch):
 run=runner(tmp_path)
 monkeypatch.setattr(r,'atomic_json',lambda *a:(_ for _ in ()).throw(OSError('injected receipt failure')))
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(r.Failure,match='evidence incomplete'):run.command('success-no-receipt',[sys.executable,'-I','-S','-c','print("done")'],env=synthetic_child_env())
 assert run.commands[0]['exit']==0 and run.evidence_errors

def test_disk_failure_before_spawn_is_retained(tmp_path):
 run=runner(tmp_path);run.disk_guard=lambda:(_ for _ in ()).throw(r.Failure('disk floor'))
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(r.Failure,match='disk floor'):run.command('disk',[sys.executable,'-c','raise AssertionError("must not run")'])
 assert 'pid' not in run.commands[0] and run.commands[0]['bytes']==0

@pytest.mark.parametrize('mutation',[lambda x:x.append(dict(x[-1])),lambda x:x[0].update(pairNumber=True),lambda x:x[0].update(pairNumber=29),lambda x:x[0].update(pairCount='28'),lambda x:x[0].update(phase='FAILED'),lambda x:x[0].update(firstDescriptorRows=-1),lambda x:x[0].update(matchCacheHit='false')])
def test_pair_events_fail_closed(mutation):
 result,pairs=measured();identity,observed=identities();mutation(pairs)
 with pytest.raises(r.Failure):p.measurement_proof(result,pairs,identity,observed)

@pytest.mark.parametrize('field,value',[('photoId','foreign'),('imageSha256','0'*64),('fixturePath','../truth.json')])
def test_measurement_ids_bind_exact_observation(field,value):
 result,pairs=measured();identity,observed=identities();identity[0][field]=value
 with pytest.raises(r.Failure):p.measurement_proof(result,pairs,identity,observed)

def test_inventory_counts_directories_before_materialization(tmp_path):
 for i in range(5):(tmp_path/str(i)).mkdir()
 with pytest.raises(r.Failure,match='count'):r.inventory(tmp_path,max_files=3)

def test_alias_allowlist_is_exact_path_and_target(tmp_path):
 target=tmp_path/'native';target.write_bytes(b'owned');alias=tmp_path/'aliases';alias.mkdir();link=alias/'libopencv_java4.so';link.symlink_to(target)
 with pytest.raises(r.Failure):r.inventory(tmp_path)
 assert r.inventory(tmp_path,aliases={str(link):str(target)})[0]==5
 nested=tmp_path/'nested/aliases';nested.mkdir(parents=True);(nested/link.name).symlink_to(target)
 with pytest.raises(r.Failure):r.inventory(tmp_path,aliases={str(link):str(target)})

def test_archive_streaming_directory_bomb(tmp_path,monkeypatch):
 archive=tmp_path/'a.tar.gz'
 with tarfile.open(archive,'w:gz') as t:
  for i in range(10):
   item=tarfile.TarInfo('root/d'+str(i));item.type=tarfile.DIRTYPE;t.addfile(item)
 monkeypatch.setattr(tarfile.TarFile,'getmembers',lambda *a:(_ for _ in ()).throw(AssertionError('unbounded allocation')))
 with pytest.raises(r.Failure,match='member limit'):extract_source(archive,tmp_path/'out','root',max_members=4)
 assert len(list((tmp_path/'out/root').iterdir()))==4

def test_archive_declared_expansion_rejected_before_payload(tmp_path):
 archive=tmp_path/'a.tar.gz'
 with tarfile.open(archive,'w:gz') as t:
  item=tarfile.TarInfo('root/big');item.size=100;t.addfile(item,io.BytesIO(b'x'*100))
 with pytest.raises(r.Failure,match='expansion'):extract_source(archive,tmp_path/'out','root',max_bytes=99)
 assert not (tmp_path/'out/root/big').exists()

def test_cleanup_restores_lifecycle_even_on_failure(tmp_path,monkeypatch):
 run=runner(tmp_path);previous=r.subreaper();run.activate_lifecycle()
 monkeypatch.setattr(run,'terminate_owned',lambda:(_ for _ in ()).throw(r.Failure('injected cleanup failure')))
 with pytest.raises(r.Failure):run.cleanup()
 assert r.subreaper()==previous

def test_cancel_during_cleanup_stays_cancelled(tmp_path,monkeypatch):
 run=runner(tmp_path);run.install_signals();run.activate_lifecycle();original=run.terminate_owned
 def stop():original();os.kill(os.getpid(),signal.SIGTERM)
 monkeypatch.setattr(run,'terminate_owned',stop)
 try:
  with pytest.raises(r.Cancelled):run.cleanup()
 finally:run.restore_signals()

def test_pidfd_retired_identity_never_signals_reused_numeric_pid(tmp_path,monkeypatch):
 run=runner(tmp_path);run.activate_lifecycle();run.owned[999999]=123
 # A stale registry cannot turn a new numeric PID into a signal target; no pidfd means no authority.
 monkeypatch.setattr(r,'proc_info',lambda pid:{'pid':pid,'ppid':1,'group':1,'start':124,'state':'S'} if pid==999999 else None)
 monkeypatch.setattr(r,'child_pids',lambda *args:[])
 seen=[];monkeypatch.setattr(signal,'pidfd_send_signal',lambda fd,s:seen.append((fd,s)))
 run.cleanup();assert seen==[]

def test_forged_non_elf_self_assertions_are_not_capability(tmp_path):
 fake=tmp_path/'plain';fake.write_text('not a native library')
 manifest=tmp_path/'capability.json';policy=tmp_path/'policy.json';policy.write_text(json.dumps({'requiredChecks':['allGood']}))
 manifest.write_text(json.dumps({'schema':1,'classification':'HOST_SOURCE_BUILT_CAPABILITY','sourceSha':SHA,'policySha256':r.sha(policy),'inputs':{},'checks':{'allGood':True},'native':{key:{'path':str(fake),'sha256':r.sha(fake)} for key in ('opencv','shim','engine')}}))
 with pytest.raises(r.Failure,match='Authoritative'):p.verify_capability(manifest,SHA,policy)
 from tools.eight_view_host.capability import parse_elf_closure
 with pytest.raises(r.Failure,match='ELF identity'):parse_elf_closure(fake,b'ELF64 Advanced Micro Devices X86-64',b'',b'')

@pytest.mark.parametrize('descriptor,expected',[('(J)Z',('jboolean',['jlong'])),('([D[D[D D J)Ljava/lang/String;'.replace(' ',''),('jstring',['jdoubleArray','jdoubleArray','jdoubleArray','jdouble','jlong'])),('([BI)[D',('jdoubleArray',['jbyteArray','jint']))])
def test_jni_descriptor_contract_types(descriptor,expected):
 from tools.eight_view_host.capability import descriptor_types
 assert descriptor_types(descriptor)==expected

@pytest.mark.parametrize('descriptor',['(V)V','(J','J)V','(J)Vjunk','(Q)V'])
def test_malformed_jni_descriptor_rejected(descriptor):
 from tools.eight_view_host.capability import descriptor_types
 with pytest.raises(r.Failure):descriptor_types(descriptor)

def test_actual_compiler_rejects_same_named_wrong_jni_descriptor(tmp_path):
 import shutil,subprocess
 from tools.native_process import native_compile_env
 from tools.eight_view_host.capability import scanner_contract
 javahome=Path(os.environ.get('JAVA_HOME','/missing'));compiler=shutil.which('g++')
 if not compiler or not (javahome/'include/jni.h').is_file():pytest.skip('NOT_RUN: actual JDK17 headers/GNU compiler unavailable')
 header=tmp_path/'contract.h';header.write_text(scanner_contract([('beginReconstruction','()J')]))
 source=tmp_path/'signature.cpp';symbol=jni_symbol('com.realityforge.scanner.engine.NativeCore','beginReconstruction')
 args=[compiler,'-std=c++20','-fsyntax-only','-include',str(header),'-I'+str(javahome/'include'),'-I'+str(javahome/'include/linux'),str(source)]
 source.write_text(f'extern "C" JNIEXPORT jlong JNICALL {symbol}(JNIEnv*,jobject) {{ return 1; }}')
 good=subprocess.run(args,env=native_compile_env(),stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
 assert good.returncode==0,{'exit':good.returncode,'bytes':len(good.stdout)}
 source.write_text(f'extern "C" JNIEXPORT jdouble JNICALL {symbol}(JNIEnv*,jobject) {{ return 1; }}')
 bad=subprocess.run(args,env=native_compile_env(),stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
 assert bad.returncode!=0 and b'conflicting declaration' in bad.stdout

def test_real_elf_closure_and_forbidden_runpath(tmp_path,monkeypatch):
 import shutil,subprocess
 from tools.native_process import native_compile_env
 from tools.eight_view_host.capability import parse_elf_closure
 # This fixed ordinary toy is not a candidate library. Keep loader injection out
 # of its compiler and inspection tools; do not alter the running test process.
 monkeypatch.setenv('LD_PRELOAD',os.environ.get('LD_PRELOAD') or '/fixture/parent-preload.so')
 monkeypatch.setenv('LD_AUDIT',os.environ.get('LD_AUDIT') or '/fixture/parent-audit.so')
 parent=dict(os.environ);expected=native_compile_env(parent);calls=[];real_run=subprocess.run
 def tool_run(args,**kwargs):
  assert kwargs.get('env')==expected
  assert dict(os.environ)==parent
  calls.append(Path(args[0]).name)
  return real_run(args,**kwargs)
 monkeypatch.setattr(subprocess,'run',tool_run)
 compiler=shutil.which('g++')
 if not compiler:pytest.skip('NOT_RUN: GNU compiler unavailable')
 source=tmp_path/'fixture.cpp';source.write_text('#include <cmath>\nextern "C" double real_fixture(double x){return std::sin(x);}\n')
 library=tmp_path/'fixture.so'
 compile_result=subprocess.run([compiler,'-shared','-fPIC',str(source),'-o',str(library)],env=native_compile_env(),stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
 assert compile_result.returncode==0
 def inspect(args):return subprocess.run(args,env=native_compile_env(),stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True,timeout=10).stdout
 header=inspect(['readelf','-h',str(library)]);dynamic=inspect(['readelf','-d',str(library)]);closure=inspect(['ldd','-r',str(library)])
 proof=parse_elf_closure(library,header,dynamic,closure);assert 'libm.so.6' in proof['providers']
 with pytest.raises(r.Failure,match='RPATH'):parse_elf_closure(library,header,dynamic+b' (RUNPATH) Library runpath: [:/tmp]\n',closure)
 with pytest.raises(r.Failure,match='DT_NEEDED'):parse_elf_closure(library,header,dynamic+b' (NEEDED) Shared library: [libforeign.so]\n',closure)
 with pytest.raises(r.Failure,match='loader output'):parse_elf_closure(library,header,dynamic,closure+b'undefined symbol: nope\n')
 for injected in (b'libasan.so.8 => /fixture/libasan.so.8 (0x123)\n',b'libclang_rt.asan-x86_64.so => /fixture/libclang_rt.asan-x86_64.so (0x123)\n'):
  with pytest.raises(r.Failure,match='Unexpected/duplicate resolved provider'):parse_elf_closure(library,header,dynamic,closure+injected)
 assert calls.count('readelf')==2 and calls.count('ldd')==1
 assert dict(os.environ)==parent
 assert expected=={key:value for key,value in parent.items() if key not in ('LD_PRELOAD','LD_AUDIT')}

def test_scan_failure_after_spawn_cannot_skip_known_leader_stop(tmp_path,monkeypatch):
 run=runner(tmp_path);original=run.scan
 monkeypatch.setattr(run,'scan',lambda *a,**k:(_ for _ in ()).throw(r.Failure('injected discovery failure')))
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(r.Failure,match='injected discovery failure') as caught:
  run.command('scan-fails',[sys.executable,'-c','import time;time.sleep(20)'])
 assert r.proc_info(run.commands[0]['pid']) is None
 assert run.commands[0]['cleanupError'] and caught.value.__notes__
 monkeypatch.setattr(run,'scan',original)
 with pytest.raises(r.Failure,match='evidence'):run.cleanup()

def test_scan_failure_during_cleanup_still_signals_known_pidfd(tmp_path,monkeypatch):
 import subprocess
 run=runner(tmp_path);run.activate_lifecycle();child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(20)'],start_new_session=True)
 original=run.scan
 try:
  run.scan();assert child.pid in run.pidfds
  monkeypatch.setattr(run,'scan',lambda *a,**k:(_ for _ in ()).throw(r.Failure('cleanup scan failed')))
  with pytest.raises(r.Failure,match='cleanup scan failed'):run.cleanup()
  assert r.proc_info(child.pid) is None
 finally:
  monkeypatch.setattr(run,'scan',original)
  if child.poll() is None:child.kill();child.wait(timeout=2)

def test_activation_verification_error_restores_prior_state(tmp_path,monkeypatch):
 run=runner(tmp_path);state=[0];calls=[]
 def fake(value=None):
  calls.append(value)
  if value is None:return state[0]
  previous=state[0];state[0]=value
  if value==1:raise r.Failure('verification GET failed after SET')
  return previous
 monkeypatch.setattr(r,'subreaper',fake)
 with pytest.raises(r.Failure,match='verification'):run.activate_lifecycle()
 assert state==[0] and calls==[None,1,0] and run.subreaper_before is None

def test_cleanup_restore_error_does_not_mask_primary_or_leak_fd(tmp_path,monkeypatch):
 run=runner(tmp_path);run.subreaper_before=0;fd=os.open(os.devnull,os.O_RDONLY);run.pidfds[999999]=fd
 monkeypatch.setattr(run,'terminate_owned',lambda:(_ for _ in ()).throw(r.Deadline('primary cleanup timeout')))
 monkeypatch.setattr(r,'subreaper',lambda *args:(_ for _ in ()).throw(r.Failure('restore failed')))
 with pytest.raises(r.Deadline,match='primary cleanup timeout') as caught:run.cleanup()
 assert any('restore failed' in note for note in caught.value.__notes__)
 with pytest.raises(OSError):os.fstat(fd)

def test_cleanup_command_can_use_reserved_deadline(tmp_path):
 run=runner(tmp_path);run.work_end=run.clock()-.1;run.end=run.clock()+10
 with contextlib.redirect_stdout(io.StringIO()):raw,code=run.command('reserved-cleanup',[sys.executable,'-I','-S','-c','print("cleanup")'],seconds=2,cleanup=True,env=synthetic_child_env())
 assert raw==b'cleanup\n' and code==0
 with pytest.raises(r.Deadline):run.remaining(1)
 run.cleanup()

def test_command_phase_budget_does_not_leak_into_later_noncommand_work(tmp_path):
 run=runner(tmp_path)
 with contextlib.redirect_stdout(io.StringIO()):run.command('short-phase',[sys.executable,'-I','-S','-c','pass'],seconds=1,env=synthetic_child_env())
 assert not hasattr(run,'phase_end') and not hasattr(run,'phase_cleanup')
 run.cleanup()

def test_overloaded_jni_names_bind_exact_argument_descriptors():
 from tools.eight_view_host.capability import native_exports_match
 owner='org.opencv.core.Mat';declarations=[('n_Mat','()J'),('n_Mat','(III)J'),('n_Mat','(I[II)J'),('n_Mat','(IIILjava/nio/ByteBuffer;)J')]
 symbols={jni_symbol(owner,name,descriptor) for name,descriptor in declarations}
 assert 'Java_org_opencv_core_Mat_n_1Mat__' in symbols
 assert 'Java_org_opencv_core_Mat_n_1Mat__I_3II' in symbols
 assert 'Java_org_opencv_core_Mat_n_1Mat__IIILjava_nio_ByteBuffer_2' in symbols
 assert native_exports_match(owner,declarations,symbols)
 assert not native_exports_match(owner,declarations,symbols-{jni_symbol(owner,'n_Mat','()J')})
 assert not native_exports_match(owner,declarations,symbols|{jni_symbol(owner,'n_Mat')})

def test_actual_javac_header_confirms_long_jni_name_encoding(tmp_path):
 import shutil,subprocess,re
 from tools.native_process import native_compile_env
 java=Path(os.environ.get('JAVA_HOME','/missing'))
 if not (java/'bin/javac').is_file():pytest.skip('NOT_RUN: actual javac unavailable')
 source=tmp_path/'Fixture.java';source.write_text('package fixture; public class Fixture { public native long n_Mat(); public native long n_Mat(int a,int[] b,int c); public native long n_Mat(java.nio.ByteBuffer data); }')
 result=subprocess.run([str(java/'bin/javac'),'-h',str(tmp_path),'-d',str(tmp_path),str(source)],env=native_compile_env(),stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
 assert result.returncode==0
 header=(tmp_path/'fixture_Fixture.h').read_text()
 actual=set(re.findall(r'JNICALL (Java_\w+)\s*\(',header))
 expected={jni_symbol('fixture.Fixture','n_Mat',desc) for desc in ('()J','(I[II)J','(Ljava/nio/ByteBuffer;)J')}
 assert actual==expected

# Actual controller race: Gradle can rename/delete an enumerated transform directory.
def _mutate_after_root_enumeration(monkeypatch,root,mutation):
 import threading
 original=r.os.scandir;ready=threading.Event();done=threading.Event();errors=[]
 expected=(root.stat().st_dev,root.stat().st_ino)
 def worker():
  try:
   assert ready.wait(5),'enumeration barrier never reached';mutation()
  except BaseException as error:errors.append(error)
  finally:done.set()
 @contextlib.contextmanager
 def scan(path):
  info=os.fstat(path) if isinstance(path,int) else Path(path).stat()
  is_root=(info.st_dev,info.st_ino)==expected
  with original(path) as iterator:yield iterator
  if is_root:
   ready.set();assert done.wait(5),'concurrent mutation did not finish'
 thread=threading.Thread(target=worker);thread.start();monkeypatch.setattr(r.os,'scandir',scan)
 def finish():
  ready.set();thread.join(5);assert not thread.is_alive();assert not errors,errors
 return finish

def test_inventory_concurrent_queued_directory_deletion(monkeypatch,tmp_path):
 root=tmp_path/'owned';root.mkdir();queued=root/'queued';queued.mkdir()
 finish=_mutate_after_root_enumeration(monkeypatch,root,queued.rmdir)
 try:
  total,entries=r.inventory(root)
  assert total==0 and entries=={'queued':0}  # Encountered node still counted.
 finally:finish()

@pytest.mark.parametrize('replacement',['directory','symlink','file','root-delete','root-replace'])
def test_inventory_concurrent_replacement_or_root_loss_fails(monkeypatch,tmp_path,replacement):
 root=tmp_path/'owned';root.mkdir();queued=root/'queued';queued.mkdir()
 outside=tmp_path/'outside';outside.mkdir();(outside/'must-not-read').write_text('outside')
 def mutate():
  if replacement.startswith('root-'):
   root.rename(tmp_path/'old-root')
   if replacement=='root-replace':root.mkdir()
  else:
   queued.rename(tmp_path/'old-queued')  # Retain inode, so new directory cannot reuse it.
   if replacement=='directory':queued.mkdir()
   elif replacement=='symlink':queued.symlink_to(outside,target_is_directory=True)
   else:queued.write_text('not-directory')
 before=len(list(Path('/proc/self/fd').iterdir()))
 finish=_mutate_after_root_enumeration(monkeypatch,root,mutate)
 try:
  with pytest.raises((r.Failure,OSError)):r.inventory(root)
 finally:finish()
 assert len(list(Path('/proc/self/fd').iterdir()))==before

def test_inventory_permission_error_is_not_deletion(monkeypatch,tmp_path):
 (tmp_path/'queued').mkdir();original=r.os.open
 def denied(path,flags,*args,**kwargs):
  if path=='queued' and 'dir_fd' in kwargs:raise PermissionError('injected permission failure')
  return original(path,flags,*args,**kwargs)
 monkeypatch.setattr(r.os,'open',denied)
 with pytest.raises(PermissionError,match='permission'):r.inventory(tmp_path)

def test_inventory_disappearing_nodes_do_not_extend_deadline(monkeypatch,tmp_path):
 root=tmp_path/'owned';root.mkdir();queued=root/'queued';queued.mkdir();now=[0.0]
 monkeypatch.setattr(r.time,'monotonic',lambda:now[0])
 def remove():queued.rmdir();now[0]=2.0
 finish=_mutate_after_root_enumeration(monkeypatch,root,remove)
 try:
  with pytest.raises(r.Failure,match='traversal'):r.inventory(root,seconds=1)
 finally:finish()

def test_inventory_vanished_nested_ancestor_remains_bounded(monkeypatch,tmp_path):
 root=tmp_path/'owned';root.mkdir();(root/'parent').mkdir();(root/'parent'/'nested').mkdir()
 finish=_mutate_after_root_enumeration(monkeypatch,root,lambda:__import__('shutil').rmtree(root/'parent'))
 try:assert r.inventory(root,max_files=1)==(0,{'parent':0})
 finally:finish()

@pytest.mark.parametrize('kind',['absent','symlink','file'])
def test_inventory_root_is_never_optional(tmp_path,kind):
 root=tmp_path/'root'
 if kind=='symlink':root.symlink_to(tmp_path,target_is_directory=True)
 elif kind=='file':root.write_text('not-directory')
 with pytest.raises((r.Failure,OSError)):r.inventory(root)


def test_child_inventory_esrch_is_vanished_not_proc_fallback(monkeypatch):
 import errno
 def vanished(path,*a,**k):raise ProcessLookupError(errno.ESRCH,'task vanished',str(path))
 monkeypatch.setattr(Path,'read_text',vanished)
 monkeypatch.setattr(r.os,'scandir',lambda *a:pytest.fail('ESRCH must not enumerate unrelated processes'))
 assert r.child_pids(987654)==[]
 assert r.proc_info(987654) is None


@pytest.mark.parametrize('error',[PermissionError(13,'denied'),OSError(5,'I/O failure'),OSError(20,'not a directory')])
@pytest.mark.parametrize('function',[r.child_pids,r.proc_info])
def test_proc_inventory_other_errors_are_not_absence(monkeypatch,error,function):
 def denied(path,*a,**k):raise error
 monkeypatch.setattr(Path,'read_text',denied)
 with pytest.raises(type(error)) as caught:function(987654)
 assert caught.value is error


def test_child_inventory_enoent_retains_bounded_ppid_fallback(monkeypatch):
 def missing(path,*a,**k):raise FileNotFoundError(str(path))
 monkeypatch.setattr(Path,'read_text',missing)
 class Entries:
  def __enter__(self):return iter([type('Entry',(),{'name':str(n)})() for n in (11,12)])
  def __exit__(self,*a):pass
 monkeypatch.setattr(r.os,'scandir',lambda path:Entries())
 monkeypatch.setattr(r,'proc_info',lambda pid:{'pid':pid,'ppid':77 if pid==11 else 88})
 assert r.child_pids(77)==[11]


def test_esrch_parent_then_adopted_descendant_next_scan(monkeypatch,tmp_path):
 import errno
 run=runner(tmp_path);run.subreaper_before=0
 parent=987650;child=987651;controller=os.getpid();gone=[False];closed=[]
 def info(pid):
  if pid==parent and not gone[0]:return {'pid':pid,'ppid':controller,'group':parent,'start':100,'state':'S'}
  if pid==child and gone[0]:return {'pid':pid,'ppid':controller,'group':child,'start':200,'state':'S'}
  return None
 def children(path,*a,**k):
  if str(path)==f'/proc/{controller}/task/{controller}/children':return str(child if gone[0] else parent)
  if str(path)==f'/proc/{parent}/task/{parent}/children':
   gone[0]=True;raise ProcessLookupError(errno.ESRCH,'exited after starttime check')
  if str(path)==f'/proc/{child}/task/{child}/children':return ''
  raise AssertionError(str(path))
 monkeypatch.setattr(r,'proc_info',info);monkeypatch.setattr(Path,'read_text',children)
 monkeypatch.setattr(r.os,'pidfd_open',lambda pid:pid);monkeypatch.setattr(r.os,'close',closed.append)
 run.scan();assert run.owned=={parent:100}
 run.scan();assert run.owned[child]==200 and child in run.pidfds and parent in closed
 # Existing identity reuse guard is still mandatory after adoption.
 run.owned[child]=201
 with pytest.raises(r.Failure,match='identity changed'):run.register(child,controller)


def test_real_short_children_and_adopted_descendant_cleanup(tmp_path):
 run=runner(tmp_path);before=r.subreaper()
 code=('import subprocess,sys; '
       '[subprocess.run([sys.executable,"-I","-S","-c","pass"],check=True) for _ in range(8)]; '
       'p=subprocess.Popen([sys.executable,"-I","-S","-c","import time; time.sleep(15)"],start_new_session=True); '
       'print(p.pid,flush=True)')
 try:
  with contextlib.redirect_stdout(io.StringIO()),pytest.raises(r.Deadline):
   run.command('short-and-adopted',[sys.executable,'-I','-S','-c',code],seconds=3,env=synthetic_child_env())
  pid=int((run.work/'logs/short-and-adopted.raw.log').read_text().strip())
  assert pid in run.owned
  run.cleanup()
  assert r.proc_info(pid) is None and not run.pidfds and r.subreaper()==before
 finally:
  # Safe owned cleanup only; never signal a stale PID directly.
  if run.subreaper_before is not None:run.cleanup()

# Timing metadata is diagnostic, never authority to extend a phase or accept a child.
def timing_record(run):
 value=run.commands[-1]['timing']
 assert value['schema']==1 and value['clock']=='PERF_COUNTER_DIAGNOSTIC_ONLY'
 assert len(value['operations'])==15 and len(json.dumps(value).encode())<4096
 assert all(isinstance(row['calls'],int) and row['calls']>=0 and row['seconds']>=0 for row in value['operations'].values())
 retained=json.loads((run.work/'commands.json').read_text())[-1]['timing']
 assert retained==value
 return value

@pytest.mark.parametrize('code',[0,17])
def test_command_timing_keeps_child_exit_and_fixed_size(tmp_path,code):
 run=runner(tmp_path)
 with contextlib.redirect_stdout(io.StringIO()):
  output,status=run.command('timed-exit',[sys.executable,'-I','-S','-c',f'print("payload");raise SystemExit({code})'],check=False,env=synthetic_child_env())
 assert status==code and output==b'payload\n', {'exit':status,'bytes':len(output),
  'sanitizerMarkers':{name:output.count(name.encode()) for name in
                      ('AddressSanitizer','LeakSanitizer','UndefinedBehaviorSanitizer')}}
 value=timing_record(run)
 assert value['failedOperationBeforeCleanup'] is None
 assert value['lastOperationBeforeCleanup']=='exit_poll'
 for name in ('preflight_disk_guard','activate_lifecycle','spawn','initial_scan','cleanup_scan','cleanup_signal','cleanup_wait','cleanup_close','cleanup_owned'):
  assert value['operations'][name]['calls']==1
 assert value['operations']['read_output']['calls']>=1
 assert value['cleanupSeconds']>=value['operations']['cleanup_wait']['seconds']
 assert b'payload' not in json.dumps(value).encode()
 run.cleanup()

def test_command_timing_preflight_exhaustion_not_scanner_cost(tmp_path,monkeypatch):
 offset=[0.0];real_clock=time.monotonic;real_diagnostic=time.perf_counter
 run=r.Runner(tmp_path,tmp_path/'owned',clock=lambda:real_clock()+offset[0],seconds=300)
 monkeypatch.setattr(r.time,'perf_counter',lambda:real_diagnostic()+offset[0])
 monkeypatch.setattr(run,'disk_guard',lambda:offset.__setitem__(0,offset[0]+4))
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(r.Deadline,match='Process ownership scan deadline'):
  run.command('preflight-exhausted',[sys.executable,'-c','print("hello")'],seconds=2)
 value=timing_record(run)
 assert value['phaseBudgetSeconds']==2
 assert value['operations']['preflight_disk_guard']['seconds']>=4
 assert value['failedOperationBeforeCleanup']=='initial_scan'
 assert value['operations']['initial_scan']['seconds']<1
 assert value['operations']['read_output']['calls']==0
 assert value['phaseElapsedBeforeCleanupSeconds']>=4
 assert r.proc_info(run.commands[-1]['pid']) is None
 run.cleanup()

def test_command_timing_zero_retained_bytes_does_not_prove_child_hang(tmp_path,monkeypatch):
 import select
 offset=[0.0];real_clock=time.monotonic;real_diagnostic=time.perf_counter
 run=r.Runner(tmp_path,tmp_path/'owned',clock=lambda:real_clock()+offset[0],seconds=300)
 monkeypatch.setattr(r.time,'perf_counter',lambda:real_diagnostic()+offset[0])
 children=[];real_popen=r.subprocess.Popen;observed={};calls=[0]
 def spawn(*args,**kwargs):
  child=real_popen(*args,**kwargs);children.append(child);return child
 monkeypatch.setattr(r.subprocess,'Popen',spawn)
 def guard():
  calls[0]+=1
  if calls[0]!=2:return
  child=children[0];end=real_clock()+3
  while real_clock()<end:
   exited=os.waitid(os.P_PID,child.pid,os.WEXITED|os.WNOHANG|os.WNOWAIT)
   if exited:break
   time.sleep(.01)
  assert exited and exited.si_status==0
  observed['pipeReady']=bool(select.select([child.stdout],[],[],0)[0])
  offset[0]+=4
 monkeypatch.setattr(run,'disk_guard',guard)
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(r.Deadline,match='Process ownership scan deadline'):
  run.command('loop-guard-exhausted',[sys.executable,'-I','-S','-c','print("already emitted",flush=True)'],seconds=2,env=synthetic_child_env())
 value=timing_record(run)
 assert observed['pipeReady'] and run.commands[-1]['bytes']==0
 assert value['failedOperationBeforeCleanup']=='loop_scan'
 assert value['operations']['loop_disk_guard']['seconds']>=4
 assert value['operations']['loop_scan']['seconds']<1
 assert value['operations']['read_output']['calls']==0
 assert not run.commands[-1]['complete'] and r.proc_info(run.commands[-1]['pid']) is None
 run.cleanup()

def test_command_timing_scan_exception_preserves_primary_and_cleanup(tmp_path,monkeypatch):
 run=runner(tmp_path);original=run.scan
 def fail_initial(*args,**kwargs):
  if not kwargs.get('cleanup'):raise PermissionError('owned scan permission refusal')
  return original(*args,**kwargs)
 monkeypatch.setattr(run,'scan',fail_initial)
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(PermissionError,match='owned scan permission refusal'):
  run.command('timed-scan-error',[sys.executable,'-c','import time;time.sleep(10)'])
 value=timing_record(run)
 assert value['failedOperationBeforeCleanup']=='initial_scan'
 assert value['operations']['cleanup_owned']['calls']==1
 assert r.proc_info(run.commands[-1]['pid']) is None
 run.cleanup()

def test_command_timing_spawn_failure_has_no_fabricated_cleanup(tmp_path,monkeypatch):
 run=runner(tmp_path)
 def refused(*args,**kwargs):raise FileNotFoundError('controlled missing executable')
 monkeypatch.setattr(r.subprocess,'Popen',refused)
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(FileNotFoundError,match='controlled missing executable'):
  run.command('timed-spawn-error',['/controlled/not-an-executable'])
 value=timing_record(run)
 assert value['failedOperationBeforeCleanup']=='spawn'
 assert all(row['calls']==0 for name,row in value['operations'].items() if name.startswith('cleanup_'))
 run.cleanup()

def test_command_timing_cleanup_failure_remains_secondary(tmp_path,monkeypatch):
 run=runner(tmp_path);original=run.scan;failed=[False]
 def scan(*args,**kwargs):
  if kwargs.get('cleanup') and not failed[0]:
   failed[0]=True;raise PermissionError('controlled cleanup scan refusal')
  return original(*args,**kwargs)
 monkeypatch.setattr(run,'scan',scan)
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(r.Failure,match='timed-secondary exit 17') as caught:
  run.command('timed-secondary',[sys.executable,'-I','-S','-c','raise SystemExit(17)'],env=synthetic_child_env())
 value=timing_record(run)
 assert value['failedOperationBeforeCleanup'] is None
 assert value['firstFailedOperation']=='cleanup_scan'
 assert value['lastOperationBeforeCleanup']=='exit_poll'
 assert 'controlled cleanup scan refusal' in run.commands[-1]['cleanupError']
 assert any('controlled cleanup scan refusal' in note for note in caught.value.__notes__)
 assert run.commands[-1]['exit']==17 and r.proc_info(run.commands[-1]['pid']) is None
 with pytest.raises(r.Failure,match='Incomplete command evidence'):run.cleanup()

def test_command_timing_retained_by_exact_command_reference(tmp_path,monkeypatch):
 from tools.eight_view_host import controller
 # Timing-only fixture: reconstruction admission is independently exercised.
 monkeypatch.setattr(controller,'validate_measurement_reference',lambda *a:None)
 run=runner(tmp_path)
 with contextlib.redirect_stdout(io.StringIO()):
  run.command('timed-retained',[sys.executable,'-I','-S','-c','print("fixed evidence")'],env=synthetic_child_env())
 timing_record(run);raw=(run.work/'commands.json').read_bytes()
 receipt={'status':'PASS','commandTranscriptRequired':True,'commandsReference':controller.command_reference(run)}
 out=controller.evidence_package(run,receipt,tmp_path/'evidence')
 stored=json.loads((out/'receipt.json').read_text())
 assert stored['status']=='PASS' and stored['evidence']['complete'] is True
 assert stored['commandsReference']['sha256']==hashlib.sha256(raw).hexdigest()
 assert (out/'commands.json').read_bytes()==raw and 'commands' not in stored
 assert json.loads(raw)[0]['timing']==run.commands[0]['timing']
 run.cleanup()


def test_next_reviewed_host_guard_matches_workflow_and_keeps_closed_scope():
 workflow=(ROOT/'.github/workflows/eight-view-host-diagnostic.yml').read_text()
 assert p.BASE_SHA=='80925b88371d7dac2a5fc18698d5a3acb94c77e7'
 assert p.MESSAGE=='test: retain fixed-capture pose replay evidence'
 assert "github.event.before == '"+p.BASE_SHA+"'" in workflow
 assert "github.event.head_commit.message == '"+p.MESSAGE+"'" in workflow
 for clause in ("github.event_name == 'push'", "github.ref == 'refs/heads/main'",
  "github.event.ref == 'refs/heads/main'", "github.event.repository.default_branch == 'main'",
  "toJSON(github.event.repository.private) == 'false'", "toJSON(github.event.created) == 'false'",
  "toJSON(github.event.deleted) == 'false'", "toJSON(github.event.forced) == 'false'",
  "github.run_attempt == '1'", 'github.event.after == github.sha',
  'github.event.head_commit.id == github.sha', 'persist-credentials: false',
  'contents: read', 'timeout-minutes: 75', 'fetch-depth: 2', 'retention-days: 1'):
  assert clause in workflow
 assert 'workflow_dispatch' not in workflow
 assert p.ALLOWED_PATHS==frozenset(('.github/workflows/eight-view-host-diagnostic.yml', 'SOURCE_MANIFEST.sha256', 'docs/development/REAL_THREE_PNP_REPLAY.md', 'docs/development/experiments/eight-view-sparse-runtime-successor/RealLicensedThreeCompleteControllerHostTest.kt', 'docs/development/experiments/eight-view-sparse-runtime-successor/RealThreePnpReplay.kt', 'docs/development/experiments/eight-view-sparse-runtime-successor/RealThreePnpReplayCheck.kt', 'docs/development/experiments/eight-view-sparse-runtime-successor/SHA256SUMS', 'tests/test_eight_view_host.py', 'tests/test_real_three_controller_source.py', 'tests/test_real_three_pnp_replay.py', 'tools/eight_view_host/controller.py', 'tools/eight_view_host/pnp_replay.py', 'tools/eight_view_host/policy.py'))
 from tools.eight_view_host import experiments
 assert experiments.ACTIVE==experiments.REAL_THREE
 assert experiments.artifact_budget(experiments.REAL_THREE)==56*1024**2
 env,event=valid_event();event['before']=p.BASE_SHA;event['head_commit']['message']=p.MESSAGE
 assert p.event_guard(env,event)==SHA
 event['head_commit']['message']+='\n'
 with pytest.raises(r.Failure):p.event_guard(env,event)
 event['head_commit']['message']=p.MESSAGE;event['before']='e493872e5455444e3a7cc32f9476927b14eb3b35'
 with pytest.raises(r.Failure):p.event_guard(env,event)


def test_synthetic_policy_does_not_change_native_runner_boundary(tmp_path, monkeypatch):
 # Do not execute the synthetic library: observe the real default launch contract.
 monkeypatch.setenv('LD_PRELOAD', '/synthetic/retained-native-runtime.so')
 monkeypatch.setenv('RF_NATIVE_LIBRARY', '/synthetic/retained-candidate.so')
 monkeypatch.setenv('ASAN_OPTIONS', 'detect_leaks=1:halt_on_error=1')
 observed=[]
 def capture(argv, **kwargs):
  observed.append((argv,kwargs.get('env')))
  raise OSError('synthetic spawn observation')
 monkeypatch.setattr(r.subprocess,'Popen',capture)
 run=runner(tmp_path)
 with contextlib.redirect_stdout(io.StringIO()),pytest.raises(OSError,match='synthetic spawn observation'):
  run.command('native-boundary',['/synthetic/native-candidate'])
 assert len(observed)==1
 for key in ('LD_PRELOAD','RF_NATIVE_LIBRARY','ASAN_OPTIONS'):
  assert observed[0][1][key]==os.environ[key], key
 assert run.commands[0]['environmentBoundary']=='INHERITED_RUNTIME'
 assert os.environ['LD_PRELOAD']=='/synthetic/retained-native-runtime.so'
 assert os.environ['RF_NATIVE_LIBRARY']=='/synthetic/retained-candidate.so'
 assert os.environ['ASAN_OPTIONS']=='detect_leaks=1:halt_on_error=1'
