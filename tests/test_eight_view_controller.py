"""Controller source/admission/failure regressions: no SDK, Gradle or pipeline executed."""
import contextlib,io,json,os,subprocess,sys,types
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import tools
SOURCE=Path(os.environ.get('RF_SOURCE_ROOT',ROOT));sys.path.append(str(SOURCE));tools.__path__.append(str(SOURCE/'tools'))
from tools.eight_view_host import runtime as r,policy as p,controller as ctl
BASE='a'*40;SHA='b'*40

def git(root,*args):
 return subprocess.run(['git',*args],cwd=root,env={**os.environ,'GIT_AUTHOR_NAME':'fixture','GIT_AUTHOR_EMAIL':'fixture@example.invalid','GIT_COMMITTER_NAME':'fixture','GIT_COMMITTER_EMAIL':'fixture@example.invalid'},stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=10).stdout.decode().strip()

class SourceRunner:
 def __init__(self,root):self.root=root;self.commands=[]
 def command(self,label,args,**kw):
  self.commands.append(label)
  if label=='source-manifest':return b'verified\n',0 # Metadata test only; production invokes actual manifest verifier.
  return (git(self.root,*args[1:])+'\n').encode(),0

def repository(tmp_path):
 root=tmp_path/'repo';root.mkdir();git(root,'init','-q');(root/'SOURCE_MANIFEST.sha256').write_text('fixture\n');git(root,'add','.');git(root,'commit','-qm','base');base=git(root,'rev-parse','HEAD')
 file=root/'tools/eight_view_host/controller.py';file.parent.mkdir(parents=True);file.write_text('frozen fixture\n');git(root,'add','.');git(root,'commit','-qm','trigger');head=git(root,'rev-parse','HEAD')
 return root,base,head

def test_real_git_parent_paths_and_clean_tree(tmp_path):
 root,base,head=repository(tmp_path);run=SourceRunner(root)
 proof=p.source_guard(run,head,base,{'tools/eight_view_host/controller.py'})
 assert proof['parentSha']==base and proof['sourceSha']==head and 'source-manifest' in run.commands

@pytest.mark.parametrize('case',['dirty','wrong-head','wrong-parent','path-mismatch','no-parent'])
def test_source_guards_reject_before_build(tmp_path,case):
 root,base,head=repository(tmp_path);allowed={'tools/eight_view_host/controller.py'}
 if case=='dirty':(root/'untracked').write_text('foreign')
 if case=='wrong-head':head='f'*40
 if case=='wrong-parent':base='f'*40
 if case=='path-mismatch':allowed={'not-allowed'}
 if case=='no-parent':git(root,'checkout','--detach',base);head=base
 with pytest.raises(r.Failure):p.source_guard(SourceRunner(root),head,base,allowed)

def test_shallow_one_missing_parent_rejected(tmp_path):
 root,base,head=repository(tmp_path);clone=tmp_path/'shallow'
 subprocess.run(['git','clone','--depth=1',root.as_uri(),str(clone)],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=10)
 with pytest.raises(r.Failure,match='Missing history'):p.source_guard(SourceRunner(clone),head,base,{'tools/eight_view_host/controller.py'})

def test_merge_ancestry_rejected(tmp_path):
 root,base,head=repository(tmp_path);git(root,'checkout','-qb','side',base);(root/'side').write_text('side');git(root,'add','.');git(root,'commit','-qm','side');side=git(root,'rev-parse','HEAD');git(root,'checkout','--detach',head);git(root,'merge','--no-ff','-m','merge',side);merge=git(root,'rev-parse','HEAD')
 with pytest.raises(r.Failure,match='merge'):p.source_guard(SourceRunner(root),merge,head,{'side'})

@pytest.mark.parametrize("postcheck_fails",[False,True])
def test_sdk_failure_still_checks_original_license_snapshot(tmp_path,monkeypatch,postcheck_fails):
 run=r.Runner(tmp_path,tmp_path/'work',env={'PATH':os.environ['PATH']});sdk=tmp_path/'sdk';java=tmp_path/'jdk';(java/'bin').mkdir(parents=True);(java/'bin/java').write_text('stub');(java/'release').write_text('JAVA_VERSION="17.0.fixture"\n');manager=sdk/'cmdline-tools/latest/bin/sdkmanager';manager.parent.mkdir(parents=True);manager.write_text('must not execute')
 run.env.update(JAVA_HOME_17_X64=str(java),ANDROID_HOME=str(sdk));original=Path.read_text
 monkeypatch.setattr(Path,'read_text',lambda self,*a,**k:'VERSION_ID="24.04"' if str(self)=='/etc/os-release' else original(self,*a,**k))
 monkeypatch.setattr(ctl,'license_snapshot',lambda root:{'existing':'digest'});seen=[]
 def verify(root,snapshot):
  seen.append((root,snapshot))
  if postcheck_fails:raise r.Failure('license snapshot changed')
 monkeypatch.setattr(ctl,'verify_license_snapshot',verify)
 def command(label,*args,**kw):
  if label=='sdk-explicit-existing-licenses':raise r.Deadline('SDK interrupted')
  return b'',0
 run.command=command
 with pytest.raises(r.Deadline,match='SDK interrupted') as caught:ctl.prepare_environment(run)
 assert seen==[(sdk,{'existing':'digest'})] and run.sdk_license_state==seen[0]
 if postcheck_fails:assert run.evidence_errors and any('license snapshot' in note for note in caught.value.__notes__)

class MainRunner(r.Runner):
 instances=[]
 def __init__(self,*args,**kw):super().__init__(*args,**kw);self.__class__.instances.append(self)
 def command(self,label,args,**kw):
  if label=='restore-normal-test-compilation' and getattr(self,'fail_restore',False):raise r.Failure('restoration failed')
  return b'',0
 def cleanup(self):
  if getattr(self,'late_cancel',False):self.signal=15
  if self.signal:raise r.Cancelled('external cancellation')

