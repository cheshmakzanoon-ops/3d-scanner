"""Owned synthetic-tree inventory controls; no live system/header directory scans."""
import json
from pathlib import Path
import pytest
from tools.eight_view_host import runtime as r

def detail(error):
    return json.loads(str(error).split('INVENTORY_LIMIT ',1)[1].split('; OWNED_ROOT_ROLE',1)[0])

def test_regular_file_inventory_does_not_build_absolute_path_per_entry(tmp_path,monkeypatch):
    root=tmp_path/'owned';root.mkdir()
    expected={}
    for i in range(4):
        directory=root/('d'+str(i));directory.mkdir();expected[directory.name]=0
        for name,raw in [('ascii',b'a'),('unicode-界',b'bb'),('space name',b'')]:
            (directory/name).write_bytes(raw);expected[directory.name+'/'+name]=len(raw)
    def forbidden(*args):raise AssertionError('unnecessary per-entry Path construction')
    monkeypatch.setattr(Path,'joinpath',forbidden)
    assert r.inventory(root)==(12,expected)

def test_count_limit_reports_numeric_reason_without_paths(tmp_path):
    root=tmp_path/'private-root-name';root.mkdir()
    for i in range(4):(root/('private-entry-'+str(i))).write_bytes(b'a')
    with pytest.raises(r.Failure) as error:r.inventory(root,max_files=2)
    assert detail(error.value)['reason']=='COUNT'
    assert detail(error.value)['visitedEntries']==3
    assert 'private-' not in str(error.value)

def test_time_limit_reports_numeric_reason_without_paths(tmp_path,monkeypatch):
    root=tmp_path/'private-root-name';root.mkdir()
    monkeypatch.setattr(r.time,'monotonic',lambda:1.0)
    with pytest.raises(r.Failure) as error:r.inventory(root,seconds=0)
    assert detail(error.value)=={'reason':'TIME','visitedEntries':0,'observedBytes':0,'maximumEntries':100000,'budgetSeconds':0,'elapsedSeconds':0}
    assert str(root) not in str(error.value)

@pytest.mark.parametrize('which',['work','extra-owned-0'])
def test_disk_guard_failure_adds_only_closed_root_role(tmp_path,monkeypatch,which):
    run=r.Runner(tmp_path,tmp_path/'work',clock=lambda:10.0,seconds=300)
    extra=tmp_path/'private-extra';extra.mkdir();run.extra_owned_roots.append(extra)
    def scan(path,**kwargs):
        if path==(run.work if which=='work' else extra):
            raise r.Failure('Owned directory traversal/count limit; INVENTORY_LIMIT {"reason":"TIME","visitedEntries":3}')
        return 0,{}
    monkeypatch.setattr(r,'inventory',scan)
    with pytest.raises(r.Failure) as error:run.disk_guard()
    assert str(error.value).endswith('OWNED_ROOT_ROLE '+which)
    assert str(tmp_path) not in str(error.value)
    assert run.last_scan==0

def test_disk_guard_retains_global_combined_count_limit(tmp_path,monkeypatch):
    run=r.Runner(tmp_path,tmp_path/'work',clock=lambda:10.0,seconds=300)
    extra=tmp_path/'extra';extra.mkdir();run.extra_owned_roots.append(extra)
    monkeypatch.setattr(r,'inventory',lambda *a,**k:(0,dict.fromkeys(range(50001),0)))
    with pytest.raises(r.Failure,match='Combined owned build directory size/count limit'):run.disk_guard()

def test_between_root_deadline_keeps_timeout_status_and_root_role(tmp_path,monkeypatch):
    now=[10.0]
    run=r.Runner(tmp_path,tmp_path/'work',clock=lambda:now[0],seconds=300)
    extra=tmp_path/'private-extra';extra.mkdir();run.extra_owned_roots.append(extra)
    def scan(*args,**kwargs):
        now[0]=run.work_end
        return 0,{}
    monkeypatch.setattr(r,'inventory',scan)
    with pytest.raises(r.Deadline) as error:run.disk_guard()
    assert str(error.value)=='Owned disk inventory deadline; OWNED_ROOT_ROLE extra-owned-0'
    assert error.value.status=='TIMEOUT' and str(tmp_path) not in str(error.value)
