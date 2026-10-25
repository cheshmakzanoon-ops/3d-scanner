import json
import os
from pathlib import Path
import sys
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.security.scan import scan
from tools.security.scan import apply_suppressions
from security_cli_boundary import scan_external
import hashlib


@pytest.fixture
def scanner():
    path = Path(os.environ.get('RF_SECURITY_PYTHON', '/workspace/campaign/096-venv/bin/python'))
    assert path.is_file(), 'install pinned security requirements and set RF_SECURITY_PYTHON'
    return path


def test_real_scanners_detect_hostile_source_without_leaking_secret(tmp_path, scanner):
    secret = 'AKIA' + 'Q7Y9J2L5Z8P3R6TN'
    (tmp_path / 'hostile.py').write_text('import os\n' + 'aws_access_key_id = "' + secret + '"\n' + 'eval(input())\n')
    report = scan_external(tmp_path, scanner)
    assert report['status'] == 'FAIL', json.dumps(report.get('scanner_failure', {}))
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
    report = scan_external(source, scanner, requirements)
    assert report['checks'].get('dependencies') == 'FAIL', json.dumps(report.get('scanner_failure', {}))
    assert any(f.get('package') == 'requests' for f in report['findings'])


def test_missing_scanner_fails_closed(tmp_path):
    report = scan(tmp_path, tmp_path / 'absent')
    assert report['status'] == 'NOT_RUN'


def test_missing_dependency_audit_never_full_pass(tmp_path, scanner):
    (tmp_path / 'safe.py').write_text('x = 3\n')
    report = scan_external(tmp_path, scanner)
    assert report['status'] == 'NOT_RUN', report
    assert report['checks'].get('dependencies') == 'NOT_RUN', json.dumps(report.get('scanner_failure', {}))


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


def test_canonical_scanner_paths_and_source_identity(tmp_path, scanner):
    (tmp_path/'safe.py').write_text('answer = 3\n')
    report = scan_external(tmp_path, scanner)
    assert report['status'] == 'NOT_RUN'
    assert report['checks'] == {'static': 'PASS', 'secrets': 'PASS', 'dependencies': 'NOT_RUN'}
    assert report['source_sha'] is None
    assert report['source_files_count'] == 1
    assert len(report['source_files_sha256']) == 64
    assert report['finding_counts'] == {'raw': 0, 'active': 0, 'suppressed': 0, 'raw_by_rule': {}, 'active_by_rule': {}, 'suppressed_by_rule': {}}


def test_wrong_expected_candidate_fails_before_scanners(tmp_path):
    (tmp_path/'safe.py').write_text('answer = 3\n')
    report = scan(tmp_path, tmp_path/'missing', expected_source='a'*40)
    assert report['status'] == 'NOT_RUN' and report['checks'] == {}


def test_source_mutation_during_scan_never_passes(tmp_path, monkeypatch):
    import importlib
    module = importlib.import_module('tools.security.scan')
    source=tmp_path/'safe.py';source.write_text('answer = 3\n')
    requirements=tmp_path/'requirements.txt';requirements.write_text('requests==2.19.1\n')
    def runner(argv, **kwargs):
        if '-c' in argv:return module.PINS
        if 'bandit' in argv:return {'results': []}
        if 'detect_secrets' in argv:return {'results': {}}
        source.write_text('answer = 4\n')
        return {'dependencies': [{'name':'requests','version':'2.19.1','vulns':[]}]}
    monkeypatch.setattr(module,'run_json',runner)
    report=module.scan(tmp_path,Path(sys.executable),requirements)
    assert report['status']=='NOT_RUN'
    assert report['checks']=={'static':'PASS','secrets':'PASS','dependencies':'PASS'}
    assert 'error' in report  # Successful child checks do not override changed-source rejection.


def test_context_fingerprint_preserves_conditional_review(tmp_path):
    source=tmp_path/'fetch.py';source.write_text('controlled downloader\n')
    context=tmp_path/'origins.json';context.write_text('{"scheme":"https"}\n')
    finding={'scanner':'bandit','path':'fetch.py','line':1,'rule':'B310'}
    entry={**finding,'source_sha256':'sha256:'+hashlib.sha256(source.read_bytes()).hexdigest(),
           'context_sha256':{'origins.json':'sha256:'+hashlib.sha256(context.read_bytes()).hexdigest()},
           'expires':'2099-01-01','justification':'Exact reviewed HTTPS origins and pinned output content, bound to independent context bytes.'}
    policy=tmp_path/'policy.json';policy.write_text(json.dumps({'schema':1,'entries':[entry]}))
    report={'findings':[finding],'checks':{'static':'FAIL'}}
    apply_suppressions(report,tmp_path,policy)
    assert not report['findings'] and len(report['suppressed'])==1
    assert 'dependencies' not in report['checks']  # No success for an unexecuted scanner.
    context.write_text('{"scheme":"file"}\n')
    report['findings']=[finding]
    apply_suppressions(report,tmp_path,policy)
    assert report['findings']==[finding]


