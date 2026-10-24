"""Closed-provider synthetic filesystem/probe controls, not installed tool qualification."""
import copy,hashlib,json,os,sys
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tools.eight_view_host import header_provider as h,capability as c
from tools.eight_view_host.runtime import Failure,Runner
from tools.native_process import native_compile_env
SOURCE='a'*40

class ProbeRun:
 def __init__(self,work):
  self.work=work;work.mkdir();(work/'logs').mkdir();self.owner='synthetic-owner'
  self.env={'PATH':'/usr/bin:/bin','LD_PRELOAD':'not-loaded','LD_AUDIT':'not-loaded','OTHER':'retained'}
  self.commands=[];self.outputs={};self.after=None
 def remaining(self,n):return n
 def command(self,label,argv,seconds,limit,env):
  assert seconds==10 and limit==4096 and env==native_compile_env(self.env)
  assert env['OTHER']=='retained' and 'LD_PRELOAD' not in env and 'LD_AUDIT' not in env
  role=next(role for role in h.PROVIDERS if label in h._labels(role));version=label.endswith('-version')
  raw=self.outputs.get(label,(f"Ubuntu clang version {role['major']}.0.6\nTarget: x86_64\n" if version else str(role['root'].parent)+'\n').encode())
  if len(raw)>limit:raise Failure('live command output limit')
  (self.work/'logs'/(label+'.raw.log')).write_bytes(raw)
  self.commands.append({'label':label,'argv':list(map(str,argv)),'runOwner':self.owner,'complete':True,'exit':0,'environmentBoundary':'EXPLICIT_COMPILATION_TOOL','bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
  if self.after:self.after(label)
  return raw,self.commands[-1]

@pytest.fixture
def fixture(tmp_path,monkeypatch):
 base=tmp_path/'installed';roles=[]
 for original in h.PROVIDERS:
  role={**original,'entry':base/str(original['entry']).lstrip('/'),'driver':base/str(original['driver']).lstrip('/'),
        'root':base/str(original['root']).lstrip('/'),'aliases':tuple(base/str(alias).lstrip('/') for alias in original['aliases'])}
  for path in [role['entry'].parent,role['driver'].parent,role['root'],*(alias.parent for alias in role['aliases'])]:path.mkdir(parents=True,exist_ok=True)
  role['driver'].write_text('synthetic driver bytes');role['driver'].chmod(0o755);role['entry'].symlink_to(role['driver'])
  for alias in role['aliases']:alias.symlink_to(role['rawTarget'])
  (role['root']/'header.h').write_text('header');roles.append(role)
 monkeypatch.setattr(h,'PROVIDERS',tuple(roles))
 run=ProbeRun(tmp_path/'work');return run,base


def test_closed_provider_admission_and_unknown_escape(fixture):
 run,base=fixture;proof=h.acquire(run,SOURCE);roots=h.validate(proof,SOURCE,run)
 assert roots==[role['root'] for role in h.PROVIDERS]
 assert proof['schema']==2 and proof['status']=='ADMITTED_ROOTS_NOT_CONTENT_SNAPSHOT'
 assert [p['major'] for p in proof['providers']]==[16,17,18]
 assert h.LABELS==tuple(x['label'] for x in run.commands)
 assert len({n['path'] for n in proof['filesystem']})==len(proof['filesystem'])
 assert len(json.dumps(proof).encode())<20*1024
 before=c.prebuild_snapshot(run,[base/'usr/include',*roots])
 assert all(str(root/'header.h') in before for root in roots)
 outside=base/'outside';outside.write_text('secret');(roots[0]/'escape').symlink_to(outside)
 with pytest.raises(Failure,match='escapes'):c.prebuild_snapshot(run,[base/'usr/include',*roots])

@pytest.mark.parametrize('major',[16,17,18])
@pytest.mark.parametrize('kind,raw',[('version',b'clang version 19.0.0\n'),('version',b'not clang\n'),('version',b'x'*4097),('resource-dir',b'/wrong\n'),('resource-dir',b'relative\n'),('resource-dir',b'/one\n/two\n'),('resource-dir',b'x'*4097),('resource-dir',b'\xff')])
def test_each_provider_probe_refusals(fixture,major,kind,raw):
 run,_=fixture;run.outputs[f'header-provider-{major}-{kind}']=raw
 with pytest.raises(Failure) as caught:h.acquire(run,SOURCE)
 if len(raw)<=4096:
  diagnostic=json.loads(str(caught.value).split('CLANG_PROVIDER_PROBE ',1)[1]);assert diagnostic['major']==major
  assert len(str(caught.value))<1024 and len(diagnostic['outputs'])==2

@pytest.mark.parametrize('index',[0,1,2])
@pytest.mark.parametrize('phase',['during','after'])
def test_every_driver_identity_rechecked_across_all_probes(fixture,index,phase):
 run,_=fixture;driver=h.PROVIDERS[index]['driver']
 if phase=='during':
  def change(label):
   if label==h.LABELS[-1]:driver.write_text('changed after all probe processes')
  run.after=change
  with pytest.raises(Failure):h.acquire(run,SOURCE)
 else:
  proof=h.acquire(run,SOURCE);driver.write_text('changed')
  with pytest.raises(Failure):h.validate(proof,SOURCE,run)

@pytest.mark.parametrize('index',[0,1,2])
@pytest.mark.parametrize('mutation',['alias','root','ancestor','driver_entry'])
def test_every_provider_role_identity_drift(fixture,index,mutation):
 run,_=fixture;proof=h.acquire(run,SOURCE);role=h.PROVIDERS[index]
 if mutation=='alias':role['aliases'][0].unlink();role['aliases'][0].symlink_to(role['root'])
 elif mutation=='root':role['root'].rename(role['root'].with_name('old'));role['root'].mkdir()
 elif mutation=='ancestor':role['root'].parent.chmod(0o700)
 else:role['entry'].unlink();role['entry'].symlink_to('/missing')
 with pytest.raises((Failure,OSError)):h.validate(proof,SOURCE,run)

@pytest.mark.parametrize('field',['schema','sourceSha','runOwner','workDir','status','filesystem','providers','extra'])
def test_forged_top_level_proof_refused(fixture,field):
 run,_=fixture;proof=h.acquire(run,SOURCE);proof[field]='forged'
 with pytest.raises(Failure):h.validate(proof,SOURCE,run)

@pytest.mark.parametrize('mutation',['missing','duplicate','reordered','fourth','unknown-alias','wrong-root'])
def test_closed_set_cannot_be_narrowed_or_widened(fixture,mutation):
 run,_=fixture;proof=h.acquire(run,SOURCE);providers=proof['providers']
 if mutation=='missing':providers.pop()
 elif mutation=='duplicate':providers[0]=copy.deepcopy(providers[1])
 elif mutation=='reordered':providers.reverse()
 elif mutation=='fourth':providers.append(copy.deepcopy(providers[0]))
 elif mutation=='unknown-alias':providers[0]['aliases'].append({'path':'/unknown','rawTarget':'unknown','root':'/unknown'})
 else:providers[0]['includeRoot']='/usr/lib'
 with pytest.raises(Failure):h.validate(proof,SOURCE,run)

@pytest.mark.parametrize('field,value',[('argv',['/wrong','--version']),('runOwner','foreign'),('complete',False),('exit',1),('environmentBoundary','INHERITED_RUNTIME'),('sha256','0'*64),('bytes',0),('error','failure')])
def test_forged_authoritative_probe_refused(fixture,field,value):
 run,_=fixture;proof=h.acquire(run,SOURCE);run.commands[0][field]=value
 with pytest.raises(Failure):h.validate(proof,SOURCE,run)


def test_missing_duplicate_and_modified_probe_logs(fixture):
 run,_=fixture;proof=h.acquire(run,SOURCE);run.commands.append(run.commands[0])
 with pytest.raises(Failure,match='missing/duplicate'):h.validate(proof,SOURCE,run)
 run.commands.pop();(run.work/'logs'/(h.LABELS[0]+'.raw.log')).write_text('changed')
 with pytest.raises(Failure,match='identity'):h.validate(proof,SOURCE,run)

@pytest.mark.parametrize('index',[0,1,2])
@pytest.mark.parametrize('case',['driver_absent','alias_absent','alias_regular','alias_wrongraw','driver_entry_wrong','symlink_ancestor','root_symlink'])
def test_unproved_fixed_role_refused(fixture,index,case):
 run,_=fixture;role=h.PROVIDERS[index];alias=role['aliases'][0]
 if case=='driver_absent':role['driver'].unlink()
 elif case.startswith('alias'):
  alias.unlink()
  if case=='alias_regular':alias.mkdir()
  elif case=='alias_wrongraw':alias.symlink_to(role['root'])
 elif case=='driver_entry_wrong':role['entry'].unlink();role['entry'].symlink_to('/bin/sh')
 elif case=='symlink_ancestor':
  parent=alias.parent;other=parent.with_name('other');parent.rename(other);parent.symlink_to(other)
 else:
  other=role['root'].with_name('other');role['root'].rename(other);role['root'].symlink_to(other)
 with pytest.raises((Failure,OSError)):h.acquire(run,SOURCE)


def test_provider_does_not_waive_consumed_header_identity(fixture):
 run,base=fixture;proof=h.acquire(run,SOURCE);before=c.prebuild_snapshot(run,[base/'usr/include',*h.validate(proof,SOURCE,run)])
 header=h.PROVIDERS[0]['root']/'header.h';header.write_text('changed bytes')
 with pytest.raises(Failure,match='changed between'):c.bind_consumed({str(header):hashlib.sha256(header.read_bytes()).hexdigest()},before,[base/'build'])


def test_real_owned_six_probe_commands(fixture,tmp_path):
 _,base=fixture
 for role in h.PROVIDERS:
  role['driver'].write_text('#!/bin/sh\ncase "$1" in\n--version) printf "clang version '+str(role['major'])+'.0.6\\n";;\n-print-resource-dir) printf "%s\\n" "'+str(role['root'].parent)+'";;\nesac\n')
  role['driver'].chmod(0o755)
 run=Runner(ROOT,tmp_path/'actual-work',seconds=180)
 try:
  proof=h.acquire(run,SOURCE);assert len(h.validate(proof,SOURCE,run))==3
  assert len(run.commands)==6 and all(x['complete'] and x['exit']==0 for x in run.commands)
 finally:run.cleanup()


def test_wrong_probe_sensitive_payload_not_encoded_around_secret_guard(fixture):
 run,_=fixture;raw=b'authorization: Bearer this-is-not-a-real-credential'
 run.outputs['header-provider-16-resource-dir']=raw
 with pytest.raises(Failure) as caught:h.acquire(run,SOURCE)
 record=json.loads(str(caught.value).split('CLANG_PROVIDER_PROBE ',1)[1])['outputs'][1]
 assert record['hexPreview']=='' and record['previewStatus']=='WITHHELD_SECRET_MARKER'
 assert record['omittedBytes']==len(raw) and record['sha256']==hashlib.sha256(raw).hexdigest()
 assert raw.hex() not in str(caught.value)

@pytest.mark.parametrize('index',[0,1])
@pytest.mark.parametrize('change',['missing','retargeted'])
def test_versioned_alias_mandatory_even_with_intact_major_alias(fixture,index,change):
 run,_=fixture;role=h.PROVIDERS[index];versioned=role['aliases'][1];versioned.unlink()
 if change=='retargeted':versioned.symlink_to('/missing')
 assert role['aliases'][0].resolve()==role['root']
 with pytest.raises((Failure,OSError)):h.acquire(run,SOURCE)


def test_generated_schema2_proof_receipt_budget_with_explicit_production_layout(fixture,tmp_path):
 from tools.eight_view_host import header_diagnostic as diagnostic
 run,base=fixture;proof=h.acquire(run,SOURCE)
 assert h.validate(proof,SOURCE,run)==[role['root'] for role in h.PROVIDERS]
 receipt={'schema':1,'classification':diagnostic.CLASSIFICATION,'status':'FAIL','qualified':False,'pipelineAttempts':0,
          'source':{'sourceSha':SOURCE,'parentSha':'b'*40,'tree':'c'*40,'changedPaths':sorted(diagnostic.ALLOWED_PATHS)},
          'headerProvider':proof,'failure':{'type':'Failure','message':'x'*(16*1024-100)},
          'jdk':{'java':{'path':'/usr/lib/jvm/temurin-17-jdk-amd64/bin/java','sha256':'d'*64}},
          'compiler':{'cxx':{'path':'/usr/bin/x86_64-linux-gnu-g++-13','sha256':'e'*64},'version':'13.3.0'},
          'image':{'imageOS':'ubuntu24','imageVersion':'20261012.334.1'}}
 generated_raw=(json.dumps(receipt,sort_keys=True,ensure_ascii=True,separators=(',',':'))+'\n').encode('ascii')
 generated_result=diagnostic.write_receipt(tmp_path/'generated-receipt',receipt)
 if len(generated_raw)>32*1024:
  assert generated_result['evidenceComplete'] is False and generated_result['originalBytes']==len(generated_raw)
  assert generated_result['originalSha256']==hashlib.sha256(generated_raw).hexdigest()
 else:assert generated_result==receipt
 # Force a separately labeled oversized serialization fixture irrespective of
 # the local temporary-directory spelling. The production guard must withhold.
 oversized=copy.deepcopy(receipt)
 oversized['headerProvider']['workDir']='long-synthetic-temp-path-'*2000
 rejected=diagnostic.write_receipt(tmp_path/'oversized-receipt',oversized)
 assert rejected['status']=='FAIL' and rejected['evidenceComplete'] is False
 assert rejected['failure']['type']=='EvidenceLimit'
 oversized_raw=(json.dumps(oversized,sort_keys=True,ensure_ascii=True,separators=(',',':'))+'\n').encode('ascii')
 assert rejected['originalBytes']==len(oversized_raw) and rejected['originalSha256']==hashlib.sha256(oversized_raw).hexdigest()
 # Serialization budget ONLY: rebase generated fixture fields to the fixed
 # production path layout. This rewritten proof is NOT filesystem admission
 # evidence; real acquire/validate above and all independent controls remain.
 layout=copy.deepcopy(proof)
 layout['filesystem']=[node for node in layout['filesystem'] if node['path']=='/' or str(node['path']).startswith(str(base)+'/')]
 def production_layout(value):
  if isinstance(value,dict):return {key:production_layout(child) for key,child in value.items()}
  if isinstance(value,list):return [production_layout(child) for child in value]
  if isinstance(value,str) and value.startswith(str(base)+'/'):return value[len(str(base)):]
  return value
 layout=production_layout(layout)
 for node in layout['filesystem']:
  node['device']=node['inode']=str(2**64-1)
  node['size']=node['mtimeNs']=node['ctimeNs']=2**63-1
 for provider in layout['providers']:
  for probe in provider['probes']:
   probe['outputSha256']=hashlib.sha256(probe['outputText'].encode()).hexdigest()
 receipt['headerProvider']=layout
 destination=tmp_path/'production-layout-receipt';result=diagnostic.write_receipt(destination,receipt)
 assert result==receipt and result['headerProvider']==layout
 assert (destination/'receipt.json').stat().st_size<=32*1024
