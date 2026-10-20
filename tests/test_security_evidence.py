import json
import os
from pathlib import Path
import sys
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.security.scan import scan
from tools.security.scan import apply_suppressions
import hashlib


@pytest.fixture
def scanner():
    path = Path(os.environ.get('RF_SECURITY_PYTHON', '/workspace/campaign/096-venv/bin/python'))
    assert path.is_file(), 'install pinned security requirements and set RF_SECURITY_PYTHON'
    return path


def test_real_scanners_detect_hostile_source_without_leaking_secret(tmp_path, scanner):
    secret = 'AKIA' + 'Q7Y9J2L5Z8P3R6TN'
    (tmp_path / 'hostile.py').write_text('import os\n' + 'aws_access_key_id = "' + secret + '"\n' + 'eval(input())\n')
    report = scan(tmp_path, scanner)
    assert report['status'] == 'FAIL', report
    assert report['checks']['static'] == 'FAIL'
    assert report['checks']['secrets'] == 'FAIL'
    assert secret not in json.dumps(report)
    assert 'hashed_secret' not in json.dumps(report)


def test_real_dependency_audit_rejects_known_vulnerable_pin(tmp_path, scanner):
    source = tmp_path / 'src'
    source.mkdir()
    (source / 'safe.py').write_text('print("synthetic security fixture")\n')
    requirements = tmp_path / 'requirements.txt'
    requirements.write_text('requests==2.19.1\n')
    report = scan(source, scanner, requirements)
    assert report['checks'].get('dependencies') == 'FAIL', report
    assert any(f.get('package') == 'requests' for f in report['findings'])


def test_missing_scanner_fails_closed(tmp_path):
    report = scan(tmp_path, tmp_path / 'absent')
    assert report['status'] == 'NOT_RUN'


def test_missing_dependency_audit_never_full_pass(tmp_path, scanner):
    (tmp_path / 'safe.py').write_text('x = 3\n')
    report = scan(tmp_path, scanner)
    assert report['status'] == 'NOT_RUN', report
    assert report['checks']['dependencies'] == 'NOT_RUN'


def test_suppression_binds_exact_source_and_expires(tmp_path):
    source = tmp_path / 'code.py'
    source.write_text('x = 3\n')
    finding = {'scanner': 'bandit', 'path': str(source), 'line': 1, 'rule': 'B101'}
    entry = {**finding, 'source_sha256': 'sha256:' + hashlib.sha256(source.read_bytes()).hexdigest(), 'expires': '2099-01-01', 'justification': 'Analytic-only assertion in a diagnostic fixture, never an input security boundary.'}
    policy = tmp_path / 'policy.json'
    policy.write_text(json.dumps({'schema': 1, 'entries': [entry]}))
    report = {'findings': [finding], 'checks': {'static': 'FAIL'}}
    apply_suppressions(report, tmp_path, policy)
    assert not report['findings'] and len(report['suppressed']) == 1
    source.write_text('eval(input())\n')
    report['findings'] = [finding]
    apply_suppressions(report, tmp_path, policy)
    assert report['findings']
    entry.update(source_sha256='sha256:' + hashlib.sha256(source.read_bytes()).hexdigest(), expires='2000-01-01')
    policy.write_text(json.dumps({'schema': 1, 'entries': [entry]}))
    with pytest.raises(ValueError):
        apply_suppressions(report, tmp_path, policy)
