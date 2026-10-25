"""Security-specific external CLI admission and failure-preservation controls."""
import hashlib
import json
import os
from pathlib import Path
import sys

import pytest
import security_cli_boundary as b


@pytest.fixture
def owned(tmp_path, monkeypatch):
    venv = tmp_path/'security-python'
    (venv/'bin').mkdir(parents=True)
    (venv/'bin/python').symlink_to(Path(sys.executable).resolve())
    (venv/'pyvenv.cfg').write_text('include-system-site-packages = false\n')
    (venv/f'lib/python{sys.version_info.major}.{sys.version_info.minor}/site-packages').mkdir(parents=True)
    (tmp_path/'installation.json').write_text(json.dumps({'status':'PASS', 'scanners':b.PINS,
        'lockSha256':{'security-requirements.lock':b.digest(b.ROOT/'tools/dependencies/security-requirements.lock')}}))
    scanner = venv/'bin/python'
    monkeypatch.setenv('RF_SECURITY_PYTHON', str(scanner))
    root = tmp_path/'source';root.mkdir();(root/'safe.py').write_text('answer = 3\n')
    return root, scanner


def report(root, status='FAIL'):
    return {'schema':1, 'status':status, 'tools':b.PINS, 'scanner_source_sha256':b.SCRIPT_SHA,
            'scan_root':str(root), 'checks':{'static':'FAIL'}, 'findings':[]}


@pytest.mark.parametrize('status,code', [('PASS',0), ('FAIL',1), ('NOT_RUN',2)])
def test_exact_cli_shape_preserves_status_and_assertion_report(owned, monkeypatch, status, code):
    root, scanner = owned
    required=root.parent/'requirements.txt';required.write_text('requests==2.19.1\n')
    policy=root.parent/'policy.json';policy.write_text('{}')
    def run(argv, env, folder, output):
        assert argv == [sys.executable,'-I','-S',str(b.SCRIPT),str(root),'--scanner-python',
                        str(scanner),'--requirements',str(required),'--suppressions',str(policy),'--output',str(output)]
        assert not output.exists() and not output.is_relative_to(root)
        output.write_text(json.dumps(report(root,status)))
        return code
    monkeypatch.setattr(b,'_execute',run)
    assert b.scan_external(root,scanner,required,policy)==report(root,status)


@pytest.mark.parametrize('code,status', [(0,'FAIL'), (1,'PASS'), (0,'NOT_RUN'), (-11,'FAIL'), (23,'FAIL')])
def test_nonmatching_exits_never_disappear(owned, monkeypatch, code, status):
    root,scanner=owned
    def run(argv,env,folder,output):
        output.write_text(json.dumps(report(root,status)));return code
    monkeypatch.setattr(b,'_execute',run)
    with pytest.raises(ValueError,match='exit/report'):b.scan_external(root,scanner)


@pytest.mark.parametrize('kind', ['missing','malformed','oversized','symlink','source','root','schema','tools'])
def test_untrusted_reports_fail_closed(owned, monkeypatch, kind):
    root,scanner=owned
    def run(argv,env,folder,output):
        value=report(root)
        if kind=='missing':return 1
        if kind=='malformed':output.write_text('PRIVATE_INVALID_JSON');return 1
        if kind=='oversized':output.write_bytes(b'x'*(b.MAX_REPORT+1));return 1
        if kind=='symlink':output.symlink_to(root/'safe.py');return 1
        if kind=='source':value['scanner_source_sha256']='0'*64
        if kind=='root':value['scan_root']=str(root.parent)
        if kind=='schema':value['schema']=99
        if kind=='tools':value['tools']={}
        output.write_text(json.dumps(value));return 1
    monkeypatch.setattr(b,'_execute',run)
    with pytest.raises((ValueError,OSError)) as caught:b.scan_external(root,scanner)
    assert 'PRIVATE_INVALID_JSON' not in str(caught.value)


