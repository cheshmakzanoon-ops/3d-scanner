"""Host-only bounded subprocess/adb fakes. Never device-runtime evidence."""
import hashlib
import ctypes
from contextlib import contextmanager, nullcontext
import os
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace

import pytest
from synthetic_child_env import synthetic_child_env

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('failure_logcat_runner', ROOT / 'tools/device/compatibility/runner.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
from device.compatibility import failure_logcat as logcat


def spawn_python(source, commands, processes):
    # Only inline stdlib fake adb clients use this adapter; never a real tool.
    def spawn(argv, **kwargs):
        commands.append((argv, kwargs.copy()))
        kwargs['env'] = synthetic_child_env(kwargs.get('env'))
        process = subprocess.Popen([sys.executable, '-I', '-S', '-c', source], **kwargs)
        processes.append(process)
        return process
    return spawn


def capture_diagnostic(result):
    # Keep failures useful without dumping client output or environment values.
    return {key: result.get(key) for key in (
        'status', 'exitCode', 'observedBytes', 'retainedBytes', 'timedOut')}


def test_fake_client_environment_does_not_change_production_forwarding(tmp_path, monkeypatch):
    monkeypatch.setenv('LD_PRELOAD', '/synthetic/never-loaded.so')
    monkeypatch.setenv('ASAN_OPTIONS', 'detect_leaks=1')
    result, commands, _ = run_capture(tmp_path,
        "import os; assert 'LD_PRELOAD' not in os.environ; "
        "assert os.environ['ASAN_OPTIONS'] == 'detect_leaks=1'; print('synthetic')")
    assert result['status'] == 'CAPTURED', capture_diagnostic(result)
    # capture() must still request normal environment inheritance for real adb.
    assert 'env' not in commands[0][1]
    assert os.environ['LD_PRELOAD'] == '/synthetic/never-loaded.so'
    assert os.environ['ASAN_OPTIONS'] == 'detect_leaks=1'


def subreaper_state():
    libc = ctypes.CDLL(None, use_errno=True)
    state = ctypes.c_int()
    if libc.prctl(37, ctypes.byref(state), 0, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), 'cannot read test subreaper state')
    return libc, state.value


@contextmanager
def owned_orphan_client(processes):
    # These two fixtures deliberately orphan a client descendant. Reap their
    # own children rather than leaving zombies for an outer CI subreaper.
    scope_spec = importlib.util.spec_from_file_location(
        'logcat_fixture_scope', ROOT / 'tools/qa/lsan_diagnostic/process_scope.py')
    module = importlib.util.module_from_spec(scope_spec)
    scope_spec.loader.exec_module(module)
    libc, previous = subreaper_state()
    if module.children(os.getpid()):
        raise RuntimeError('test already owns unrelated children')
    scope = None
    try:
        scope = module.Scope()
        yield scope
    finally:
        persistent = False
        if processes:
            if scope is None or len(processes) != 1:
                raise RuntimeError('unexpected test client ownership')
            process = processes[0]
            scope.leader = process.pid
            persistent, drained = scope.finish(
                process, allow_natural=process.poll() is not None)
            if not drained or scope.errors or process.poll() is None:
                raise RuntimeError('test client cleanup unverified')
        if module.children(os.getpid()):
            raise RuntimeError('test children remain; retain subreaper ownership')
        # Restore only after actual child enumeration/reaping confirms drain.
        if libc.prctl(36, previous, 0, 0, 0) != 0:
            raise OSError(ctypes.get_errno(), 'cannot restore test subreaper state')
        if subreaper_state()[1] != previous:
            raise RuntimeError('test subreaper restoration unverified')
        if persistent or (scope is not None and scope.escaped):
            raise RuntimeError('unexpected surviving or escaped test client')


def run_capture(tmp_path, source, *, owned_orphans=False, **limits):
    commands, processes = [], []
    ownership = owned_orphan_client(processes) if owned_orphans else nullcontext()
    with ownership:
        result = logcat.capture('emulator-5554', True, tmp_path / 'logcat.txt',
                               popen=spawn_python(source, commands, processes), **limits)
    return result, commands, processes


