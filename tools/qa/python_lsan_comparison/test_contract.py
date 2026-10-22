"""SYNTHETIC orchestration/classifier tests; NOT evidence of LSan behavior."""
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import run as diagnostic

LEAK = '''==123==ERROR: LeakSanitizer: detected memory leaks
Direct leak of 1237 byte(s) in 1 object(s) allocated from:
    #0 malloc asan_malloc_linux.cpp:69
    #1 planted_leak /tmp/control.c:4
SUMMARY: AddressSanitizer: 1237 byte(s) leaked in 1 allocation(s).
'''


class SyntheticClassifier(unittest.TestCase):
    def classify(self, text='', code=1, **extra):
        return diagnostic.classify(dict(exit_code=code, **extra), text)

    def test_planted_requires_stack_and_exact_size(self):
        self.assertTrue(self.classify(LEAK)['planted_detected'])
        for text in (LEAK.replace('planted_leak', 'other'), LEAK.replace('1237', '1238'), ''):
            self.assertFalse(self.classify(text)['planted_detected'])

    def test_nonzero_not_detection(self):
        self.assertEqual(self.classify('failed')['state'], 'invalid')

    def test_fatal_timeout_truncation_override_report(self):
        for extra in ({'timeout': True}, {'truncated': True}, {'launch_error': 'failed'}, {'cleanup_errors': ['failed']}, {'cancelled_signal': 15}):
            self.assertEqual(self.classify(LEAK, **extra)['state'], 'invalid')
        for fatal in ('LeakSanitizer does not work under ptrace', 'AddressSanitizer:DEADLYSIGNAL', 'Traceback (most recent call last)'):
            self.assertEqual(self.classify(LEAK + fatal)['state'], 'invalid')

    def test_clean_requires_zero_exit(self):
        self.assertEqual(self.classify('', 0)['state'], 'clean')
        self.assertEqual(self.classify(LEAK, 0)['state'], 'invalid')

    def test_stack_in_other_allocation_not_enough(self):
        text = LEAK.replace('planted_leak', 'other').replace('SUMMARY:', 'Direct leak of 12 byte(s) in 1 object(s) allocated from:\n #0 planted_leak\nSUMMARY:')
        self.assertFalse(self.classify(text)['planted_detected'])

    def test_environment_rejects_hidden_overrides(self):
        for name in ('LD_PRELOAD', 'LD_AUDIT', 'ASAN_OPTIONS', 'LSAN_OPTIONS', 'UBSAN_OPTIONS'):
            with self.assertRaises(RuntimeError):
                diagnostic.clean_env('/tmp/home', {name: ''})
        env = diagnostic.clean_env('/tmp/home', {'PYTHONPATH': 'evil', 'PYTHONHOME': 'evil', 'CFLAGS': 'evil', 'PATH': 'evil'})
        self.assertNotIn('PYTHONPATH', env)
        self.assertNotIn('PYTHONHOME', env)
        self.assertNotIn('CFLAGS', env)
        self.assertEqual(env['PYTHONNOUSERSITE'], '1')
        self.assertEqual(env['PATH'], '/usr/bin:/bin')