def main_fixture(tmp_path,monkeypatch):
 root=tmp_path/'repo';root.mkdir();event=tmp_path/'event.json';event.write_text('{}');temp=tmp_path/'runner';temp.mkdir()
 monkeypatch.setenv('RUNNER_TEMP',str(temp));monkeypatch.setenv('GITHUB_EVENT_PATH',str(event));monkeypatch.setattr(ctl,'ROOT',root);monkeypatch.setattr(ctl,'Runner',MainRunner)
 monkeypatch.setattr(p,'event_guard',lambda *a:SHA);monkeypatch.setattr(p,'source_guard',lambda *a:{'sourceSha':SHA})
 monkeypatch.setattr(ctl,'load_adapter',lambda *a:(root,object()));monkeypatch.setattr(ctl,'prepare_environment',lambda *a:(None,None,{'fixture':'not-runtime'}))
 monkeypatch.setattr(ctl,'prepare_runtime',lambda *a:(None,{}, {},{}));monkeypatch.setattr(ctl.capability,'build',lambda *a:None)
 return temp

def test_original_probe_failure_survives_restoration_failure(tmp_path,monkeypatch):
 temp=main_fixture(tmp_path,monkeypatch)
 def probe(run,*args):run.pipeline_attempts=1;run.fail_restore=True;raise r.Failure('original pipeline failure')
 monkeypatch.setattr(ctl,'run_probe',probe)
 with contextlib.redirect_stdout(io.StringIO()):assert ctl.main()==1
 receipt=json.loads((temp/'rf-eight-evidence/receipt.json').read_text())
 assert receipt['firstFailure']['message']=='original pipeline failure' and receipt['status']=='FAIL' and receipt['pipelineAttempts']==1
 assert any(x['phase']=='restoration' for x in receipt['secondaryErrors'])

def test_failure_before_source_admission_never_calls_sdk(tmp_path,monkeypatch):
 temp=main_fixture(tmp_path,monkeypatch);called=[]
 monkeypatch.setattr(p,'event_guard',lambda *a:(_ for _ in ()).throw(r.Blocked('NOT ARMED')))
 monkeypatch.setattr(ctl,'prepare_environment',lambda *a:called.append('SDK'))
 with contextlib.redirect_stdout(io.StringIO()):assert ctl.main()==1
 receipt=json.loads((temp/'rf-eight-evidence/receipt.json').read_text())
 assert called==[] and receipt['pipelineAttempts']==0 and receipt['status']=='BLOCKED'

def test_successful_test_cannot_override_late_cleanup_cancel(tmp_path,monkeypatch):
 temp=main_fixture(tmp_path,monkeypatch)
 def probe(run,*args):run.late_cancel=True;return {'test':'fixture success'}
 monkeypatch.setattr(ctl,'run_probe',probe)
 with contextlib.redirect_stdout(io.StringIO()):assert ctl.main()==1
 receipt=json.loads((temp/'rf-eight-evidence/receipt.json').read_text())
 assert receipt['status']=='CANCELLED' and receipt['ownedCleanup']=='FAIL'

def test_signal_during_final_frame_downgrades_artifact(tmp_path,monkeypatch):
 # Signal-only fixture; actual measurement admission is exercised independently.
 monkeypatch.setattr(ctl,'validate_measurement_reference',lambda *a:None)
 temp=main_fixture(tmp_path,monkeypatch);monkeypatch.setattr(ctl,'run_probe',lambda *args:{'test':'fixture success'})
 original=ctl.frame
 def framed(label,raw,*args):
  original(label,raw,*args)
  if label=='terminal-receipt':os.kill(os.getpid(),15)
 monkeypatch.setattr(ctl,'frame',framed)
 with contextlib.redirect_stdout(io.StringIO()):assert ctl.main()==1
 receipt=json.loads((temp/'rf-eight-evidence/receipt.json').read_text())
 assert receipt['status']=='CANCELLED'

def provider_fixture(tmp_path):
 run=types.SimpleNamespace(work=tmp_path,remaining=lambda cap,cleanup=False:cap);(tmp_path/'measurement').mkdir();cap=tmp_path/'capability.json';cap.write_text('source-bound unit fixture')
 native={key:{'path':str(tmp_path/(key+'.so')),'sha256':str(i)*64} for i,key in enumerate(('opencv','shim','engine'),1)}
 dependency={'path':'/usr/lib/x86_64-linux-gnu/libc.so.6','sha256':'a'*64};provider={'path':str(tmp_path/'dist.jar'),'sha256':'b'*64,'entry':'native/linux/x86_64/libandroid_runtime.so','entrySha256':'c'*64,'entryBytes':123}
 verified={'native':native,'runtimeProvider':provider,'opencvElf':{'providers':{'libc.so.6':dependency}},'scannerElf':{key:{'providers':{'libc.so.6':dependency}} for key in ('engine','shim')}}
 for phase in ('after-bitmap-room','after-room-reopen'):
  value={'schema':1,'phase':phase,'status':'OBSERVED_PROVIDER_IDENTITIES_MATCH','sourceSha':SHA,'policySha256':r.sha(ctl.capability.POLICY),'capabilitySha256':r.sha(cap),'sqliteVersion':'3.46.1','activity':['actual Bitmap pixel roundtrip','actual Room SELECT sqlite_version()'],'scannerProviders':[{'role':key,**entry,'inode':i} for i,(key,entry) in enumerate(native.items(),1)],'scannerElfDependencies':[{**dependency,'inode':4}],'androidRuntimeProvider':{'path':str(tmp_path/'runtime-tmp/native/libandroid_runtime.so'),'sha256':provider['entrySha256'],'bytes':123,'inode':5,'archivePath':provider['path'],'archiveSha256':provider['sha256'],'archiveEntry':provider['entry']}}
  (tmp_path/'measurement'/('runtime-providers-'+phase+'.json')).write_text(json.dumps(value))
 return run,cap,verified

