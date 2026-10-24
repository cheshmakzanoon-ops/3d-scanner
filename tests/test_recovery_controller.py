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

def recovery_transcript(result=None, terminal=0):
    receipt = {} if result is None else {'recoveryReceipt': result}
    return stdio_transcript(m.CLASS, receipt, terminal)


@pytest.mark.parametrize('changes',[{'preparePid':0},{'preparePid':'123'},{'nonce':'wrong'},{'readyStage':'publication'},{'originals':[]}])
def test_stale_or_invalid_readiness_never_authorizes_kill(changes):
    with pytest.raises(ValueError):m.validate_marker(json.dumps(MARKER|changes),NONCE,'import')

@pytest.mark.parametrize('changes',[{'verifyPid':123},{'revision':4},{'nonce':'wrong'},{'status':'FAIL'}])
def test_process_identity_and_revision_are_verified(changes):
    text=recovery_transcript(RESULT|changes)
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
            if 'verify' in args:return recovery_transcript(RESULT)
            if 'cleanup' in args:return recovery_transcript()
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
                events.append('verified');return recovery_transcript(RESULT)
            if 'cleanup' in args:return recovery_transcript()
            raise AssertionError(args)
    fake=Fake()
    def upgrade(adb):
        assert adb is fake and fake.process.dead
        events.append('upgrade')
    assert m.cycle(fake,NONCE,'import',tmp_path,upgrade=upgrade)==RESULT
    assert events==['killed','death-waited','upgrade','verified']


# The stdio controller's doubles model the proposed installed Android fixture,
# not actual UI/device execution. No process/provider/product gate is qualified.
import io
import subprocess

stdio_spec = importlib.util.spec_from_file_location(
    'recovery_stdio', Path(__file__).resolve().parents[1] / 'tools/device/failure_recovery/stdio_controller.py')
stdio = importlib.util.module_from_spec(stdio_spec)
stdio_spec.loader.exec_module(stdio)
STDIO_NONCE = 'c' * 32
STDIO_PROPERTIES = {
    'ro.build.fingerprint': 'vendor/device/release:15/build/userdebug',
    'ro.build.version.sdk': '35', 'ro.product.cpu.abi': 'arm64-v8a',
    'ro.product.manufacturer': 'Synthetic host double', 'ro.product.model': 'fixture-model',
    'ro.kernel.qemu': '0', 'ro.boot.qemu': '0',
}


def stdio_request(scenario='process_kill_relaunch'):
    identity = {'serial': 'fixture-serial', 'properties': STDIO_PROPERTIES, 'api': 35, 'emulator': False}
    request = {
        'schema_version': 1, 'scenario': scenario, 'run_id': 'd' * 32,
        'candidate': {'source_sha': 'a' * 40, 'apk_sha256': 'b' * 64, 'test_apk_sha256': 'c' * 64},
        'device_identity_sha256': stdio.digest(identity), 'adb': '/tmp/explicit adb wrapper',
        'serial': 'fixture-serial', 'required_evidence': sorted(stdio.REQUIRED),
    }
    request['request_sha256'] = stdio.digest(request)
    return request


def stdio_rehash(request):
    request['request_sha256'] = stdio.digest({key: value for key, value in request.items() if key != 'request_sha256'})
    return request


def stdio_transcript(test, results=None, terminal=0):
    """Listener format captured in the real API29 emulator JNI-boundary run.

    This is a single-test synthetic protocol fixture, not device/UI acceptance.
    """
    class_name, method = test.split('#')
    blocks = []
    for code in (1, terminal):
        blocks.append('\n'.join([
            'INSTRUMENTATION_STATUS: class=' + class_name,
            'INSTRUMENTATION_STATUS: current=1',
            'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner',
            'INSTRUMENTATION_STATUS: numtests=1',
            'INSTRUMENTATION_STATUS: stream=' + ('\n' + class_name + ':' if code == 1 else '.'),
            'INSTRUMENTATION_STATUS: test=' + method,
            'INSTRUMENTATION_STATUS_CODE: ' + str(code),
        ]))
    blocks.extend('INSTRUMENTATION_RESULT: ' + key + '=' + json.dumps(value) for key, value in (results or {}).items())
    return '\n'.join(blocks) + '\nINSTRUMENTATION_RESULT: stream=\n\nTime: 0.125\n\nOK (1 test)\n\n\nINSTRUMENTATION_CODE: -1\n'


