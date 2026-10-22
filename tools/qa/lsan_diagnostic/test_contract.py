"""Synthetic evidence tests only: never claim sanitizer execution."""
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from classify import classify

class ClassifierTests(unittest.TestCase):
    def fixture(self, folder):
        rows = []
        for kind in ('compile', 'empty', 'numpy', 'leak', 'uaf', 'ub'):
            for check in ([None] if kind == 'compile' else ['exit', 'observe', 'boundary']):
                text = {'compile':'', 'empty':'', 'numpy':'',
                        'leak':'LeakSanitizer: detected memory leaks\nDirect leak of 12347 byte(s) in 1 object(s)\nlose_native_allocation faults.c:10\nSUMMARY: AddressSanitizer: 12347 byte(s) leaked in 1 allocation(s).\n',
                        'uaf':'ERROR: AddressSanitizer: heap-use-after-free\nrf_proof_uaf faults.c:24\n',
                        'ub':'runtime error: signed integer overflow\nrf_proof_ub faults.c:28\n'}[kind]
                name = 'compile.log' if kind == 'compile' else kind+'-'+check+'.log'
                (folder/name).write_text(text)
                row = dict(kind=kind, log=name, exit_code=int(kind in ('leak','uaf','ub')),
                           reason=None, group_drained=True, sha256=hashlib.sha256(text.encode()).hexdigest(),
                           log_bytes=len(text.encode()))
                if check:
                    row['check'] = check
                rows.append(row)
        return dict(schema=2,status='COMPLETE',pins_before={'synthetic':'abc'},pins_after={'synthetic':'abc'},records=rows)

    def check(self, mutate, valid=False):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d); receipt=self.fixture(folder); mutate(receipt,folder)
            (folder/'receipt.json').write_text(json.dumps(receipt))
            try:
                with contextlib.redirect_stdout(io.StringIO()): result=classify(folder)
            except (ValueError,OSError,KeyError,TypeError): result=1
            self.assertEqual(result == 0, valid)

    def test_synthetic_valid(self): self.check(lambda r,p:None,True)
    def test_tamper(self): self.check(lambda r,p:(p/'leak-exit.log').write_text('changed'))
    def test_missing(self): self.check(lambda r,p:(p/'leak-exit.log').unlink())
    def test_truncated(self): self.check(lambda r,p:(p/'leak-exit.log').write_bytes(b''))
    def test_duplicate(self): self.check(lambda r,p:r['records'].__setitem__(-1,copy.deepcopy(r['records'][-2])))
    def test_compile_fail(self): self.check(lambda r,p:r['records'][0].update(exit_code=1))
    def test_timeout(self): self.check(lambda r,p:r['records'][7].update(reason='TIMEOUT'))
    def test_orphan(self): self.check(lambda r,p:r['records'][7].update(reason='ORPHAN_DESCENDANTS'))
    def test_undrained(self): self.check(lambda r,p:r['records'][7].update(group_drained=False))
    def test_unsafe_path(self): self.check(lambda r,p:r['records'][7].update(log='../elsewhere'))
    def test_changed_input(self): self.check(lambda r,p:r['pins_after'].update(synthetic='other'))
    def test_short_matrix(self): self.check(lambda r,p:r['records'].pop())
    def test_generic_and_fatal(self):
        for text in ('generic failure','LeakSanitizer has encountered a fatal error',
                     'AddressSanitizer:DEADLYSIGNAL','Traceback (most recent call last)'):
            def mutate(r,p):
                row=next(x for x in r['records'] if x.get('kind')=='leak' and x.get('check')=='exit')
                (p/row['log']).write_text(text)
                row.update(log_bytes=len(text),sha256=hashlib.sha256(text.encode()).hexdigest())
            with self.subTest(text=text): self.check(mutate)

if __name__ == '__main__': unittest.main()

class LifecycleTests(unittest.TestCase):
    def test_normal_and_timeout(self):
        import os, sys
        from run_matrix import run
        with tempfile.TemporaryDirectory() as d:
            normal=run([sys.executable,'-c','pass'],os.environ.copy(),Path(d),'normal.log',2)
            self.assertEqual(normal['exit_code'],0)
            self.assertIsNone(normal['reason'])
            self.assertTrue(normal['group_drained'])
            timed=run([sys.executable,'-c','import time; time.sleep(20)'],os.environ.copy(),Path(d),'timeout.log',.15)
            self.assertEqual(timed['reason'],'TIMEOUT')
            self.assertTrue(timed['group_drained'])

    def test_exec_failure(self):
        import os
        from run_matrix import run
        with tempfile.TemporaryDirectory() as d:
            row=run(['/nonexistent/rf-proof-test-command'],os.environ.copy(),Path(d),'failed.log',2)
            self.assertEqual(row['reason'],'EXEC_FAILURE')
            self.assertTrue(row['group_drained'])