def test_runtime_provider_receipts_bind_actual_source_and_reopen_identity(tmp_path):
 run,cap,verified=provider_fixture(tmp_path)
 assert len(ctl.runtime_provider_proof(run,SHA,cap,verified)['receipts'])==2

@pytest.mark.parametrize('mutation',[
 lambda v:v.update(status='UNQUALIFIED'),lambda v:v.update(sourceSha='f'*40),lambda v:v.update(capabilitySha256='f'*64),
 lambda v:v['androidRuntimeProvider'].update(path='/tmp/foreign/libandroid_runtime.so'),lambda v:v['androidRuntimeProvider'].update(inode=0),
 lambda v:v['androidRuntimeProvider'].update(sha256='f'*64),lambda v:v['scannerProviders'].pop(),lambda v:v['scannerElfDependencies'].clear(),
 lambda v:v.update(sqliteVersion='3.46.2'),
])
def test_runtime_provider_missing_foreign_changed_unqualified_rejected(tmp_path,mutation):
 run,cap,verified=provider_fixture(tmp_path);path=tmp_path/'measurement/runtime-providers-after-room-reopen.json';value=json.loads(path.read_text());mutation(value);path.write_text(json.dumps(value))
 with pytest.raises(r.Failure):ctl.runtime_provider_proof(run,SHA,cap,verified)

def test_gradle_task_scoping_and_private_jvm_paths():
 source=(ROOT/'tools/eight_view_host/probe.init.gradle').read_text()
 assert "workingDir=runtimeCwd" in source and "systemProperty 'java.io.tmpdir',runtimeTmp.canonicalPath" in source
 assert "setExcludes([]) // Override ONLY this explicit narrow task" in source
 args=ctl.gradle_args(types.SimpleNamespace(root=Path('/repo'),work=Path('/owned')),Path('/owned/config'),True)
 for flag in ('--no-daemon','--no-parallel','--max-workers=1','--offline','-Pandroid.builder.sdkDownload=false','-Pkotlin.compiler.execution.strategy=in-process'):
  assert flag in args

def failed_probe_fixture(tmp_path,monkeypatch,error,postcheck_failure=False,expired=False):
 run=r.Runner(tmp_path,tmp_path/'work',env={'PATH':os.environ['PATH']});cap=run.work/'capability.json';cap.write_text('{}');(run.work/'prepared.json').write_text('{}');config=run.work/'config.json';seen=[]
 verified={'native':{role:{'path':str(run.work/(role+'.so'))} for role in ('opencv','shim')}}
 def verify(*args):
  seen.append('capability')
  if postcheck_failure and seen.count('capability')==2:raise r.Failure('framework hash changed')
  return verified
 monkeypatch.setattr(p,'verify_capability',verify)
 monkeypatch.setattr(ctl,'classpath_check',lambda *a,**kw:seen.append('classpath'))
 monkeypatch.setattr(ctl,'runtime_classpath_proof',lambda *a:seen.append('actual-runtime-classpath') or [])
 def command(*args,**kw):
  if expired:run.end=run.clock()-1
  if isinstance(error,r.Cancelled):run.signal=15
  if error=='nonzero':return b'original JUnit failure',1
  raise error
 run.command=command
 inputs=types.SimpleNamespace(__file__=str(run.root/ctl.ADAPTER/ctl.experiments.get_profile(ctl.experiments.ACTIVE).input_module),validate=lambda path:seen.append('observations') or {'images':'fixture'})
 cfg={'experiment':ctl.experiments.ACTIVE,'selector':ctl.experiments.get_profile(ctl.experiments.ACTIVE).selector,'sourceNames':list(ctl.SOURCE_NAMES)}
 with pytest.raises(r.Failure if error=='nonzero' else type(error)) as caught:ctl.run_probe(run,SHA,config,cfg, {},cap,inputs,{'images':'fixture'})
 if error!='nonzero':assert caught.value is error
 return run,seen,caught.value

@pytest.mark.parametrize('error',[r.Deadline('selected timeout'),r.Cancelled('external cancellation'),r.Failure('selected invocation failure')])
def test_failed_selected_invocation_still_rechecks_runtime_inputs(tmp_path,monkeypatch,error):
 run,seen,caught=failed_probe_fixture(tmp_path,monkeypatch,error)
 assert seen==['capability','classpath','capability','classpath','actual-runtime-classpath','observations']
 assert run.runtime_input_postcheck['status']=='PASS' and run.pipeline_attempts==1
 if isinstance(error,r.Cancelled):assert run.signal==15

def test_failed_runtime_postcheck_never_masks_original_selected_timeout(tmp_path,monkeypatch):
 run,seen,error=failed_probe_fixture(tmp_path,monkeypatch,r.Deadline('selected timeout'),postcheck_failure=True)
 assert run.runtime_input_postcheck['status']=='FAIL' and run.evidence_errors
 assert any('framework hash changed' in note for note in error.__notes__)
 assert seen==['capability','classpath','capability']

def test_runtime_postcheck_exhausted_global_budget_is_not_claimed_complete(tmp_path,monkeypatch):
 run,seen,error=failed_probe_fixture(tmp_path,monkeypatch,r.Deadline('selected timeout'),expired=True)
 assert run.runtime_input_postcheck['status']=='TIMEOUT' and run.evidence_errors
 assert seen==['capability','classpath']


