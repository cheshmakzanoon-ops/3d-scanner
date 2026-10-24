"""Standalone admission/evidence controls; synthetic host fixture, not qualification."""
import copy,json,os,shutil,subprocess,sys,types
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tools.eight_view_host import header_diagnostic as h,capability as c
from tools.eight_view_host.runtime import Failure,Blocked,sha


def git(root,*args):
 env={**os.environ,'GIT_AUTHOR_NAME':'fixture','GIT_AUTHOR_EMAIL':'fixture@example.invalid','GIT_COMMITTER_NAME':'fixture','GIT_COMMITTER_EMAIL':'fixture@example.invalid'}
 return subprocess.run(['git',*args],cwd=root,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=10).stdout.decode().strip()

class GitRunner:
 def __init__(self,root):self.root=root;self.calls=[]
 def command(self,label,args,**kwargs):
  self.calls.append(label);value=subprocess.run([str(v) for v in args],cwd=self.root,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=kwargs.get('seconds',10))
  if value.returncode or len(value.stdout)>kwargs.get('limit',32768):raise Failure('Fixture command failed: '+label)
  return value.stdout,0

def fixture_repository(tmp_path,monkeypatch,extra=None,omit=None):
 root=tmp_path/'repo';root.mkdir();git(root,'init','-q')
 (root/'tools').mkdir();shutil.copyfile(ROOT/'tools/source_manifest.py',root/'tools/source_manifest.py')
 for name in h.ALLOWED_PATHS:
  path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('base fixture\n')
 git(root,'add','.');subprocess.run([sys.executable,'tools/source_manifest.py','generate'],cwd=root,check=True,stdout=subprocess.PIPE);git(root,'add','SOURCE_MANIFEST.sha256');git(root,'commit','-qm','fixture base');base=git(root,'rev-parse','HEAD')
 for name in h.ALLOWED_PATHS-{'SOURCE_MANIFEST.sha256'}:
  if name!=omit:(root/name).write_text('changed fixture\n')
 if extra:(root/extra).write_text('foreign change\n')
 git(root,'add','.');subprocess.run([sys.executable,'tools/source_manifest.py','generate'],cwd=root,check=True,stdout=subprocess.PIPE);git(root,'add','SOURCE_MANIFEST.sha256');git(root,'commit','-qm','fixture diagnostic');head=git(root,'rev-parse','HEAD')
 monkeypatch.setattr(h,'BASE',base)
 return root,head


def test_actual_git_single_parent_tree_and_real_manifest_guard(tmp_path,monkeypatch):
 root,head=fixture_repository(tmp_path,monkeypatch);run=GitRunner(root);record=h.source_guard(run,head)
 assert record['sourceSha']==head and record['tree']==git(root,'rev-parse','HEAD^{tree}')
 assert set(record['changedPaths'])==h.ALLOWED_PATHS and len(h.ALLOWED_PATHS)==31
 assert 'header-source-manifest' in run.calls
 h.source_postcheck(run,record)

@pytest.mark.parametrize('case',['extra','omitted','dirty','wrong-source','wrong-parent','shallow','merge'])
def test_real_git_source_guard_refuses_widened_or_unbound_source(tmp_path,monkeypatch,case):
 root,head=fixture_repository(tmp_path,monkeypatch,extra='foreign' if case=='extra' else None,omit='tests/test_eight_view_header_provider.py' if case=='omitted' else None)
 if case=='dirty':(root/'untracked').write_text('not allowed')
 if case=='wrong-source':head='a'*40
 if case=='wrong-parent':monkeypatch.setattr(h,'BASE','b'*40)
 if case=='merge':
  git(root,'checkout','-qb','side',h.BASE);(root/'side-file').write_text('side');git(root,'add','.');git(root,'commit','-qm','side');git(root,'checkout','--detach',head);git(root,'merge','--no-ff','-m','merge','side');head=git(root,'rev-parse','HEAD')
 if case=='shallow':
  clone=tmp_path/'shallow';subprocess.run(['git','clone','--depth=1',root.as_uri(),str(clone)],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE);root=clone
 with pytest.raises(Failure):h.source_guard(GitRunner(root),head)


