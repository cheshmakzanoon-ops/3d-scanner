"""Actual fake-process lifecycle and narrow SDK retry-policy regression tests."""
import importlib.util
import io
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

import pytest
from synthetic_child_env import synthetic_child_env
from stdlib_cli_boundary import sdk_rejected_argument_cli
from tools.native_process import native_compile_env
from tools.native_test_build import native_compile_prefix

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / 'tools/ci/install_android_sdk.py'
spec = importlib.util.spec_from_file_location('sdk_install_under_test', PATH)
m = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = m
spec.loader.exec_module(m)


@pytest.mark.parametrize('status,zip_error,attempts', [
    (0, False, 1), (0, True, 1), (1, False, 1), (1, True, 3),
    (2, True, 1), (124, True, 1), (130, True, 1), (143, True, 1),
])
def test_only_exit_one_and_exact_diagnostic_retry(monkeypatch, status, zip_error, attempts):
    calls, delays = [], []
    monkeypatch.setattr(m, 'run_attempt', lambda cmd, cancel: (calls.append(cmd) or status, zip_error))
    monkeypatch.setattr(m, '_backoff', lambda delay, cancel: delays.append(delay))
    assert m.install('/test/sdkmanager') == status
    assert calls == [['/test/sdkmanager', *m.PACKAGES]] * attempts
    assert delays == ([5, 10] if attempts == 3 else [])


def test_recovery_stops_after_success(monkeypatch):
    results = iter([(1, True), (0, False)])
    delays = []
    monkeypatch.setattr(m, 'run_attempt', lambda cmd, cancel: next(results))
    monkeypatch.setattr(m, '_backoff', lambda delay, cancel: delays.append(delay))
    assert m.install() == 0
    assert delays == [5]


def test_cancelled_backoff_does_not_launch_again(monkeypatch):
    calls = []
    monkeypatch.setattr(m, 'run_attempt', lambda cmd, cancel: (calls.append(cmd) or 1, True))
    monkeypatch.setattr(m, '_backoff', lambda delay, cancel: cancel.record(signal.SIGTERM, None))
    assert m.install() == 143
    assert len(calls) == 1


def test_handlers_restored_on_spawn_error(monkeypatch):
    previous = {s: signal.getsignal(s) for s in (signal.SIGTERM, signal.SIGINT)}
    def failed(*args):
        raise FileNotFoundError('missing sdkmanager')
    monkeypatch.setattr(m, 'run_attempt', failed)
    with pytest.raises(FileNotFoundError):
        m.install()
    assert {s: signal.getsignal(s) for s in previous} == previous


@pytest.mark.parametrize('content,matched', [
    (m.ZIP_READ_ERROR, True), (m.ZIP_READ_ERROR.lower(), False),
    (b'Error reading archive', False), (b'license not accepted', False),
    (b'', False), (b'prefix\xff'+m.ZIP_READ_ERROR+b' suffix', True),
])
def test_actual_child_output_and_detection(monkeypatch, content, matched):
    monkeypatch.setattr(m, 'CLEANUP_GRACE_SECONDS', .01)
    out = io.BytesIO()
    script = f'import os; os.write(1,{content!r}); os._exit(1)'
    assert m.run_attempt([sys.executable, '-c', script], m.Cancellation(), timeout=2, output=out) == (1, matched)
    assert out.getvalue() == content


def test_split_diagnostic_and_large_stream(monkeypatch):
    monkeypatch.setattr(m, 'CLEANUP_GRACE_SECONDS', .01)
    phrase = m.ZIP_READ_ERROR
    out = io.BytesIO()
    script = f'import os,time; os.write(1,b"x"*65530+{phrase[:20]!r}); time.sleep(.03); os.write(2,{phrase[20:]!r}); os._exit(1)'
    assert m.run_attempt([sys.executable, '-c', script], m.Cancellation(), timeout=2, output=out) == (1, True)
    assert out.getvalue() == b'x'*65530 + phrase