def test_nonzero_selected_exit_precedes_changed_input_secondary(tmp_path,monkeypatch):
 run,seen,error=failed_probe_fixture(tmp_path,monkeypatch,'nonzero',postcheck_failure=True)
 assert str(error)=='Actual selected sparse invocation failed; original logs/events retained'
 assert run.runtime_input_postcheck['status']=='FAIL'
 assert any('framework hash changed' in note for note in error.__notes__)


def test_failed_capability_admission_does_not_claim_pipeline_attempt(tmp_path,monkeypatch):
 temp=main_fixture(tmp_path,monkeypatch)
 monkeypatch.setattr(ctl,'run_probe',lambda *a:(_ for _ in ()).throw(r.Failure('capability admission failed')))
 with contextlib.redirect_stdout(io.StringIO()):assert ctl.main()==1
 receipt=json.loads((temp/'rf-eight-evidence/receipt.json').read_text())
 assert receipt['pipelineAttempts']==0 and receipt['firstFailure']['message']=='capability admission failed'


@pytest.mark.parametrize('name',['Api35ProviderContract.class','Api35ProviderContract$Companion.class','EvidenceFailureHandlingKt.class','RuntimeProviderIdentity$Mapping.class','EightViewSparsePrefixHostTest.class'])
def test_every_probe_compiled_type_including_nonfilename_owner_blocks_restoration(tmp_path,name):
 path=tmp_path/'app/build/tmp/kotlin-classes/debugUnitTest/com/realityforge/scanner/hostintegration'/name
 path.parent.mkdir(parents=True);path.write_bytes(b'synthetic class identity only')
 with pytest.raises(r.Failure,match='survived restoration'):ctl.verify_probe_classes_removed(tmp_path)
 path.unlink();ctl.verify_probe_classes_removed(tmp_path)


def test_native_inspection_never_uses_compiler_environment():
 import ast
 tree=ast.parse((ROOT/'tools/eight_view_host/capability.py').read_text())
 labels=set()
 for node in ast.walk(tree):
  if not isinstance(node,ast.Call) or not isinstance(node.func,ast.Attribute) or node.func.attr!='command' or not node.args:continue
  if not isinstance(node.args[0],ast.Constant) or node.args[0].value!='scanner-symbols':continue
  labels.add(node.args[0].value)
  assert not any(key.arg=='env' for key in node.keywords)
 assert labels=={'scanner-symbols'}


def runtime_capture_fixture(tmp_path):
 run=r.Runner(tmp_path,tmp_path/'work')
 prepared={key:('fixture-'+key) for key in ctl.CLASSPATH_FIELDS}
 prepared.update(classpath=[{'path':'/first.jar'},{'path':'/second.jar'}],owners={'owner':{'container':'/first.jar'}})
 for name in ('runtime-prepare.json','runtime-before-fork.json'):(run.work/name).write_text(json.dumps(prepared))
 return run,prepared


def test_actual_ordered_runtime_captures_equal_immutable_baseline(tmp_path):
 run,prepared=runtime_capture_fixture(tmp_path)
 assert len(ctl.runtime_classpath_proof(run,prepared))==2
 source=(ROOT/'tools/eight_view_host/probe.init.gradle').read_text()
 assert "assert sha(prepared.bytes)==cfg.preparedSha256" in source
 assert "record==baseline.findAll" in source
 assert "!prepared.exists()" in source and "runtime-before-fork" in source


@pytest.mark.parametrize('mutation',[
 lambda value:value['classpath'].append({'path':'/extra.jar'}),lambda value:value['classpath'].pop(),
 lambda value:value['classpath'].reverse(),lambda value:value['owners']['owner'].update(container='/foreign.jar'),
 lambda value:value.update(classpathString='/foreign:classpath')])
def test_actual_added_removed_reordered_or_foreign_classpath_rejected(tmp_path,mutation):
 run,prepared=runtime_capture_fixture(tmp_path);path=run.work/'runtime-before-fork.json';value=json.loads(path.read_text());mutation(value);path.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='immutable baseline'):ctl.runtime_classpath_proof(run,prepared)


def test_classpath_directory_bomb_is_bounded_before_hashes(tmp_path,monkeypatch):
 monkeypatch.setattr(ctl,'classpath_schema_check',lambda *a,**kw:None)
 directory=tmp_path/'classes';directory.mkdir();(directory/'some.class').write_bytes(b'fixture')
 seen=[];original=ctl.inventory
 def bounded(*args,**kwargs):seen.append('inventory');return original(*args,**{**kwargs,'max_files':0})
 monkeypatch.setattr(ctl,'inventory',bounded);monkeypatch.setattr(ctl,'bounded_hash',lambda *a:seen.append('hash'))
 with pytest.raises(r.Failure,match='count limit'):ctl.classpath_check({'classpath':[{'path':str(directory),'kind':'directory','files':[]}]})
 assert seen==['inventory']


def test_packaging_directory_bomb_precedes_content_reads(tmp_path,monkeypatch):
 run=r.Runner(tmp_path,tmp_path/'work');(run.work/'measurement').mkdir()
 for i in range(1991):(run.work/'measurement'/str(i)).mkdir()
 monkeypatch.setattr(ctl,'bounded_bytes',lambda *a:(_ for _ in ()).throw(AssertionError('must not read contents')))
 with pytest.raises(r.Failure,match='count limit'):ctl.evidence_package(run,{'status':'FAIL'},tmp_path/'out')


def test_evidence_seal_inventories_before_hashing_and_rejects_symlink(tmp_path,monkeypatch):
 out=tmp_path/'out';out.mkdir();(out/'foreign').symlink_to(tmp_path/'missing')
 monkeypatch.setattr(ctl,'bounded_hash',lambda *a:(_ for _ in ()).throw(AssertionError('must not hash')))
 with pytest.raises(r.Failure,match='symlink'):ctl.seal_receipt(out,{'status':'FAIL'})