class StdioFakeRunning:
    def __init__(self, adb):
        self.adb = adb
        self.closed = False
    def poll(self):
        return -9 if self.adb.killed else None
    def finish(self, timeout=20):
        assert self.adb.killed
        return subprocess.CompletedProcess([], 1, 'Target instrumentation ended on external process death', '')
    def close(self):
        self.closed = True  # Only the adb client is closed on failures.


class StdioFakeAdb:
    def __init__(self, request, **changes):
        self.request = request
        self.serial = request['serial']
        self.commands = []
        self.killed = False
        self.ack = None
        self.running = None
        self.verified = False
        self.changes = changes
        self.properties = dict(STDIO_PROPERTIES)
        self.marker = stdio.bindings(request, STDIO_NONCE) | {
            'state': 'READY', 'event_sequence': 1,
            'test_identity': stdio.selector(request['scenario']) if request['scenario'] in stdio.SCENARIOS else '',
            'barrier': stdio.SCENARIOS[request['scenario']][1] if request['scenario'] in stdio.SCENARIOS else '',
            'action': 'external_kill', 'prepare_pid': 123, 'project_id': 'disposable-fixture-uuid', 'revision': 7,
            'original_hashes_before': {'original.jpg': 'e' * 64},
            'previous_result_sha256_before': 'f' * 64, 'dataset_identity_sha256': '1' * 64,
            'synthetic': True, 'expected_artifact_key': '2' * 64,
        }
        self.marker.update(changes.get('marker_changes', {}))
    def result(self, stdout='', returncode=0, stderr=''):
        return subprocess.CompletedProcess([], returncode, stdout, stderr)
    def run(self, *args, **kwargs):
        self.commands.append({'args': list(args)})
        assert args == ('get-state',)
        return self.result('offline' if self.changes.get('offline') else 'device')
    def shell(self, *args, **kwargs):
        self.commands.append({'args': list(args), 'input': kwargs.get('input_text')})
        if args[0] == 'getprop':
            value = self.properties[args[1]]
            if self.verified and args[1] == 'ro.build.fingerprint' and self.changes.get('changed_device_after_verify'):
                value += '-changed'
            return self.result(value)
        if args[:2] == ('pm', 'path'):
            return self.result('package:/data/app/fixture/' + args[2] + '/base.apk')
        if args[0] == 'sha256sum':
            is_test = '.test/' in args[1]
            value = self.request['candidate']['test_apk_sha256' if is_test else 'apk_sha256']
            if self.changes.get('wrong_installed_hash') or (self.verified and self.changes.get('changed_apk_after_verify')):
                value = '0' * 64
            return self.result(value + '  ' + args[1])
        if args[:2] == ('am', 'instrument'):
            phase = args[args.index('rf088ControllerPhase') + 1]
            test = args[args.index('class') + 1]
            assert json.loads(args[args.index('rf088ControllerRequest') + 1]) == self.request
            if phase == 'describe':
                if self.changes.get('probe_missing'):
                    return self.result('java.lang.ClassNotFoundException: ' + stdio.DESCRIBE.split('#')[0], 1)
                descriptor = stdio.bindings(self.request, STDIO_NONCE) | {
                    'supported_scenarios': [self.request['scenario']],
                    'test_identity': stdio.selector(self.request['scenario']),
                    'barrier': stdio.SCENARIOS[self.request['scenario']][1],
                }
                descriptor.update(self.changes.get('descriptor_changes', {}))
                output = stdio_transcript(test, {'rf088Fixture': descriptor}, self.changes.get('describe_terminal', 0))
                return self.result(self.changes.get('describe_output', output))
            assert phase == 'verify' and self.killed
            self.verified = True
            receipt = stdio.bindings(self.request, STDIO_NONCE) | {
                key: self.marker[key] for key in ('test_identity', 'project_id', 'revision', 'prepare_pid',
                    'synthetic', 'dataset_identity_sha256', 'original_hashes_before',
                    'previous_result_sha256_before', 'expected_artifact_key')
            } | {
                'status': 'PASS', 'verify_pid': 456, 'original_hashes_after': {'original.jpg': 'e' * 64},
                'previous_result_sha256_after': 'f' * 64, 'observed_artifact_key': '2' * 64,
                'no_duplicate_publication': True, 'checkpoint_identity_verified': True,
            }
            receipt.update(self.changes.get('receipt_changes', {}))
            output = stdio_transcript(test, {'rf088Receipt': receipt}, self.changes.get('verify_terminal', 0))
            return self.result(self.changes.get('verify_output', output))
        if args[:3] == ('run-as', stdio.PACKAGE, 'test'):
            return self.result(returncode=0 if self.changes.get('existing_run') else 1)
        if args[:3] == ('run-as', stdio.PACKAGE, 'cat'):
            marker = dict(self.marker)
            if self.ack is not None:
                marker |= {'state': 'ACKNOWLEDGED', 'event_sequence': 2, 'ack_sha256': self.ack['ack_sha256']}
                marker.update(self.changes.get('ack_changes', {}))
            return self.result(json.dumps(marker))
        if args[:3] == ('run-as', stdio.PACKAGE, 'sh'):
            self.ack = json.loads(kwargs['input_text'])
            assert stdio.digest({key: value for key, value in self.ack.items() if key != 'ack_sha256'}) == self.ack['ack_sha256']
            return self.result()
        if args[0] == 'pidof':
            if self.killed:
                return self.result('123' if self.changes.get('survives_kill') else '', returncode=0 if self.changes.get('survives_kill') else 1)
            if self.changes.get('stale_pid') or (self.ack is not None and self.changes.get('stale_pid_after_ack')):
                return self.result('999')
            return self.result('123')
        if args[:3] == ('run-as', stdio.PACKAGE, 'kill'):
            assert args == ('run-as', stdio.PACKAGE, 'kill', '-9', '123')
            assert self.ack is not None
            self.killed = True
            return self.result()
        if args[0] == 'find':
            return self.result(self.changes.get('screenshots', '/data/local/tmp/rf-ui-' + self.request['run_id'] + '-recovered.png'))
        raise AssertionError(args)
    def start(self, args):
        self.commands.append({'start_args': list(args)})
        assert args[args.index('rf088ControllerPhase') + 1] == 'prepare'
        self.running = StdioFakeRunning(self)
        return self.running