def test_source_postcheck_detects_modification_even_after_snapshot_failure(tmp_path,monkeypatch):
 root,head=fixture_repository(tmp_path,monkeypatch);run=GitRunner(root);record=h.source_guard(run,head);(root/'foreign').write_bytes(b'drift')
 with pytest.raises(Failure,match='changed'):h.source_postcheck(run,record)

@pytest.fixture
def observation(tmp_path,monkeypatch):
 home=tmp_path/'jdk';(home/'bin').mkdir(parents=True);(home/'include').mkdir();(home/'bin/java').write_bytes(b'synthetic java identity');(home/'release').write_text('JAVA_VERSION="17.0.fixture"\n')
 gcc=tmp_path/'gcc';gcc.write_bytes(b'synthetic gcc identity');gxx=tmp_path/'g++';gxx.write_bytes(b'synthetic g++ identity')
 headers=tmp_path/'headers';headers.mkdir();(headers/'inside.h').write_bytes(b'fixture header')
 class Run:
  def __init__(self):
   self.root=tmp_path;self.env={'JAVA_HOME_17_X64':str(home),'PATH':os.environ['PATH'],'ImageOS':'ubuntu24','ImageVersion':'synthetic-fixture','LD_PRELOAD':'retained-parent.so','LD_AUDIT':'retained-audit.so','ASAN_OPTIONS':'detect_leaks=1'};self.calls=[]
  def remaining(self,*a,**k):return 90
  def command(self,label,args,**kwargs):
   self.calls.append((label,args,kwargs))
   return (b'13.2.0\n' if label=='header-compiler-version' else b'openjdk version "17.0.fixture"\n'),0
 run=Run();read=h.bounded_bytes;which=shutil.which
 monkeypatch.setattr(h,'bounded_bytes',lambda path,*a,**k:b'ID=ubuntu\nVERSION_ID="24.04"\n' if Path(path).name=='os-release' else read(path,*a,**k))
 monkeypatch.setattr(h.platform,'system',lambda:'Linux');monkeypatch.setattr(h.platform,'machine',lambda:'x86_64')
 monkeypatch.setattr(h.shutil,'which',lambda name,**k:str(gxx if name=='g++' else gcc) if name in ('g++','gcc') else which(name,**k))
 calls=[]
 def roots(java):calls.append(java);return [headers,home/'include']
 monkeypatch.setattr(c,'system_header_roots',roots)
 providers=tuple({**role,'root':tmp_path/f"synthetic-provider-{role['major']}/include"} for role in h.hp.PROVIDERS)
 for role in providers:role['root'].mkdir(parents=True)
 monkeypatch.setattr(h.hp,'PROVIDERS',providers)
 provider_roots=[role['root'] for role in providers]
 # This fixture tests the entrypoint integration, not installed Clang admission.
 proof={'schema':2,'sourceSha':'a'*40,'status':'SYNTHETIC_PROVIDER_FIXTURE_NOT_ADMISSION'}
 monkeypatch.setattr(h.hp,'acquire',lambda context,source:copy.deepcopy(proof) if context is run and source=='a'*40 else pytest.fail('wrong source/provider context'))
 monkeypatch.setattr(h.hp,'validate',lambda actual,source,context:provider_roots if actual==proof and source=='a'*40 and context is run else pytest.fail('wrong provider proof binding'))
 return run,home,headers,calls


def test_actual_snapshot_uses_shared_java_executable_roots_and_compiler_boundary(observation):
 run,home,headers,calls=observation;receipt={'source':{'sourceSha':'a'*40}};h.observe(run,receipt)
 assert calls==[home/'bin/java'] and receipt['snapshot']['files']==1 and receipt['snapshot']['secondsLimit']==90
 command=next(item for item in run.calls if item[0]=='header-compiler-version');env=command[2]['env']
 assert 'LD_PRELOAD' not in env and 'LD_AUDIT' not in env and env['ASAN_OPTIONS']=='detect_leaks=1'
 assert run.env['LD_PRELOAD']=='retained-parent.so' and run.env['LD_AUDIT']=='retained-audit.so'
 assert {item[0] for item in run.calls}=={'header-java-version','header-compiler-version'}