def test_parent_instrumentation_is_unchanged(monkeypatch):
    values={'LD_PRELOAD':'private-runtime','LD_AUDIT':'private-audit','LD_LIBRARY_PATH':'private-lib',
            'PYTHONPATH':'private-import','PYTHONSTARTUP':'private-startup','RF_NATIVE_LIBRARY':'private-engine',
            'RF_CABI_LIBRARY':'private-cabi','PYTEST_PLUGINS':'private-plugin','GIT_EXTERNAL_DIFF':'private-git',
            'ASAN_OPTIONS':'detect_leaks=1','LSAN_OPTIONS':'exitcode=23','UBSAN_OPTIONS':'halt_on_error=1',
            'PYTHONMALLOC':'malloc'}
    for key,value in values.items():monkeypatch.setenv(key,value)
    before=dict(os.environ);env=b._security_environment()
    assert dict(os.environ)==before
    for key in values:
        if key in ('ASAN_OPTIONS','LSAN_OPTIONS','UBSAN_OPTIONS','PYTHONMALLOC'):assert env[key]==values[key]
        else:assert key not in env
    assert env['PYTHONNOUSERSITE']==env['PYTHONSAFEPATH']==env['PYTHONDONTWRITEBYTECODE']=='1'


@pytest.mark.parametrize('kind',['source','receipt','selected','system-site','pth','hook','hook-package','hook-extension','bytecode','forged-bytecode','symlink','writable'])
def test_unreviewed_closure_refused_before_launch(owned,monkeypatch,kind):
    root,scanner=owned;venv=scanner.parent.parent
    site=next((venv/'lib').glob('*/site-packages'))
    if kind=='source':
        source=root/'changed.py';source.write_text('import ctypes\n');monkeypatch.setattr(b,'SCRIPT',source)
    if kind=='receipt':(venv.parent/'installation.json').write_text('{}')
    if kind=='selected':monkeypatch.setenv('RF_SECURITY_PYTHON',str(root/'other'))
    if kind=='system-site':(venv/'pyvenv.cfg').write_text('include-system-site-packages = true\n')
    if kind=='pth':(site/'inject.pth').write_text('import ctypes\n')
    if kind=='hook':(site/'sitecustomize.py').write_text('pass\n')
    if kind=='hook-package':(site/'sitecustomize').mkdir()
    if kind=='hook-extension':(site/'sitecustomize.cpython-312-x86_64-linux-gnu.so').write_bytes(b'no code')
    if kind=='forged-bytecode':
        import py_compile
        source=site/'module.py';source.write_text('answer = 1\n');py_compile.compile(str(source),doraise=True)
        source.write_text('answer = 2\n')
    if kind=='bytecode':(site/'bad.pyc').write_bytes(b'bad bytecode')
    if kind=='symlink':(site/'bad.py').symlink_to(root/'safe.py')
    if kind=='writable':
        file=site/'bad.py';file.write_text('pass\n');file.chmod(0o666)
    monkeypatch.setattr(b,'_execute',lambda *a:pytest.fail('untrusted closure launched'))
    with pytest.raises(ValueError):b.scan_external(root,scanner)


def test_post_execution_package_mutation_rejected(owned,monkeypatch):
    root,scanner=owned;site=next((scanner.parent.parent/'lib').glob('*/site-packages'))
    def run(argv,env,folder,output):
        output.write_text(json.dumps(report(root)));(site/'changed.py').write_text('pass\n');return 1
    monkeypatch.setattr(b,'_execute',run)
    with pytest.raises(ValueError,match='changed'):b.scan_external(root,scanner)


def test_real_bounded_process_keeps_nonzero_and_redacts_streams(tmp_path):
    code=b._execute([sys.executable,'-I','-S','-c',"import sys;print('private-token');sys.exit(23)"],
                   b._security_environment(),tmp_path,tmp_path/'report.json')
    assert code==23


def test_process_timeout_and_output_cap(tmp_path,monkeypatch):
    monkeypatch.setattr(b,'TIMEOUT',.05)
    with pytest.raises(ValueError,match='timeout'):
        b._execute([sys.executable,'-I','-S','-c','import time;time.sleep(10)'],b._security_environment(),tmp_path,tmp_path/'report.json')
    monkeypatch.setattr(b,'TIMEOUT',10)
    with pytest.raises(ValueError,match='budget'):
        b._execute([sys.executable,'-I','-S','-c',"print('x'*100000)"],b._security_environment(),tmp_path,tmp_path/'report.json')


def test_canonical_fixture_cannot_pass_on_early_probe_failure(tmp_path, monkeypatch):
    import test_security_evidence as integration
    monkeypatch.setattr(integration, 'scan_external', lambda *a, **k: {
        'status':'NOT_RUN', 'checks':{}, 'source_sha':None, 'source_files_count':1,
        'source_files_sha256':'a'*64,
        'finding_counts':{'raw':0,'active':0,'suppressed':0,'raw_by_rule':{},'active_by_rule':{},'suppressed_by_rule':{}}})
    with pytest.raises(AssertionError):
        integration.test_canonical_scanner_paths_and_source_identity(tmp_path, Path('/unexecuted'))


