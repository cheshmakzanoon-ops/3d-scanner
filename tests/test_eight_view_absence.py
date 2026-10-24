"""Typed optional-output absence controls; synthetic host metadata, not Android proof."""
import copy,errno,json,os,stat,types
from pathlib import Path
import pytest
from test_eight_view_normalization import ledger
from tools.eight_view_host import controller as ctl
from tools.eight_view_host.runtime import Failure


def independent_proof(root,target):
 ancestors=[];current=Path(target.anchor)
 for component in ('',*target.parts[1:]):
  if component:current/=component
  try:value=os.stat(current,follow_symlinks=False)
  except FileNotFoundError:
   return dict(schema=1,root=str(root),ancestors=ancestors,firstMissing=str(current))
  assert stat.S_ISDIR(value.st_mode)
  ancestors.append(dict(path=str(current),device=str(value.st_dev),inode=str(value.st_ino)))
 raise AssertionError('fixture target must be absent')

@pytest.fixture
def absent(ledger,tmp_path):
 root=Path(ledger['appBuildDirectory']);run=types.SimpleNamespace(root=tmp_path,remaining=lambda *a,**k:10)
 for producer in ctl.OPTIONAL_DIRECTORY_PRODUCERS:
  path=root/producer['relativePath'];path.parent.mkdir(parents=True,exist_ok=True)
  ledger['classpath'].append(dict(kind='absent-declared-directory',path=str(path),producer=copy.deepcopy(producer),absence=independent_proof(root,path)))
 ledger['classpathString']=os.pathsep.join(e['path'] for e in ledger['classpath'])
 return ledger,run

def test_two_exact_declared_absences_preserve_order_and_native_normalization(absent):
 record,run=absent;before=copy.deepcopy(record)
 ctl.classpath_check(record,run);ctl.normalization_check(record,run)
 assert record==before and len(record['classpath'])==5
 assert record['opencvNormalization']['schema']==1

@pytest.mark.parametrize('schema',[1,2,3,5,True,'4'])
def test_old_unknown_capture_schema_refused_before_any_skip(absent,schema):
 record,run=absent;record['schema']=schema
 with pytest.raises(Failure,match='schema'):ctl.classpath_check(record,run)

@pytest.mark.parametrize('mutation',[
 lambda r:r['classpath'][-1].update(kind='missing'),
 lambda r:r['classpath'][-1].update(directory=True),
 lambda r:r['classpath'][-1].update(sha256='0'*64),
 lambda r:r['classpath'][-1].pop('producer'),
 lambda r:r['classpath'][-1]['producer'].update(task=':app:otherTask'),
 lambda r:r['classpath'][-1]['producer'].update(taskType='unknown.Type'),
 lambda r:r['classpath'][-1]['producer'].update(outputProperty='optionalDirectory'),
 lambda r:r['classpath'][-1]['producer'].update(admission='up-to-date'),
 lambda r:r['classpath'][-1]['producer'].update(relativePath='../outside'),
 lambda r:r['classpath'][-1]['producer'].update(extra='unreviewed'),
 lambda r:r['classpath'][-1]['absence'].update(schema=2),
 lambda r:r['classpath'][-1]['absence'].update(schema=True),
 lambda r:r['classpath'][-1]['absence'].update(extra=True),
 lambda r:r['classpath'][-1]['absence'].update(firstMissing='/forged'),
 lambda r:r['classpath'][-1]['absence'].update(root='/forged'),
 lambda r:r['classpath'][-1]['absence']['ancestors'].pop(0),
 lambda r:r['classpath'][-1]['absence']['ancestors'].reverse(),
 lambda r:r['classpath'][-1]['absence']['ancestors'][0].update(inode='0001'),
 lambda r:r['classpath'][-1]['absence']['ancestors'][0].update(device=123),
 lambda r:r['classpath'][-1]['absence']['ancestors'][0].update(inode='999999999999'),
 lambda r:r['classpath'][-1]['absence']['ancestors'][0].update(extra=True),
])
def test_unknown_forged_producer_or_absence_proof_fails_closed(absent,mutation):
 record,run=absent;mutation(record)
 with pytest.raises(Failure):ctl.classpath_check(record,run)