def execute_stdio(request=None, **changes):
    request = request or stdio_request()
    adb = StdioFakeAdb(request, **changes)
    def factory(executable, serial):
        assert executable == '/tmp/explicit adb wrapper' and serial == 'fixture-serial'
        return adb
    return stdio.run_request(request, adb_factory=factory, nonce_factory=lambda: STDIO_NONCE), adb


@pytest.mark.parametrize('scenario', list(stdio.SCENARIOS))
def test_stdio_synthetic_fixture_double_requires_bound_ready_ack_death_new_ui_and_preservation(scenario):
    result, adb = execute_stdio(stdio_request(scenario))
    assert result['status'] == 'PASS' and adb.killed and adb.running.closed
    assert result['test_identity'] == stdio.selector(scenario)
    assert result['test_counts'] == {'started_count': 1, 'completed_count': 1, 'passed_count': 1, 'skipped_count': 0}
    assert result['device']['properties']['ro.product.cpu.abi'] == 'arm64-v8a'
    assert result['release_qualification'] == 'NOT_RUN'
    events = [item['event'] for item in result['events']]
    assert events.index('fresh_ack_and_live_target_reverified') < events.index('target_process_death_observed')
    assert all(type(item['host_unix_ms']) is int for item in result['events'])


@pytest.mark.parametrize('change', [
    {'schema_version': True}, {'scenario': 'baseline-import'}, {'run_id': '../unsafe'},
    {'candidate': {}}, {'device_identity_sha256': 'bad'}, {'serial': 'x;kill'},
    {'adb': 'adb\nmalformed'}, {'required_evidence': []}, {'request_sha256': '0' * 64},
    {'extra_field': True},
])
def test_stdio_invalid_or_unbound_request_never_accesses_device(change):
    request = stdio_request() | change
    def forbidden(*args):
        pytest.fail('Invalid request must not instantiate adb')
    result = stdio.run_request(request, adb_factory=forbidden)
    assert result['status'] == 'FAIL'