def test_real_driver_missing_packages_stays_not_run(owned):
    root, scanner = owned
    # Synthetic empty venv is not real scanner qualification. It proves that the
    # actual pinned CLI still rejects absent packages rather than bypassing pins.
    result = b.scan_external(root, scanner)
    assert result['status'] == 'NOT_RUN' and result['checks'] == {}
    assert result['scanner_failure']['tool'] == 'version-probe'
    assert result['scanner_failure']['reason'] == 'nonaccepted-exit'


def test_valid_installed_bytecode_is_not_rejected(owned):
    import py_compile
    root,scanner=owned;site=next((scanner.parent.parent/'lib').glob('*/site-packages'))
    source=site/'module.py';source.write_text('answer = 1\n')
    py_compile.compile(str(source),doraise=True)
    assert b.scanner_identity(scanner)


def test_absent_full_install_receipt_admitted_but_bad_receipt_rejected(owned):
    root,scanner=owned
    receipt=scanner.parent.parent.parent/'installation.json'
    receipt.unlink()
    assert b.scanner_identity(scanner)
    receipt.write_text('{}')
    with pytest.raises(ValueError,match='receipt'):b.scanner_identity(scanner)


def test_reviewed_lock_drift_rejected(owned,monkeypatch):
    root,scanner=owned
    monkeypatch.setattr(b,'LOCK_SHA','0'*64)
    with pytest.raises(ValueError,match='lock'):b.scanner_identity(scanner)


def test_output_inside_scanned_source_refused(owned,monkeypatch):
    root,scanner=owned
    folder=root/'output';folder.mkdir()
    class Temporary:
        def __enter__(self):return str(folder)
        def __exit__(self,*args):pass
    monkeypatch.setattr(b.tempfile,'TemporaryDirectory',lambda **kwargs:Temporary())
    monkeypatch.setattr(b,'_execute',lambda *a:pytest.fail('unsafe output launched'))
    with pytest.raises(ValueError,match='outside'):b.scan_external(root,scanner)