@pytest.mark.parametrize('index',[3,4])
@pytest.mark.parametrize('kind',['directory','file','symlink'])
def test_optional_path_appearance_is_not_silently_reclassified(absent,index,kind,tmp_path):
 record,run=absent;path=Path(record['classpath'][index]['path'])
 if kind=='directory':path.mkdir()
 elif kind=='file':path.write_bytes(b'not an optional directory')
 else:path.symlink_to(tmp_path,target_is_directory=True)
 with pytest.raises(Failure):ctl.classpath_check(record,run)

@pytest.mark.parametrize('kind',['jar','directory'])
def test_arbitrary_missing_regular_entries_remain_required(absent,tmp_path,kind):
 record,run=absent;entry=dict(kind=kind,path=str(tmp_path/'missing.jar' if kind=='jar' else tmp_path/'missing-classes'))
 entry.update(sha256='0'*64) if kind=='jar' else entry.update(files=[])
 record['classpath'].append(entry);record['classpathString']=os.pathsep.join(e['path'] for e in record['classpath'])
 with pytest.raises((Failure,OSError)):ctl.classpath_check(record,run)

@pytest.mark.parametrize('target',['foreign','jar','kotlin','resource-neighbor'])
def test_declared_tuple_cannot_authorize_another_path(absent,tmp_path,target):
 record,run=absent;entry=record['classpath'][-1];root=Path(record['appBuildDirectory'])
 paths={'foreign':tmp_path/'outside','jar':root/'missing.jar','kotlin':root/'tmp/kotlin-classes/debugUnitTest','resource-neighbor':root/'generated/ksp/debugUnitTest/other'}
 path=paths[target];entry['path']=str(path);entry['absence']=independent_proof(root,path)
 record['classpathString']=os.pathsep.join(e['path'] for e in record['classpath'])
 with pytest.raises(Failure,match='exact task-declared path'):ctl.classpath_check(record,run)

def test_app_build_directory_cannot_move_with_forged_record(absent,tmp_path):
 record,run=absent;other=tmp_path/'foreign/build';other.mkdir(parents=True);record['appBuildDirectory']=str(other)
 with pytest.raises(Failure,match='selected repository'):ctl.classpath_check(record,run)

def test_absence_requires_independent_selected_root_authority(absent):
 record,_=absent
 with pytest.raises(Failure,match='authority'):ctl.classpath_check(record)

def test_existing_ancestor_swap_is_detected(absent):
 record,run=absent;path=Path(record['classpath'][-1]['path']);parent=path.parent;saved=parent.with_name(parent.name+'-saved');parent.rename(saved);parent.mkdir()
 with pytest.raises(Failure,match='identity changed'):ctl.classpath_check(record,run)

def test_ancestor_symlink_is_not_an_absence(absent):
 record,run=absent;path=Path(record['classpath'][-1]['path']);parent=path.parent;saved=parent.with_name(parent.name+'-saved');parent.rename(saved);parent.symlink_to(saved,target_is_directory=True)
 with pytest.raises(Failure,match='symlink'):ctl.classpath_check(record,run)

def test_ancestor_above_build_root_is_bound(absent):
 record,run=absent;app=Path(record['appBuildDirectory']).parent;saved=app.with_name('saved-app');app.rename(saved);app.mkdir();(saved/'build').rename(app/'build')
 with pytest.raises(Failure,match='identity changed'):ctl.classpath_check(record,run)

def test_first_missing_component_appearance_is_detected_even_when_leaf_still_absent(absent):
 record,run=absent;entry=record['classpath'][-1];path=Path(entry['path']);path.parent.rmdir()
 entry['absence']=independent_proof(Path(record['appBuildDirectory']),path)
 ctl.classpath_check(record,run)
 path.parent.mkdir()
 with pytest.raises(Failure,match='identity changed'):ctl.classpath_check(record,run)