def test_finding_outside_selected_scope_rejected(tmp_path):
    policy=tmp_path/'policy.json';policy.write_text(json.dumps({'schema':1,'entries':[]}))
    report={'findings':[{'scanner':'bandit','path':'../outside.py','line':1,'rule':'B101'}],'checks':{'static':'FAIL'}}
    with pytest.raises(ValueError):apply_suppressions(report,tmp_path,policy)


def test_new_secret_does_not_inherit_checksum_file_review(tmp_path, scanner):
    source=tmp_path/'evidence.json'
    source.write_text(json.dumps({'artifact_sha256':'a'*64})+'\n')
    known={'scanner':'detect-secrets','path':'evidence.json','line':1,'rule':'Hex High Entropy String'}
    entry={**known,'source_sha256':'sha256:'+hashlib.sha256(source.read_bytes()).hexdigest(),
           'expires':'2099-01-01','justification':'Reviewed exact public artifact digest; every changed byte invalidates the exception.'}
    policy=tmp_path.parent/(tmp_path.name+'-policy.json');policy.write_text(json.dumps({'schema':1,'entries':[entry]}))
    secret='AKIA'+'Q7Y9J2L5Z8P3R6TN'
    source.write_text(json.dumps({'artifact_sha256':'a'*64,'aws_access_key_id':secret})+'\n')
    report=scan_external(tmp_path,scanner,suppressions=policy)
    assert report['status']=='FAIL' and report['checks']['secrets']=='FAIL', json.dumps(report.get('scanner_failure', {}))
    assert not report['suppressed']
    assert report['finding_counts']['raw']==report['finding_counts']['active']>0
    assert secret not in json.dumps(report)


def test_real_audit_includes_vulnerable_nested_requirements(tmp_path, scanner):
    source = tmp_path / 'source'
    source.mkdir()
    (source/'safe.py').write_text('answer = 3\n')
    included = tmp_path/'included.txt'
    included.write_text('requests==2.19.1\n')
    requirements = tmp_path/'requirements.txt'
    requirements.write_text('-r included.txt\n')
    report = scan_external(source, scanner, requirements)
    assert report['status'] == 'FAIL', json.dumps(report.get('scanner_failure', {}))
    assert report['checks']['dependencies'] == 'FAIL'
    assert report['requirements_packages_count'] == 1
    assert len(report['requirements_files_sha256']) == 2
    assert any(f.get('package') == 'requests' for f in report['findings'])


@pytest.mark.parametrize('text', ['', '-r requirements.txt\n', '-r ../outside.txt\n', '--index-url https://example.invalid\n', 'requests>=2\n'])
def test_incomplete_or_escaping_dependency_input_never_passes(tmp_path, text):
    source = tmp_path/'source'
    source.mkdir()
    requirements = source/'requirements.txt'
    requirements.write_text(text)
    report = scan(source, tmp_path/'absent', requirements)
    assert report['status'] == 'NOT_RUN' and report['checks'] == {}


def test_audit_omission_and_included_input_mutation_never_pass(tmp_path, monkeypatch):
    import importlib
    module = importlib.import_module('tools.security.scan')
    source=tmp_path/'source';source.mkdir()
    included=tmp_path/'included.txt';included.write_text('requests==2.19.1\n')
    requirements=tmp_path/'requirements.txt';requirements.write_text('-r included.txt\n')
    def runner(argv, **kwargs):
        if '-c' in argv:return module.PINS
        if 'bandit' in argv:return {'results': []}
        if 'detect_secrets' in argv:return {'results': {}}
        return {'dependencies': []}
    monkeypatch.setattr(module,'run_json',runner)
    report=module.scan(source,Path(sys.executable),requirements)
    assert report['status']=='NOT_RUN' and report['checks']['dependencies']=='NOT_RUN'
    def mutate(argv, **kwargs):
        if 'pip_audit' in argv:
            included.write_text('requests==2.19.2\n')
            return {'dependencies':[{'name':'requests','version':'2.19.1','vulns':[]}]}
        return runner(argv,**kwargs)
    monkeypatch.setattr(module,'run_json',mutate)
    assert module.scan(source,Path(sys.executable),requirements)['status']=='NOT_RUN'


@pytest.mark.parametrize('rows', [[], [('requests','2.19.1')],
    [('requests','2.19.2'),('urllib3','1.23')],
    [('requests','2.19.1'),('urllib3','1.23'),('urllib3','1.23')]])
def test_partial_changed_or_duplicate_dependency_coverage_rejected(tmp_path, monkeypatch, rows):
    import importlib
    module = importlib.import_module('tools.security.scan')
    source=tmp_path/'source';source.mkdir()
    requirements=tmp_path/'requirements.txt'
    requirements.write_text('requests==2.19.1\nurllib3==1.23\n')
    def runner(argv, **kwargs):
        if '-c' in argv:return module.PINS
        if 'bandit' in argv:return {'results': []}
        if 'detect_secrets' in argv:return {'results': {}}
        return {'dependencies':[{'name':name,'version':version,'vulns':[]} for name,version in rows]}
    monkeypatch.setattr(module,'run_json',runner)
    report=module.scan(source,Path(sys.executable),requirements)
    assert report['status']=='NOT_RUN' and report['checks']['dependencies']=='NOT_RUN'


