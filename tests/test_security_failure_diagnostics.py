"""Failure evidence must stay fail-closed without copying scanner secrets."""
import importlib
import json
from pathlib import Path
import subprocess
import sys

import pytest

scanner = importlib.import_module('tools.security.scan')
SECRET = 'synthetic-private-source-and-credential'


@pytest.mark.parametrize('mode,reason,error_name', [
    ('exit', 'nonaccepted-exit', 'RuntimeError'),
    ('json', 'invalid-json', 'JSONDecodeError'),
    ('timeout', 'timeout', 'TimeoutExpired'),
    ('missing', 'execution-error', 'FileNotFoundError'),
])
def test_scanner_failure_reason_retained_without_streams(tmp_path, monkeypatch, mode, reason, error_name):
    original_run = subprocess.run
    def run(argv, **kwargs):
        if '-c' not in argv:
            return original_run(argv, **kwargs)
        if mode == 'timeout':
            raise subprocess.TimeoutExpired(argv, 180, output=SECRET, stderr=SECRET)
        if mode == 'missing':
            raise FileNotFoundError(SECRET)
        return subprocess.CompletedProcess(argv, 17 if mode == 'exit' else 0,
                                           SECRET, SECRET * 10000)
    monkeypatch.setattr(scanner.subprocess, 'run', run)
    report = scanner.scan(tmp_path, Path(sys.executable))
    assert report['status'] == 'NOT_RUN' and report['checks'] == {}
    assert report['error'] == error_name + ': scanner unavailable, failed, or incomplete'
    evidence = report['scanner_failure']
    assert evidence['tool'] == 'version-probe' and evidence['reason'] == reason
    if mode in ('exit', 'json'):
        assert evidence['exit_code'] == (17 if mode == 'exit' else 0)
        assert evidence['stderr_chars'] == len(SECRET) * 10000
    assert SECRET not in json.dumps(report)
    assert len(json.dumps(evidence)) < 512


@pytest.mark.parametrize('tool', ['bandit', 'detect_secrets', 'pip_audit', SECRET])
def test_tool_identity_is_allowlisted_and_exit_still_raises(monkeypatch, tool):
    monkeypatch.setattr(scanner.subprocess, 'run', lambda *a, **k:
                        subprocess.CompletedProcess(a[0], 17, SECRET, SECRET))
    with pytest.raises(RuntimeError, match='scanner failed, exit 17') as caught:
        scanner.run_json([SECRET, '-m', tool, SECRET])
    evidence = caught.value.scanner_failure
    assert evidence['tool'] == (tool if tool != SECRET else 'scanner')
    assert evidence['reason'] == 'nonaccepted-exit' and evidence['exit_code'] == 17
    assert SECRET not in json.dumps(evidence)


def test_numeric_leak_summary_is_bounded_evidence_not_reclassification(tmp_path, monkeypatch):
    original_run = subprocess.run
    def run(argv, **kwargs):
        if '-c' not in argv:
            return original_run(argv, **kwargs)
        return subprocess.CompletedProcess(argv, 1, json.dumps(scanner.PINS),
            SECRET * 10000 + '\nSUMMARY: AddressSanitizer: 123 byte(s) leaked in 4 allocation(s).\n')
    monkeypatch.setattr(scanner.subprocess, 'run', run)
    report = scanner.scan(tmp_path, Path(sys.executable))
    assert report['status'] == 'NOT_RUN' and report['checks'] == {}
    assert report['scanner_failure']['exit_code'] == 1
    assert report['scanner_failure']['leak_summary'] == {'bytes': 123, 'allocations': 4}
    assert SECRET not in json.dumps(report)
    assert len(json.dumps(report['scanner_failure'])) < 512


@pytest.mark.parametrize('stderr', [
    'SUMMARY: AddressSanitizer: 123 byte(s) leaked in 4 allocation(s). ' + SECRET,
    'SUMMARY: AddressSanitizer: 123 byte(s) leaked in 4 allocation(s).\n' + 'x' * 4096,
    'SUMMARY: AddressSanitizer: ' + '1' * 10000 + ' byte(s) leaked in 4 allocation(s).',
])
def test_unrecognized_or_out_of_budget_summary_is_not_copied(monkeypatch, stderr):
    monkeypatch.setattr(scanner.subprocess, 'run', lambda *a, **k:
                        subprocess.CompletedProcess(a[0], 17, SECRET, stderr))
    with pytest.raises(RuntimeError) as caught:
        scanner.run_json(['python', '-m', 'bandit'])
    assert 'leak_summary' not in caught.value.scanner_failure
    assert SECRET not in json.dumps(caught.value.scanner_failure)


@pytest.mark.parametrize('exit_code', [0, 1])
def test_accepted_exits_and_json_remain_unchanged(monkeypatch, exit_code):
    monkeypatch.setattr(scanner.subprocess, 'run', lambda *a, **k:
                        subprocess.CompletedProcess(a[0], exit_code, '{"results": []}', SECRET))
    assert scanner.run_json(['python', '-m', 'bandit']) == {'results': []}