def test_stdio_offline_is_explicit_blocked_external_without_process_action():
    result, adb = execute_stdio(offline=True)
    assert result['status'] == 'BLOCKED_EXTERNAL' and not adb.killed and adb.running is None


@pytest.mark.parametrize('kind', ['device', 'installed'])
def test_stdio_candidate_or_device_mismatch_fails_before_fixture_probe(kind):
    request = stdio_request()
    if kind == 'device':
        request['device_identity_sha256'] = '0' * 64
        stdio_rehash(request)
    result, adb = execute_stdio(request, wrong_installed_hash=kind == 'installed')
    assert result['status'] == 'FAIL' and not adb.killed
    assert not any(item.get('args', [])[:2] == ['am', 'instrument'] for item in adb.commands)


def test_stdio_absent_source_owned_ui_fixture_is_honest_not_run_not_baseline_substitution():
    result, adb = execute_stdio(probe_missing=True)
    assert result['status'] == 'NOT_RUN' and 'not installed' in result['reason']
    assert result['candidate'] == stdio_request()['candidate']
    assert adb.running is None and not adb.killed
    assert 'RecoveryStageContractTest' not in json.dumps(adb.commands)


@pytest.mark.parametrize('scenario', list(stdio.OTHER_SCENARIOS))
def test_stdio_unagreed_permission_provider_and_lifecycle_actions_remain_not_run(scenario):
    result, adb = execute_stdio(stdio_request(scenario))
    assert result['status'] == 'NOT_RUN' and result['reason']
    assert not adb.killed and adb.running is None


@pytest.mark.parametrize('change', [
    {'controller_nonce': '0' * 32}, {'candidate': {'source_sha': '0' * 40}},
    {'device_identity_sha256': '0' * 64}, {'request_sha256': '0' * 64},
    {'test_identity': 'other.Test#method'}, {'barrier': 'import'},
])
def test_stdio_stale_descriptor_never_starts_controlled_instrumentation(change):
    result, adb = execute_stdio(descriptor_changes=change)
    assert result['status'] == 'FAIL' and adb.running is None and not adb.killed


@pytest.mark.parametrize('change', [
    {'controller_nonce': '0' * 32}, {'run_id': '0' * 32}, {'barrier': 'import'},
    {'request_sha256': '0' * 64}, {'target_package': 'other.package'},
    {'prepare_pid': 1}, {'prepare_pid': '123'}, {'revision': True},
    {'original_hashes_before': {}}, {'synthetic': False}, {'event_sequence': 2},
])
def test_stdio_stale_or_invalid_ready_marker_never_authorizes_ack_or_kill(change):
    result, adb = execute_stdio(marker_changes=change)
    assert result['status'] == 'FAIL' and not adb.killed and adb.ack is None
    assert adb.running.closed


