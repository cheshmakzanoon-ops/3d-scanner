import tempfile
import unittest
from pathlib import Path
import importlib.util
SPEC = importlib.util.spec_from_file_location("android_runtime_readiness", Path(__file__).resolve().parents[1] / "tools/android_runtime_readiness.py")
readiness = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(readiness)
CRITICAL, LogCursor, Window, single_pid = readiness.CRITICAL, readiness.LogCursor, readiness.Window, readiness.single_pid

class Tests(unittest.TestCase):
    def test_exact_window_and_pid_change(self):
        w=Window()
        self.assertFalse(w.update(0, ('1','2'), True))
        for t in [20,40,60,80]: self.assertFalse(w.update(t, ('1','2'), True))
        self.assertFalse(w.update(89.999, ('1','2'), True))
        self.assertTrue(w.update(90, ('1','2'), True))
        self.assertFalse(w.update(91, ('1','3'), True))
        for t in [110,130,150,170]: self.assertFalse(w.update(t, ('1','3'), True))
        self.assertFalse(w.update(180, ('1','3'), True))
        self.assertTrue(w.update(181, ('1','3'), True))
        self.assertFalse(w.update(182, ('1','3'), False))
        self.assertFalse(w.update(183, ('1','3'), True))
    def test_user_change_resets_window(self):
        w=Window()
        w.update(0, ('1','2','0'), True)
        w.update(20, ('1','2','0'), True)
        self.assertFalse(w.update(30, ('1','2','10'), True))
        self.assertEqual(30,w.since)
    def test_probe_gap_resets_window(self):
        w=Window()
        w.update(0, ('1','2'), True)
        self.assertFalse(w.update(90, ('1','2'), True))
        self.assertEqual(90,w.since)
    def test_stalled_log_fails_closed(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'log'; p.write_text('boot\n'); c=LogCursor(p)
            with patch.object(readiness.time, 'monotonic',return_value=0): c.poll()
            with patch.object(readiness.time, 'monotonic',return_value=31):
                self.assertEqual((False,['LOG_NOT_ADVANCING_30S']),c.poll())
    def test_current_user_state_is_scoped_and_exact(self):
        dump = ('Users:\n  UserInfo{0:Owner:13} serialNo=0 isPrimary=true\n'
                '    Type: android.os.usertype.full.SYSTEM\n    State: RUNNING_LOCKED\n'
                '  UserInfo{10:Other:10} serialNo=10 isPrimary=false\n'
                '    State: RUNNING_UNLOCKED\nGlobal:\n    State: RUNNING_UNLOCKED\n')
        self.assertFalse(readiness.current_user_unlocked(dump, '0'))
        self.assertTrue(readiness.current_user_unlocked(dump, '10'))
        self.assertFalse(readiness.current_user_unlocked(dump, '11'))
        self.assertFalse(readiness.current_user_unlocked(dump + dump, '10'))
        self.assertFalse(readiness.current_user_unlocked('    State: RUNNING_UNLOCKED\n', '0'))
        self.assertFalse(readiness.current_user_unlocked(dump.replace('RUNNING_UNLOCKED','RUNNING_UNLOCKING'), '10'))
    def test_boot_completed_required_by_gate(self):
        source = Path(SPEC.origin).read_text()
        self.assertIn("boot = command('getprop', 'sys.boot_completed')", source)
        self.assertIn("healthy = bool(boot == '1' and", source)
    def test_narrow_events(self):
        for line in ['ANR in com.android.networkstack.process',
                     'Timeout executing service: ServiceRecord{u0 com.android.networkstack.tethering/.TetheringService}',
                     'Killing 863:com.android.networkstack.process/1073 (adj -800): bg anr',
                     'FATAL EXCEPTION IN SYSTEM PROCESS: main', 'Lost network stack']:
            self.assertIsNotNone(CRITICAL.search(line), line)
        for line in ['ANR in com.android.systemui', 'ANR in com.android.phone',
                     'ANR in com.realityforge.scanner', 'networkstack service connected']:
            self.assertIsNone(CRITICAL.search(line), line)
    def test_pid_requires_single_nonzero(self):
        for value in ['', '0', '123 456', 'abc']:
            self.assertIsNone(single_pid(value))
        self.assertEqual('123',single_pid('123\r\n'))
    def test_history_partial_rotation_truncate_regrowth(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'log'; p.write_text('ANR in com.android.networkstack.process\n')
            c=LogCursor(p)
            self.assertEqual((False,[]),c.poll())
            self.assertEqual((True,[]),c.poll())
            with p.open('a') as f: f.write('Lost network')
            self.assertEqual((True,[]),c.poll())
            with p.open('a') as f: f.write(' stack\n')
            self.assertEqual((True,['Lost network stack']),c.poll())
            self.assertEqual((True,[]),c.poll())
            p.rename(Path(d)/'old'); p.write_text('new boot\n')
            self.assertEqual((False,[]),c.poll())
            p.write_text('different longer contents' * 20)
            self.assertEqual((False,[]),c.poll())
            p.write_text('')
            self.assertEqual((False,[]),c.poll())
            p.unlink()
            self.assertFalse(c.poll()[0])

if __name__ == '__main__': unittest.main()
