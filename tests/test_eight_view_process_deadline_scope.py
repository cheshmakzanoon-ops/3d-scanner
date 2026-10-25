"""Deterministic clocks/owned-PID fixtures only; no live process enumeration."""
import os
from pathlib import Path
import pytest
from tools.eight_view_host import runtime as r

def fixture(tmp_path,monkeypatch,now=0.0):
    clock=[now]
    run=r.Runner(tmp_path,tmp_path/'work',clock=lambda:clock[0],seconds=300)
    run.subreaper_before=0  # Synthetic active lifecycle; no prctl/pidfd calls.
    def forbidden(*args,**kwargs):raise AssertionError('live process/filesystem audit forbidden')
    monkeypatch.setattr(r.os,'scandir',forbidden)
    monkeypatch.setattr(Path,'read_text',forbidden)
    monkeypatch.setattr(r,'proc_info',forbidden)
    monkeypatch.setattr(r,'child_pids',forbidden)
    monkeypatch.setattr(r.os,'pidfd_open',forbidden)
    return run,clock

@pytest.mark.parametrize('cleanup,now,phase,scope',[
    (False,10,10,'COMMAND_PHASE'),
    (False,180,200,'OVERALL_WORK'),
    (False,180,180,'OVERALL_WORK'),
    (True,300,1,'OVERALL_CLEANUP'),
])
def test_exhausted_enclosing_budget_is_not_a_scan_subdeadline(tmp_path,monkeypatch,cleanup,now,phase,scope):
    run,clock=fixture(tmp_path,monkeypatch)
    clock[0]=now;run.phase_end=phase
    with pytest.raises(r.Deadline) as error:run.scan(cleanup=cleanup)
    assert type(error.value) is r.Deadline and error.value.status=='TIMEOUT'
    assert str(error.value)=='Process ownership scan deadline; deadlineScope='+scope+'; scanStarted=false'
    assert not run.owned and not run.pidfds

@pytest.mark.parametrize('cleanup,now,phase,elapsed,scope',[
    (False,0,10,2.01,'OWNERSHIP_TRAVERSAL'),
    (False,0,1,1.01,'COMMAND_PHASE'),
    (False,179,200,1.01,'OVERALL_WORK'),
    (True,299,1,1.01,'OVERALL_CLEANUP'),
    (False,0,2,2.01,'COMMAND_PHASE'),
])
def test_in_traversal_refusal_identifies_original_limiting_budget(tmp_path,monkeypatch,cleanup,now,phase,elapsed,scope):
    run,clock=fixture(tmp_path,monkeypatch);clock[0]=now;run.phase_end=phase
    readings=iter((0.0,elapsed));monkeypatch.setattr(r.time,'monotonic',lambda:next(readings))
    with pytest.raises(r.Deadline) as error:run.scan(cleanup=cleanup)
    assert type(error.value) is r.Deadline
    assert str(error.value)=='Process ownership traversal limit; deadlineScope='+scope+'; scanStarted=true'

@pytest.mark.parametrize('cleanup',[False,True])
def test_success_keeps_exact_owned_pid_identity_checks(tmp_path,monkeypatch,cleanup):
    run,clock=fixture(tmp_path,monkeypatch);run.phase_end=10
    monkeypatch.setattr(r.time,'monotonic',lambda:0.0)
    parent=os.getpid();child=987654;calls=[]
    def info(pid):
        assert pid==child
        calls.append(('identity',pid))
        return {'pid':child,'ppid':parent,'group':child,'start':123,'state':'S'}
    def children(pid,end):
        assert pid in (parent,child)
        calls.append(('children',pid))
        return [child] if pid==parent else []
    monkeypatch.setattr(r,'proc_info',info);monkeypatch.setattr(r,'child_pids',children)
    monkeypatch.setattr(r.os,'pidfd_open',lambda pid:456 if pid==child else pytest.fail('foreign PID'))
    run.scan(cleanup=cleanup)
    assert run.owned=={child:123} and run.pidfds=={child:456}
    assert calls==[('children',parent),('identity',child),('identity',child),('identity',child),('children',child)]
    run.owned[child]=124
    with pytest.raises(r.Failure,match='identity changed'):run.register(child,parent)

def test_cleanup_does_not_inherit_expired_command_phase(tmp_path,monkeypatch):
    run,clock=fixture(tmp_path,monkeypatch);run.phase_end=-1
    monkeypatch.setattr(r.time,'monotonic',lambda:0.0)
    monkeypatch.setattr(r,'child_pids',lambda pid,end:[])
    run.scan(cleanup=True)
    with pytest.raises(r.Deadline,match='COMMAND_PHASE'):run.scan()
