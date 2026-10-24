"""Collect, but never execute, the full suite to guard bounded accounting IDs."""
import json
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
COLLECT = r'''
import json
import pytest
from _pytest.junitxml import bin_xml_escape, mangle_test_address
from tools.qa.native_regression.pytest_accounting import MAX_NAME_BYTES, VERSION

class Audit:
    def __init__(self):
        self.count = 0
        self.errors = 0
        self.skips = 0
        self.maxima = [0, 0, 0]
        self.oversized = 0

    def pytest_collectreport(self, report):
        self.errors += int(report.failed)
        self.skips += int(report.skipped)

    def pytest_collection_finish(self, session):
        self.count = len(session.items)
        for item in session.items:
            names = mangle_test_address(item.nodeid)
            values = (item.nodeid, '.'.join(names[:-1]), bin_xml_escape(names[-1]))
            sizes = [len(value.encode('utf-8')) for value in values]
            self.maxima = [max(old, new) for old, new in zip(self.maxima, sizes)]
            self.oversized += int(any(size > MAX_NAME_BYTES for size in sizes))

audit = Audit()
# Collection never runs test bodies, including this regression: no recursion.
# No terminal plugin: huge parameter values must not leak into diagnostic output.
code = pytest.main(['tests', '--collect-only', '-p', 'no:terminal',
                    '-p', 'no:cacheprovider'], plugins=[audit])
print(json.dumps(dict(exitcode=int(code), count=audit.count,
                     errors=audit.errors, skips=audit.skips,
                     maxima=audit.maxima, oversized=audit.oversized,
                     version=pytest.__version__, expected_version=VERSION,
                     budget=MAX_NAME_BYTES)))
'''


def test_full_suite_collected_identities_fit_native_accounting_budget():
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1')
    # This nested process is collection only, never a second accounting producer.
    env.pop('PYTEST_ADDOPTS', None)
    result = subprocess.run([sys.executable, '-c', COLLECT], cwd=ROOT, env=env,
                            text=True, capture_output=True, timeout=120)
    assert result.returncode == 0, 'Identity audit subprocess failed'
    summary = json.loads(result.stdout)
    assert summary['version'] == summary['expected_version']
    assert summary['exitcode'] == 0 and summary['errors'] == 0, summary
    assert summary['skips'] == 0 and summary['count'] > 0, summary
    assert summary['oversized'] == 0, summary
    assert all(size <= summary['budget'] for size in summary['maxima']), summary
