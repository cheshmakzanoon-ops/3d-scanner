"""Metadata-only domain enumeration, never provider admission/content qualification."""
import builtins,json,os,sys,types
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tools.eight_view_host import header_inventory as h
from tools.eight_view_host.runtime import Failure
RUN=types.SimpleNamespace(remaining=lambda n:n)


def tree(tmp_path):
 root=tmp_path/'headers';root.mkdir();(root/'real').mkdir();(root/'real/header.h').write_text('header')
 outside=tmp_path/'outside';outside.mkdir();(outside/'never-read').write_text('secret')
 return root,outside


def test_complete_multiple_escapes_without_any_payload_reads(tmp_path,monkeypatch):
 root,outside=tree(tmp_path);(root/'one').symlink_to(outside);(root/'two').symlink_to(outside/'never-read');(root/'internal').symlink_to(root/'real')
 real_scandir=os.scandir;opened=[]
 def guarded_scan(fd):
  path=Path(os.readlink(f'/proc/self/fd/{fd}'));assert path.is_relative_to(root);opened.append(path)
  return real_scandir(fd)
 with monkeypatch.context() as patch:
  patch.setattr(h.os,'scandir',guarded_scan)
  patch.setattr(builtins,'open',lambda *a,**k:pytest.fail('regular content open forbidden'))
  patch.setattr(Path,'open',lambda *a,**k:pytest.fail('regular content open forbidden'))
  result=h.scan(RUN,[root])
 assert result['complete'] and result['escapedCount']==2 and result['symlinksSeen']==3
 assert {row['path'] for row in result['escapes']}=={str(root/'one'),str(root/'two')}
 assert set(opened)=={root,root/'real'}
 assert result['classification'].endswith('NOT_CONTENT_SNAPSHOT')


def test_no_escapes_and_overlapping_roots_enumerated_once(tmp_path):
 root,_=tree(tmp_path);(root/'internal').symlink_to(root/'real')
 result=h.scan(RUN,[root,root/'real',root])
 assert result['complete'] and result['escapedCount']==0 and result['entriesSeen']==3

@pytest.mark.parametrize('kind',['broken','loop','fifo'])
def test_unresolved_or_special_entries_incomplete(tmp_path,kind):
 root,_=tree(tmp_path)
 if kind=='broken':(root/'bad').symlink_to(root/'absent')
 elif kind=='loop':(root/'bad').symlink_to(root/'bad')
 else:os.mkfifo(root/'bad')
 result=h.scan(RUN,[root]);assert not result['complete'] and result['errors']

@pytest.mark.parametrize('err',[PermissionError(13,'denied'),OSError(5,'io')])
def test_directory_errors_are_explicit_not_absence(tmp_path,monkeypatch,err):
 root,_=tree(tmp_path)
 def fail(*a,**k):raise err
 monkeypatch.setattr(h.os,'scandir',fail)
 result=h.scan(RUN,[root]);assert not result['complete'] and result['errors'][0]['errno']==err.errno


def test_entry_limit_marks_incomplete(tmp_path,monkeypatch):
 root,_=tree(tmp_path);monkeypatch.setattr(h,'MAX_ENTRIES',1)
 result=h.scan(RUN,[root]);assert not result['complete'] and 'ENTRIES' in result['limitsHit']
 assert result['entriesSeen']==1


def test_time_limit_marks_incomplete(tmp_path,monkeypatch):
 root,_=tree(tmp_path);clock=iter([0,91]);monkeypatch.setattr(h.time,'monotonic',lambda:next(clock,91))
 result=h.scan(RUN,[root]);assert not result['complete'] and 'TIME' in result['limitsHit']


