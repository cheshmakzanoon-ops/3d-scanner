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
                        'leak':'LeakSanitizer: detected memory leaks\nDirect leak of 12347 byte(s) in 1 object(s)\nlose_native_allocation faults.c:10\n',
                        'uaf':'ERROR: AddressSanitizer: heap-use-after-free\nrf_proof_uaf faults.c:24\n',
                        'ub':'runtime error: signed integer overflow\nrf_proof_ub faults.c:28\n'}[kind]
                name = 'compile.log' if kind == 'compile' else kind+'-'+check+'.log'
                (folder/name).write_text(text)
                row = dict(kind=kind, log=name, exit_code=int(kind in ('leak','uaf','ub')),
                           reason=None, group_drained=True, sha256=hashlib.sha256(text.encode()).hexdigest(),
                           log_bytes=len(text.encode()))
                if check: row['check'] = check
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