def test_actual_header_escape_is_fail_metadata_only_and_bounded_artifact(observation,tmp_path,monkeypatch):
 run,_,headers,_=observation;outside=tmp_path/'outside.h';outside.write_bytes(b'SENTINEL OUTSIDE HEADER PAYLOAD');(headers/'escape.h').symlink_to(outside)
 original=c.sha;reads=[]
 def admitted_only(path):
  reads.append(Path(path));assert Path(path)!=outside,'escaped target payload read';return original(path)
 monkeypatch.setattr(c,'sha',admitted_only)
 receipt={'schema':1,'classification':h.CLASSIFICATION,'status':'FAIL','qualified':False,'pipelineAttempts':0,'source':{'sourceSha':'a'*40}}
 with pytest.raises(Failure,match='escapes admitted roots') as error:h.observe(run,receipt)
 h.record_failure(receipt,error.value);result=h.write_receipt(tmp_path/'evidence',receipt);raw=(tmp_path/'evidence/receipt.json').read_bytes()
 assert result['status']=='FAIL' and len(raw)<=32768 and b'SENTINEL' not in raw and outside not in reads
 assert result['failure']['headerEscape']['resolvedTarget']['text']==str(outside)
 assert result['snapshot']['status']=='FAIL'

@pytest.mark.parametrize('name',['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS'])
def test_jvm_overrides_refuse_without_snapshot_and_are_not_deleted(observation,name):
 run,_,_,calls=observation;run.env[name]='unreviewed'
 with pytest.raises(Blocked,match='override'):h.observe(run,{})
 assert calls==[] and run.env[name]=='unreviewed'


def test_non17_preinstalled_jdk_refuses_without_snapshot(observation):
 run,home,_,calls=observation;(home/'release').write_text('JAVA_VERSION="21.0.fixture"\n')
 with pytest.raises(Blocked,match='JDK17'):h.observe(run,{})
 assert calls==[]


def test_unapproved_compiler_refuses_without_snapshot(observation):
 run,_,_,calls=observation;original=run.command
 run.command=lambda label,*a,**k:(b'99.0.0\n',0) if label=='header-compiler-version' else original(label,*a,**k)
 with pytest.raises(Blocked,match='compiler major'):h.observe(run,{})
 assert calls==[]

@pytest.mark.parametrize('kind',['oversize','secret'])
def test_actual_artifact_writer_never_emits_oversize_secret_or_false_success(tmp_path,kind):
 record={'status':'SNAPSHOT_COMPLETED','payload':'x'*40000 if kind=='oversize' else 'password=DO_NOT_PUBLISH'}
 result=h.write_receipt(tmp_path/'evidence',record);raw=(tmp_path/'evidence/receipt.json').read_bytes()
 assert len(raw)<=32768 and result['status']=='FAIL' and result['evidenceComplete'] is False and b'DO_NOT_PUBLISH' not in raw
 assert list((tmp_path/'evidence').iterdir())==[tmp_path/'evidence/receipt.json']

@pytest.mark.parametrize('kind',['directory','symlink'])
def test_artifact_destination_must_be_fresh(tmp_path,kind):
 target=tmp_path/'evidence'
 if kind=='directory':target.mkdir()
 else:target.symlink_to(tmp_path,target_is_directory=True)
 with pytest.raises(Failure,match='fresh'):h.write_receipt(target,{'status':'FAIL'})


def event_fixture():
 source='a'*40
 env={'GITHUB_SHA':source,'GITHUB_EVENT_NAME':'push','GITHUB_REPOSITORY':h.policy.REPOSITORY,'GITHUB_REF':'refs/heads/main','GITHUB_RUN_ATTEMPT':'1'}
 event={'repository':{'full_name':h.policy.REPOSITORY,'private':False,'default_branch':'main'},'head_commit':{'id':source,'message':h.MESSAGE},'ref':'refs/heads/main','before':h.BASE,'after':source,'created':False,'deleted':False,'forced':False}
 return env,event

