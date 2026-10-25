"""No host permissions are changed: lease admission/ACL semantics with test doubles."""
import copy
import importlib.util
from pathlib import Path
import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('kvm_lease', ROOT / 'tools/device/installed_journey/kvm_lease.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def fixture():
    head = 'a' * 40
    env = dict(GITHUB_EVENT_NAME='push', GITHUB_REPOSITORY=m.REPOSITORY,
               GITHUB_REF='refs/heads/main', GITHUB_RUN_ATTEMPT='1', GITHUB_SHA=head,
               RUNNER_ENVIRONMENT='github-hosted', RUNNER_OS='Linux')
    event = dict(repository=dict(full_name=m.REPOSITORY, private=False), before=m.BASE,
                 after=head, forced=False, deleted=False, head_commit=dict(id=head, message=m.MESSAGE))
    return env, event, head, m.BASE, m.MESSAGE, m.ALLOWED


def test_exact_source_is_admitted():
    m.admit(*fixture())


@pytest.mark.parametrize('key,value', [('GITHUB_EVENT_NAME','workflow_dispatch'),
    ('GITHUB_REPOSITORY','other/repo'), ('GITHUB_REF','refs/heads/topic'),
    ('GITHUB_RUN_ATTEMPT','2'), ('GITHUB_SHA','b'*40),
    ('RUNNER_ENVIRONMENT','self-hosted'), ('RUNNER_OS','Windows')])
def test_other_events_never_authorize_permission_change(key, value):
    args = list(fixture()); args[0][key] = value
    with pytest.raises(ValueError): m.admit(*args)


@pytest.mark.parametrize('key,value', [('before','b'*40), ('after','b'*40),
    ('forced',True), ('deleted',True), ('repository',dict(full_name=m.REPOSITORY, private=True)),
    ('head_commit',dict(id='a'*40, message='other'))])
def test_event_forgery_or_unrelated_push_is_rejected(key,value):
    args = list(fixture()); args[1][key] = value
    with pytest.raises(ValueError): m.admit(*args)


@pytest.mark.parametrize('index,value', [(3,'b'*40), (4,'another message'),
    (5,m.ALLOWED | {'unrelated.py'}), (5,m.ALLOWED - {'SOURCE_MANIFEST.sha256'})])
def test_source_parent_message_and_exact_change_set(index,value):
    args = list(fixture()); args[index] = value
    with pytest.raises(ValueError): m.admit(*args)


def effective(acl):
    entries = {}
    for line in acl.splitlines():
        line=line.split('#',1)[0].strip()
        if not line: continue
        kind, qualifier, bits=line.split(':')
        entries[kind,qualifier]=set(bits)-{'-'}
    mask=entries.get(('mask',''),set('rwx'))
    return {key:(bits & mask if key[0]=='group' or key[0]=='user' and key[1] else bits)
            for key,bits in entries.items() if key[0]!='mask'}


@pytest.mark.parametrize('mask', ['---','r--','rw-','r-x','rwx'])
@pytest.mark.parametrize('runner_existing', ['', 'user:1001:r-x\n'])
def test_named_runner_grant_preserves_every_other_effective_permission(mask,runner_existing):
    original=f'# file: /dev/kvm\n# owner: 0\nuser::rw-\nuser:42:rwx\n{runner_existing}group::rwx\ngroup:9:rw-\nmask::{mask}\nother::---\n'
    before=effective(original); after=effective(m.runner_acl(original,1001))
    for key, perms in before.items():
        if key != ('user','1001'): assert after[key] == perms
    assert after['user','1001'] == before.get(('user','1001'),set()) | set('rw')
    assert set(after)-set(before) <= {('user','1001')}


def test_basic_mode_acl_is_extended_without_group_or_other_expansion():
    acl='user::rw-\ngroup::r--\nother::---\n'
    assert effective(m.runner_acl(acl,1001)) == {
        ('user',''):set('rw'), ('group',''):set('r'), ('other',''):set(), ('user','1001'):set('rw')}


@pytest.mark.parametrize('acl', ['user::rw-\n', 'user::rw-\nuser::rw-\ngroup::---\nother::---\n',
    'user::rw-\ngroup::---\nother::---\ndefault:user::rwx\n',
    'user::abc\ngroup::---\nother::---\n'])
def test_unexpected_acl_fails_closed(acl):
    with pytest.raises(ValueError): m.runner_acl(acl,1001)


def test_root_is_not_a_runner_lease():
    with pytest.raises(ValueError): m.runner_acl('user::rw-\ngroup::---\nother::---\n',0)


def test_rejected_admission_prevents_any_command_or_directory(monkeypatch,tmp_path):
    def reject(): raise ValueError('not authorized')
    monkeypatch.setattr(m,'admission',reject)
    monkeypatch.setattr(m,'command',lambda *args: pytest.fail('Command executed before admission'))
    with pytest.raises(ValueError): m.acquire(tmp_path/'lease')
    assert not (tmp_path/'lease').exists()


def test_release_restores_snapshot_and_verifies_exact_acl(monkeypatch,tmp_path):
    original='# file: /dev/kvm\n# owner: 0\n# group: 108\nuser::rw-\ngroup::rw-\nother::---\n\n'
    (tmp_path/'original.acl').write_text(original)
    (tmp_path/'identity.json').write_text('{"inode": 12}')
    monkeypatch.setattr(m,'device_identity',lambda:dict(inode=12))
    calls=[]
    def command(*args):
        calls.append(args)
        return original if args[0]=='getfacl' else ''
    monkeypatch.setattr(m,'command',command)
    m.release(tmp_path)
    assert calls == [('sudo','-n','setfacl','--restore='+str(tmp_path/'original.acl')),
                     ('getfacl','--absolute-names','--numeric','/dev/kvm')]


def test_release_refuses_replacement_device(monkeypatch,tmp_path):
    (tmp_path/'original.acl').write_text('snapshot')
    (tmp_path/'identity.json').write_text('{"inode": 12}')
    monkeypatch.setattr(m,'device_identity',lambda:dict(inode=13))
    monkeypatch.setattr(m,'command',lambda *args:pytest.fail('Must not touch replacement'))
    with pytest.raises(ValueError):m.release(tmp_path)


def test_workflow_remains_one_shot_and_uses_real_rendering_existing_ui_runner():
    text=(ROOT/'.github/workflows/installed-journey-once.yml').read_text()
    assert m.BASE in text and m.MESSAGE in text
    assert 'github.run_attempt == 1' in text and 'workflow_dispatch' not in text
    assert 'fetch-depth: 2' in text and 'ref: ${{ github.sha }}' in text
    assert 'target: google_apis' in text and 'aosp_atd' not in text
    assert '-accel on' in text and 'disable-linux-hw-accel: false' in text
    assert 'script: python3 tools/device/installed_journey/run.py' in text
    assert 'if: always()\n        run: python3 tools/device/installed_journey/kvm_lease.py release' in text
    assert 'retention-days: 1' in text
    for forbidden in ('chmod','udevadm','99-kvm4all','continue-on-error','--all-required'):
        assert forbidden not in text
    ordinary=(ROOT/'.github/workflows/verify.yml').read_text()
    assert 'kvm_lease.py' not in ordinary


def runtime_module():
    spec = importlib.util.spec_from_file_location('installed_runtime', ROOT / 'tools/device/installed_journey/run.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def test_build_receipt_matches_actual_existing_ui_runner_contract(monkeypatch,tmp_path):
    r=runtime_module()
    import hashlib,json,subprocess
    app=tmp_path/'app.apk';app.write_bytes(b'app')
    test=tmp_path/'test.apk';test.write_bytes(b'test')
    head='a'*40
    expected=dict(source_sha=head,apk_sha256=hashlib.sha256(b'app').hexdigest(),test_apk_sha256=hashlib.sha256(b'test').hexdigest())
    receipt=tmp_path/'receipt.json';receipt.write_text(json.dumps(expected))
    def git(args,**kwargs):
        assert args[0]=='git'
        return subprocess.CompletedProcess(args,0,head if 'rev-parse' in args else '', '')
    monkeypatch.setattr(r.ui.subprocess,'run',git)
    assert r.ui.source_binding(tmp_path,head,app,test,receipt)==expected


@pytest.mark.parametrize('mode', ['pass','fail','skip','wrong','zero','timeout','hash-mismatch'])
def test_lifecycle_requires_installed_hashes_and_complete_exact_method(mode,tmp_path):
    import subprocess,json
    r=runtime_module()
    candidate={'apk_sha256':'a'*64,'test_apk_sha256':'b'*64}
    calls=[]
    class Commands:
        def shell(self,*args,**kwargs):
            calls.append((args,kwargs))
            if args[:2]==('pm','path'):
                return subprocess.CompletedProcess(args,0,'package:/data/app/'+args[2]+'.apk\n','')
            if args[0]=='sha256sum':
                value='b'*64 if '.test.apk' in args[1] else 'a'*64
                if mode=='hash-mismatch':value='c'*64
                return subprocess.CompletedProcess(args,0,value+'  '+args[1],'')
            if args[:2]==('am','force-stop'):
                return subprocess.CompletedProcess(args,0,'','')
            assert args[:2]==('am','instrument') and kwargs=={'timeout':300}
            identity=r.FILAMENT_METHOD if mode!='wrong' else 'com.example.Wrong#method'
            classname,method=identity.split('#')
            if mode=='zero':return subprocess.CompletedProcess(args,0,'OK (0 tests)\nINSTRUMENTATION_CODE: -1\n','')
            if mode=='timeout':return subprocess.CompletedProcess(args,124,'','Command timed out')
            code={'fail':-2,'skip':-3}.get(mode,0)
            transcript=''
            for state in (1,code):
                transcript+=f'INSTRUMENTATION_STATUS: class={classname}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: {state}\n'
            transcript+='OK (1 test)\nINSTRUMENTATION_CODE: -1\n'
            return subprocess.CompletedProcess(args,0,transcript,'')
    assert r.run_filament(Commands(),candidate,tmp_path) is (mode=='pass')
    receipt=json.loads((tmp_path/'filament.json').read_text())
    assert receipt['status']==('PASS' if mode=='pass' else 'FAIL')
    if mode=='hash-mismatch':assert not any(args[:2]==('am','instrument') for args,_ in calls)


def test_wrapper_has_fixed_two_test_scope_and_independent_failure_retention():
    r=runtime_module()
    assert r.FILAMENT_METHOD=='com.realityforge.scanner.PhotographicViewerTest#filamentRestoresCameraPausesAndRejectsOversizedResize'
    text=(ROOT/'tools/device/installed_journey/run.py').read_text()
    assert "'synthetic_import_reconstruct_export_reopen'" in text
    assert "receipt['ui_returncode'] == 0 and receipt['filament_passed']" in text
    assert 'start_new_session=True' in text and 'os.killpg' in text
    assert "timeout=1200" in text and "timeout=300" in text