def test_requirements_directory_symlink_rejected(tmp_path):
    source=tmp_path/'source';source.mkdir()
    actual=tmp_path/'actual';actual.mkdir()
    (actual/'child.txt').write_text('requests==2.19.1\n')
    (tmp_path/'alias').symlink_to(actual,target_is_directory=True)
    requirements=tmp_path/'requirements.txt';requirements.write_text('-r alias/child.txt\n')
    report=scan(source,tmp_path/'absent',requirements)
    assert report['status']=='NOT_RUN' and report['checks']=={}


def test_oversized_requirements_never_uses_unbounded_read(tmp_path, monkeypatch):
    source=tmp_path  # Actual workflow shape: dependency inputs are inside scanned tools.
    requirements=tmp_path/'requirements.txt'
    with requirements.open('wb') as stream:stream.truncate(4*1024*1024+1)
    read=Path.read_bytes
    def guarded(path):
        assert path != requirements, 'requirements must use bounded closure reads'
        return read(path)
    monkeypatch.setattr(Path,'read_bytes',guarded)
    report=scan(source,tmp_path/'absent',requirements)
    assert report['status']=='NOT_RUN' and report['checks']=={}


def test_external_policy_directory_retarget_never_passes(tmp_path, monkeypatch):
    import importlib
    module=importlib.import_module('tools.security.scan')
    source=tmp_path/'source';source.mkdir();(source/'safe.py').write_text('answer = 3\n')
    requirements=tmp_path/'requirements.txt';requirements.write_text('requests==2.19.1\n')
    directory=tmp_path/'policy-dir';directory.mkdir()
    policy=directory/'policy.json';policy.write_text('{"schema":1,"entries":[]}')
    other=tmp_path/'other';other.mkdir();(other/'policy.json').write_text('{"schema":1,"entries":[]}')
    def runner(argv, **kwargs):
        if '-c' in argv:return module.PINS
        if 'bandit' in argv:return {'results':[]}
        if 'detect_secrets' in argv:return {'results':{}}
        directory.rename(tmp_path/'original-policy-dir')
        directory.symlink_to(other,target_is_directory=True)
        return {'dependencies':[{'name':'requests','version':'2.19.1','vulns':[]}]}
    monkeypatch.setattr(module,'run_json',runner)
    report=scan(source,Path(sys.executable),requirements,policy)
    assert report['status']=='NOT_RUN'


def test_policy_bytes_applied_must_match_recorded_hash(tmp_path):
    policy=tmp_path/'policy.json';policy.write_text('{"schema":1,"entries":[]}')
    report={'findings':[],'checks':{}}
    with pytest.raises(ValueError,match='changed'):
        apply_suppressions(report,tmp_path,policy,hashlib.sha256(b'old policy').hexdigest())


def test_transient_policy_replacement_cannot_change_captured_review(tmp_path, monkeypatch):
    import importlib
    module=importlib.import_module('tools.security.scan')
    source=tmp_path/'source';source.mkdir();code=source/'safe.py';code.write_text('eval(input())\n')
    requirements=tmp_path/'requirements.txt';requirements.write_text('requests==2.19.1\n')
    policy=tmp_path/'policy.json';original='{"schema":1,"entries":[]}'
    policy.write_text(original)
    replacement={'schema':1,'entries':[{'scanner':'bandit','path':'safe.py','line':1,'rule':'B307',
        'source_sha256':'sha256:'+hashlib.sha256(code.read_bytes()).hexdigest(),'expires':'2099-01-01',
        'justification':'Synthetic alternate policy must never be applied after the original policy was captured.'}]}
    def runner(argv, **kwargs):
        if '-c' in argv:return module.PINS
        if 'bandit' in argv:return {'results':[{'filename':str(code),'line_number':1,'test_id':'B307','issue_severity':'MEDIUM'}]}
        if 'detect_secrets' in argv:return {'results':{}}
        return {'dependencies':[{'name':'requests','version':'2.19.1','vulns':[]}]}
    apply=module.apply_policy
    def replace_then_restore(report,root,entries):
        policy.write_text(json.dumps(replacement))
        try:apply(report,root,entries)
        finally:policy.write_text(original)
    monkeypatch.setattr(module,'run_json',runner)
    monkeypatch.setattr(module,'apply_policy',replace_then_restore)
    report=scan(source,Path(sys.executable),requirements,policy)
    assert report['status']=='FAIL' and len(report['findings'])==1 and not report['suppressed']
    assert report['suppressions_sha256']==hashlib.sha256(original.encode()).hexdigest()
