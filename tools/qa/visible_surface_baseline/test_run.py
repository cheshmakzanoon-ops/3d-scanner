"""Fail-closed execution receipts, independent of expensive reconstruction."""
import json, subprocess, sys, tempfile, unittest
from pathlib import Path

class WrapperGuards(unittest.TestCase):

    def test_missing_fixture_fails_nonzero_with_receipt(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            out = p / 'new'
            r = subprocess.run([sys.executable, str(Path(__file__).with_name('run.py')), '--repo', str(p), '--library', str(p / 'missing.so'), '--fixture', str(p / 'missing'), '--output', str(out)], capture_output=True)
            self.assertNotEqual(r.returncode, 0)
            receipt = json.loads((out / 'receipt.json').read_text())
            self.assertEqual(receipt['status'], 'FAILED')
            self.assertNotIn('evaluation', receipt)

    def test_existing_output_refused_without_overwrite(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            (p / 'receipt.json').write_text('old receipt')
            r = subprocess.run([sys.executable, str(Path(__file__).with_name('run.py')), '--repo', d, '--library', d, '--fixture', d, '--output', d], capture_output=True)
            self.assertNotEqual(r.returncode, 0)
            self.assertEqual((p / 'receipt.json').read_text(), 'old receipt')
if __name__ == '__main__':
    unittest.main()