class FakeProcessOrchestration(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = diagnostic.clean_env(self.root, {})

    def run_code(self, code, **kwargs):
        return diagnostic.run([sys.executable, '-I', '-c', code], self.root, self.env, self.root, 'fake', **kwargs)

    def test_fake_success_and_exit_evidence(self):
        result = self.run_code('print("SYNTHETIC only"); raise SystemExit(7)')
        self.assertEqual(result['exit_code'], 7)
        self.assertIn('SYNTHETIC', (self.root / 'fake.log').read_text())
        self.assertEqual(json.loads((self.root / 'fake.json').read_text())['exit_code'], 7)

    def test_fake_timeout(self):
        result = self.run_code('import time; time.sleep(20)', seconds=0.1)
        self.assertTrue(result['timeout'])
        self.assertLess(result['elapsed_seconds'], 3)

    def test_fake_output_cap(self):
        result = self.run_code('print("X" * 100000)', max_log=100)
        self.assertTrue(result['truncated'])
        self.assertEqual((self.root / 'fake.log').stat().st_size, 100)

    def test_fake_launch_failure(self):
        result = diagnostic.run(['/nonexistent/synthetic-program'], self.root, self.env, self.root, 'missing')
        self.assertIn('launch_error', result)
        self.assertTrue((self.root / 'missing.json').is_file())

    def test_fake_descendant_killed_after_parent_exit(self):
        marker = self.root / 'descendant-survived'
        code = ('import subprocess,sys; subprocess.Popen([sys.executable,"-c",'
                + repr('import time,pathlib; time.sleep(0.4); pathlib.Path(' + repr(str(marker)) + ').touch()')
                + '],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)')
        self.run_code(code)
        time.sleep(0.6)
        self.assertFalse(marker.exists())

    def test_fake_signal_preserves_command_evidence(self):
        cancel = diagnostic.Cancellation()
        old = signal.signal(signal.SIGALRM, cancel.record)
        try:
            signal.setitimer(signal.ITIMER_REAL, 0.1)
            result = self.run_code('import time; time.sleep(20)', cancel=cancel)
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            signal.signal(signal.SIGALRM, old)
        self.assertEqual(result['cancelled_signal'], signal.SIGALRM)
        result = json.loads((self.root / 'fake.json').read_text())
        self.assertEqual(result['exit_code'], -signal.SIGKILL)

    def test_fake_sigterm_between_spawn_and_ownership_assignment(self):
        cancel = diagnostic.Cancellation()
        old = signal.signal(signal.SIGTERM, cancel.record)
        real_popen = diagnostic.subprocess.Popen
        processes = []
        marker = self.root / 'spawn-window-orphan'
        def spawn_then_signal(*args, **kwargs):
            process = real_popen(*args, **kwargs)
            processes.append(process)
            # Deterministic injection after spawn, BEFORE run() receives/owns Popen.
            os.kill(os.getpid(), signal.SIGTERM)
            return process
        try:
            with patch.object(diagnostic.subprocess, 'Popen', spawn_then_signal):
                result = self.run_code('import time,pathlib; time.sleep(0.4); pathlib.Path(' + repr(str(marker)) + ').touch()', cancel=cancel)
        finally:
            signal.signal(signal.SIGTERM, old)
        self.assertEqual(result['cancelled_signal'], signal.SIGTERM)
        self.assertEqual(result['exit_code'], -signal.SIGKILL)
        self.assertEqual(processes[0].returncode, -signal.SIGKILL)
        time.sleep(0.5)
        self.assertFalse(marker.exists())
        self.assertEqual(json.loads((self.root / 'fake.json').read_text())['cancelled_signal'], signal.SIGTERM)

    def test_fake_group_signaled_before_first_reap(self):
        order = []
        real_popen = diagnostic.subprocess.Popen
        real_kill = diagnostic.kill_group
        real_waitid = diagnostic.os.waitid
        def spawn(*args, **kwargs):
            process = real_popen(*args, **kwargs)
            real_wait = process.wait
            def wait(*args, **kwargs):
                order.append('reap')
                self.assertIn('kill', order)
                return real_wait(*args, **kwargs)
            process.wait = wait
            return process
        def observe(pidtype, pid, flags):
            self.assertTrue(flags & os.WNOWAIT)
            order.append('observe')
            return real_waitid(pidtype, pid, flags)
        def kill(process):
            self.assertIsNone(process.returncode)
            self.assertNotIn('reap', order)
            order.append('kill')
            real_kill(process)
        with patch.object(diagnostic.subprocess, 'Popen', spawn), patch.object(diagnostic.os, 'waitid', observe), patch.object(diagnostic, 'kill_group', kill):
            result = self.run_code('print("SYNTHETIC ordinary exit")')
        self.assertEqual(result['exit_code'], 0)
        self.assertIn('observe', order)
        self.assertEqual(order[-2:], ['kill', 'reap'])

    def test_fake_cleanup_error_still_preserves_metadata(self):
        real_kill = diagnostic.kill_group
        def kill_then_error(process):
            real_kill(process)  # Keep test safe: actual child is killed first.
            raise OSError('SYNTHETIC cleanup error')
        with patch.object(diagnostic, 'kill_group', kill_then_error):
            result = self.run_code('print("synthetic evidence")')
        stored = json.loads((self.root / 'fake.json').read_text())
        self.assertEqual(stored['cleanup_errors'], result['cleanup_errors'])
        self.assertIn('SYNTHETIC cleanup error', stored['cleanup_errors'][0])
        self.assertEqual(diagnostic.classify(stored, '')['state'], 'invalid')
        self.assertIn('synthetic evidence', (self.root / 'fake.log').read_text())

    def test_fake_wait_and_close_errors_preserve_metadata(self):
        real_popen = diagnostic.subprocess.Popen
        for stage in ('wait', 'close'):
            with self.subTest(stage=stage):
                def spawn(*args, **kwargs):
                    process = real_popen(*args, **kwargs)
                    target = process if stage == 'wait' else process.stdout
                    real_method = getattr(target, stage)
                    def fail_after_cleanup(*args, **kwargs):
                        real_method(*args, **kwargs)
                        raise OSError('SYNTHETIC ' + stage + ' error')
                    setattr(target, stage, fail_after_cleanup)
                    return process
                with patch.object(diagnostic.subprocess, 'Popen', spawn):
                    result = self.run_code('pass')
                stored = json.loads((self.root / 'fake.json').read_text())
                self.assertEqual(stored['cleanup_errors'], result['cleanup_errors'])
                self.assertIn('SYNTHETIC ' + stage + ' error', stored['cleanup_errors'][0])
                self.assertEqual(diagnostic.classify(stored, '')['state'], 'invalid')

    def test_cancelled_before_spawn_never_launches(self):
        cancel = diagnostic.Cancellation()
        cancel.record(signal.SIGTERM, None)
        with patch.object(diagnostic.subprocess, 'Popen') as popen:
            result = self.run_code('pass', cancel=cancel)
        popen.assert_not_called()
        self.assertEqual(result['cancelled_signal'], signal.SIGTERM)
        self.assertTrue((self.root / 'fake.json').is_file())

    def test_fake_failed_command_keeps_log(self):
        experiment = diagnostic.Experiment(self.root / 'experiment')
        experiment.env = self.env
        with self.assertRaisesRegex(RuntimeError, 'Failed bounded command'):
            experiment.command('fake-build', [sys.executable, '-c', 'print("synthetic build failure"); exit(2)'])
        self.assertIn('synthetic build failure', (experiment.evidence / 'fake-build.log').read_text())

    def test_global_budget_refuses_new_process(self):
        experiment = diagnostic.Experiment(self.root / 'experiment')
        experiment.deadline = time.monotonic() - 1
        with patch.object(diagnostic, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'budget exhausted'):
                experiment.command('never', ['/no'])
            run.assert_not_called()

class FakeCompleteExperiment(unittest.TestCase):
    """Entire orchestration uses fake commands/reports; no network/build/LSan runs."""
    def test_fake_late_cancellation_cannot_write_completion(self):
        class FakeExperiment(diagnostic.Experiment):
            def execute(self):
                pass
            def save(self):
                self.cancel.record(signal.SIGTERM, None)
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'out'
            previous = signal.getsignal(signal.SIGTERM)
            with patch.object(diagnostic, 'Experiment', FakeExperiment), patch.object(sys, 'argv', ['run.py', '--output', str(output)]):
                self.assertEqual(diagnostic.main(), 1)
            self.assertFalse((output / 'evidence/COMPLETE.txt').exists())
            self.assertIn('Cancelled', (output / 'evidence/FAILURE.txt').read_text())
            self.assertEqual(signal.getsignal(signal.SIGTERM), previous)

    def test_same_source_flags_preloads_and_six_python_cells(self):
        with tempfile.TemporaryDirectory() as temp:
            exp = diagnostic.Experiment(Path(temp) / 'out')
            calls, cells = [], []
            def command(name, args, seconds=30, cwd=None, env=None, require=True):
                calls.append((name, list(map(str, args)), env))
                log = ''
                if name in ('asan-path', 'cxx-path'):
                    log = '/usr/bin/true\n'
                if name == 'gcc-major':
                    log = '13\n'
                if name == 'extract-source':
                    (exp.root / 'Python-3.12.15').mkdir()
                if name == 'download-wheel-metadata':
                    (exp.root / 'pypi.json').write_text(json.dumps({'urls': [{'digests': {'sha256': diagnostic.WHEEL_HASH}, 'url': 'https://files.pythonhosted.org/fake.whl', 'filename': 'numpy-2.2.6-cp312-cp312-manylinux_2_17_x86_64.whl'}]}))
                if name.endswith('-make'):
                    (cwd / 'pyconfig.h').write_text('SYNTHETIC pyconfig')
                (exp.evidence / (name + '.log')).write_text(log)
                return {'exit_code': 0, 'timeout': False, 'truncated': False}
            def cell(name, args, env):
                cells.append((name, list(map(str, args)), dict(env)))
                row = dict(exit_code=0, state='clean', planted_detected='planted' in name,
                           bytes=None, allocations=None, timeout=False, truncated=False)
                exp.rows[name] = row
                if name.endswith('-metadata'):
                    meta = dict(version_info=[3, 12, 15, 'final', 0], py_debug=int(name.startswith('debug')), numpy_version='2.2.6', numpy_file=str(exp.root / 'numpy-wheel/numpy/__init__.py'))
                    (exp.evidence / (name + '.log')).write_text('METADATA=' + json.dumps(meta) + '\n')
                return row
            def digest(path):
                return diagnostic.SOURCE_HASH if str(path).endswith('.xz') else diagnostic.WHEEL_HASH if str(path).endswith('.whl') else '0' * 64
            with patch.object(exp, 'command', command), patch.object(exp, 'cell', cell), patch.object(diagnostic, 'digest', digest), patch.dict(os.environ, {}, clear=True):
                exp.execute()
            configs = [c for c in calls if c[0].endswith('-configure')]
            self.assertEqual(configs[0][1], configs[1][1][:-1])
            self.assertEqual(configs[1][1][-1], '--with-pydebug')
            self.assertEqual(configs[0][2], configs[1][2])
            primary = [c for c in cells if c[0].split('-', 1)[0] in ('release', 'debug')]
            self.assertEqual(len(primary), 8)  # Six workloads plus two metadata probes.
            self.assertTrue(all(c[2] == primary[0][2] for c in primary))
            empty = [c for c in primary if c[0].endswith('-empty')]
            self.assertTrue(all(c[1][-1] == 'pass' for c in empty))
            numpy = [c for c in primary if c[0].endswith('-numpy')]
            self.assertTrue(all(c[1][-1] == 'import numpy' for c in numpy))
            planted = [c for c in primary if c[0].endswith('-planted')]
            expected_planted = 'import ctypes,sys; helper=ctypes.CDLL(sys.argv[1]); helper.planted_leak.argtypes=[]; helper.planted_leak.restype=None; helper.planted_leak()'
            self.assertEqual(len(planted), 2)
            self.assertTrue(all(c[1][-2] == expected_planted for c in planted))
            self.assertTrue(all(c[1][-1] == str(exp.root / 'control.so') for c in planted))
            self.assertTrue((exp.evidence / 'release-pyconfig.h').is_file())
            self.assertTrue((exp.evidence / 'debug-pyconfig.h').is_file())


class ScopedWorkflowContract(unittest.TestCase):
    def test_only_manual_or_own_files_on_main_trigger_experiment(self):
        workflow = (Path(__file__).resolve().parents[3] / '.github/workflows/python-lsan-comparison.yml').read_text()
        self.assertIn('name: python-lsan-comparison-${{ github.sha }}-${{ github.run_attempt }}', workflow)
        events = workflow.split('\non:\n', 1)[1].split('\npermissions:', 1)[0]
        self.assertEqual(events, '  workflow_dispatch:\n  push:\n    branches: [main]\n    paths:\n      - .github/workflows/python-lsan-comparison.yml\n      - tools/qa/python_lsan_comparison/**')


if __name__ == '__main__':
    unittest.main()