def test_evidence_expired_deadline_never_claims_sealed_success(tmp_path):
 run=r.Runner(tmp_path,tmp_path/'work');run.end=run.clock()-1
 with pytest.raises(r.Deadline):ctl.evidence_package(run,{'status':'PASS'},tmp_path/'out')


def test_artifact_io_failure_still_frames_original_failure(tmp_path,monkeypatch):
 temp=main_fixture(tmp_path,monkeypatch)
 def probe(run,*args):
  (run.work/'measurement').mkdir();(run.work/'measurement/failure.json').write_text('{"reason":"original production failure"}')
  raise r.Failure('original pipeline failure')
 monkeypatch.setattr(ctl,'run_probe',probe)
 monkeypatch.setattr(ctl,'evidence_package',lambda *a:(_ for _ in ()).throw(OSError('artifact filesystem unavailable')))
 monkeypatch.setattr(ctl,'seal_receipt',lambda *a:(_ for _ in ()).throw(OSError('artifact filesystem unavailable')))
 seen=[];monkeypatch.setattr(ctl,'frame',lambda label,raw,*a:seen.append((label,json.loads(raw))))
 with contextlib.redirect_stdout(io.StringIO()):assert ctl.main()==1
 terminal=next(value for label,value in seen if label=='terminal-incomplete')
 assert terminal['firstFailure']['message']=='original pipeline failure' and terminal['status']=='FAIL'
 assert ('original-failure-incomplete-package',{'reason':'original production failure'}) in seen


def test_bounded_read_and_hash_check_cleanup_deadline(tmp_path):
 run=r.Runner(tmp_path,tmp_path/'work');path=run.work/'payload';path.write_bytes(b'x'*65537)
 assert ctl.bounded_hash(path,65537,run)==r.sha(path)
 with pytest.raises(r.Failure,match='byte limit'):ctl.bounded_bytes(path,65536,run)
 run.end=run.clock()-1
 with pytest.raises(r.Deadline):ctl.bounded_hash(path,65537,run)
 with pytest.raises(r.Deadline):ctl.bounded_bytes(path,65537,run)


def test_incomplete_console_withholds_secret_and_never_emits_workflow_commands(tmp_path,monkeypatch):
 run=r.Runner(tmp_path,tmp_path/'work');(run.work/'measurement').mkdir()
 (run.work/'measurement/failure.json').write_text('{"message":"password=synthetic-secret"}')
 seen=[];monkeypatch.setattr(ctl,'frame',lambda label,raw,*a:seen.append((label,raw)))
 ctl.emit_incomplete_essential(run,{'status':'FAIL','firstFailure':{'type':'Failure','message':'password=synthetic-secret\n::warning::never-raw'}})
 assert all(b'synthetic-secret' not in raw and b'::warning::' not in raw for _,raw in seen)
 assert [name for name,_ in seen]==['terminal-incomplete','original-failure-withheld']


def test_classpath_normal_work_cannot_consume_cleanup_reserve(tmp_path,monkeypatch):
 monkeypatch.setattr(ctl,'normalization_check',lambda *a,**kw:None)
 monkeypatch.setattr(ctl,'agp_resources_check',lambda *a,**kw:None)
 monkeypatch.setattr(ctl,'classpath_schema_check',lambda *a,**kw:None)
 run=r.Runner(tmp_path,tmp_path/'work');path=run.work/'one.jar';path.write_bytes(b'fixture')
 record={'classpath':[{'path':str(path),'kind':'jar','sha256':r.sha(path)}]}
 run.work_end=run.clock()-1
 with pytest.raises(r.Deadline):ctl.classpath_check(record,run)
 ctl.classpath_check(record,run,cleanup=True)


def test_global_deadline_after_initial_pass_seal_invalidates_artifact(tmp_path,monkeypatch):
 # Deadline-only orchestration fixture; real measurement admission is tested separately.
 monkeypatch.setattr(ctl,'validate_measurement_reference',lambda *a:None)
 temp=main_fixture(tmp_path,monkeypatch);monkeypatch.setattr(ctl,'run_probe',lambda *a:{'fixture':'success'})
 original=ctl.evidence_package
 def package(run,receipt,destination):
  result=original(run,receipt,destination)
  assert json.loads((result/'receipt.json').read_text())['status']=='PASS'
  run.end=run.clock()-1
  return result
 monkeypatch.setattr(ctl,'evidence_package',package)
 output=io.StringIO()
 with contextlib.redirect_stdout(output):assert ctl.main()==1
 evidence=temp/'rf-eight-evidence';receipt=json.loads((evidence/'receipt.json').read_text())
 assert receipt['status']=='TIMEOUT' and receipt['evidence']['complete'] is False
 assert (evidence/'TERMINAL_INVALIDATION.json').is_file()
 assert r.sha(evidence/'receipt.json')+'  receipt.json' in (evidence/'SHA256SUMS').read_text()
 assert 'terminal-emergency-invalidation' in output.getvalue()


def test_emergency_status_is_invalidated_before_malformed_manifest_failure(tmp_path):
 out=tmp_path/'out';out.mkdir();(out/'receipt.json').write_text('{"status":"PASS"}');(out/'SHA256SUMS').write_text('malformed')
 with pytest.raises(r.Failure,match='manifest malformed'):ctl.emergency_terminal_downgrade(out,{'status':'TIMEOUT'})
 assert json.loads((out/'receipt.json').read_text())['status']=='TIMEOUT'