@pytest.mark.parametrize('changes', [
    {'stale_pid': True}, {'stale_pid_after_ack': True}, {'existing_run': True},
    {'ack_changes': {'ack_sha256': '0' * 64}}, {'ack_changes': {'prepare_pid': 999}},
    {'ack_changes': {'original_hashes_before': {'different': 'f' * 64}}},
])
def test_stdio_stale_pid_or_ack_refuses_device_kill(changes):
    result, adb = execute_stdio(**changes)
    assert result['status'] == 'FAIL' and not adb.killed


def test_stdio_external_kill_must_actually_remove_old_process_before_verify():
    result, adb = execute_stdio(survives_kill=True)
    assert result['status'] == 'FAIL' and adb.killed and not adb.verified


@pytest.mark.parametrize('changes', [
    {'verify_pid': 123}, {'verify_pid': True}, {'revision': 8}, {'project_id': 'other-project'},
    {'original_hashes_after': {'original.jpg': '0' * 64}}, {'previous_result_sha256_after': '0' * 64},
    {'dataset_identity_sha256': '0' * 64}, {'observed_artifact_key': 'other-key'},
    {'expected_artifact_key': 'other-key'}, {'no_duplicate_publication': False},
    {'checkpoint_identity_verified': False}, {'controller_nonce': '0' * 32},
])
def test_stdio_recovery_receipt_requires_originals_prior_result_artifacts_and_process_identity(changes):
    result, adb = execute_stdio(receipt_changes=changes)
    assert result['status'] == 'FAIL' and adb.killed


@pytest.mark.parametrize('terminal', [-1, -2, -3, -4])
def test_stdio_skipped_failed_ignored_or_assumed_ui_never_passes(terminal):
    result, _ = execute_stdio(verify_terminal=terminal)
    assert result['status'] == 'FAIL'


@pytest.mark.parametrize('output', [
    'OK (1 test)\nINSTRUMENTATION_CODE: -1\n',
    stdio_transcript('other.Test#method'),
    stdio_transcript(stdio.selector('process_kill_relaunch')).replace('numtests=1', 'numtests=2'),
    stdio_transcript(stdio.selector('process_kill_relaunch')).replace('current=1', 'current=2'),
    stdio_transcript(stdio.selector('process_kill_relaunch')).replace('INSTRUMENTATION_CODE: -1', ''),
    stdio_transcript(stdio.selector('process_kill_relaunch')) + 'INSTRUMENTATION_CODE: -1\n',
    stdio_transcript(stdio.selector('process_kill_relaunch')).replace('OK (1 test)', 'OK (0 tests)'),
    stdio_transcript(stdio.selector('process_kill_relaunch')).replace('INSTRUMENTATION_STATUS: numtests=1', 'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: numtests=1'),
])
def test_stdio_exact_single_actual_ui_identity_and_complete_raw_counts_required(output):
    with pytest.raises(stdio.EvidenceError):
        stdio.parse_instrumentation(output, 0, stdio.selector('process_kill_relaunch'))


@pytest.mark.parametrize('changes', [
    {'screenshots': ''}, {'screenshots': '/data/local/tmp/rf-ui-' + '0' * 32 + '-stale.png'},
    {'screenshots': '/sdcard/rf-ui-' + 'd' * 32 + '-bad.png'},
    {'changed_device_after_verify': True}, {'changed_apk_after_verify': True},
])
def test_stdio_screenshot_or_final_candidate_device_mismatch_prevents_pass(changes):
    result, _ = execute_stdio(**changes)
    assert result['status'] == 'FAIL'


def test_stdio_fixture_cannot_override_host_validated_transcript_counts_or_qualification():
    result, _ = execute_stdio(receipt_changes={
        'instrumentation_stdout': 'fake text', 'instrumentation_returncode': 99,
        'test_counts': {'passed_count': 999}, 'release_qualification': 'PASS', 'events': [],
    })
    assert result['status'] == 'PASS'  # Synthetic protocol double only.
    assert result['instrumentation_returncode'] == 0
    assert result['instrumentation_stdout'] != 'fake text'
    assert result['test_counts']['passed_count'] == 1 and result['events']
    assert result['release_qualification'] == 'NOT_RUN'


