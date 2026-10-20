"""Synthetic process-adapter tests. Actual kills require an installed test APK/device."""
import importlib.util
from pathlib import Path
import json
import pytest
spec=importlib.util.spec_from_file_location('recovery',Path(__file__).resolve().parents[1]/'tools/device/failure_recovery/controller.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
NONCE='a'*32
MARKER={'nonce':NONCE,'readyStage':'import','preparePid':123,'revision':3,'originals':[{'sha256':'b'*64}]}
RESULT={'nonce':NONCE,'stage':'import','status':'PASS','preparePid':123,'verifyPid':456,'revision':3,'artifactKey':'actual-fixture-key'}

@pytest.mark.parametrize('changes',[{'preparePid':0},{'preparePid':'123'},{'nonce':'wrong'},{'readyStage':'publication'},{'originals':[]}])
def test_stale_or_invalid_readiness_never_authorizes_kill(changes):
    with pytest.raises(ValueError):m.validate_marker(json.dumps(MARKER|changes),NONCE,'import')

@pytest.mark.parametrize('changes',[{'verifyPid':123},{'revision':4},{'nonce':'wrong'},{'status':'FAIL'}])
def test_process_identity_and_revision_are_verified(changes):
    text='INSTRUMENTATION_RESULT: recoveryReceipt='+json.dumps(RESULT|changes)+'\nOK (1 test)'
    with pytest.raises(ValueError):m.validate_result(text,MARKER,NONCE,'import')


def test_actual_controller_cycle_requires_old_process_disappear_and_new_receipt(tmp_path):
    class Process:
        dead=False
        def poll(self):return -9 if self.dead else None
        def wait(self,timeout):self.dead=True
        def terminate(self):self.dead=True
    class Fake:
        commands=[]
        killed=False
        process=Process()
        def start(self,args,stream):self.commands.append(args);return self.process
        def run(self,*args):
            self.commands.append(args)
            if 'cat' in args:return json.dumps(MARKER)
            if 'pidof' in args:return '' if self.killed else '123'
            if 'kill' in args:self.killed=True;return ''
            if 'verify' in args:return 'INSTRUMENTATION_RESULT: recoveryReceipt='+json.dumps(RESULT)+'\nOK (1 test)'
            if 'cleanup' in args:return 'OK (1 test)'
            raise AssertionError(args)
    fake=Fake()
    assert m.cycle(fake,NONCE,'import',tmp_path)==RESULT
    assert ('shell','run-as',m.PACKAGE,'kill','-9','123') in fake.commands


def test_controller_refuses_stale_pid_before_kill(tmp_path):
    class Process:
        def poll(self):return None
        def terminate(self):pass
        def wait(self,timeout):pass
    class Fake:
        def start(self,args,stream):return Process()
        def run(self,*args):
            assert 'kill' not in args
            return json.dumps(MARKER) if 'cat' in args else '999'
    with pytest.raises(RuntimeError,match='stale'):m.cycle(Fake(),NONCE,'import',tmp_path)


def test_upgrade_hook_runs_after_verified_process_death_before_verification(tmp_path):
    events=[]
    class Process:
        dead=False
        def poll(self):return -9 if self.dead else None
        def wait(self,timeout):self.dead=True;events.append('death-waited')
        def terminate(self):self.dead=True
    class Fake:
        process=Process();killed=False
        def start(self,args,stream):return self.process
        def run(self,*args):
            if 'cat' in args:return json.dumps(MARKER)
            if 'pidof' in args:return '' if self.killed else '123'
            if 'kill' in args:self.killed=True;events.append('killed');return ''
            if 'verify' in args:
                events.append('verified');return 'INSTRUMENTATION_RESULT: recoveryReceipt='+json.dumps(RESULT)+'\nOK (1 test)'
            if 'cleanup' in args:return 'OK (1 test)'
            raise AssertionError(args)
    fake=Fake()
    def upgrade(adb):
        assert adb is fake and fake.process.dead
        events.append('upgrade')
    assert m.cycle(fake,NONCE,'import',tmp_path,upgrade=upgrade)==RESULT
    assert events==['killed','death-waited','upgrade','verified']