@pytest.mark.parametrize('code',[errno.EACCES,errno.ENOTDIR,errno.EIO,errno.ELOOP])
def test_non_enoent_errors_never_become_absence(absent,monkeypatch,code):
 record,run=absent;path=record['classpath'][-1]['path'];original=os.stat
 def error_at_target(target,*a,**k):
  if str(target)==path:raise OSError(code,'synthetic metadata failure',path)
  return original(target,*a,**k)
 monkeypatch.setattr(ctl.os,'stat',error_at_target)
 with pytest.raises(Failure,match='observation failed'):ctl.classpath_check(record,run)

def test_unreadable_parent_does_not_confirm_missing(absent,monkeypatch):
 record,run=absent;parent=Path(record['classpath'][-1]['path']).parent;original=os.scandir
 def unreadable(path):
  if Path(path)==parent:raise PermissionError(errno.EACCES,'synthetic parent permission')
  return original(path)
 monkeypatch.setattr(ctl.os,'scandir',unreadable)
 with pytest.raises(Failure,match='parent cannot confirm'):ctl.classpath_check(record,run)

def test_false_enoent_is_caught_by_parent_listing(absent,monkeypatch):
 record,run=absent;path=Path(record['classpath'][-1]['path']);path.mkdir();original=os.stat
 def false_absence(target,*a,**k):
  if Path(target)==path:raise FileNotFoundError(errno.ENOENT,'synthetic race',str(path))
  return original(target,*a,**k)
 monkeypatch.setattr(ctl.os,'stat',false_absence)
 with pytest.raises(Failure,match='child appeared'):ctl.classpath_check(record,run)

def test_ancestor_swap_during_observation_is_detected(absent,monkeypatch):
 record,run=absent;entry=record['classpath'][-1];path=Path(entry['path']);parent=path.parent;original=os.scandir;swapped=False
 def swap(path_to_list):
  nonlocal swapped
  result=original(path_to_list)
  if Path(path_to_list)==parent and not swapped:
   swapped=True;parent.rename(parent.with_name(parent.name+'-old'));parent.mkdir()
  return result
 monkeypatch.setattr(ctl.os,'scandir',swap)
 with pytest.raises(Failure,match='changed during observation'):ctl.classpath_check(record,run)

def test_absent_entries_are_revalidated_by_normalization_before_skipping(absent):
 record,run=absent;Path(record['classpath'][-1]['path']).mkdir()
 with pytest.raises(Failure,match='appeared'):ctl.normalization_check(record,run)

def test_reordered_absent_entries_cannot_change_original_classpath_identity(absent):
 record,run=absent;record['classpath'][-1],record['classpath'][-2]=record['classpath'][-2],record['classpath'][-1]
 with pytest.raises(Failure,match='order/identity'):ctl.classpath_check(record,run)

@pytest.mark.parametrize('value',[0,2**63-1,2**63,2**64-1])
def test_unsigned64_identity_encoding_matches_java_decimal_contract(absent,monkeypatch,value):
 record,run=absent;entry=record['classpath'][-1];original=os.stat
 def boundary(path,*a,**k):
  actual=original(path,*a,**k)
  return types.SimpleNamespace(st_mode=actual.st_mode,st_dev=value,st_ino=value)
 monkeypatch.setattr(ctl.os,'stat',boundary)
 proof=ctl.optional_absence_proof(Path(record['appBuildDirectory']),Path(entry['path']),run)
 assert all(a['device']==str(value) and a['inode']==str(value) for a in proof['ancestors'])