@pytest.mark.parametrize('text', ['[]', '{} {}', '{"x":1,"x":2}', '{"x":NaN}', 'x' * (stdio.MAX_INPUT + 1)],
                         ids=['array', 'multiple-objects', 'duplicate-key', 'nan', 'over-input-budget'])
def test_stdio_main_emits_one_json_failure_and_diagnostics_without_protocol_noise(text):
    output, diagnostics = io.StringIO(), io.StringIO()
    assert stdio.main(io.StringIO(text), output, diagnostics) == 0
    response = json.loads(output.getvalue())
    assert response['status'] == 'FAIL'
    assert len(output.getvalue().splitlines()) == 1 and diagnostics.getvalue().startswith('FAIL:')


def test_stdio_adb_wrapper_and_explicit_serial_are_used_without_shell_execution(monkeypatch):
    calls = []
    def fake_run(argv, **kwargs):
        calls.append((argv, kwargs))
        return subprocess.CompletedProcess(argv, 0, 'device', '')
    monkeypatch.setattr(stdio.subprocess, 'run', fake_run)
    adb = stdio.Adb('/tmp/wrapper with spaces', 'tcp-device:5555')
    adb.run('get-state')
    adb.shell('getprop', 'ro.build.fingerprint')
    assert all(argv[:3] == ['/tmp/wrapper with spaces', '-s', 'tcp-device:5555'] for argv, _ in calls)
    assert all('shell' not in kwargs for _, kwargs in calls)
    assert calls[1][0][3:] == ['shell', '-T', 'getprop ro.build.fingerprint']


@pytest.mark.parametrize('error', [OSError('cleanup failed'), subprocess.TimeoutExpired('adb', 5)])
def test_stdio_cleanup_failure_preserves_machine_readable_fail_and_never_pass(monkeypatch, error):
    def failed_close(self):
        raise error
    monkeypatch.setattr(StdioFakeRunning, 'close', failed_close)
    result, adb = execute_stdio()
    assert adb.killed and result['status'] == 'FAIL'
    assert 'cleanup failed' in result['reason']
    assert json.loads(json.dumps(result))['status'] == 'FAIL'


def test_stdio_installed_descriptor_failure_with_classnotfound_text_is_fail_not_unavailable():
    output = stdio_transcript(stdio.DESCRIBE, terminal=-2)
    output = output.replace('OK (1 test)', 'java.lang.ClassNotFoundException: ' + stdio.DESCRIBE.split('#')[0] + '\nFAILURES!!!')
    result, adb = execute_stdio(describe_output=output)
    assert result['status'] == 'FAIL' and not adb.killed and adb.running is None


def test_stdio_missing_class_detection_rejects_unrelated_or_prefix_matching_class():
    assert not stdio.fixture_is_missing('java.lang.ClassNotFoundException: other.Test')
    assert not stdio.fixture_is_missing('java.lang.ClassNotFoundException: ' + stdio.DESCRIBE.split('#')[0] + 'Other')
    assert stdio.fixture_is_missing('Caused by: java.lang.ClassNotFoundException: Didn\'t find class "' + stdio.DESCRIBE.split('#')[0] + '" on path')


@pytest.mark.parametrize('change', [
    lambda text: text.replace('INSTRUMENTATION_CODE: -1', ''),
    lambda text: text.replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0'),
    lambda text: text.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -4'),
    lambda text: text.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -3'),
    lambda text: text.replace('current=1', 'current=2'),
    lambda text: text.replace('test=externalProcessBoundary', 'test=unrelatedTest'),
    lambda text: text + 'INSTRUMENTATION_CODE: -1\n',
])
def test_recovery_rejects_skips_wrong_identity_and_incomplete_completion(change):
    with pytest.raises(ValueError): m.validate_result(change(recovery_transcript(RESULT)), MARKER, NONCE, 'import')