def test_normal_seal_never_publishes_unexpected_receipt_secret(tmp_path):
 out=tmp_path/'out';out.mkdir()
 with pytest.raises(r.Failure,match='secret marker'):ctl.seal_receipt(out,{'status':'FAIL','message':'password=synthetic-secret'})
 assert not (out/'receipt.json').exists()

@pytest.mark.parametrize('secret',[False,True])
@pytest.mark.parametrize('compact',[False,True])
def test_bounded_failure_preserves_original_identity_in_artifact_and_console(tmp_path,monkeypatch,secret,compact):
 import hashlib
 run=r.Runner(tmp_path,tmp_path/'work');out=tmp_path/'out';out.mkdir()
 message='original reason '+('界'*100000)+(' password=late-secret' if secret else '')
 raw=message.encode();receipt={'status':'FAIL','firstFailure':{'type':'Failure','status':'BLOCKED','message':message},'secondaryErrors':[{'type':'OSError','phase':'cleanup','message':'secondary reason'}]}
 if compact:receipt['oversizedOtherField']='x'*300000
 if compact:artifact=ctl.emergency_terminal_downgrade(out,receipt)
 else:ctl.seal_receipt(out,receipt);artifact=(out/'receipt.json').read_bytes()
 seen=[];monkeypatch.setattr(ctl,'frame',lambda label,raw,*a:seen.append(raw))
 ctl.emit_incomplete_essential(run,receipt)
 for payload in [artifact,*seen]:
  assert len(payload)<=256*1024
  value=json.loads(payload);failure=value['firstFailure']
  assert failure['type']=='Failure' and failure['status']=='BLOCKED'
  assert failure['messageMetadata']['bytes']==len(raw)
  assert failure['messageMetadata']['sha256']==hashlib.sha256(raw).hexdigest()
  assert value['secondaryErrors'][0]['message']=='secondary reason'
  if secret:assert failure['message']=='' and failure['messageMetadata']['withheld'] and b'late-secret' not in payload
  else:assert failure['message'].startswith('original reason ') and failure['messageMetadata']['truncated']


def test_secondary_failure_omissions_explicit(tmp_path):
 receipt={'status':'FAIL','secondaryErrors':[{'type':'Failure','message':str(i)} for i in range(100)]}
 result=ctl.compact_receipt(receipt)
 assert len(result['secondaryErrors'])==16 and result['secondaryErrorsOmitted']['count']==84
 assert len(result['secondaryErrorsOmitted']['sha256'])==64

@pytest.mark.parametrize('mutation',['missing','changed','count','hash','path','withheld','owned','missing-ref'])
def test_command_reference_failure_cannot_seal_complete_pass(tmp_path,mutation):
 run=r.Runner(tmp_path,tmp_path/'work');run.commands=[{'label':'synthetic','complete':True}];r.atomic_json(run.work/'commands.json',run.commands)
 ref=ctl.command_reference(run);out=tmp_path/'out';out.mkdir();(out/'commands.json').write_bytes((run.work/'commands.json').read_bytes())
 receipt={'status':'PASS','commandTranscriptRequired':True,'commandsReference':ref,'evidence':{'complete':True}}
 if mutation=='missing':(out/'commands.json').unlink()
 elif mutation=='changed':(out/'commands.json').write_text('[]')
 elif mutation=='count':ref['count']+=1
 elif mutation=='hash':ref['sha256']='0'*64
 elif mutation=='path':ref['path']='../commands.json'
 elif mutation=='withheld':(out/'commands.json').write_text('[{"message":"password=secret"}]')
 elif mutation=='owned':run.commands=[]
 else:receipt.pop('commandsReference')
 ctl.seal_receipt(out,receipt,run)
 value=json.loads((out/'receipt.json').read_text());assert value['status']=='FAIL' and value['evidence']['complete'] is False
 assert 'commandTranscriptFailure' in value


def test_large_command_transcript_retained_not_embedded(tmp_path):
 run=r.Runner(tmp_path,tmp_path/'work');run.commands=[{'label':'synthetic','argv':['x'*1000]} for _ in range(300)]
 r.atomic_json(run.work/'commands.json',run.commands);original=(run.work/'commands.json').read_bytes()
 assert len(original)>256*1024
 receipt={'status':'FAIL','commandsReference':ctl.command_reference(run),'commandTranscriptRequired':True,'firstFailure':ctl.failure_descriptor(r.Failure('original failure'))}
 out=ctl.evidence_package(run,receipt,tmp_path/'evidence');value=json.loads((out/'receipt.json').read_text())
 assert (out/'commands.json').read_bytes()==original and 'commands' not in value
 assert value['commandsReference']['count']==300 and value['firstFailure']['message']=='original failure'
 assert (out/'receipt.json').stat().st_size<16384


def test_actual_retained_e1fee_command_transcript_replay(tmp_path):
 import hashlib,zipfile
 path=os.environ.get('RF_E1FEE_ARTIFACT')
 if not path:pytest.skip('Explicit retained e1fee artifact required; no download in test')
 assert hashlib.sha256(Path(path).read_bytes()).hexdigest()=='594ac0907f5f99633a242a6ac4ab3229b63263178c2b698586f87cc84911f075'
 with zipfile.ZipFile(path) as archive:raw=archive.read('commands.json')
 assert len(raw)==282347
 run=r.Runner(tmp_path,tmp_path/'work');run.commands=json.loads(raw);(run.work/'commands.json').write_bytes(raw)
 receipt={'status':'FAIL','commandsReference':ctl.command_reference(run),'commandTranscriptRequired':True,
  'firstFailure':ctl.failure_descriptor(r.Failure('synthetic replay marker, not reconstructed historical failure'))}
 out=ctl.evidence_package(run,receipt,tmp_path/'evidence')
 assert (out/'commands.json').read_bytes()==raw
 assert json.loads((out/'receipt.json').read_text())['commandsReference']['count']==len(run.commands)
 assert (out/'receipt.json').stat().st_size<16384