@pytest.mark.parametrize('value',[-1,2**64,True])
def test_unsupported_actual_filesystem_identity_range_refused(absent,monkeypatch,value):
 record,run=absent;entry=record['classpath'][-1];original=os.stat
 def boundary(path,*a,**k):
  actual=original(path,*a,**k)
  return types.SimpleNamespace(st_mode=actual.st_mode,st_dev=value,st_ino=actual.st_ino)
 monkeypatch.setattr(ctl.os,'stat',boundary)
 with pytest.raises(Failure,match='unsigned64'):ctl.optional_absence_proof(Path(record['appBuildDirectory']),Path(entry['path']),run)

@pytest.mark.parametrize('value',[str(2**64),'-1','+1','1.0','01','9'*100])
def test_proof_identity_requires_canonical_unsigned64_decimal(absent,value):
 record,run=absent;record['classpath'][-1]['absence']['ancestors'][0]['device']=value
 with pytest.raises(Failure,match='identity schema'):ctl.classpath_check(record,run)

def test_unknown_capture_fields_fail_closed(absent):
 record,run=absent;record['unknown']='not a supported capture/enrichment field'
 with pytest.raises(Failure,match='capture fields'):ctl.classpath_check(record,run)

def test_missing_present_directory_cannot_bypass_direct_normalization_check(absent,tmp_path):
 record,run=absent;record['classpath'].append(dict(kind='directory',path=str(tmp_path/'missing-ordinary-directory'),files=[]))
 record['classpathString']=os.pathsep.join(e['path'] for e in record['classpath'])
 with pytest.raises(Failure,match='Required classpath'):ctl.normalization_check(record,run)

def test_parent_listing_is_bounded_before_accepting_absence(absent,monkeypatch):
 record,run=absent;parent=Path(record['classpath'][-1]['path']).parent;original=os.scandir
 class OversizedListing:
  def __enter__(self):return (types.SimpleNamespace(name='other-'+str(i)) for i in range(100001))
  def __exit__(self,*args):pass
 monkeypatch.setattr(ctl.os,'scandir',lambda path:OversizedListing() if Path(path)==parent else original(path))
 with pytest.raises(Failure,match='listing count limit'):ctl.classpath_check(record,run)

def test_absolute_component_budget_precedes_filesystem_walk(absent):
 record,run=absent;root=Path(record['appBuildDirectory']);target=root.joinpath(*(['nested']*65))
 with pytest.raises(Failure,match='path/root boundary'):ctl.optional_absence_proof(root,target,run)

@pytest.mark.parametrize('mutation',[
 lambda r:r.update(appBuildDirectory=r['appBuildDirectory']+'-other'),
 lambda r:r['classpath'][-1]['producer'].update(admission='up-to-date'),
 lambda r:r['classpath'][-1]['absence']['ancestors'][0].update(inode='42'),
])
def test_runtime_before_fork_capture_binds_absence_and_selected_root(absent,tmp_path,mutation):
 record,run=absent;run.work=tmp_path/'capture';run.work.mkdir()
 for name in ('runtime-prepare.json','runtime-before-fork.json'):(run.work/name).write_text(json.dumps(record))
 assert len(ctl.runtime_classpath_proof(run,record))==2
 changed=copy.deepcopy(record);mutation(changed);(run.work/'runtime-before-fork.json').write_text(json.dumps(changed))
 with pytest.raises(Failure,match='immutable baseline'):ctl.runtime_classpath_proof(run,record)

def test_absent_entry_does_not_widen_existing_cumulative_file_cap(absent,tmp_path,monkeypatch):
 record,run=absent;directory=tmp_path/'counted-directory';directory.mkdir()
 record['classpath'].insert(3,dict(kind='directory',path=str(directory),files=[]));record['classpathString']=os.pathsep.join(e['path'] for e in record['classpath'])
 class CountedEmptyInventory:
  # Abstract budget-only inventory: no large fixture tree or payload reads.
  def __init__(self,count):self.count=count
  def __len__(self):return self.count
  def __iter__(self):return iter(())
 monkeypatch.setattr(ctl,'inventory',lambda *a,**k:(0,CountedEmptyInventory(k['max_files'])))
 with pytest.raises(Failure,match='cumulative byte/count'):ctl.classpath_check(record,run)