def running(pid):
    try:
        return Path(f'/proc/{pid}/stat').read_text().split()[2] != 'Z'
    except (FileNotFoundError, ProcessLookupError):
        return False


@pytest.mark.parametrize('leader_exits', [False, True])
def test_timeout_kills_owned_descendants_even_after_leader_exit(tmp_path, monkeypatch, leader_exits):
    monkeypatch.setattr(m, 'CLEANUP_GRACE_SECONDS', .02)
    pidfile = tmp_path/'descendant'
    child = f'import os,signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); open({str(pidfile)!r},"w").write(str(os.getpid())); time.sleep(60)'
    script = f'import subprocess,sys,time; p=subprocess.Popen([sys.executable,"-c",{child!r}]); time.sleep(.05); ' + ('sys.exit(1)' if leader_exits else 'time.sleep(60)')
    started = time.monotonic()
    assert m.run_attempt([sys.executable, '-c', script], m.Cancellation(), timeout=.4, output=io.BytesIO()) == (124, False)
    assert time.monotonic() - started < 3
    deadline = time.monotonic() + 1
    while running(int(pidfile.read_text())) and time.monotonic() < deadline:
        time.sleep(.01)
    assert not running(int(pidfile.read_text()))


def test_cancel_during_spawn_acquires_cleanup_ownership(monkeypatch):
    monkeypatch.setattr(m, 'CLEANUP_GRACE_SECONDS', .01)
    original = m.subprocess.Popen
    cancel = m.Cancellation()
    children = []
    def interrupted(*args, **kwargs):
        proc = original(*args, **kwargs)
        children.append(proc)
        cancel.record(signal.SIGTERM, None)
        return proc
    monkeypatch.setattr(m.subprocess, 'Popen', interrupted)
    assert m.run_attempt([sys.executable, '-c', 'import time; time.sleep(60)'], cancel, timeout=2, output=io.BytesIO()) == (143, False)
    assert children[0].poll() is not None


# These generated wrappers import only the stdlib-only installer controller and
# replace run_attempt before install(), so no SDK/native tool can be launched.
# The actual installer CLI test below retains the inherited runtime environment.
@pytest.mark.parametrize('sig,status', [(signal.SIGTERM, 143), (signal.SIGINT, 130)])
def test_real_signal_interrupts_blocked_stdout_without_retry(tmp_path, sig, status):
    ready = tmp_path/'ready'
    helper = f'''import importlib.util,sys
spec=importlib.util.spec_from_file_location("sdk",{str(PATH)!r})
m=importlib.util.module_from_spec(spec);sys.modules[spec.name]=m;spec.loader.exec_module(m)
original=m.run_attempt
m.run_attempt=lambda command,cancel: original([sys.executable,"-I","-S","-c", "import os,time; open({str(ready)!r},'w').write(str(os.getpid())); time.sleep(60)"],cancel,timeout=5)
sys.exit(m.install())
'''
    proc = subprocess.Popen([sys.executable, '-I', '-S', '-c', helper], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            env=synthetic_child_env())
    try:
        deadline = time.monotonic()+4
        while not ready.exists() and time.monotonic()<deadline:
            time.sleep(.01)
        assert ready.exists()
        proc.send_signal(sig)
        stdout, stderr = proc.communicate(timeout=3)
        assert proc.returncode == status, (stdout, stderr)
        assert b'retry' not in stdout
        assert not running(int(ready.read_text()))
    finally:
        if proc.poll() is None:
            proc.kill(); proc.wait()


def test_package_pins_and_workflow_wiring():
    assert m.PACKAGES == ('platforms;android-36','build-tools;36.0.0','ndk;28.2.13676358','cmake;3.22.1')
    assert m.ATTEMPT_TIMEOUT_SECONDS == 300
    assert m.BACKOFF_SECONDS == (5, 10)
    text = (ROOT/'.github/workflows/verify.yml').read_text()
    assert text.count('- run: python tools/ci/install_android_sdk.py') == 2
    assert 'Verify existing KVM access' in text
    assert '99-kvm4all.rules' not in text