@pytest.mark.parametrize('package_failure',[False,True])
def test_main_normal_and_compact_frames_keep_original_bounded_failure(tmp_path,monkeypatch,package_failure):
 temp=main_fixture(tmp_path,monkeypatch)
 def probe(*args):raise r.Blocked('original blocked reason '+('界'*100000))
 monkeypatch.setattr(ctl,'run_probe',probe)
 if package_failure:monkeypatch.setattr(ctl,'evidence_package',lambda *args:(_ for _ in ()).throw(OSError('secondary artifact error')))
 seen=[];monkeypatch.setattr(ctl,'frame',lambda label,raw,*args:seen.append((label,json.loads(raw))))
 with contextlib.redirect_stdout(io.StringIO()):assert ctl.main()==1
 final=next(value for label,value in seen if label==('terminal-incomplete' if package_failure else 'terminal-receipt'))
 assert final['status']=='BLOCKED' and final['firstFailure']['type']=='Blocked'
 assert final['firstFailure']['message'].startswith('original blocked reason') and final['firstFailure']['messageMetadata']['truncated']
 assert 'commands' not in final and final['commandsReference']['path']=='commands.json'
 if package_failure:assert any(x['message']=='secondary artifact error' for x in final['secondaryErrors'])


def test_secret_command_transcript_withheld_never_complete_pass(tmp_path):
 run=r.Runner(tmp_path,tmp_path/'work');run.commands=[{'label':'synthetic','argv':['password=synthetic-late-secret']}]
 r.atomic_json(run.work/'commands.json',run.commands)
 receipt={'status':'PASS','commandTranscriptRequired':True,'commandsReference':ctl.command_reference(run)}
 out=ctl.evidence_package(run,receipt,tmp_path/'out');raw=(out/'receipt.json').read_bytes();value=json.loads(raw)
 assert value['status']=='FAIL' and value['evidence']['complete'] is False and not (out/'commands.json').exists()
 assert b'synthetic-late-secret' not in raw and value['evidence']['withheld'][0]['path']=='commands.json'


def test_compact_console_withholds_unexpected_metadata_secret(tmp_path,monkeypatch):
 run=r.Runner(tmp_path,tmp_path/'work');seen=[];monkeypatch.setattr(ctl,'frame',lambda label,raw,*a:seen.append(raw))
 receipt={'status':'FAIL','sourceSha':'password=secret','firstFailure':{'type':'Failure','message':'original safe reason'}}
 ctl.emit_incomplete_essential(run,receipt)
 assert b'password=secret' not in seen[0]
 assert json.loads(seen[0])['firstFailure']['message']=='original safe reason'

@pytest.mark.parametrize('valid_metadata',[False,True])
def test_preformatted_failure_is_rebounded_idempotently(tmp_path,valid_metadata):
 descriptor=ctl.failure_descriptor(r.Failure('original message'))
 original=dict(descriptor['messageMetadata'])
 if not valid_metadata:descriptor['messageMetadata']={'untrusted':'x'*300000}
 descriptor.update(message='界'*200000,type='T'*300000,phase='phase'*100000)
 receipt={'status':'FAIL','firstFailure':descriptor,'secondaryErrors':[descriptor]*17,'large':'x'*300000}
 value=ctl.compact_receipt(receipt);raw=json.dumps(value).encode()
 assert len(raw)<=256*1024 and value['firstFailure']['messageMetadata']['truncated']
 assert value['firstFailure']['type']=='WITHHELD_OR_OVERSIZED_LABEL'
 if valid_metadata:
  assert value['firstFailure']['messageMetadata']['sha256']==original['sha256']
  assert value['firstFailure']['messageMetadata']['bytes']==original['bytes']
 again=ctl.compact_receipt(value)
 assert again['firstFailure']['messageMetadata']==value['firstFailure']['messageMetadata']
 assert len((json.dumps(again,sort_keys=True,indent=2)+'\n').encode())<=256*1024
 out=tmp_path/'compact';out.mkdir();raw=ctl.emergency_terminal_downgrade(out,receipt)
 assert len(raw)<=256*1024 and raw==(out/'receipt.json').read_bytes()


def test_pass_seal_requires_transcript_even_without_required_marker(tmp_path):
 out=tmp_path/'out';out.mkdir();receipt={'status':'PASS','evidence':{'complete':True}}
 ctl.seal_receipt(out,receipt)
 assert receipt['status']=='FAIL' and receipt['evidence']['complete'] is False
 assert 'commandTranscriptFailure' in receipt


def test_genuine_empty_command_transcript_reference_is_valid(tmp_path):
 run=r.Runner(tmp_path,tmp_path/'work');ref=ctl.command_reference(run)
 assert ref['count']==0
 receipt={'status':'PASS','commandsReference':ref}
 ctl.validate_command_reference(run.work,receipt,run)
 assert receipt['status']=='PASS'
 assert json.loads((run.work/'commands.json').read_text())==[]

# Real checkpoint admission, unlike the orchestration-only mocks above.
@pytest.mark.parametrize('profile',[ctl.experiments.LEGACY,ctl.experiments.RELIEF,ctl.experiments.COMPLETE])
def test_checked_in_adapter_admission_and_injected_bytes(tmp_path,profile):
 package,module=ctl.load_adapter(ROOT,profile)
 assert callable(module.stage)
 rows=dict(line.split('  ')[::-1] for line in (package/'SHA256SUMS').read_text().splitlines())
 tracked=set(git(ROOT,'ls-files','--',ctl.ADAPTER).splitlines())
 assert {str(Path(ctl.ADAPTER)/name) for name in rows}==tracked-{str(Path(ctl.ADAPTER)/'SHA256SUMS')}
 import shutil
 copied=tmp_path/'sources';copied.mkdir()
 for name in ctl.source_names(profile):
  shutil.copyfile(package/name,copied/name)
  assert r.sha(copied/name)==rows[name]
 assert ctl.load_adapter(ROOT,profile)[0]==package