def test_all_escape_count_survives_row_and_output_limits(tmp_path,monkeypatch):
 root,outside=tree(tmp_path)
 for i in range(15):(root/f'escape-{i}').symlink_to(outside)
 monkeypatch.setattr(h,'MAX_ROWS',3)
 result=h.scan(RUN,[root]);assert not result['complete'] and result['escapedCount']==15
 assert len(result['escapes'])==3 and result['escapedRowsOmitted']==12
 monkeypatch.setattr(h,'MAX_ROWS',128);monkeypatch.setattr(h,'MAX_BYTES',1000)
 result=h.scan(RUN,[root]);assert not result['complete'] and result['escapedCount']==15
 assert len(json.dumps(result,sort_keys=True,ensure_ascii=True,separators=(',',':')).encode())<=1000
 assert result['escapedRowsOmitted']+len(result['escapes'])==15 and 'OUTPUT' in result['limitsHit']


def test_text_and_root_omission_are_explicit(tmp_path,monkeypatch):
 root,outside=tree(tmp_path);deep=root
 for i in range(5):deep=deep/('x'*55);deep.mkdir()
 (deep/'escape').symlink_to(outside)
 result=h.scan(RUN,[root]);assert not result['complete'] and result['textsTruncated']>0
 assert result['escapes'][0]['path']['truncated']
 monkeypatch.setattr(h,'MAX_ROOTS',1)
 result=h.scan(RUN,[root,outside]);assert not result['complete'] and result['rootsOmitted']==1


def test_directory_replacement_is_refused_without_following_new_symlink(tmp_path,monkeypatch):
 root,outside=tree(tmp_path);real_open=os.open;done=[False]
 def opened(path,flags,*a,**k):
  if str(path)=='real' and not done[0]:
   done[0]=True;(root/'real').rename(root/'old');(root/'real').symlink_to(outside)
  return real_open(path,flags,*a,**k)
 monkeypatch.setattr(h.os,'open',opened)
 result=h.scan(RUN,[root]);assert done[0] and not result['complete'] and result['errors']


def test_symlink_retarget_race_is_refused(tmp_path,monkeypatch):
 root,outside=tree(tmp_path);link=root/'escape';link.symlink_to(outside);real=Path.resolve
 def changed(path,*a,**k):
  answer=real(path,*a,**k)
  if path==link:link.unlink();link.symlink_to(root/'real')
  return answer
 monkeypatch.setattr(Path,'resolve',changed)
 result=h.scan(RUN,[root]);assert not result['complete'] and result['errors']


def test_cancelled_budget_never_claims_complete(tmp_path):
 root,_=tree(tmp_path)
 calls=[0]
 def left(n):
  calls[0]+=1
  if calls[0]>1:raise Failure('cancelled budget')
  return n
 result=h.scan(types.SimpleNamespace(remaining=left),[root]);assert not result['complete'] and result['errors']

@pytest.mark.parametrize('exception',['Deadline','Cancelled'])
def test_deadline_or_cancel_stops_immediately_before_enumeration(tmp_path,monkeypatch,exception):
 from tools.eight_view_host import runtime
 root,_=tree(tmp_path);calls=[0]
 def left(n):
  calls[0]+=1
  if calls[0]>1:raise getattr(runtime,exception)('budget ended')
  return n
 monkeypatch.setattr(h.os,'scandir',lambda *a:pytest.fail('must stop before enumeration'))
 result=h.scan(types.SimpleNamespace(remaining=left),[root,root/'real'])
 assert not result['complete'] and exception.upper() in result['limitsHit'] and calls[0]==2


def test_new_escape_during_directory_listing_marks_incomplete(tmp_path,monkeypatch):
 root,outside=tree(tmp_path);real=os.scandir;changed=[False]
 class Listing:
  def __init__(self,fd):self.fd=fd;self.inner=real(fd)
  def __enter__(self):
   def entries():
    yield from self.inner
    if Path(os.readlink(f'/proc/self/fd/{self.fd}'))==root and not changed[0]:
     changed[0]=True;(root/'added-after-listing').symlink_to(outside)
   return entries()
  def __exit__(self,*args):self.inner.close()
 monkeypatch.setattr(h.os,'scandir',Listing)
 result=h.scan(RUN,[root]);assert changed[0] and not result['complete'] and result['errors']


def test_empty_domain_cannot_claim_complete():
 result=h.scan(RUN,[]);assert not result['complete'] and result['errors']
