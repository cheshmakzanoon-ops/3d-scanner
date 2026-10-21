import unittest
from pathlib import Path
import importlib.util
SPEC = importlib.util.spec_from_file_location("android_runtime_batch_probe", Path(__file__).resolve().parents[1] / "tools/android_runtime_batch_probe.py")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)
COMMANDS, parse, shell_script = probe.COMMANDS, probe.parse, probe.shell_script
N='a'*32

def fixture():
    lines=[N+' SNAPSHOT_BEGIN']
    for name,_ in COMMANDS:
        lines += [N+' BEGIN '+name+' 1.0', 'value', N+' END '+name+' 0 2.0']
    return '\n'.join(lines+[N+' SNAPSHOT_END'])+'\n'

class Tests(unittest.TestCase):
    def test_complete(self):
        self.assertTrue(parse(fixture(),N)['complete'])
        self.assertEqual(10,len(parse(fixture(),N)['fields']))
    def test_all_truncations_fail_closed(self):
        text=fixture()
        for index in range(len(text)-len(N+' SNAPSHOT_END\n')):
            try: self.assertFalse(parse(text[:index],N)['complete'])
            except ValueError: pass
    def test_duplicate_wrong_nonce_trailing_rejected(self):
        for value in [fixture()+N+' SNAPSHOT_END\n',fixture().replace('BEGIN boot','BEGIN settings'),fixture().replace(N,'b'*32),fixture().replace('END boot 0','END boot 999')]:
            with self.assertRaises(ValueError): parse(value,N)
    def test_timeout_is_not_pid_restart(self):
        result=parse(fixture().replace('END system_before 0','END system_before 124'),N)
        self.assertEqual('timeout_or_exit124',result['fields']['system_before']['status'])
        self.assertIsNone(result['fields']['system_before']['value'])
    def test_negative_elapsed_rejected(self):
        with self.assertRaises(ValueError): parse(fixture().replace('0 2.0','0 0.1'),N)
    def test_fixed_commands_guest_bounds_and_nonce_validation(self):
        script=shell_script(N)
        self.assertIn('timeout 25',script);self.assertEqual(10,script.count('timeout 8'))
        with self.assertRaises(ValueError): shell_script('x; echo unsafe')

if __name__=='__main__':unittest.main()