def test_recovery_requires_exact_one_actual_pass():
    assert m.validate_transcript(recovery_transcript()) == {'tests':1, 'passed':1, 'skipped':0, 'failed':0}


@pytest.mark.parametrize('line_endings', ['\n', '\r\n'])
def test_complete_real_listener_format_preserves_bound_rf088_metadata(line_endings):
    selected = stdio.selector('process_kill_relaunch')
    metadata = {'rf088Fixture': {'protocol': stdio.PROTOCOL, 'synthetic': True},
                'rf088Verified': {'run_id': 'd' * 32, 'status': 'PASS'}}
    transcript = stdio_transcript(selected, metadata).replace('\n', line_endings)
    parsed = stdio.parse_instrumentation(transcript, 0, selected)
    assert parsed['results'] == metadata
    assert parsed['passed_count'] == 1 and parsed['skipped_count'] == 0
    # Extra JSON result bundles can follow the JUnit stream as well as precede it.
    moved = stdio_transcript(selected).replace(
        'INSTRUMENTATION_CODE: -1',
        'INSTRUMENTATION_RESULT: rf088Verified=' + json.dumps(metadata['rf088Verified'])
        + '\nINSTRUMENTATION_CODE: -1')
    assert stdio.parse_instrumentation(moved, 0, selected)['results'] == {
        'rf088Verified': metadata['rf088Verified']}