def test_unexpected_cli_arguments_rejected():
    args, env = sdk_rejected_argument_cli([sys.executable, str(PATH), '--licenses'])
    result = subprocess.run(args, env=env, capture_output=True, timeout=3)
    assert result.returncode == 2
    assert result.stdout == b''
    assert result.stderr == b'This helper takes no arguments; SDK package pins are fixed.\n'


def test_rejected_argument_never_enters_install(monkeypatch, capsys):
    def forbidden():
        raise AssertionError('Rejected CLI argument entered installer')
    monkeypatch.setattr(m, 'install', forbidden)
    monkeypatch.setattr(sys, 'argv', [str(PATH), '--licenses'])
    assert m.main() == 2
    captured = capsys.readouterr()
    assert captured.out == ''
    assert captured.err == 'This helper takes no arguments; SDK package pins are fixed.\n'


def test_cancellation_before_spawn(monkeypatch):
    def forbidden(*args, **kwargs):
        raise RuntimeError('must not spawn')
    monkeypatch.setattr(m.subprocess, 'Popen', forbidden)
    assert m.run_attempt(['sdkmanager'], m.Cancellation(signal.SIGINT)) == (130, False)


def test_missing_executable_is_not_retried(tmp_path):
    with pytest.raises(FileNotFoundError):
        m.install(str(tmp_path/'absent-sdkmanager'))


def test_no_stdout_but_live_process_still_times_out(monkeypatch):
    monkeypatch.setattr(m, 'CLEANUP_GRACE_SECONDS', .01)
    script = 'import os,time; os.close(1); os.close(2); time.sleep(60)'
    assert m.run_attempt([sys.executable, '-c', script], m.Cancellation(), timeout=.2, output=io.BytesIO()) == (124, False)


def test_signal_during_real_backoff_returns_promptly():
    helper = f'''import importlib.util,sys,os,signal,threading
spec=importlib.util.spec_from_file_location("sdk",{str(PATH)!r})
m=importlib.util.module_from_spec(spec);sys.modules[spec.name]=m;spec.loader.exec_module(m)
calls=[]
def attempt(command,cancel):
 calls.append(1)
 if len(calls)>1: raise RuntimeError("retried after cancellation")
 threading.Timer(.05,lambda:os.kill(os.getpid(),signal.SIGTERM)).start()
 return 1,True
m.run_attempt=attempt
sys.exit(m.install())
'''
    started = time.monotonic()
    result = subprocess.run([sys.executable, '-I', '-S', '-c', helper], capture_output=True, timeout=3,
                            env=synthetic_child_env())
    assert result.returncode == 143, result.stderr
    assert time.monotonic() - started < 2


@pytest.mark.parametrize('cancel', [True, False])
def test_undrained_helper_stdout_cancels_or_times_out_and_kills_child(tmp_path, cancel):
    """Do not read helper stdout until it exits: exercises actual backpressure."""
    ready = tmp_path/'flood-child'
    helper = f'''import importlib.util,sys
spec=importlib.util.spec_from_file_location("sdk",{str(PATH)!r})
m=importlib.util.module_from_spec(spec);sys.modules[spec.name]=m;spec.loader.exec_module(m)
original=m.run_attempt
child="import os; open({str(ready)!r},'w').write(str(os.getpid())); \\nwhile True: os.write(1,b'x'*65536)"
m.run_attempt=lambda command,cancel: original([sys.executable,"-I","-S","-c",child],cancel,timeout=.8)
sys.exit(m.install())
'''
    proc = subprocess.Popen([sys.executable, '-I', '-S', '-c', helper], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            env=synthetic_child_env())
    try:
        deadline=time.monotonic()+4
        while not ready.exists() and time.monotonic()<deadline:
            time.sleep(.01)
        assert ready.exists()
        time.sleep(.15)  # Both child and helper output pipes fill without a reader.
        if cancel:
            proc.send_signal(signal.SIGTERM)
        proc.wait(timeout=3)  # Deliberately not communicate(): it would drain output.
        assert proc.returncode == (143 if cancel else 124), proc.stderr.read()
        assert not running(int(ready.read_text()))
    finally:
        if proc.poll() is None:
            proc.kill();proc.wait()
        proc.stdout.close();proc.stderr.close()