def test_capture_is_serial_bound_and_does_not_clear_or_escalate(tmp_path):
    result, commands, processes = run_capture(tmp_path,
        "print('--------- beginning of crash'); print('AndroidRuntime: synthetic failure')")
    assert result['status'] == 'CAPTURED' and result['exitCode'] == 0, capture_diagnostic(result)
    assert result['buffersRequested'] == ['main', 'system', 'crash']
    assert result['buffersObserved'] == ['crash'] and result['bufferAvailability'] == 'NOT_VERIFIED'
    assert commands[0][0] == ['adb', '-s', 'emulator-5554', 'logcat', '-d', '-t', '4000',
        '-v', 'threadtime', '-b', 'main', '-b', 'system', '-b', 'crash']
    assert commands[0][1]['start_new_session'] is True
    assert commands[0][1]['stdin'] == subprocess.DEVNULL
    assert result['maxBytes'] == 2 * 1024 * 1024 and result['timeoutSeconds'] == 20
    assert result['startedAtUtc'] <= result['finishedAtUtc']
    assert processes[0].poll() is not None and processes[0].stdout.closed
    assert (tmp_path / result['artifact']).stat().st_size == result['retainedBytes']


def test_byte_cap_is_streamed_utf8_safe_tail_not_unbounded_communicate(tmp_path, monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError('unbounded communicate is forbidden')
    monkeypatch.setattr(subprocess.Popen, 'communicate', forbidden)
    result, _, _ = run_capture(tmp_path,
        "import sys; sys.stdout.buffer.write(('é' * 100000 + 'TAIL').encode()); sys.stdout.flush()", max_bytes=1023)
    raw = (tmp_path / 'logcat.txt').read_bytes()
    assert len(raw) <= 1023 and raw.decode('utf-8').endswith('TAIL'), capture_diagnostic(result)
    assert result['truncated'] is True and result['observedBytes'] == 200004
    assert result['retainedBytes'] == len(raw) and result['status'] == 'CAPTURED'


def test_invalid_utf8_does_not_expand_the_byte_cap(tmp_path):
    result, _, _ = run_capture(tmp_path,
        "import sys; sys.stdout.buffer.write(b'\\xff' * 4096 + b'END')", max_bytes=64)
    assert result['retainedBytes'] <= 64 and result['truncated']
    assert (tmp_path / 'logcat.txt').read_text() == 'END', capture_diagnostic(result)


def test_timeout_preserves_partial_tail_and_reaps_its_client(tmp_path):
    result, _, processes = run_capture(tmp_path,
        "import sys,time; print('partial crash evidence', flush=True); time.sleep(60)", timeout=0.5)
    assert result['status'] == 'TIMED_OUT' and result['timedOut'] is True
    assert 'partial crash evidence' in (tmp_path / 'logcat.txt').read_text()
    assert processes[0].poll() is not None and processes[0].stdout.closed


def test_inherited_pipe_from_stalled_child_is_deadline_bounded(tmp_path):
    # Parent exits, but a child still owns stdout. The collector must not wait
    # forever on EOF or leave that newly-created client group running.
    result, _, processes = run_capture(tmp_path,
        "import os,time; child=os.fork(); print('parent/child fixture',flush=True); "
        "time.sleep(60) if child == 0 else None", timeout=0.5, owned_orphans=True)
    assert result['status'] == 'TIMED_OUT'
    assert processes[0].poll() is not None and result['retainedBytes'] > 0



def test_descendant_without_inherited_pipe_is_still_terminated(tmp_path):
    result, _, processes = run_capture(tmp_path,
        "import os,time\nchild=os.fork()\n"
        "if child == 0:\n os.close(1); os.close(2); time.sleep(60)\n"
        "else:\n print('child='+str(child),flush=True)\n", owned_orphans=True)
    assert result['status'] == 'CAPTURED' and result['residualGroupTerminated'] is True, capture_diagnostic(result)
    assert processes[0].poll() == 0


def test_invalid_only_output_is_explicitly_empty_not_captured(tmp_path):
    result, _, _ = run_capture(tmp_path, "import sys; sys.stdout.buffer.write(b'\\xff' * 12)")
    assert result['status'] == 'EMPTY' and result['omittedInvalidUtf8Bytes'] == 12, capture_diagnostic(result)
    assert result['retainedBytes'] == 0


def test_disconnected_client_keeps_stderr_and_failed_status(tmp_path):
    result, _, processes = run_capture(tmp_path,
        "import sys; print('adb: device offline',file=sys.stderr); sys.exit(1)")
    assert result['status'] == 'FAILED' and result['exitCode'] == 1
    assert 'device offline' in (tmp_path / 'logcat.txt').read_text()
    assert processes[0].poll() == 1


def test_missing_adb_and_empty_logs_have_explicit_receipts(tmp_path):
    def missing(*args, **kwargs): raise FileNotFoundError('synthetic missing adb')
    result = logcat.capture('emulator-5554', True, tmp_path / 'logcat.txt', popen=missing)
    assert result['status'] == 'ERROR' and 'missing adb' in result['error']
    assert 'artifact' not in result and not (tmp_path / 'logcat.txt').exists()
    result, _, _ = run_capture(tmp_path, 'pass')
    assert result['status'] == 'EMPTY' and result['retainedBytes'] == 0, capture_diagnostic(result)


@pytest.mark.parametrize('serial,emulator', [('physical', False), ('physical', True), ('emulator-5554', False), (None, True)])
def test_collector_refuses_unverified_or_physical_identity(tmp_path, serial, emulator):
    def forbidden(*args, **kwargs): raise AssertionError('must not collect')
    result = logcat.capture(serial, emulator, tmp_path / 'logcat.txt', popen=forbidden)
    assert result['status'] == 'NOT_CAPTURED' and not (tmp_path / 'logcat.txt').exists()


@pytest.mark.parametrize('limits', [{'timeout': 21}, {'timeout': 0}, {'max_bytes': 2097153}, {'max_bytes': 0}])
def test_diagnostic_bounds_cannot_be_enlarged(tmp_path, limits):
    with pytest.raises(ValueError): logcat.capture('emulator-5554', True, tmp_path / 'logcat.txt', **limits)


def fake_run(tmp_path, *, enabled=True, serial='emulator-5554', emulator='1',
             failure='crash', capture=None):
    apk = tmp_path / 'synthetic.apk'; apk.write_bytes(b'synthetic controller-only input')
    args = SimpleNamespace(output=tmp_path/'output', app_apk=apk, test_apk=apk,
        serial=serial, expect_api=35, expect_abi='x86_64', expect_page_size=4096,
        retain_emulator_failure_logcat=enabled)
    args.output.mkdir(exist_ok=True)
    (args.output / 'logcat.txt').write_text('STALE log from earlier run')
    commands, captures = [], []
    def execute(command):
        commands.append(command)
        if command == ['adb', 'devices', '-l']:
            return f'List of devices attached\n{serial} device\n'
        assert command[:3] == ['adb', '-s', serial]
        if 'instrument' in command:
            if failure == 'timeout':
                raise subprocess.TimeoutExpired(command, 900, output=b'partial instrumentation', stderr=b'original stderr')
            if failure == 'nonzero': raise RuntimeError('ORIGINAL nonzero adb failure')
            return 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\nINSTRUMENTATION_CODE: 0'
        if command[3] == 'pull': Path(command[-1]).write_bytes(b'only used with mocked screenshot validator')
        key = ' '.join(command[4:])
        if key.startswith('pm path '): return 'package:/data/app/synthetic.apk'
        if key.startswith('sha256sum '): return hashlib.sha256(apk.read_bytes()).hexdigest() + '  /data/app/synthetic.apk'
        return {'getprop ro.build.fingerprint':'synthetic emulator', 'getprop ro.build.version.sdk':'35',
                'getprop ro.product.cpu.abi':'x86_64', 'getconf PAGESIZE':'4096', 'getprop ro.kernel.qemu':emulator}.get(key, '')
    def collect(selected, measured, destination):
        captures.append((selected, measured, destination))
        assert not destination.exists(), 'stale diagnostic must be removed'
        if capture: return capture(selected, measured, destination)
        destination.write_text('NEW synthetic diagnostic')
        return {'status':'CAPTURED', 'artifact':destination.name}
    status = runner.run(args, execute, collect)
    receipt = json.loads((args.output/'compatibility.json').read_text())
    return status, receipt, args, commands, captures


@pytest.mark.parametrize('failure', ['crash', 'timeout', 'nonzero'])
def test_failed_instrumentation_retains_original_failure_counts_and_artifacts(tmp_path, failure):
    status, receipt, args, commands, captures = fake_run(tmp_path, failure=failure)
    assert status == 1 and receipt['status'] == 'FAIL'
    assert 'counts' not in receipt and 'tests' not in receipt
    assert receipt['runtime16kb'] == 'NOT_RUN' and receipt['physicalCapture'] == 'NOT_RUN'
    assert receipt['suite']['fullQualification'] == 'NOT_RUN'
    assert len(captures) == 1 and captures[0][:2] == ('emulator-5554', True)
    assert receipt['logcat']['status'] == 'CAPTURED'
    assert (args.output/'logcat.txt').read_text() == 'NEW synthetic diagnostic'
    if failure == 'timeout':
        assert receipt['instrumentationTimedOut'] is True
        assert (args.output/'instrumentation.txt').read_bytes() == b'partial instrumentation'
        assert (args.output/'instrumentation.stderr.txt').read_bytes() == b'original stderr'
    elif failure == 'crash':
        assert 'Process crashed' in (args.output/'instrumentation.txt').read_text()
        assert receipt['error'] == 'Instrumentation failed or did not execute any tests'
    else: assert receipt['error'] == 'ORIGINAL nonzero adb failure'
    assert commands[-1][-3:] == ['rm', '-f', runner.SCREEN]


def test_capture_failure_cannot_mask_instrumentation_failure(tmp_path):
    def failed(*args): raise OSError('diagnostic unavailable')
    status, receipt, _, _, _ = fake_run(tmp_path, failure='nonzero', capture=failed)
    assert status == 1 and receipt['error'] == 'ORIGINAL nonzero adb failure'
    assert receipt['logcat'] == {'status':'ERROR', 'error':'diagnostic unavailable'}


@pytest.mark.parametrize('options,expected', [({'enabled':False},'NOT_REQUESTED'),
    ({'serial':'physical','emulator':'0'},'NOT_CAPTURED'),
    ({'serial':'physical','emulator':'1'},'NOT_CAPTURED'),
    ({'serial':'emulator-5554','emulator':'0'},'NOT_CAPTURED')])
def test_no_default_or_physical_capture_and_no_stale_success_artifact(tmp_path, options, expected):
    status, receipt, args, _, captures = fake_run(tmp_path, **options)
    assert status == 1 and not captures
    assert receipt['logcat']['status'] == expected
    assert not (args.output/'logcat.txt').exists()


def test_passing_run_is_not_collected_or_changed(tmp_path, monkeypatch):
    monkeypatch.setattr(runner, 'scoped_counts', lambda *args: {'tests':2,'passed':2,'skipped':0,'failed':0})
    monkeypatch.setattr(runner, 'validate', lambda path: (128,128))
    status, receipt, args, _, captures = fake_run(tmp_path)
    assert status == 0 and receipt['status'] == 'PASS' and receipt['counts']['passed'] == 2
    assert not captures and receipt['logcat']['status'] == 'NOT_CAPTURED'
    assert 'run passed' in receipt['logcat']['reason']
    assert not (args.output/'logcat.txt').exists()



def test_capture_write_failure_is_reported_and_client_is_closed(tmp_path):
    destination = tmp_path / 'directory-not-file'; destination.mkdir()
    commands, processes = [], []
    result = logcat.capture('emulator-5554', True, destination,
        popen=spawn_python("print('synthetic log')", commands, processes))
    assert result['status'] == 'ERROR' and result['artifactError']
    assert processes[0].poll() == 0 and processes[0].stdout.closed, capture_diagnostic(result)


def test_missing_apk_cannot_capture_unverified_device(tmp_path):
    args = SimpleNamespace(output=tmp_path/'output', app_apk=tmp_path/'missing.apk',
        test_apk=tmp_path/'also-missing.apk', retain_emulator_failure_logcat=True)
    def forbidden(*args): raise AssertionError('no device identity was established')
    assert runner.run(args, forbidden, forbidden) == 1
    receipt = json.loads((args.output/'compatibility.json').read_text())
    assert receipt['error'].startswith('APK missing:')
    assert receipt['logcat']['status'] == 'NOT_CAPTURED'
    assert 'verified emulator serial required' in receipt['logcat']['reason']


def test_stale_log_removal_failure_is_explicit_without_masking_original_error(tmp_path, monkeypatch):
    original_unlink = Path.unlink
    def fail_log(path, *args, **kwargs):
        if path.name == 'logcat.txt': raise PermissionError('synthetic stale artifact error')
        return original_unlink(path, *args, **kwargs)
    monkeypatch.setattr(Path, 'unlink', fail_log)
    status, receipt, args, _, captures = fake_run(tmp_path, failure='nonzero')
    assert status == 1 and receipt['error'] == 'ORIGINAL nonzero adb failure'
    assert not captures and receipt['logcat']['status'] == 'ERROR'
    assert receipt['logcat']['staleArtifactRemovalError'] == 'synthetic stale artifact error'
    assert (args.output/'logcat.txt').read_text().startswith('STALE')


def test_existing_always_uploads_include_diagnostics_and_opt_in_is_emulator_only():
    import yaml
    for filename, job, path in [('verify.yml','device','${{ runner.temp }}/rf-ci/device/runner/'),
                              ('android-compatibility.yml','emulator','reports/device/')]:
        workflow = yaml.safe_load((ROOT/'.github/workflows'/filename).read_text())
        steps = workflow['jobs'][job]['steps']
        emulators = [step for step in steps if step.get('uses','').startswith('reactivecircus/android-emulator-runner@')]
        assert len(emulators) == 1
        assert '--serial emulator-5554 --retain-emulator-failure-logcat' in emulators[0]['with']['script']
        uploads = [step for step in steps if step.get('uses','').startswith('actions/upload-artifact@') and step['with'].get('path') == path]
        assert len(uploads) == 1 and uploads[0]['if'] == 'always()'
        assert steps.index(uploads[0]) > steps.index(emulators[0])


@pytest.mark.parametrize('escaped', [False, True])
def test_owned_orphan_fixture_rejects_residual_and_restores_state(escaped):
    previous = subreaper_state()[1]
    processes = []
    with pytest.raises(RuntimeError, match='unexpected surviving or escaped'):
        with owned_orphan_client(processes) as scope:
            code = ("import os,time\nchild=os.fork()\n"
                    "if child == 0:\n " + ("os.setsid(); " if escaped else "") +
                    "time.sleep(60)\n")
            process = subprocess.Popen([sys.executable, '-c', code], start_new_session=True)
            processes.append(process)
            assert process.wait(timeout=2) == 0
    assert scope.escaped is escaped
    assert not scope.errors and scope.snapshot('test_final') == []
    assert subreaper_state()[1] == previous


def test_owned_orphan_fixture_natural_drain_restores_state():
    previous = subreaper_state()[1]
    processes = []
    with owned_orphan_client(processes) as scope:
        process = subprocess.Popen([sys.executable, '-I', '-S', '-c',
            "import os,time\nchild=os.fork()\nif child == 0: time.sleep(0.05)"],
            start_new_session=True, env=synthetic_child_env())
        processes.append(process)
        assert process.wait(timeout=2) == 0
    assert not scope.escaped and not scope.errors and scope.snapshot('test_final') == []
    assert subreaper_state()[1] == previous