PROTOCOL_CORRUPTIONS = [
    ('missing-runner-id', lambda s: s.replace('INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n', '')),
    ('wrong-runner-id', lambda s: s.replace('id=AndroidJUnitRunner', 'id=OtherRunner')),
    ('missing-start-stream', lambda s: s.replace('INSTRUMENTATION_STATUS: stream=\n', '', 1)),
    ('missing-success-stream', lambda s: s.replace('INSTRUMENTATION_STATUS: stream=.\n', '')),
    ('empty-success-stream', lambda s: s.replace('INSTRUMENTATION_STATUS: stream=.', 'INSTRUMENTATION_STATUS: stream=')),
    ('unknown-status-prefix', lambda s: s.replace('INSTRUMENTATION_RESULT: stream=', 'INSTRUMENTATION_STATUS_UNKNOWN: fail\nINSTRUMENTATION_RESULT: stream=')),
    ('unknown-status-field', lambda s: s.replace('INSTRUMENTATION_STATUS: id=', 'INSTRUMENTATION_STATUS: error=failed\nINSTRUMENTATION_STATUS: id=', 1)),
    ('error-result-shortmsg', lambda s: s.replace('INSTRUMENTATION_RESULT: stream=', 'INSTRUMENTATION_RESULT: shortMsg=Some error\nINSTRUMENTATION_RESULT: stream=')),
    ('unknown-result-field', lambda s: s.replace('INSTRUMENTATION_RESULT: stream=', 'INSTRUMENTATION_RESULT: unknown=value\nINSTRUMENTATION_RESULT: stream=')),
    ('bare-ok-without-result-stream', lambda s: s.replace('INSTRUMENTATION_RESULT: stream=\n\nTime: 0.125\n\n', '')),
    ('summary-without-time', lambda s: s.replace('Time: 0.125\n\n', '')),
    ('invalid-time', lambda s: s.replace('Time: 0.125', 'Time: NaN')),
    ('failure-text-in-summary', lambda s: s.replace('Time: 0.125', 'java.lang.AssertionError: broken\nTime: 0.125')),
    ('duplicate-summary', lambda s: s.replace('OK (1 test)', 'OK (1 test)\nOK (1 test)')),
    ('duplicate-result-stream', lambda s: s.replace('INSTRUMENTATION_RESULT: stream=', 'INSTRUMENTATION_RESULT: stream=\nINSTRUMENTATION_RESULT: stream=')),
    ('partial-status-before-results', lambda s: s.replace('INSTRUMENTATION_RESULT: stream=', 'INSTRUMENTATION_STATUS: class=partial\nINSTRUMENTATION_RESULT: stream=')),
    ('orphan-text-before-start', lambda s: 'Some error\n' + s),
    ('trailing-orphan-text', lambda s: s + 'Some error\n'),
    ('trailing-unknown-status', lambda s: s + 'INSTRUMENTATION_STATUS_UNKNOWN: fail\n'),
    ('trailing-duplicate-completion', lambda s: s + 'INSTRUMENTATION_CODE: -1\n'),
    ('trailing-test-event', lambda s: s + 'INSTRUMENTATION_STATUS_CODE: 0\n'),
    ('incomplete-completion', lambda s: s.replace('INSTRUMENTATION_CODE: -1', '')),
    ('wrong-completion', lambda s: s.replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0')),
    ('duplicate-terminal-event', lambda s: s.replace('INSTRUMENTATION_RESULT: stream=', 'INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=')),
    ('assumption-terminal', lambda s: s.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -4')),
    ('ignored-terminal', lambda s: s.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -3')),
    ('failed-terminal', lambda s: s.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -2')),
]


@pytest.mark.parametrize('parser', ['stdio', 'dedicated'])
@pytest.mark.parametrize('name,corrupt', PROTOCOL_CORRUPTIONS, ids=[item[0] for item in PROTOCOL_CORRUPTIONS])
def test_both_recovery_entrypoints_reject_incomplete_or_ambiguous_runner_protocol(parser, name, corrupt):
    selected = stdio.selector('process_kill_relaunch') if parser == 'stdio' else m.CLASS
    output = corrupt(stdio_transcript(selected))
    if parser == 'stdio':
        with pytest.raises(stdio.EvidenceError): stdio.parse_instrumentation(output, 0, selected)
    else:
        with pytest.raises(ValueError): m.validate_transcript(output)


def test_reported_missing_event_metadata_and_unknown_error_counterexample_fails():
    selected = stdio.PACKAGE + '.qa.ReleaseNativeContractTest#installedCandidateIsMinifiedReleaseAndNativeExportsReopen'
    class_name, method = selected.split('#')
    events = ''.join(
        f'INSTRUMENTATION_STATUS: class={class_name}\nINSTRUMENTATION_STATUS: current=1\n'
        f'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: test={method}\n'
        f'INSTRUMENTATION_STATUS_CODE: {code}\n' for code in (1, 0))
    output = events + ('INSTRUMENTATION_STATUS_UNKNOWN: fail\nINSTRUMENTATION_RESULT: shortMsg=Some error\n'
                       'OK (1 test)\nINSTRUMENTATION_CODE: -1\n')
    with pytest.raises(stdio.EvidenceError): stdio.parse_instrumentation(output, 0, selected)


@pytest.mark.parametrize('result_line', [
    'INSTRUMENTATION_RESULT: rf088Fixture={"x":1,"x":2}',
    'INSTRUMENTATION_RESULT: rf088Fixture=[]',
    'INSTRUMENTATION_RESULT: rf088Fixture={"x":NaN}',
    'INSTRUMENTATION_RESULT: rf088Fixture={}\nINSTRUMENTATION_RESULT: rf088Fixture={}',
])
def test_rf088_result_extras_must_be_one_unambiguous_finite_json_object(result_line):
    selected = stdio.selector('process_kill_relaunch')
    output = stdio_transcript(selected).replace('INSTRUMENTATION_RESULT: stream=', result_line + '\nINSTRUMENTATION_RESULT: stream=')
    with pytest.raises(stdio.EvidenceError): stdio.parse_instrumentation(output, 0, selected)


def test_dedicated_receipt_uses_shared_duplicate_key_rejection():
    output = recovery_transcript(RESULT).replace('"status": "PASS"', '"status": "PASS", "status": "FAIL"')
    with pytest.raises(ValueError): m.validate_result(output, MARKER, NONCE, 'import')