def test_forwarder_preserves_inherited_pipe_flags():
    read_fd, write_fd = os.pipe()
    try:
        with os.fdopen(os.dup(write_fd), 'wb', buffering=0) as output:
            before = os.get_blocking(write_fd)
            sink = m.OutputForwarder(output)
            try:
                assert os.get_blocking(write_fd) == before
                assert not os.get_blocking(sink.fd)
                sink.write(b'hello', m.Cancellation(), time.monotonic()+1)
                assert os.read(read_fd, 5) == b'hello'
            finally:
                sink.close()
            assert os.get_blocking(write_fd) == before
    finally:
        os.close(read_fd);os.close(write_fd)


def _compile_exit_fixture(directory):
    """No-op process, not a CPython shutdown or scanner-engine qualification.

    Instrument generated code with the selected matrix ABI. Only the compiler
    uses the existing build-tool environment boundary; execution inherits every
    parent loader/sanitizer setting through unchanged run_attempt.
    """
    source = directory / 'reserved_leader.cpp'
    binary = directory / 'reserved_leader'
    source.write_text('int main() { return 0; }\n')
    subprocess.run([*native_compile_prefix(), str(source), '-o', str(binary)],
                   env=native_compile_env(), check=True, capture_output=True, timeout=30)
    return binary


@pytest.mark.parametrize('failure', ['missing', 'nonzero'])
def test_leader_fixture_compiler_failure_is_fatal(tmp_path, monkeypatch, failure):
    prefix = ['/missing/fixture-compiler'] if failure == 'missing' else ['/usr/bin/false']
    monkeypatch.setattr(sys.modules[__name__], 'native_compile_prefix', lambda: prefix)
    error = FileNotFoundError if failure == 'missing' else subprocess.CalledProcessError
    with pytest.raises(error):
        _compile_exit_fixture(tmp_path)
    assert not (tmp_path / 'reserved_leader').exists()


def test_leader_pid_remains_reserved_until_group_cleanup(monkeypatch, tmp_path):
    binary = _compile_exit_fixture(tmp_path)
    monkeypatch.setattr(m, 'CLEANUP_GRACE_SECONDS', .01)
    real_signal = m._signal_group
    observed=[]
    def checked(pid, signum):
        # ChildProcessError would mean a prematurely reaped leader.
        state=os.waitid(os.P_PID,pid,os.WEXITED|os.WNOHANG|os.WNOWAIT)
        observed.append((signum,state is not None))
        real_signal(pid,signum)
    monkeypatch.setattr(m,'_signal_group',checked)
    # Preserve the actual runtime boundary, including preload and LSan options.
    real_popen = m.subprocess.Popen
    environment = dict(os.environ)
    owned = []
    def inherited_popen(command, **kwargs):
        assert command == [str(binary)]
        assert 'env' not in kwargs
        assert dict(os.environ) == environment
        proc = real_popen(command, **kwargs)
        owned.append(proc)
        return proc
    monkeypatch.setattr(m.subprocess, 'Popen', inherited_popen)
    output = io.BytesIO()
    result = m.run_attempt([str(binary)],m.Cancellation(),timeout=2,output=output)
    raw = output.getvalue()
    assert result == (0,False), {'result': result, 'output_bytes': len(raw),
                                'sanitizer_markers': {name: raw.count(name.encode()) for name in
                                                     ('AddressSanitizer', 'LeakSanitizer', 'UndefinedBehaviorSanitizer')}}
    assert observed == [(signal.SIGTERM,True),(signal.SIGKILL,True)]
    assert raw == b'', 'No-op native fixture must not emit sanitizer diagnostics'
    assert len(owned) == 1 and owned[0].returncode == 0
    with pytest.raises(ChildProcessError):
        os.waitid(os.P_PID, owned[0].pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
