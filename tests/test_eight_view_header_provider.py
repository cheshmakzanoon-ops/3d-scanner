"""Synthetic fixed-role files/probes, not qualification of an installed Clang package."""
import copy,hashlib,json,os,sys,types
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tools.eight_view_host import header_provider as h,capability as c
from tools.eight_view_host.runtime import Failure,Runner
from tools.native_process import native_compile_env
SOURCE='a'*40

class ProbeRun:
 def __init__(self,work):
  self.work=work;work.mkdir();(work/'logs').mkdir();self.owner='synthetic-owner';self.env={'PATH':'/usr/bin:/bin','LD_PRELOAD':'not-loaded','LD_AUDIT':'not-loaded','OTHER':'retained'};self.commands=[];self.outputs=None;self.after=None
 def remaining(self,n):return n
 def command(self,label,argv,seconds,limit,env):
  assert seconds==10 and limit==4096 and env==native_compile_env(self.env)
  assert env['OTHER']=='retained' and 'LD_PRELOAD' not in env and 'LD_AUDIT' not in env
  i=h.LABELS.index(label);raw=(self.outputs or [b'Ubuntu clang version 18.1.3\nTarget: x86_64\n',(str(h.INCLUDE_ROOT.parent)+'\n').encode()])[i]
  if len(raw)>limit:raise Failure('live command output limit')
  (self.work/'logs'/(label+'.raw.log')).write_bytes(raw)
  self.commands.append({'label':label,'argv':list(map(str,argv)),'runOwner':self.owner,'complete':True,'exit':0,'environmentBoundary':'EXPLICIT_COMPILATION_TOOL','bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
  if self.after:self.after(i)
  return raw,self.commands[-1]

@pytest.fixture
def fixture(tmp_path,monkeypatch):
 base=tmp_path/'installed';entry=base/'usr/bin/clang-18';driver=base/'usr/lib/llvm-18/bin/clang';alias=base/'usr/include/clang/18.1.3/include';include=base/'usr/lib/llvm-18/lib/clang/18/include'
 for path in [entry.parent,driver.parent,alias.parent,include]:path.mkdir(parents=True,exist_ok=True)
 driver.write_text('synthetic driver bytes');driver.chmod(0o755);entry.symlink_to(driver);alias.symlink_to('../../../lib/llvm-18/lib/clang/18/include')
 (include/'header.h').write_text('header')
 for name,value in [('DRIVER_ENTRY',entry),('DRIVER',driver),('ALIAS',alias),('INCLUDE_ROOT',include)]:monkeypatch.setattr(h,name,value)
 run=ProbeRun(tmp_path/'work');return run,base

def test_exact_provider_admission_and_unknown_escape(fixture):
 run,base=fixture;proof=h.acquire(run,SOURCE)
 assert h.validate(proof,SOURCE,run)==h.INCLUDE_ROOT
 assert proof['status']=='ADMITTED_ROOT_NOT_CONTENT_SNAPSHOT'
 assert h.LABELS==tuple(x['label'] for x in run.commands)
 roots=[base/'usr/include',h.INCLUDE_ROOT]
 assert str(h.INCLUDE_ROOT/'header.h') in c.prebuild_snapshot(run,roots)
 outside=base/'outside';outside.write_text('secret');(h.INCLUDE_ROOT/'escape').symlink_to(outside)
 with pytest.raises(Failure,match='escapes'):c.prebuild_snapshot(run,roots)

@pytest.mark.parametrize('version,resource',[(b'clang version 19.0.0\n',None),(b'not clang18\n',None),(b'x'*4097,None),(None,b'/wrong\n'),(None,b'relative\n'),(None,b'/one\n/two\n'),(None,b'x'*4097),(None,b'\xff')])
def test_probe_refusals(fixture,version,resource):
 run,_=fixture;run.outputs=[version or b'clang version 18.1.3\n',resource or (str(h.INCLUDE_ROOT.parent)+'\n').encode()]
 with pytest.raises((Failure,UnicodeError)):h.acquire(run,SOURCE)

@pytest.mark.parametrize('mutation',['driver','alias','root','ancestor','driver_entry'])
def test_before_after_probe_identity_drift(fixture,mutation):
 run,base=fixture
 def mutate(i):
  if i:return
  if mutation=='driver':h.DRIVER.write_text('changed')
  elif mutation=='alias':h.ALIAS.unlink();h.ALIAS.symlink_to(h.INCLUDE_ROOT)
  elif mutation=='root':h.INCLUDE_ROOT.rename(h.INCLUDE_ROOT.with_name('old'));h.INCLUDE_ROOT.mkdir()
  elif mutation=='ancestor':h.INCLUDE_ROOT.parent.chmod(0o700)
  else:h.DRIVER_ENTRY.unlink();h.DRIVER_ENTRY.symlink_to('/missing')
 run.after=mutate
 with pytest.raises((Failure,OSError)):h.acquire(run,SOURCE)

@pytest.mark.parametrize('mutation',['driver','alias','root','ancestor'])
def test_post_admission_identity_drift(fixture,mutation):
 run,_=fixture;proof=h.acquire(run,SOURCE)
 if mutation=='driver':h.DRIVER.write_text('changed')
 elif mutation=='alias':h.ALIAS.unlink();h.ALIAS.symlink_to(h.INCLUDE_ROOT)
 elif mutation=='root':h.INCLUDE_ROOT.chmod(0o700)
 else:h.DRIVER.parent.chmod(0o700)
 with pytest.raises(Failure):h.validate(proof,SOURCE,run)

@pytest.mark.parametrize('field',['schema','sourceSha','runOwner','workDir','status','driver','filesystem','probes','extra'])
def test_forged_proof_refused(fixture,field):
 run,_=fixture;proof=h.acquire(run,SOURCE);proof[field]='forged'
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

def test_missing_driver_and_symlink_ancestor_refused(fixture):
 run,_=fixture;h.DRIVER.unlink()
 with pytest.raises(OSError):h.acquire(run,SOURCE)

def test_real_owned_probe_commands(fixture,tmp_path):
 _,base=fixture
 # Actual child processes through Runner, but the script is explicitly synthetic.
 h.DRIVER.write_text('#!/bin/sh\ncase "$1" in\n--version) printf "clang version 18.1.3\\n";;\n-print-resource-dir) printf "%s\\n" "'+str(h.INCLUDE_ROOT.parent)+'";;\nesac\n')
 h.DRIVER.chmod(0o755)
 run=Runner(ROOT,tmp_path/'actual-work',seconds=180)
 try:
  proof=h.acquire(run,SOURCE);assert h.validate(proof,SOURCE,run)==h.INCLUDE_ROOT
  assert all(x['complete'] and x['exit']==0 for x in run.commands)
 finally:run.cleanup()

@pytest.mark.parametrize('case',['alias_absent','alias_regular','alias_wrongraw','alias_wrongcanonical','driver_entry_wrong','symlink_ancestor','root_symlink'])
def test_unproved_fixed_role_refused(fixture,case):
 run,base=fixture
 if case.startswith('alias'):
  h.ALIAS.unlink()
  if case=='alias_regular':h.ALIAS.mkdir()
  elif case=='alias_wrongraw':h.ALIAS.symlink_to(h.INCLUDE_ROOT)
  elif case=='alias_wrongcanonical':h.ALIAS.symlink_to('../../../lib/llvm-19/lib/clang/19/include')
 elif case=='driver_entry_wrong':h.DRIVER_ENTRY.unlink();h.DRIVER_ENTRY.symlink_to('/bin/sh')
 elif case=='symlink_ancestor':
  parent=h.ALIAS.parent;other=parent.with_name('other');parent.rename(other);parent.symlink_to(other)
 else:
  other=h.INCLUDE_ROOT.with_name('other');h.INCLUDE_ROOT.rename(other);h.INCLUDE_ROOT.symlink_to(other)
 with pytest.raises((Failure,OSError)):h.acquire(run,SOURCE)


def test_provider_does_not_waive_consumed_header_identity(fixture):
 run,base=fixture;proof=h.acquire(run,SOURCE);before=c.prebuild_snapshot(run,[base/'usr/include',h.validate(proof,SOURCE,run)])
 header=h.INCLUDE_ROOT/'header.h';header.write_text('changed bytes')
 with pytest.raises(Failure,match='changed between'):
  c.bind_consumed({str(header):hashlib.sha256(header.read_bytes()).hexdigest()},before,[base/'build'])