@pytest.mark.parametrize('case',['stale-qualified','stale-new','omit-new','omit-inputs','omit-policy','duplicate','malformed','listed-symlink','manifest-symlink'])
def test_adapter_inventory_refuses_invalid_checkpoint(tmp_path,monkeypatch,case):
 import shutil
 package=tmp_path/ctl.ADAPTER
 shutil.copytree(ROOT/ctl.ADAPTER,package,ignore=shutil.ignore_patterns('__pycache__'))
 manifest=package/'SHA256SUMS';lines=manifest.read_text().splitlines()
 if case.startswith('stale-'):
  name='QualifiedCapability.kt' if case=='stale-qualified' else 'NativeCapabilityContract.kt'
  with (package/name).open('a') as out:out.write('\n// drift\n')
 elif case.startswith('omit-'):
  name={'omit-new':'NativeCapabilityContract.kt','omit-inputs':'inputs.py','omit-policy':'policy.json'}[case]
  lines=[line for line in lines if not line.endswith('  '+name)]
 elif case=='duplicate':lines.append(lines[0])
 elif case=='malformed':lines.append('invalid')
 elif case=='listed-symlink':
  source=package/'QualifiedCapability.kt';outside=tmp_path/'outside.kt';source.rename(outside);source.symlink_to(outside)
 manifest.write_text('\n'.join(lines)+'\n')
 monkeypatch.setattr(ctl,'ADAPTER_MANIFEST',r.sha(manifest)) # Deliberately repinned invalid inventory must still refuse.
 if case=='manifest-symlink':
  outside=tmp_path/'outside-manifest';manifest.rename(outside);manifest.symlink_to(outside)
 with pytest.raises(r.Failure):ctl.load_adapter(tmp_path)

def test_adapter_manifest_pin_is_not_replaced_by_global_manifest(tmp_path):
 import shutil
 package=tmp_path/ctl.ADAPTER;shutil.copytree(ROOT/ctl.ADAPTER,package,ignore=shutil.ignore_patterns('__pycache__'))
 with (package/'SHA256SUMS').open('a') as out:out.write('\n')
 with pytest.raises(r.Blocked,match='NOT ARMED'):ctl.load_adapter(tmp_path)

@pytest.mark.parametrize('profile',[ctl.experiments.LEGACY,ctl.experiments.RELIEF,ctl.experiments.COMPLETE])
def test_real_adapter_admission_through_prepare_runtime_before_gradle(tmp_path,profile):
 package,inputs=ctl.load_adapter(ROOT,profile)
 class BeforeGradle(Exception):pass
 class AdmissionRun:
  root=ROOT
  work=tmp_path
  def command(self,label,args,**kwargs):
   assert label=='gradle-task-graph'
   assert ':app:eightPrepare' in args and '--dry-run' in args
   assert {p.name for p in (self.work/'sources').iterdir()}==set(ctl.source_names(profile))
   for name in ctl.source_names(profile):assert (self.work/'sources'/name).read_bytes()==(package/name).read_bytes()
   assert (self.work/'observation-proof.json').is_file()
   raise BeforeGradle('actual adapter staging verified; no Gradle executed')
 with pytest.raises(BeforeGradle):ctl.prepare_runtime(AdmissionRun(),SHA,package,inputs,profile)

@pytest.mark.parametrize('profile',[ctl.experiments.LEGACY,ctl.experiments.RELIEF,ctl.experiments.COMPLETE])
def test_mixed_profile_stager_is_refused_before_outputs(tmp_path,profile):
 other=ctl.experiments.RELIEF if profile==ctl.experiments.LEGACY else ctl.experiments.LEGACY
 package,module=ctl.load_adapter(ROOT,other)
 run=types.SimpleNamespace(root=ROOT,work=tmp_path)
 with pytest.raises(r.Failure,match='Mixed experiment input module'):
  ctl.prepare_runtime(run,SHA,package,module,profile)
 assert list(tmp_path.iterdir())==[]

@pytest.mark.parametrize('field',['experiment','selector','sourceNames','inputModule'])
def test_mixed_profile_runtime_is_refused_before_capability_or_attempt(tmp_path,monkeypatch,field):
 profile=ctl.experiments.RELIEF;selected=ctl.experiments.get_profile(profile);other=ctl.experiments.get_profile(ctl.experiments.LEGACY)
 cfg={'experiment':profile,'selector':selected.selector,'sourceNames':list(ctl.source_names(profile))}
 inputs=types.SimpleNamespace(__file__=str(ROOT/ctl.ADAPTER/selected.input_module))
 if field=='experiment':cfg[field]=other.id
 elif field=='selector':cfg[field]=other.selector
 elif field=='sourceNames':cfg[field]=list(ctl.source_names(other.id))
 else:inputs.__file__=str(ROOT/ctl.ADAPTER/other.input_module)
 run=types.SimpleNamespace(root=ROOT,work=tmp_path,pipeline_attempts=0)
 def forbidden(*args,**kwargs):pytest.fail('mixed profile reached capability gate')
 monkeypatch.setattr(p,'verify_capability',forbidden)
 with pytest.raises(r.Failure,match='Mixed experiment runtime binding'):
  ctl.run_probe(run,SHA,tmp_path/'config',cfg,{},tmp_path/'cap',inputs,{},profile)
 assert run.pipeline_attempts==0 and list(tmp_path.iterdir())==[]
