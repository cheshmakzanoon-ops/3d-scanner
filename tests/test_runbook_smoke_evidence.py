"""Bounded evidence must not weaken the actual documented-command smoke gate."""
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.docs import check_runbook


@pytest.mark.parametrize('returncode,stdout,status', [
    (0, 'Usage: cli', 'PASS'), (1, 'usage: cli', 'FAIL'),
    (-11, 'usage: cli', 'FAIL'), (0, 'no help here', 'FAIL'),
])
def test_smoke_retains_bounded_evidence_and_original_gate(monkeypatch, returncode, stdout, status):
    secret = 'PRIVATE_PATH_AND_TOKEN'
    stderr = secret + '\nSUMMARY: AddressSanitizer: 412940 byte(s) leaked in 8050 allocation(s).\n'
    calls = []
    monkeypatch.setenv('LSAN_OPTIONS', 'exitcode=17')
    def run(argv, **kwargs):
        calls.append(argv)
        assert argv[0] == sys.executable and argv[-1] == '--help'
        assert kwargs == dict(cwd=ROOT, capture_output=True, text=True, timeout=30)
        assert os.environ['LSAN_OPTIONS'] == 'exitcode=17'
        return subprocess.CompletedProcess(argv, returncode, stdout + ' é ' + secret, stderr)
    monkeypatch.setattr(check_runbook.subprocess, 'run', run)
    rows = check_runbook.smoke(ROOT)
    assert [argv[1] for argv in calls] == [str(ROOT / name) for name in (
        'tools/capture_audit.py', 'tools/check_apk.py', 'tools/benchmark_multiview_coverage.py')]
    for row in rows:
        assert row['status'] == status
        assert row['returncode'] == returncode
        assert row['usage_present'] == ('usage:' in stdout.lower())
        assert row['stdout_utf8_bytes'] == len((stdout + ' é ' + secret).encode('utf-8'))
        assert row['stderr_utf8_bytes'] == len(stderr.encode('utf-8'))
        assert row['sanitizer_summary'] == {'leaked_bytes': 412940, 'allocations': 8050}
        assert set(row) == {'script', 'status', 'returncode', 'usage_present',
                            'stdout_utf8_bytes', 'stderr_utf8_bytes', 'sanitizer_summary'}
    assert secret not in json.dumps(rows)
    assert 'AddressSanitizer' not in json.dumps(rows)


@pytest.mark.parametrize('stderr,expected', [
    ('SUMMARY: LeakSanitizer: 12 byte(s) leaked in 3 allocation(s).\n',
     {'leaked_bytes': 12, 'allocations': 3}),
    ('SUMMARY: AddressSanitizer: 0 byte(s) leaked in 0 allocation(s).\n',
     {'leaked_bytes': 0, 'allocations': 0}),
    ('SUMMARY: AddressSanitizer: secret byte(s) leaked in 3 allocation(s).', None),
    ('SUMMARY: AddressSanitizer: ' + '9' * 21 + ' byte(s) leaked in 3 allocation(s).', None),
    ('SUMMARY: AddressSanitizer: 12 byte(s) leaked in 3 allocation(s). private', None),
    ('SUMMARY: AddressSanitizer: 12 byte(s) leaked in 3 allocation(s).\n' + 'x' * 4096, None),
    ('unclassified failure', None),
], ids=['lsan', 'asan-zero', 'nonnumeric', 'oversized-number', 'suffix', 'outside-tail', 'unknown'])
def test_smoke_summary_is_numeric_exact_and_tail_bounded(monkeypatch, stderr, expected):
    monkeypatch.setattr(check_runbook.subprocess, 'run', lambda *a, **kw:
                        subprocess.CompletedProcess(a[0], 1, 'usage:', stderr))
    for row in check_runbook.smoke(ROOT):
        assert row['status'] == 'FAIL'
        assert row['sanitizer_summary'] == expected


def test_smoke_timeout_behavior_unchanged(monkeypatch):
    def timeout(*args, **kwargs):
        raise subprocess.TimeoutExpired(args[0], 30)
    monkeypatch.setattr(check_runbook.subprocess, 'run', timeout)
    with pytest.raises(subprocess.TimeoutExpired):
        check_runbook.smoke(ROOT)