@pytest.mark.parametrize('case',['dispatch','rerun','wrong-parent','wrong-message','forced','foreign-repo'])
def test_exact_event_admission_negative_controls(case):
 env,event=event_fixture()
 if case=='dispatch':env['GITHUB_EVENT_NAME']='workflow_dispatch'
 if case=='rerun':env['GITHUB_RUN_ATTEMPT']='2'
 if case=='wrong-parent':event['before']='b'*40
 if case=='wrong-message':event['head_commit']['message']='other'
 if case=='forced':event['forced']=True
 if case=='foreign-repo':event['repository']['full_name']='other/repo'
 with pytest.raises(Failure):h.policy.event_guard(env,event,base=h.BASE,message=h.MESSAGE)

@pytest.mark.parametrize('fails',[False,True])
@pytest.mark.parametrize('secondary',['none','postcheck','cleanup','restore'])
def test_entrypoint_always_postchecks_source_cleans_and_returns_failure_for_refusal(tmp_path,monkeypatch,fails,secondary):
 env,event=event_fixture();event_path=tmp_path/'event.json';event_path.write_text(json.dumps(event))
 for key,value in {**env,'RUNNER_TEMP':str(tmp_path),'GITHUB_EVENT_PATH':str(event_path)}.items():monkeypatch.setenv(key,value)
 monkeypatch.setattr(sys,'argv',['header_diagnostic.py']);calls=[]
 class Run:
  def __init__(self,root,work,**kwargs):
   assert kwargs['seconds']==270
   self.root=root;self.work=work;self.env=dict(os.environ)
  def remaining(self,*a,**k):return 90
  def install_signals(self):calls.append('signals')
  def cleanup(self):
   calls.append('cleanup')
   if secondary=='cleanup':raise Failure('secondary cleanup failure')
  def restore_signals(self):
   calls.append('restore')
   if secondary=='restore':raise Failure('secondary restore failure')
 monkeypatch.setattr(h,'Runner',Run);monkeypatch.setattr(h,'source_guard',lambda run,source:{'sourceSha':source})
 def postcheck(*a):
  calls.append('postcheck')
  if secondary=='postcheck':raise Failure('secondary postcheck failure')
 monkeypatch.setattr(h,'source_postcheck',postcheck)
 def observe(*args):
  calls.append('snapshot')
  if fails:raise Failure('actual header gate refusal')
 monkeypatch.setattr(h,'observe',observe)
 assert h.main()==(1 if fails or secondary!='none' else 0)
 receipt=json.loads((tmp_path/'rf-header-evidence/receipt.json').read_text())
 assert receipt['qualified'] is False and receipt['pipelineAttempts']==0
 assert calls==['signals','snapshot','postcheck','cleanup','restore'] and not list(tmp_path.glob('rf-header-work-*'))
 assert receipt['status']==('FAIL' if fails or secondary!='none' else 'SNAPSHOT_COMPLETED')
 if fails:
  assert receipt['failure']['message']=='actual header gate refusal'
  if secondary!='none':assert receipt['secondaryFailures'][0]['message']=='secondary '+secondary+' failure'


def test_command_line_cannot_supply_custom_roots(tmp_path,monkeypatch):
 monkeypatch.setenv('RUNNER_TEMP',str(tmp_path));monkeypatch.setattr(sys,'argv',['header_diagnostic.py','--roots','/outside'])
 monkeypatch.setattr(c,'prebuild_snapshot',lambda *a:pytest.fail('custom-root invocation must not inspect headers'))
 assert h.main()==1


def test_actual_full_manifest_verification_rejects_forged_manifest(tmp_path,monkeypatch):
 root,_=fixture_repository(tmp_path,monkeypatch);(root/'SOURCE_MANIFEST.sha256').write_text('forged manifest\n');git(root,'add','SOURCE_MANIFEST.sha256');git(root,'commit','--amend','--no-edit','-q');head=git(root,'rev-parse','HEAD')
 with pytest.raises(Failure,match='header-source-manifest'):h.source_guard(GitRunner(root),head)


def test_wrong_runner_image_refuses_before_snapshot(observation,monkeypatch):
 run,_,_,calls=observation;original=h.bounded_bytes
 monkeypatch.setattr(h,'bounded_bytes',lambda path,*a,**k:b'VERSION_ID="22.04"\n' if Path(path).name=='os-release' else original(path,*a,**k))
 with pytest.raises(Blocked,match='Ubuntu24.04'):h.observe(run,{})
 assert calls==[]