class LogBoundTests(unittest.TestCase):
    def test_flood_fails_with_hard_bound(self):
        import os, sys
        from run_matrix import run, LOG_LIMIT
        with tempfile.TemporaryDirectory() as d:
            code = "import os; b=b'x'*65536; " + "\nwhile True: os.write(1,b)"
            row=run([sys.executable,'-c',code],os.environ.copy(),Path(d),'flood.log',2)
            self.assertEqual(row['reason'],'LOG_LIMIT')
            self.assertNotEqual(row['exit_code'],0)
            self.assertLessEqual(row['log_bytes'],LOG_LIMIT)
            self.assertTrue(row['group_drained'])


class DescendantTests(unittest.TestCase):
    def execute_child(self, body):
        import os,sys
        from run_matrix import run
        with tempfile.TemporaryDirectory() as d:
            return run([sys.executable,'-c',body],os.environ.copy(),Path(d),'child.log',2)

    def test_delayed_natural_exit(self):
        row=self.execute_child("import os,time; p=os.fork(); time.sleep(.18) if p==0 else None; os._exit(0)")
        self.assertIsNone(row['reason'])
        self.assertTrue(row['group_drained'])
        self.assertTrue(any(e['members'] for e in row['process_events'] if e['event']=='leader_exit'))
        self.assertEqual(row['process_events'][-1]['members'],[])

    def test_persistent_descendant_fails_and_reaps(self):
        row=self.execute_child("import os,time; p=os.fork(); time.sleep(20) if p==0 else None; os._exit(0)")
        self.assertEqual(row['reason'],'ORPHAN_DESCENDANTS')
        self.assertTrue(row['group_drained'])
        self.assertEqual(row['process_events'][-1]['members'],[])

    def test_escaped_descendant_fails_and_reaps(self):
        row=self.execute_child("import os,time; p=os.fork(); (os.setsid(),time.sleep(20)) if p==0 else None; os._exit(0)")
        self.assertEqual(row['reason'],'ESCAPED_DESCENDANTS')
        self.assertTrue(row['group_drained'])
        self.assertEqual(row['process_events'][-1]['members'],[])


class IdentityOwnershipTests(unittest.TestCase):
    def test_existing_unrelated_child_rejected(self):
        import subprocess,sys
        from process_scope import Scope
        child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(10)'])
        try:
            with self.assertRaisesRegex(RuntimeError,'unrelated children'):
                Scope()
            self.assertIsNone(child.poll())
        finally:
            child.terminate();child.wait(timeout=2)

    def test_changed_start_time_never_signaled(self):
        from unittest.mock import patch
        import signal
        from process_scope import Scope
        scope=Scope()
        with patch('process_scope.os.pidfd_open',return_value=987), \
             patch('process_scope.identity',return_value={'start_time':222}), \
             patch('process_scope.signal.pidfd_send_signal') as send, \
             patch('process_scope.os.close') as close:
            scope.send({'pid':12345,'start_time':111},signal.SIGKILL)
            send.assert_not_called();close.assert_called_once_with(987)

    def test_exited_before_pidfd_open_is_safe(self):
        from unittest.mock import patch
        import signal
        from process_scope import Scope
        scope=Scope()
        with patch('process_scope.os.pidfd_open',side_effect=ProcessLookupError), \
             patch('process_scope.signal.pidfd_send_signal') as send:
            scope.send({'pid':12345,'start_time':111},signal.SIGKILL)
            send.assert_not_called()


class AncestryRaceTests(unittest.TestCase):
    def test_changed_ppid_between_listing_and_identity_rejected(self):
        from unittest.mock import patch
        import os
        from process_scope import Scope
        scope=Scope()
        with patch('process_scope.children',return_value={12345}), \
             patch('process_scope.identity',return_value=dict(pid=12345,ppid=os.getpid()+1,
                    pgid=12345,start_time=777,state='S',executable='unrelated')):
            self.assertEqual(scope.snapshot('race'),[])
            self.assertEqual(scope.known,{})

    def test_reused_owned_pid_rejected(self):
        from unittest.mock import patch
        import os
        from process_scope import Scope
        scope=Scope();scope.known[(12345,111)]={}
        with patch('process_scope.children',return_value=set()), \
             patch('process_scope.identity',return_value=dict(pid=12345,ppid=os.getpid(),
                    pgid=12345,start_time=222,state='S',executable='reused')):
            self.assertEqual(scope.snapshot('race'),[])


class SignalRaceTests(unittest.TestCase):
    def test_exit_between_pidfd_open_and_signal_is_benign(self):
        from unittest.mock import patch
        import os,signal
        from process_scope import Scope
        scope=Scope()
        with patch('process_scope.os.pidfd_open',return_value=987), \
             patch('process_scope.identity',return_value={'start_time':111,'ppid':os.getpid()}), \
             patch('process_scope.signal.pidfd_send_signal',side_effect=ProcessLookupError), \
             patch('process_scope.os.close') as close:
            scope.send({'pid':12345,'start_time':111},signal.SIGKILL)
            close.assert_called_once_with(987)