@pytest.mark.parametrize('signum', [2, 15], ids=['sigint', 'sigterm'])
def test_actual_worker_interrupt_cleans_owned_group_before_parent_signal(tmp_path, signum):
    import signal
    import subprocess
    import time
    ready = tmp_path/'ready.json'
    child = """import json,os,subprocess,sys,time
from pathlib import Path
kid=subprocess.Popen([sys.executable,'-I','-S','-c','import time;time.sleep(5)'])
def identity(pid):
    text=Path('/proc',str(pid),'stat').read_text();parts=text[text.rindex(')')+2:].split()
    return [pid,parts[19]]
Path(sys.argv[1]).write_text(json.dumps([identity(os.getpid()),identity(kid.pid)]))
time.sleep(5)
"""
    worker = """import os,signal,sys
from pathlib import Path
sys.path.insert(0,sys.argv[1])
import security_cli_boundary as b
original=b.os.killpg
def interrupted_cleanup(pid,sig):
    # Repeated parent interruptions during cleanup must only set a flag.
    os.kill(os.getpid(),int(sys.argv[4]))
    os.kill(os.getpid(),int(sys.argv[4]))
    return original(pid,sig)
b.os.killpg=interrupted_cleanup
b._execute([sys.executable,'-I','-S','-c',sys.argv[2],sys.argv[3]],
           b._security_environment(),Path(sys.argv[3]).parent,Path(sys.argv[3]).parent/'report.json')
raise SystemExit(99)
"""
    process = subprocess.Popen([sys.executable,'-I','-S','-c',worker,str(Path(b.__file__).parent),
                                child,str(ready),str(signum)], env=b._security_environment(),
                               stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    identities=[]
    def current(identity):
        pid,birth=identity
        try:
            text=Path('/proc',str(pid),'stat').read_text();parts=text[text.rindex(')')+2:].split()
            return parts[0] if parts[19]==birth else None
        except FileNotFoundError:return None
    try:
        deadline=time.monotonic()+10
        while not ready.exists() and process.poll() is None and time.monotonic()<deadline:
            time.sleep(.01)
        assert ready.exists(), 'actual child did not reach readiness'
        identities=json.loads(ready.read_text())
        os.kill(process.pid,signum)
        assert process.wait(timeout=10)==-signum
        deadline=time.monotonic()+.5
        while any(current(row) not in (None,'Z') for row in identities) and time.monotonic()<deadline:
            time.sleep(.01)
        assert all(current(row) in (None,'Z') for row in identities), 'owned child survived parent interruption'
    finally:
        if process.poll() is None:
            process.kill();process.wait()
        # Children self-exit after five seconds if testing a broken boundary.
        # Never signal a raw PID after its owner exited; no stale-PID fallback.
        deadline=time.monotonic()+6
        while any(current(row) not in (None,'Z') for row in identities) and time.monotonic()<deadline:
            time.sleep(.02)


@pytest.mark.parametrize('phase', ['loop', 'spawn'])
def test_signal_handlers_restored_and_custom_handler_runs_after_reap(tmp_path,monkeypatch,phase):
    import signal
    import subprocess
    seen=[];previous={sig:signal.getsignal(sig) for sig in (signal.SIGINT,signal.SIGTERM)}
    real_wait=subprocess.Popen.wait
    reaped=[]
    def wait(process,*args,**kwargs):
        result=real_wait(process,*args,**kwargs);reaped.append(process.pid);return result
    def custom(signum,frame):
        assert reaped
        assert signal.getsignal(signal.SIGTERM) is custom
        seen.append(signum)
    real_sleep=b.time.sleep
    def interrupt(delay):
        os.kill(os.getpid(),signal.SIGTERM)
        real_sleep(delay)
    signal.signal(signal.SIGTERM,custom)
    monkeypatch.setattr(subprocess.Popen,'wait',wait)
    if phase == 'loop':
        monkeypatch.setattr(b.time,'sleep',interrupt)
    else:
        original_popen=subprocess.Popen
        def spawn(*args,**kwargs):
            process=original_popen(*args,**kwargs)
            os.kill(os.getpid(),signal.SIGTERM)
            return process
        monkeypatch.setattr(subprocess,'Popen',spawn)
    try:
        code=b._execute([sys.executable,'-I','-S','-c','import time;time.sleep(60)'],
                        b._security_environment(),tmp_path,tmp_path/'report.json')
        assert code==-signal.SIGTERM and seen==[signal.SIGTERM]
        assert signal.getsignal(signal.SIGINT)==previous[signal.SIGINT]
    finally:
        for sig,handler in previous.items():signal.signal(sig,handler)


def test_signal_handlers_restored_when_spawn_fails(tmp_path,monkeypatch):
    import signal
    previous={sig:signal.getsignal(sig) for sig in (signal.SIGINT,signal.SIGTERM)}
    def fail(*args,**kwargs):raise OSError('synthetic spawn failure')
    monkeypatch.setattr(b.subprocess,'Popen',fail)
    with pytest.raises(OSError,match='synthetic spawn'):
        b._execute([],b._security_environment(),tmp_path,tmp_path/'report.json')
    assert all(signal.getsignal(sig)==handler for sig,handler in previous.items())


def test_ignored_parent_signal_remains_ignored(tmp_path,monkeypatch):
    import signal
    previous=signal.signal(signal.SIGTERM,signal.SIG_IGN)
    original=b.subprocess.Popen
    def spawn(*args,**kwargs):
        os.kill(os.getpid(),signal.SIGTERM)
        return original(*args,**kwargs)
    monkeypatch.setattr(b.subprocess,'Popen',spawn)
    try:
        assert b._execute([sys.executable,'-I','-S','-c','pass'],b._security_environment(),tmp_path,tmp_path/'report.json')==0
        assert signal.getsignal(signal.SIGTERM)==signal.SIG_IGN
    finally:signal.signal(signal.SIGTERM,previous)


def test_pending_cancel_wins_over_reaped_success_with_returning_handler(tmp_path,monkeypatch):
    import signal
    import subprocess
    seen=[]
    previous=signal.signal(signal.SIGTERM,lambda sig,frame:seen.append(sig))
    real_wait=subprocess.Popen.wait
    def wait(process,*args,**kwargs):
        result=real_wait(process,*args,**kwargs)
        assert result==0
        os.kill(os.getpid(),signal.SIGTERM)
        return result
    monkeypatch.setattr(subprocess.Popen,'wait',wait)
    try:
        assert b._execute([sys.executable,'-I','-S','-c','pass'],b._security_environment(),tmp_path,tmp_path/'report.json')==-signal.SIGTERM
        assert seen==[signal.SIGTERM]
    finally:signal.signal(signal.SIGTERM,previous)