def test_actual_escape_entrypoint_exits_one_retains_first_metadata_and_never_qualifies(observation,tmp_path,monkeypatch):
 run,_,headers,_=observation;outside=tmp_path/'outside';outside.write_bytes(b'not admitted');(headers/'escape').symlink_to(outside)
 env,event=event_fixture();event_path=tmp_path/'event.json';event_path.write_text(json.dumps(event));run.env.update(env,GITHUB_EVENT_PATH=str(event_path))
 monkeypatch.setenv('RUNNER_TEMP',str(tmp_path));monkeypatch.setattr(sys,'argv',['header_diagnostic.py'])
 run.install_signals=lambda:None;run.cleanup=lambda:None;run.restore_signals=lambda:None
 monkeypatch.setattr(h,'Runner',lambda *a,**k:run);monkeypatch.setattr(h,'source_guard',lambda *a:{'sourceSha':env['GITHUB_SHA']})
 checked=[];monkeypatch.setattr(h,'source_postcheck',lambda *a:checked.append(True))
 assert h.main()==1 and checked==[True]
 record=json.loads((tmp_path/'rf-header-evidence/receipt.json').read_text())
 assert record['status']=='FAIL' and record['qualified'] is False and record['pipelineAttempts']==0
 assert record['failure']['headerEscape']['resolvedTarget']['text']==str(outside)
 assert record['snapshot']['status']=='FAIL' and record['sourcePostcheck']=='PASS'

@pytest.mark.parametrize('payload',['not json','\u00e9','x'*17000],
                         ids=['not-json', 'non-ascii', 'oversized-17000-bytes'])
def test_malformed_or_oversized_embedded_metadata_remains_failure(payload):
 record={};h.record_failure(record,Failure('gate refused; EIGHT_VIEW_HEADER_ESCAPE '+payload))
 assert record['status']=='FAIL' and 'headerEscape' not in record['failure']
 assert record['failure']['diagnosticMetadata'].endswith('WITHHELD')

@pytest.mark.parametrize('snapshot_fails',[False,True])
def test_fast_provider_postcheck_runs_after_snapshot_and_preserves_original_failure(observation,tmp_path,monkeypatch,snapshot_fails):
 run,_,headers,_=observation
 if snapshot_fails:
  outside=tmp_path/'another-outside';outside.write_bytes(b'forbidden');(headers/'escape').symlink_to(outside)
 env,event=event_fixture();event_path=tmp_path/'event.json';event_path.write_text(json.dumps(event));run.env.update(env,GITHUB_EVENT_PATH=str(event_path))
 monkeypatch.setenv('RUNNER_TEMP',str(tmp_path));monkeypatch.setattr(sys,'argv',['header_diagnostic.py'])
 run.install_signals=lambda:None;run.cleanup=lambda:None;run.restore_signals=lambda:None
 monkeypatch.setattr(h,'Runner',lambda *a,**k:run);monkeypatch.setattr(h,'source_guard',lambda *a:{'sourceSha':env['GITHUB_SHA']});monkeypatch.setattr(h,'source_postcheck',lambda *a:None)
 original=h.hp.validate;calls=[]
 def changed(*args):
  calls.append(True)
  if len(calls)>1:raise Failure('provider changed during snapshot')
  return original(*args)
 monkeypatch.setattr(h.hp,'validate',changed)
 assert h.main()==1 and len(calls)==2
 receipt=json.loads((tmp_path/'rf-header-evidence/receipt.json').read_text())
 assert receipt['status']=='FAIL' and receipt['headerProviderPostcheck']=='FAIL' and receipt['headerProvider']['sourceSha']==env['GITHUB_SHA']
 if snapshot_fails:
  assert 'headerEscape' in receipt['failure']
  assert receipt['secondaryFailures'][0]['message']=='provider changed during snapshot'
 else:assert receipt['failure']['message']=='provider changed during snapshot'
 assert {call[0] for call in run.calls}=={'header-java-version','header-compiler-version'}

def test_fast_entrypoint_appends_only_proved_exact_header_root(observation,monkeypatch):
 run,_,headers,_=observation;original=c.prebuild_snapshot;seen=[]
 def inspect(context,roots):seen.extend(roots);return original(context,roots)
 monkeypatch.setattr(c,'prebuild_snapshot',inspect)
 receipt={'source':{'sourceSha':'a'*40}};h.observe(run,receipt)
 assert seen[-3:]==[role['root'] for role in h.hp.PROVIDERS] and len(seen)==5
 assert receipt['headerProvider']['status']=='SYNTHETIC_PROVIDER_FIXTURE_NOT_ADMISSION'

def test_fast_entrypoint_rejects_unexpected_provider_root_before_snapshot(observation,tmp_path,monkeypatch):
 run,_,_,_=observation;monkeypatch.setattr(h.hp,'validate',lambda *a:tmp_path)
 monkeypatch.setattr(c,'prebuild_snapshot',lambda *a:pytest.fail('unproved root reached snapshot'))
 with pytest.raises(Failure,match='Unexpected admitted Clang header root'):h.observe(run,{'source':{'sourceSha':'a'*40}})

@pytest.mark.parametrize('mutation',['missing','duplicate','reordered','extra','legacy-single'])
def test_fast_snapshot_requires_exact_ordered_closed_provider_set(observation,monkeypatch,mutation):
 run,_,_,_=observation;roots=[role['root'] for role in h.hp.PROVIDERS]
 if mutation=='missing':roots=roots[:-1]
 elif mutation=='duplicate':roots=[roots[0],roots[0],roots[2]]
 elif mutation=='reordered':roots=roots[::-1]
 elif mutation=='extra':roots=[*roots,Path('/usr/lib')]
 else:roots=roots[0]
 monkeypatch.setattr(h.hp,'validate',lambda *a:roots)
 monkeypatch.setattr(c,'prebuild_snapshot',lambda *a:pytest.fail('unproved provider set reached snapshot'))
 with pytest.raises(Failure,match='Unexpected admitted Clang header root'):h.observe(run,{'source':{'sourceSha':'a'*40}})


def test_fast_content_snapshot_does_not_call_metadata_inventory(observation,monkeypatch):
 from tools.eight_view_host import header_inventory
 run,_,_,_=observation
 monkeypatch.setattr(header_inventory,'scan',lambda *a:pytest.fail('metadata inventory substituted for actual content snapshot'))
 original=c.prebuild_snapshot;calls=[]
 def snapshot(context,roots):calls.append(True);return original(context,roots)
 monkeypatch.setattr(c,'prebuild_snapshot',snapshot)
 receipt={'source':{'sourceSha':'a'*40}};h.observe(run,receipt)
 assert calls==[True] and receipt['snapshot']['attempted'] is True and receipt['snapshot']['status']=='COMPLETED'
 assert 'headerInventory' not in receipt


def test_closed_provider_proof_and_maximum_escape_metadata_fit_receipt(observation,tmp_path):
 run,_,_,_=observation
 receipt={'schema':1,'classification':h.CLASSIFICATION,'status':'FAIL','qualified':False,'pipelineAttempts':0,
  'source':{'sourceSha':'a'*40,'parentSha':h.BASE,'tree':'b'*40,'sourceManifestSha256':'c'*64,'changedPaths':sorted(h.ALLOWED_PATHS)}}
 h.observe(run,receipt)
 # Budget-only padding for the deduplicated three-provider filesystem/probes;
 # not provider-admission evidence. Keep the real artifact cap unchanged.
 receipt['headerProvider']['budgetOnlySyntheticPadding']='p'*10000
 metadata={'classification':'HEADER_ESCAPE_METADATA_ONLY','budgetOnlySyntheticPadding':'x'*16000}
 h.record_failure(receipt,Failure('header gate refused; EIGHT_VIEW_HEADER_ESCAPE '+json.dumps(metadata)))
 receipt.update(headerProviderPostcheck='PASS',sourcePostcheck='PASS',ownedCleanup='PASS')
 result=h.write_receipt(tmp_path/'budget-evidence',receipt)
 assert result['failure']['headerEscape']==metadata and result['headerProvider']==receipt['headerProvider']
 assert len((tmp_path/'budget-evidence/receipt.json').read_bytes())<=32768
